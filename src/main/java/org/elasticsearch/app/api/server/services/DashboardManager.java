package org.elasticsearch.app.api.server.services;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonString;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.admin.indices.shrink.ResizeRequest;
import org.elasticsearch.action.admin.indices.shrink.ResizeResponse;
import org.elasticsearch.action.admin.indices.shrink.ResizeType;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.app.Indexer;
import org.elasticsearch.app.api.server.exceptions.ConnectionLost;
import org.elasticsearch.app.api.server.exceptions.CouldNotCloneIndex;
import org.elasticsearch.app.api.server.exceptions.CouldNotSearchForIndex;
import org.elasticsearch.app.api.server.exceptions.CouldNotSetSettingsOfIndex;
import org.elasticsearch.app.logging.ESLogger;
import org.elasticsearch.app.logging.Loggers;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.util.Pair;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class DashboardManager {

    private static final ESLogger logger = Loggers.getLogger(ConfigManager.class);

    private final Indexer indexer;

    private final CacheManager cacheManager;

    private final ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    public DashboardManager(Indexer indexer, CacheManager cacheManager, ThreadPoolTaskScheduler taskScheduler) {
        this.indexer = indexer;
        this.cacheManager = cacheManager;
        this.taskScheduler = taskScheduler;
    }

    public void deleteIndex(String name) throws ConnectionLost {
        checkConnection();
        DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(name);
        try {
            indexer.clientES.indices().delete(deleteIndexRequest, RequestOptions.DEFAULT);
        } catch (IOException | ElasticsearchException e) {
            logger.error(e.getMessage());
        }
    }

    public String getKibanaHost() {
        return indexer.clientKibana.getLowLevelClient().getNodes().get(0).getHost().toString();
    }

    public void checkConnection() throws ConnectionLost {
        try {
            boolean ping = indexer.clientES.ping(RequestOptions.DEFAULT);
            Response response = indexer.clientKibana.getLowLevelClient().performRequest(new Request("GET", "/"));
            if (ping && Objects.nonNull(response))
                return;
        } catch (IOException | ElasticsearchException e) {
            logger.error(e.getMessage());
        }
        throw new ConnectionLost("Could not connect to ElasticSearch or Kibana");
    }

    @Cacheable("dashboardsInfo")
    public Map<String, Map<String, String>> getAssociationOfIndexPatternsAndDashboards() {
        SearchRequest searchRequest = new SearchRequest();
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(QueryBuilders.regexpQuery("type", "dashboard"));
        searchRequest.source(searchSourceBuilder);
        Map<String, Map<String, String>> mapIndexDashboards = new HashMap<>();
        try {
            SearchResponse searchResponse = indexer.clientES.search(searchRequest, RequestOptions.DEFAULT);
            for (Pair<String, String> dashboard : Arrays.stream(searchResponse.getHits().getHits()).map(hit -> Pair.of(hit.getId(), ((Map) hit.getSourceAsMap().get("dashboard")).get("title").toString())).collect(Collectors.toList())) {
                try {
                    Response kibanaResponse = indexer.clientKibana.getLowLevelClient().performRequest(new Request("GET", "/api/kibana/dashboards/export?" + dashboard.getFirst().replace(':', '=')));

                    JsonObject dashboardInfos = JSON.parse(kibanaResponse.getEntity().getContent());
                    List<String> dashboardsIndexPatterns;
                    dashboardsIndexPatterns = dashboardInfos.get("objects").getAsArray().stream().filter(dashboardInfo ->
                            dashboardInfo.getAsObject().get("type").getAsString().equals(new JsonString("index-pattern"))
                    ).map(pattern -> pattern.getAsObject().get("attributes").getAsObject().get("title").getAsString().value()).collect(Collectors.toList());
                    for (String indexPatternRegex : dashboardsIndexPatterns) {
                        if (!mapIndexDashboards.containsKey(indexPatternRegex))
                            mapIndexDashboards.put(indexPatternRegex, new HashMap<>());
                        mapIndexDashboards.get(indexPatternRegex).put(dashboard.getFirst().replace("dashboard:", ""), dashboard.getSecond());

                    }
                } catch (Exception e) {
                    logger.debug("No attributes in dashboard");
                }
            }
        } catch (IOException | ElasticsearchException e) {
            logger.error(e.getMessage());
        }
        cacheEvictAfter("dashboardsInfo", indexer.cacheDurationInSeconds);
        return mapIndexDashboards;
    }

    private void cacheEvictAfter(String cacheName, long cacheDurationInSeconds) {
        taskScheduler.getScheduledExecutor().schedule(() -> {
            Objects.requireNonNull(cacheManager.getCache(cacheName)).clear();
            logger.debug("Cache '{}' cleared", cacheName);
        }, cacheDurationInSeconds, TimeUnit.SECONDS);
    }

    public void cloneIndexes(String source, String target) throws CouldNotCloneIndex, CouldNotSearchForIndex, CouldNotSetSettingsOfIndex {
        if (!indexExists(source))
            throw new CouldNotCloneIndex(String.format("Source index [{}] not found.", source, target, source));
        setWriteBlockOnIndex(true, source);
        ResizeRequest cloneRequest = new ResizeRequest(target, source);
        cloneRequest.setResizeType(ResizeType.CLONE);
        try {
            ResizeResponse clone = indexer.clientES.indices().clone(cloneRequest, RequestOptions.DEFAULT);
            if (!clone.isAcknowledged() || !clone.isShardsAcknowledged()) {
                logger.error("Cloning index {} to {} was not successful:\n\t\t\t\t\t\t\t\t\t\t\t\t\t" +
                                "Acknowledged:{}\n\t\t\t\t\t\t\t\t\t\t\t\t\tShardsAcknowledged:{}"
                        , source, target, clone.isAcknowledged(), clone.isShardsAcknowledged());
                throw new CouldNotCloneIndex(String.format("Cloning index %s to %s was not successful:\n\t\t\t\t\t\t\t\t\t\t\t\t\t" +
                                "Acknowledged:%s\n\t\t\t\t\t\t\t\t\t\t\t\t\tShardsAcknowledged:%s"
                        , source, target, clone.isAcknowledged(), clone.isShardsAcknowledged()));
            }
        } catch (ElasticsearchException | IOException e) {
            logger.error("Could not clone index {} to {}", source, target);
            logger.error(e.getLocalizedMessage());
            throw new CouldNotCloneIndex(String.format("Could not clone index %s to %s", source, target));
        }
    }

    public boolean indexExists(String indexName) throws CouldNotSearchForIndex {
        GetIndexRequest getIndexRequest = new GetIndexRequest(indexName);
        boolean exists;
        try {
            exists = indexer.clientES.indices().exists(getIndexRequest, RequestOptions.DEFAULT);
        } catch (ElasticsearchException | IOException e) {
            logger.error("Something went wrong when searching for index: " + indexName);
            logger.error(e.getLocalizedMessage());
            throw new CouldNotSearchForIndex(String.format("Something went wrong when searching for index: {}\n{}",indexName,e.getLocalizedMessage()));
        }
        return exists;
    }


    public void setWriteBlockOnIndex(boolean block, String indexName) throws CouldNotSetSettingsOfIndex {
        UpdateSettingsRequest settingsRequest = new UpdateSettingsRequest(indexName);
        Settings settings = Settings.builder().put("index.blocks.write", block).build();
        settingsRequest.settings(settings);
        try {
            indexer.clientES.indices().putSettings(settingsRequest, RequestOptions.DEFAULT);
        } catch (ElasticsearchException | IOException e) {
            logger.error("Could not set index.blocks.write={} on index {}", block, indexName);
            logger.error(e.getLocalizedMessage());
            throw new CouldNotSetSettingsOfIndex(String.format("Could not set index.blocks.write={} on index {}", block, indexName));
        }
    }
}
