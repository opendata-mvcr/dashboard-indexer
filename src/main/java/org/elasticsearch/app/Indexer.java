package org.elasticsearch.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.search.*;
import org.elasticsearch.app.api.server.entities.River;
import org.elasticsearch.app.api.server.scheduler.RunningHarvester;
import org.elasticsearch.app.api.server.services.ConfigManager;
import org.elasticsearch.app.api.server.services.DashboardManager;
import org.elasticsearch.app.logging.ESLogger;
import org.elasticsearch.app.logging.Loggers;
import org.elasticsearch.client.*;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.search.Scroll;
import org.elasticsearch.search.SearchHit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

@Component
@Scope("singleton")
public class Indexer {

    public final int cacheDurationInSeconds;

    private String riverIndex;

    public String loglevel;

    public ConfigManager configManager;
    public DashboardManager dashboardManager;

    private final Set<RunningHarvester> runningHarvestersPool = new HashSet<>();

    private boolean usingAPI = true;

    private static final ESLogger logger = Loggers.getLogger(Indexer.class);

    private ArrayList<River> rivers = new ArrayList<>();

    public Map<String, String> envMap;

    private static final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();

    public final RestHighLevelClient clientES;
    public final RestHighLevelClient clientKibana;


    private final ThreadPoolTaskExecutor harvestingTaskExecutor;

    public void startIndexing() {

        for (River river : rivers) {
            Harvester h = new Harvester();

            h.client(clientES)
                    .river(river)
                    .riverIndex(riverIndex)
                    .setIncrementally(river.isIndexIncrementally())
                    .indexer(this);

            this.addHarvesterSettings(h, river.getRiverSettings());

            harvestingTaskExecutor.submit(h);
            runningHarvestersPoolAdd(h);

            logger.info("Created thread for river: {}", river.getRiverName());
        }

        logger.info("All tasks submitted.");
    }

    public void runningHarvestersPoolAdd(Harvester harvester) {
        runningHarvestersPool.add(harvester);
    }

    public void runningHarvestersPoolRemove(Harvester harvester) {
        runningHarvestersPool.remove(harvester);
    }

    public Set<RunningHarvester> getRunningHarvestersPool() {
        return runningHarvestersPool;
    }

    public boolean isUsingAPI() {
        return usingAPI;
    }

    public String getRiverIndex() {
        return riverIndex;
    }

    public void setUsingAPI(boolean usingAPI) {
        this.usingAPI = usingAPI;
    }

    public void setRivers(ArrayList<River> rivers) {
        this.rivers = rivers;
    }

    public void setRivers(River river) {
        ArrayList<River> rivers = new ArrayList<>();
        rivers.add(river);
        this.rivers = rivers;
    }

    private static void switchAliases(RestClient lowclient, Indexer indexer) {
        Response response = null;
        String indexA = "";

        try {
            response = lowclient.performRequest(new Request("GET", "global-search/_mappings"));
            String responseBody = EntityUtils.toString(response.getEntity());

            HashMap myMap = new HashMap<String, String>();

            ObjectMapper objectMapper = new ObjectMapper();
            String reali = "";

            try {
                myMap = objectMapper.readValue(responseBody, HashMap.class);
                reali = (String) myMap.keySet().toArray()[0].toString();

                String alias = reali.replace("_green", "").replace("_blue", "");
                // switching aliases
                if (reali.contains("_green")) {
                    indexA = reali.replace("_green", "_blue");
                } else if (reali.contains("_blue")) {
                    indexA = reali.replace("_blue", "_green");
                }

                Map<String, String> params = Collections.emptyMap();

                String jsonStringRemove = "{ " +
                        "\"actions\" : [ " +
                        "{ \"remove\" : {" +
                        "\"index\" : \"" + reali + "\"," +
                        "\"alias\" : \"" + alias + "\"" +
                        "}" +
                        " }" + "," +
                        "{ \"remove\" : {" +
                        "\"index\" : \"" + reali + "_status" + "\"," +
                        "\"alias\" : \"" + alias + "_status" + "\"" +
                        "}" +
                        " }" +
                        "]}";

                HttpEntity entityR = new NStringEntity(jsonStringRemove, ContentType.APPLICATION_JSON);
                Request req = new Request("POST", "/_aliases");
                req.addParameters(params);
                req.setEntity(entityR);
                Response responseRemove = lowclient.performRequest(req);

                if (responseRemove.getStatusLine().getStatusCode() == 200) {
                    logger.info("{}", EntityUtils.toString(responseRemove.getEntity()));
                    logger.info("Removed alias from index: " + reali);
                }

                String jsonStringAdd = "{ " +
                        "\"actions\" : [ " +
                        "{ \"add\" : {" +
                        "\"index\" : \"" + indexA + "\"," +
                        "\"alias\" : \"" + alias + "\"" +
                        "}" +
                        " }" + "," +
                        "{ \"add\" : {" +
                        "\"index\" : \"" + indexA + "_status" + "\"," +
                        "\"alias\" : \"" + alias + "_status" + "\"" +
                        "}" +
                        " }" +
                        "]}";

                HttpEntity entityAdd = new NStringEntity(jsonStringAdd, ContentType.APPLICATION_JSON);
                try {
                    Request req1 = new Request("POST", "/_aliases");
                    req1.setEntity(entityAdd);
                    req1.addParameters(params);
                    Response responseAdd = lowclient.performRequest(req1);

                    if (responseAdd.getStatusLine().getStatusCode() == 200) {
                        logger.info("{}", EntityUtils.toString(responseAdd.getEntity()));
                        logger.info("Added alias to index: " + indexA);
                    }

                } catch (IOException exe) {
                    logger.error("Could not add alias to index : {}; reason: {}", indexA, exe.getMessage());
                }


            } catch (IOException ex) {
                logger.error("Could not remove alias from index : {}; reason: {}", reali, ex.getMessage());
            }

        } catch (IOException e) {
            logger.error("Could not get aliases from index; reason: {}", e.getMessage());
        }
    }

