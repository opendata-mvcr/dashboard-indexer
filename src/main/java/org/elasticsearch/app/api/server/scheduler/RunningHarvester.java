package org.elasticsearch.app.api.server.scheduler;

import org.elasticsearch.app.HarvestStates;

public interface RunningHarvester {

    void stop();

    long getConfigId();

    HarvestStates getHarvestState();
}
