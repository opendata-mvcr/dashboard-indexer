package org.elasticsearch.app.api.server;

import org.elasticsearch.app.Indexer;
import org.elasticsearch.app.api.server.dto.ConfigInfoDTO;
import org.elasticsearch.app.api.server.entities.River;
import org.elasticsearch.app.api.server.exceptions.AlreadyRunningException;
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
    public Map<String, String> runningHarvests() {
        return configManager.getRunning();
    }

    @GetMapping("/configs/{id}")
    public Map<String, Object> getConfig(@PathVariable String id) {
        return configManager.getConfig(id);
    }

    @GetMapping("/export/configs")
    public List<Map<String, Object>> exportConfigs() {
        return configManager.getAllConfigs();
    }

    @PutMapping(path = "/configs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public String saveConfig(@RequestBody String jsonConfig) {
        River river = configManager.save(jsonConfig);
        return river.getRiverName();
    }

    @PutMapping(path = "/configAndIndex", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public String saveConfigAndStart(@RequestBody String jsonConfig) {
        String id = saveConfig(jsonConfig);
        startIndex(id);
        return id;
    }

    @PostMapping("/configs/{id}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void startIndex(@PathVariable String id) {
        River river = configManager.getRiverRef(id);
        if (configManager.isRunning(river.getRiverName())) {
            throw new AlreadyRunningException("Indexing of index '" + id + "', already running");
        }
        configManager.startIndexing(river);
    }

    @PostMapping("/configs/{id}/stop")
    public void stopIndex(@PathVariable String id) {
        configManager.stopIndexing(id);
    }

    @PostMapping("/{source}/_clone/{target}")
    public void cloneIndex(@PathVariable String source, @PathVariable String target) {
        dashboardManager.cloneIndexes(source, target);
    }

    @PostMapping("/import/configs")
    public List<Integer> importConfigs(@RequestBody List<String> jsonConfig) {
        return configManager.setAllConfigs(jsonConfig);
    }

    @DeleteMapping("/configs/{id}")
    public void deleteIndex(@PathVariable String id, @RequestParam(required = false, defaultValue = "false") boolean deleteData) {
        configManager.delete(id, deleteData);
    }
}
