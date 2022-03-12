package org.elasticsearch.app.api.server.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.admin.indices.shrink.ResizeRequest;
import org.elasticsearch.action.admin.indices.shrink.ResizeResponse;
import org.elasticsearch.action.admin.indices.shrink.ResizeType;
import org.elasticsearch.app.Indexer;
import org.elasticsearch.app.api.server.dao.RiverDAO;
import org.elasticsearch.app.api.server.dto.ConfigInfoDTO;
import org.elasticsearch.app.api.server.entities.UpdateRecord;
import org.elasticsearch.app.api.server.exceptions.ConfigNotFoundException;
import org.elasticsearch.app.api.server.exceptions.ConnectionLost;
import org.elasticsearch.app.api.server.exceptions.CouldNotCloneIndex;
import org.elasticsearch.app.api.server.exceptions.ParsingException;
import org.elasticsearch.app.api.server.scheduler.RunScheduledIndexing;
import org.elasticsearch.app.api.server.scheduler.RunningHarvester;
import org.elasticsearch.app.logging.ESLogger;
import org.elasticsearch.app.logging.Loggers;
import org.elasticsearch.app.api.server.entities.River;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.common.settings.Settings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

@Service
public class ConfigManager {

    private static final ESLogger logger = Loggers.getLogger(ConfigManager.class);

    private final RiverDAO riverDAO;

    private final ThreadPoolTaskScheduler taskScheduler;

    private final Indexer indexer;

    private final DashboardManager dashboardManager;

    private final Map<String, ScheduledFuture<?>> scheduledFutures = new HashMap<>();

    @Autowired
    public ConfigManager(RiverDAO riverDAO, ThreadPoolTaskScheduler taskScheduler, Indexer indexer, DashboardManager dashboardManager) {
        this.riverDAO = riverDAO;
        this.taskScheduler = taskScheduler;
        this.indexer = indexer;
        this.dashboardManager = dashboardManager;
        logger.setLevel(indexer.loglevel);
        createSchedule();
    }


    @Transactional
    public River save(String jsonConfig) throws ParsingException {
        River newRiver = createRiver(jsonConfig);
        River foundRiver = riverDAO.findById(newRiver.getRiverName()).orElse(null);
        if (Objects.isNull(foundRiver)) foundRiver = newRiver;
        else foundRiver.update(newRiver);
        addOrUpdateSchedule(foundRiver);
        riverDAO.save(foundRiver);
        return foundRiver;
    }

    private River createRiver(String jsonConfig) throws ParsingException {
        Map<String, Object> map;
        River river = new River();
        try {
            map = new ObjectMapper().readValue(jsonConfig, Map.class);
        } catch (JsonProcessingException e) {
            throw new ParsingException("Could not parse input JSON");
        }
        try {
            Map<String, Object> config = (Map<String, Object>) map.get("config");
            Map<String, Object> scheduleMap = (Map<String, Object>) map.get("schedule");
            String name = ((Map) config.get("index")).get("index").toString();
            boolean indexIncrementally = (boolean) map.get("incrementally");

            river.setAutomaticScheduling((boolean) scheduleMap.get("automatic"));
            river.setSchedule(scheduleMap.get("schedule").toString());
            river.setRiverName(name);
            river.setRiverSettings(config);
            river.setIndexIncrementally(indexIncrementally);
        } catch (Exception e) {
            throw new ParsingException("Could not create config from JSON");
        }
        return river;

    }

    @Transactional
    public void delete(String id, boolean deleteData) throws ConnectionLost {
        if (!riverDAO.existsById(id))
            return;
        River river = riverDAO.getById(id);
        if (deleteData) dashboardManager.deleteIndex(river.getRiverName());
        removeSchedule(river);
        riverDAO.delete(river);
    }

