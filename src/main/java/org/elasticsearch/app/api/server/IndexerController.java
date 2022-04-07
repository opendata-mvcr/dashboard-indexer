package org.elasticsearch.app.api.server;

import org.elasticsearch.app.Indexer;
import org.elasticsearch.app.api.server.dto.ConfigInfoDTO;
import org.elasticsearch.app.api.server.entities.River;
import org.elasticsearch.app.api.server.services.ConfigManager;
import org.elasticsearch.app.api.server.services.DashboardManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class IndexerController {

    private final ConfigManager configManager;

    private final DashboardManager dashboardManager;

    @Autowired
    public IndexerController(ConfigManager configManager, Indexer indexer, DashboardManager dashboardManager) {
        this.configManager = configManager;
        this.dashboardManager = dashboardManager;
        indexer.configManager = this.configManager;
        indexer.dashboardManager = dashboardManager;
    }

    @GetMapping("/configs")
    public List<ConfigInfoDTO> getConfigs() {
        return configManager.getListOfConfigs();
    }

    @GetMapping("/kibanaHost")
    public String getKibanaHost() {
        return dashboardManager.getKibanaHost();
    }

    @GetMapping("/running")
    public Map<Long, String> runningHarvests() {
        return configManager.getRunning();
    }

    @GetMapping("/configs/{configId}")
    public Map<String, Object> getConfig(@PathVariable long configId) {
        return configManager.getConfig(configId);
    }

    @GetMapping("/export/configs")
    public List<Map<String, Object>> exportConfigs() {
        return configManager.getAllConfigs();
    }

    @PutMapping(path = "/configs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public long saveConfig(@RequestBody String jsonConfig) {
        River river = configManager.save(jsonConfig);
        return river.getId();
    }

    @PutMapping(path = "/configAndIndex", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public long saveConfigAndStart(@RequestBody String jsonConfig) {
        long configId = saveConfig(jsonConfig);
        configManager.startIndexing(configId);
        return configId;
    }

    @PatchMapping(path = "/configs/{configId}/indexName", consumes = MediaType.TEXT_PLAIN_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public String renameConfigsIndex(@PathVariable long configId, @RequestBody String newIndexName) {
        configManager.renameIndex(configId, newIndexName);
        return newIndexName;
    }

    @PostMapping("/configs/{configId}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void startIndex(@PathVariable long configId) {
        configManager.startIndexing(configId);
    }

    @PostMapping("/configs/{configId}/stop")
    public void stopIndex(@PathVariable long configId) {
        configManager.stopIndexing(configId);
    }

    @PostMapping("/{source}/_clone/{target}")
    public void cloneIndex(@PathVariable String source, @PathVariable String target) {
        dashboardManager.cloneIndexes(source, target);
    }

    @PostMapping("/import/configs")
    public List<Integer> importConfigs(@RequestBody List<String> jsonConfig) {
        return configManager.setAllConfigs(jsonConfig);
    }

    @DeleteMapping("/configs/{configId}")
    public void deleteIndex(@PathVariable long configId, @RequestParam(required = false, defaultValue = "false") boolean deleteData) {
        configManager.delete(configId, deleteData);
    }
}