    @Autowired
    public Indexer(ThreadPoolTaskExecutor harvestingTaskExecutor) {
        this.harvestingTaskExecutor = harvestingTaskExecutor;

        Map<String, String> env = System.getenv();
        this.envMap = env;

        String hostES = (env.get("elastic_host") != null) ? env.get("elastic_host") : EEASettings.ELASTICSEARCH_HOST;
        int portES = (env.get("elastic_port") != null) ? Integer.parseInt(env.get("elastic_port")) : EEASettings.ELASTICSEARCH_PORT;

        String hostKibana = (env.get("kibana_host") != null) ? env.get("kibana_host") : EEASettings.KIBANA_HOST;
        int portKibana = (env.get("kibana_port") != null) ? Integer.parseInt(env.get("kibana_port")) : EEASettings.KIBANA_PORT;
        String kibanaBasePath = (env.get("kibana_base_path") != null) ? env.get("kibana_base_path") : EEASettings.KIBANA_BASE_PATH;
        String user = (env.get("elastic_user") != null) ? env.get("elastic_user") : EEASettings.USER;
        String pass = (env.get("elastic_pass") != null) ? env.get("elastic_pass") : EEASettings.PASS;
        this.riverIndex = (env.get("river_index") != null) ? env.get("river_index") : EEASettings.DEFAULT_RIVER_INDEX;
        this.loglevel = (env.get("log_level") != null) ? env.get("log_level") : EEASettings.LOG_LEVEL;
        this.cacheDurationInSeconds = (env.get("cache_duration_in_seconds") != null) ? Integer.parseInt(env.get("cache_duration_in_seconds")) : EEASettings.CACHE_DURATION_IN_SECONDS;


        credentialsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(user, pass));

        clientES = getRestClient(hostES, portES);
        clientKibana = getRestClient(hostKibana, portKibana, kibanaBasePath);

