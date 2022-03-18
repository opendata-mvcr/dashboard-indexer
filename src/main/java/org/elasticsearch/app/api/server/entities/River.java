package org.elasticsearch.app.api.server.entities;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.*;
import java.util.*;
import java.util.stream.Collectors;

@Entity
public class River {

    @Transient
    private static final Logger logger = LoggerFactory.getLogger(River.class);

    @Id
    @GeneratedValue
    private long id;

    @Column(nullable = false, unique = true)
    private String riverName;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String settingsJSON;

    @Column
    @Basic
    private String schedule;

    @Column(nullable = false, columnDefinition = "boolean default false")
    @Basic
    private boolean automaticScheduling;


    @Column(nullable = false, columnDefinition = "boolean default false")
    @Basic
    private boolean indexIncrementally;

    @OneToMany(cascade = CascadeType.ALL)
    private final List<UpdateRecord> updateHistory = new ArrayList<>();

    @Transient
    private Map<String, Object> riverSettings;

    public River() {
    }

    public long getId() {
        return id;
    }

    public boolean isAutomatic() {
        return automaticScheduling;
    }

    public void setAutomaticScheduling(boolean automaticScheduling) {
        this.automaticScheduling = automaticScheduling;
    }

    public String getSchedule() {
        return schedule;
    }


    public void setSchedule(String schedule) {
        //TODO: parse for cron
        this.schedule = schedule;
    }

    public String getRiverName() {
        return this.riverName;
    }

    public River setRiverName(String riverName) {
        this.riverName = riverName;
        return this;
    }

    public River setRiverSettings(Map<String, Object> settings) {
        try {
            settingsJSON = new ObjectMapper().writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            logger.error(e.getMessage(), e);
        }
        this.riverSettings = settings;
        return this;
    }

    public Map<String, Object> getRiverSettings() {
        if (riverSettings == null) try {
            riverSettings = new ObjectMapper().readValue(settingsJSON, Map.class);
        } catch (JsonProcessingException e) {
            logger.error(e.getMessage(), e);
        }
        return riverSettings;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> res = new HashMap<>();
        Map<String, Object> scheduleMap = new HashMap<>();
        scheduleMap.put("schedule", schedule);
        scheduleMap.put("automatic", automaticScheduling);
        res.put("incrementally", indexIncrementally);
        res.put("schedule", scheduleMap);
        res.put("config", getRiverSettings());
        return res;
    }

    public void update(River newRiver) {
        setRiverSettings(newRiver.getRiverSettings());
        automaticScheduling = newRiver.isAutomatic();
        indexIncrementally = newRiver.isIndexIncrementally();
        setSchedule(newRiver.getSchedule());
    }

    public List<UpdateRecord> getLastSuccessAndLastTenUpdateRecords() {
        List<UpdateRecord> updateRecordList = new ArrayList<>();
        List<UpdateRecord> sorted = updateHistory.stream().sorted(Comparator.comparing(UpdateRecord::getLastUpdateStartDate, Comparator.nullsLast(Comparator.reverseOrder()))).collect(Collectors.toList());
        updateRecordList.add(sorted.stream().filter(s -> s.getFinishState().equals(UpdateStates.SUCCESS)).findFirst().orElse(null));
        updateRecordList.addAll(sorted.stream().limit(10).collect(Collectors.toList()));
        return updateRecordList;
    }

    public void addUpdateRecord(UpdateRecord updateRecord) {
        updateHistory.add(updateRecord);
    }

    public boolean isIndexIncrementally() {
        return indexIncrementally;
    }

    public void setIndexIncrementally(boolean indexIncrementally) {
        this.indexIncrementally = indexIncrementally;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.riverName, this.settingsJSON);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof River))
            return false;
        River river = (River) obj;
        return Objects.equals(this.riverName, river.riverName) && Objects.equals(this.riverSettings, river.riverSettings);
    }

    @Override
    public String toString() {
        return "River: " + this.riverName + " = " + this.toMap().toString();

    }
}