    @Transactional(readOnly = true)
    public List<ConfigInfoDTO> getListOfConfigs() throws ConnectionLost {
        return riverDAO.findAll().stream()
                .map(river -> new ConfigInfoDTO(river, getAssociatedDashboards(river.getRiverName())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public River getRiver(String id) throws ConfigNotFoundException {
        if (!riverDAO.existsById(id))
            throw new ConfigNotFoundException("Config of index '" + id + "', not found");
        return riverDAO.findById(id).orElse(null);
    }

    @Transactional(readOnly = true)
    public River getRiverRef(String id) throws ConfigNotFoundException {
        if (!riverDAO.existsById(id))
            throw new ConfigNotFoundException("Config of index '" + id + "', not found");
        return riverDAO.getById(id);
    }

    @Transactional
    public void addUpdateRecordToIndex(String indexName, UpdateRecord updateRecord) {
        River river = riverDAO.findById(indexName).orElse(null);
        if (Objects.isNull(river)) return;
        river.addUpdateRecord(updateRecord);
        riverDAO.save(river);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllConfigs() {
        return riverDAO.findAll().stream().map(River::toMap).collect(Collectors.toList());
    }

    @Transactional()
    public List<Integer> setAllConfigs(List<String> configs) throws ParsingException {
        int newConfigs = 0;
        int updatedConfigs = 0;
        for (String config : configs) {
            River newRiver = createRiver(config);
            River resRiver;
            if (!riverDAO.existsById(newRiver.getRiverName())) {
                resRiver = newRiver;
                newConfigs++;
            } else {
                resRiver = getRiver(newRiver.getRiverName());
                resRiver.update(newRiver);
                updatedConfigs++;
            }
            addOrUpdateSchedule(resRiver);
            riverDAO.save(resRiver);
        }
        return Arrays.asList(newConfigs, updatedConfigs);
    }

    public Map<String, String> getRunning() {
        Map<String, String> running = new HashMap<>();
        for (RunningHarvester harvester : indexer.getRunningHarvestersPool()) {
            running.put(harvester.getIndexName(), harvester.getHarvestState().toString());
        }
        return running;
    }

    public void startIndexing(River river) {
        dashboardManager.checkConnection();
        indexer.setRivers(river);
        indexer.startIndexing();
    }

    public void stopIndexing(String id) throws ConfigNotFoundException {
        for (RunningHarvester harvester : indexer.getRunningHarvestersPool()) {
            if (harvester.getIndexName().equals(id)) {
                harvester.stop();
                return;
            }
        }
        throw new ConfigNotFoundException("Indexing of index '" + id + "' is not running");
    }

    public boolean isRunning(String riverName) {
        return indexer.getRunningHarvestersPool().stream().anyMatch(h -> h.getIndexName().equals(riverName));
    }

    public Map<String, Object> getConfig(String id) throws ConfigNotFoundException {
        return getRiver(id).toMap();
    }

    private void createSchedule() {
        List<River> rivers = riverDAO.findAll();
        for (River river : rivers) {
            addOrUpdateSchedule(river);
        }
    }

    private void removeSchedule(River river) {
        ScheduledFuture<?> scheduledFuture = scheduledFutures.get(river.getRiverName());
        if (Objects.isNull(scheduledFuture)) return;
        scheduledFuture.cancel(false);
        scheduledFutures.remove(scheduledFuture);
        logger.debug("Schedule for index '{}' - removed", river.getRiverName());
    }

    private void addOrUpdateSchedule(River river) {
        if (!river.isAutomatic()) {
            removeSchedule(river);
            return;
        }
        String state = "added";
        ScheduledFuture<?> scheduledFuture = scheduledFutures.get(river.getRiverName());
        if (Objects.nonNull(scheduledFuture)) {
            scheduledFuture.cancel(false);
            scheduledFutures.remove(scheduledFuture);
            state = "updated";
        }
        CronTrigger cronTrigger = new CronTrigger(river.getSchedule());
        scheduledFutures.put(river.getRiverName(), taskScheduler.schedule(new RunScheduledIndexing(river, indexer), cronTrigger));
        logger.debug("Schedule for index '{}' - {}", river.getRiverName(), state);
    }

    private Map<String, String> getAssociatedDashboards(String riverName) throws ConnectionLost {
        Map<String, String> associatedDashboards = new HashMap<>();
        Map<String, Map<String, String>> associationOfIndexPatternsAndDashboards = dashboardManager.getAssociationOfIndexPatternsAndDashboards();
        associationOfIndexPatternsAndDashboards.keySet().stream()
                .filter(indexPattern -> riverName.matches(convertPatternToRegex(indexPattern)))
                .forEach(indexPattern -> associatedDashboards.putAll(associationOfIndexPatternsAndDashboards.get(indexPattern)));
        return associatedDashboards;
    }

    private String convertPatternToRegex(String indexPatternRegex) {
        return "^" + indexPatternRegex.replace(".", "\\.").replace("*", ".*");
    }
}