        logger.debug("USERNAME: " + user);
        logger.debug("PASSWORD: " + pass);
        logger.debug("ES HOST: " + hostES);
        logger.debug("ES PORT: " + portES);
        logger.debug("KIBANA HOST: " + hostKibana);
        logger.debug("KIBANA PORT: " + portKibana);
        logger.debug("KIBANA BASE PATH: " + kibanaBasePath);
        logger.debug("RIVER INDEX: " + this.riverIndex);
        logger.debug("Max concurrent harvests: " + harvestingTaskExecutor.getCorePoolSize());
        logger.info("LOG LEVEL: " + this.loglevel);
        logger.debug("DOCUMENT BULK: ", Integer.toString(EEASettings.DEFAULT_BULK_REQ));
    }

    private RestHighLevelClient getRestClient(String host, int port) {
        return getRestClient(host, port, "/");
    }

    private RestHighLevelClient getRestClient(String host, int port, String basePath) {
        String protocol = "http";
        return new RestHighLevelClient(
                RestClient.builder(
                                new HttpHost(host, port, protocol)
                        ).setPathPrefix(basePath)
                        .setHttpClientConfigCallback(
                                httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
                        )
                        .setFailureListener(new RestClient.FailureListener() {
                            @Override
                            public void onFailure(Node node) {
                                super.onFailure(node);
                                logger.error("Connection failure: [{}://{}:{}]", protocol, host, port);
                            }
                        })
        );
    }

    public void getRivers() {
        this.getAllRivers();

    }

    private void getAllRivers() {
        this.rivers.clear();
        ArrayList<SearchHit> searchHitsA = new ArrayList<>();

        final Scroll scroll = new Scroll(TimeValue.timeValueMinutes(1L));
        SearchRequest searchRequest = new SearchRequest(this.riverIndex);
        searchRequest.scroll(scroll);

        SearchResponse searchResponse = null;
        try {
            logger.info("{}", searchRequest);
            searchResponse = clientES.search(searchRequest, RequestOptions.DEFAULT);
            logger.info("River index {} found", this.riverIndex);
            String scrollId = searchResponse.getScrollId();
            SearchHit[] searchHits = searchResponse.getHits().getHits();

            //process the hits
            searchHitsA.addAll(Arrays.asList(searchHits));

            while (searchHits != null && searchHits.length > 0) {
                SearchScrollRequest scrollRequest = new SearchScrollRequest(scrollId);
                scrollRequest.scroll(scroll);
                searchResponse = clientES.searchScroll(scrollRequest, RequestOptions.DEFAULT);
                scrollId = searchResponse.getScrollId();
                searchHits = searchResponse.getHits().getHits();

                //process the hits
                searchHitsA.addAll(Arrays.asList(searchHits));
            }

            ClearScrollRequest clearScrollRequest = new ClearScrollRequest();
            clearScrollRequest.addScrollId(scrollId);
            ClearScrollResponse clearScrollResponse = clientES.clearScroll(clearScrollRequest, RequestOptions.DEFAULT);
            boolean succeeded = clearScrollResponse.isSucceeded();
        } catch (IOException e) {
            logger.info("River index " + this.riverIndex + " not found");
            System.exit(0);
        } catch (ElasticsearchStatusException ex) {
            logger.info(ex.toString());
            logger.info("River index " + this.riverIndex + " not found");
            System.exit(0);
        }

        for (SearchHit sh : searchHitsA) {
            Map<String, Object> source = sh.getSourceAsMap();
            //logger.debug("{}", source.containsKey("eeaRDF"));

            if (source.containsKey("eeaRDF")) {
                River river = new River()
                        .setRiverName(sh.getId())
                        .setRiverSettings(source);
                rivers.add(river);
                continue;
            }

            if (!((Map) source.get("syncReq")).containsKey("eeaRDF")) {
                logger.error("not has river settings: " + sh.getId());
                throw new IllegalArgumentException(
                        "There are no eeaRDF settings in the river settings");

            } else {
                River river = new River()
                        .setRiverName(sh.getId())
                        .setRiverSettings(source);
                rivers.add(river);
            }

        }
    }

    private void addHarvesterSettings(Harvester harv, Map<String, Object> settings) {
        if (settings.containsKey("eeaRDF")) {

        } else if (!(((HashMap) settings.get("syncReq")).containsKey("eeaRDF"))) {
            logger.error("There are no syncReq settings in the river settings");
            throw new IllegalArgumentException(
                    "There are no eeaRDF settings in the river settings");
        }

        Map<String, Object> rdfSettings = extractSettings(settings, "eeaRDF");

        harv.rdfIndexType(XContentMapValues.nodeStringValue(
                        rdfSettings.get("indexType"), "full"))
                .rdfStartTime(XContentMapValues.nodeStringValue(
                        rdfSettings.get("startTime"), ""))
                .rdfUris(XContentMapValues.nodeStringValue(
                        rdfSettings.get("uris"), "[]"))
                .rdfEndpoint(XContentMapValues.nodeStringValue(
                        rdfSettings.get("endpoint"),
                        EEASettings.DEFAULT_ENDPOINT))
                .rdfClusterId(XContentMapValues.nodeStringValue(
                        rdfSettings.get("cluster_id"),
                        EEASettings.DEFAULT_CLUSTER_ID))
                .rdfQueryType(XContentMapValues.nodeStringValue(
                        rdfSettings.get("queryType"),
                        EEASettings.DEFAULT_QUERYTYPE))
                .rdfListType(XContentMapValues.nodeStringValue(
                        rdfSettings.get("listtype"),
                        EEASettings.DEFAULT_LIST_TYPE))
                .rdfAddLanguage(XContentMapValues.nodeBooleanValue(
                        rdfSettings.get("addLanguage"),
                        EEASettings.DEFAULT_ADD_LANGUAGE))
                .rdfAddCounting(XContentMapValues.nodeBooleanValue(
                        rdfSettings.get("addCounting"),
                        EEASettings.DEFAULT_ADD_COUNTING))
                .rdfLanguage(XContentMapValues.nodeStringValue(
                        rdfSettings.get("language"),
                        EEASettings.DEFAULT_LANGUAGE))
                .rdfAddUriForResource(XContentMapValues.nodeBooleanValue(
                        rdfSettings.get("includeResourceURI"),
                        EEASettings.DEFAULT_ADD_URI))
                .rdfURIDescription(XContentMapValues.nodeStringValue(
                        rdfSettings.get("uriDescription"),
                        EEASettings.DEFAULT_URI_DESCRIPTION))
                .rdfSyncConditions(XContentMapValues.nodeStringValue(
                        rdfSettings.get("syncConditions"),
                        EEASettings.DEFAULT_SYNC_COND))
                .rdfGraphSyncConditions(XContentMapValues.nodeStringValue(
                        rdfSettings.get("graphSyncConditions"), ""))
                .rdfSyncTimeProp(XContentMapValues.nodeStringValue(
                        rdfSettings.get("syncTimeProp"),
                        EEASettings.DEFAULT_SYNC_TIME_PROP))
                .rdfSyncOldData(XContentMapValues.nodeBooleanValue(
                        rdfSettings.get("syncOldData"),
                        EEASettings.DEFAULT_SYNC_OLD_DATA));

        if (rdfSettings.containsKey("proplist")) {
            harv.rdfPropList(getStrListFromSettings(rdfSettings, "proplist"));
        }
        if (rdfSettings.containsKey("query")) {
            harv.rdfQuery(getStrListFromSettings(rdfSettings, "query"));
        } else {
            harv.rdfQuery(EEASettings.DEFAULT_QUERIES);
        }

        if (rdfSettings.containsKey("normProp")) {
            harv.rdfNormalizationProp(getStrObjMapFromSettings(rdfSettings, "normProp"));
        }
        if (rdfSettings.containsKey("normMissing")) {
            harv.rdfNormalizationMissing(getStrObjMapFromSettings(rdfSettings, "normMissing"));
        }
        if (rdfSettings.containsKey("normObj")) {
            harv.rdfNormalizationObj(getStrStrMapFromSettings(rdfSettings, "normObj"));
        }
        if (rdfSettings.containsKey("blackMap")) {
            harv.rdfBlackMap(getStrObjMapFromSettings(rdfSettings, "blackMap"));
        }
        if (rdfSettings.containsKey("whiteMap")) {
            harv.rdfWhiteMap(getStrObjMapFromSettings(rdfSettings, "whiteMap"));
        }
        //TODO : change to index
        if (settings.containsKey("index")) {
            Map<String, Object> indexSettings = extractSettings(settings, "index");
            harv.index(XContentMapValues.nodeStringValue(
                            indexSettings.get("index"),
                            EEASettings.DEFAULT_INDEX_NAME)
                    )
                    .type(XContentMapValues.nodeStringValue(
                            indexSettings.get("type"),
                            EEASettings.DEFAULT_TYPE_NAME)

                    )
                    .statusIndex(XContentMapValues.nodeStringValue(indexSettings.get("statusIndex"), EEASettings.DEFAULT_INDEX_NAME + "_status")
                    );
        } else {
            //TODO: don't know if is correct
            if (settings.containsKey("syncReq")) {
                harv.index(((HashMap) ((HashMap) settings.get("syncReq")).get("index")).get("index").toString());
                harv.type(((HashMap) ((HashMap) settings.get("syncReq")).get("index")).get("type").toString());

                String indexName = ((HashMap) ((HashMap) settings.get("syncReq")).get("index")).get("index").toString();

                HashMap indexMap = ((HashMap) ((HashMap) settings.get("syncReq")).get("index"));
                String statusI = indexMap.get("statusIndex") != null ? indexMap.get("statusIndex").toString() : indexName + "_status";
                harv.statusIndex(statusI);
            } else {
                harv.index(EEASettings.DEFAULT_INDEX_NAME);
                harv.type("river");
                harv.statusIndex(EEASettings.DEFAULT_INDEX_NAME + "_status");
            }

        }

    }

    /**
     * Type casting accessors for river settings
     **/
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractSettings(Map<String, Object> settings,
                                                       String key) {
        if (settings.containsKey("eeaRDF")) {
            return (Map<String, Object>) settings.get(key);
        } else {
            return (Map<String, Object>) ((Map<String, Object>) settings.get("syncReq")).get(key);
        }

    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> getStrStrMapFromSettings(Map<String, Object> settings,
                                                                String key) {
        return (Map<String, String>) settings.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getStrObjMapFromSettings(Map<String, Object> settings,
                                                                String key) {
        return (Map<String, Object>) settings.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStrListFromSettings(Map<String, Object> settings,
                                                       String key) {
        return (List<String>) settings.get(key);
    }

    public void start() {

    }

    public void close() {
        System.exit(0);
    }

    public void closeHarvester(Harvester that) {
        logger.info("Closing thread");
    }

}