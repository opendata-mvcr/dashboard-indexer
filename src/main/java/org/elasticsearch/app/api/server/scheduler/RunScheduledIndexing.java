package org.elasticsearch.app.api.server.scheduler;

import org.elasticsearch.app.Indexer;
import org.elasticsearch.app.api.server.entities.River;


public class RunScheduledIndexing implements Runnable {

    private final River river;

    private final Indexer indexer;

    public RunScheduledIndexing(River river, Indexer indexer) {
        this.river = river;
        this.indexer = indexer;
    }

    @Override
    public void run() {
        if (indexer.getRunningHarvestersPool().stream().anyMatch(h -> h.getConfigId()==river.getId()))
            return;
        indexer.setRivers(river);
        indexer.startIndexing();
    }
}
