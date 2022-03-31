package org.elasticsearch.app.api.server.dto;

import org.elasticsearch.app.api.server.entities.River;
import org.elasticsearch.app.api.server.entities.UpdateRecord;

import java.util.List;
import java.util.Map;

public class ConfigInfoDTO {
    private final long id;
    private final String name;
    private final Map<String, String> dashboards;
    private final List<UpdateRecord> lastSuccessAndLastTenUpdateRecords;

    public ConfigInfoDTO(River river, Map<String, String> dashboards) {
        this.id = river.getId();
        this.name = river.getRiverName();
        this.dashboards = dashboards;
        this.lastSuccessAndLastTenUpdateRecords = river.getLastSuccessAndLastTenUpdateRecords();
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Map<String, String> getDashboards() {
        return dashboards;
    }

    public List<UpdateRecord> getLastSuccessAndLastTenUpdateRecords() {
        return lastSuccessAndLastTenUpdateRecords;
    }
}
