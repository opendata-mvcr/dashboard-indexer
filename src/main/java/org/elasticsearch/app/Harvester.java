package org.elasticsearch.app;

import com.hp.hpl.jena.graph.Graph;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.NodeFactory;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.query.*;
import com.hp.hpl.jena.rdf.model.*;
import com.hp.hpl.jena.sparql.ARQException;
import com.hp.hpl.jena.sparql.engine.http.QueryExceptionHTTP;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RiotException;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.admin.indices.shrink.ResizeRequest;
import org.elasticsearch.action.admin.indices.shrink.ResizeResponse;
import org.elasticsearch.action.admin.indices.shrink.ResizeType;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.*;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.replication.ReplicationResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.app.api.server.entities.River;
import org.elasticsearch.app.api.server.entities.UpdateRecord;
import org.elasticsearch.app.api.server.entities.UpdateStates;
import org.elasticsearch.app.api.server.exceptions.CouldNotCloneIndex;
import org.elasticsearch.app.api.server.exceptions.CouldNotSearchForIndex;
import org.elasticsearch.app.api.server.exceptions.CouldNotSetSettingsOfIndex;
import org.elasticsearch.app.api.server.scheduler.RunningHarvester;
import org.elasticsearch.app.logging.ESLogger;
import org.elasticsearch.app.logging.Loggers;
import org.elasticsearch.app.support.ESNormalizer;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.Scroll;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;

import java.io.IOException;
import java.net.IDN;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;


/**
 * @author iulia
 */
public class Harvester implements Runnable, RunningHarvester {
    private static boolean DEBUG_TIME = false;

    private boolean synced = false;
    private boolean failed = false;
    private boolean incrementally = false;

    private enum QueryType {
        SELECT,
        CONSTRUCT,
        DESCRIBE
    }

    private HarvestStates harvestState = HarvestStates.WAITING;

    private static final String indexPrefix = "@temp-";

    private String indexWithPrefix;

    private long startTime = 0;

    private UpdateRecord updateRecord = new UpdateRecord();

    private final ESLogger logger = Loggers.getLogger(Harvester.class);

    private Indexer indexer;

    private boolean stopped = false;

    private String rdfEndpoint = "";

    private String rdfClusterId = "";

    /* Harvester operation info */
    private Boolean indexAll = true;
    private String lastupdateDate = "";

    /* Harvest from uris options */
    private Set<String> rdfUris = new HashSet<String>();

    /* Harvest from query options */
    private List<String> rdfQueries = new ArrayList<String>();
    private QueryType rdfQueryType;

    /* WhiteList / BlackList properties */
    private List<String> rdfPropList = new ArrayList<String>();
    private Boolean isWhitePropList = false;

    /* Normalization options */
    private Map<String, Object> normalizeProp = new HashMap<String, Object>();
    private Map<String, String> normalizeObj = new HashMap<String, String>();
    private Map<String, Object> normalizeMissing = new HashMap<String, Object>();

    /* Language options */
    private Boolean addLanguage = false;
    private String language;

    /* Counting options */
    private Boolean addCounting = false;

    /* Resource augmenting options */
    private List<String> uriDescriptionList = new ArrayList<String>();
    private Boolean addUriForResource = false;

    /* BlackMap and WhiteMap */
    private Map<String, Set<String>> blackMap = new HashMap<String, Set<String>>();
    private Map<String, Set<String>> whiteMap = new HashMap<String, Set<String>>();

    /* Sync options */
    private String syncConditions;
    private String syncTimeProp;
    private String graphSyncConditions;
    private Boolean syncOldData;

    /* ES api options */
    private RestHighLevelClient client;

    @Override
    public long getConfigId() {
        return river.getId();
    }

    private String indexName;
    private String typeName;
    private String statusIndex;

    private String riverName;
    private River river;
    private String riverIndex;


    private volatile Boolean closed = false;

    public void log(String message) {
        logger.info(message);
    }

    public void close() {
        closed = true;
    }

    public void indexer(Indexer indexer) {
        this.indexer = indexer;
    }

    private HashMap<String, String> uriLabelCache = new HashMap<>();

    public HashMap<String, String> getUriLabelCache() {
        return this.uriLabelCache;
    }

    public void putToUriLabelCache(String uri, String result) {
        uriLabelCache.put(uri, result);
    }

    public String getRiverName() {
        return this.riverName;
    }

    public String getRdfEndpoint() {
        return rdfEndpoint;
    }

    public HarvestStates getHarvestState() {
        return harvestState;
    }

    public void setHarvestState(HarvestStates harvestState) {
        this.harvestState = harvestState;
    }

    /**
     * Sets the {@link Harvester}'s {@link #rdfUris} parameter
     *
     * @param url - a list of urls
     * @return the {@link Harvester} with the {@link #rdfUris} parameter set
     */
    public Harvester rdfUris(String url) {
        url = url.substring(1, url.length() - 1);
        rdfUris = new HashSet<String>(Arrays.asList(url.split(",")));
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #rdfEndpoint} parameter
     *
     * @param endpoint - new endpoint
     * @return the same {@link Harvester} with the {@link #rdfEndpoint}
     * parameter set
     */
    public Harvester rdfEndpoint(String endpoint) {
        try {
            URL url = new URL(endpoint);
            String host = IDN.toASCII(url.getHost());
            url = new URL(url.getProtocol(), host, url.getPort(), url.getFile());
            rdfEndpoint = url.toString();
        } catch (MalformedURLException e) {
            logger.warn("Could not convert endpoint to URL. Using provided string: " + endpoint);
            logger.warn(e.getLocalizedMessage());
            rdfEndpoint = endpoint;
        }
        return this;
    }

    public Harvester rdfClusterId(String clusterId) {
        rdfClusterId = clusterId;
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #rdfQueries} parameter
     *
     * @param query - new list of queries
     * @return the same {@link Harvester} with the {@link #rdfQueries} parameter
     * set
     */
    public Harvester rdfQuery(List<String> query) {
        rdfQueries = new ArrayList<String>(query);
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #rdfQueryType} parameter
     *
     * @param queryType - the type of any possible query
     * @return the same {@link Harvester} with the {@link #rdfQueryType}
     * parameter set
     */
    public Harvester rdfQueryType(String queryType) {
        try {
            rdfQueryType = QueryType.valueOf(queryType.toUpperCase());
        } catch (IllegalArgumentException e) {

            logger.info("Bad query type: {}", queryType);
            /* River process can't continue */
            throw e;
        }
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #rdfPropList} parameter
     *
     * @param list - a list of properties names that are either required in
     *             the object description, or undesired, depending on its
     *             {@link #isWhitePropList}
     * @return the same {@link Harvester} with the {@link #rdfPropList}
     * parameter set
     */
    public Harvester rdfPropList(List<String> list) {
        if (!list.isEmpty()) {
            rdfPropList = new ArrayList<String>(list);
        }
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #isWhitePropList} parameter
     *
     * @param listType - a type ("black" or "white") for the {@link #rdfPropList}
     *                 in case it exists
     * @return the same {@link Harvester} with the {@link #isWhitePropList}
     * parameter set
     * @Observation A blacklist contains properties that should not be indexed
     * with the data while a white-list contains all the properties that should
     * be indexed with the data.
     */
    public Harvester rdfListType(String listType) {
        if (listType.equals("white"))
            isWhitePropList = true;
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #addLanguage} parameter.
     *
     * @param rdfAddLanguage - a new value for the parameter
     * @return the same {@link Harvester} with the {@link #addLanguage}
     * parameter set
     * @Observation When "addLanguage" is set on "true", all the languages
     * of the String Literals will be included in the output of a new property,
     * "language".
     */
    public Harvester rdfAddLanguage(Boolean rdfAddLanguage) {
        addLanguage = rdfAddLanguage;
        return this;
    }

    public Harvester rdfAddCounting(Boolean rdfAddCounting) {
        addCounting = rdfAddCounting;
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #language} parameter. The default
     * value is 'en"
     *
     * @param rdfLanguage - new value for the parameter
     * @return the same {@link Harvester} with the {@link #language} parameter
     * set
     */
    public Harvester rdfLanguage(String rdfLanguage) {
        language = rdfLanguage;
        if (!language.isEmpty()) {
            addLanguage = true;
        }
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #normalizeProp} parameter.
     * {@link #normalizeProp} contains pairs of property-replacement. The
     * properties are replaced with the given values and if one resource has
     * both properties their values are grouped in a list.
     *
     * @param normalizeProp - new value for the parameter
     * @return the same {@link Harvester} with the {@link #normalizeProp}
     * parameter set
     */
    public Harvester rdfNormalizationProp(Map<String, Object> normalizeProp) {
        if (normalizeProp != null && !normalizeProp.isEmpty()) {
            this.normalizeProp = normalizeProp;
        }
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #normalizeObj} parameter.
     * {@link #normalizeObj} contains pairs of object-replacement. Objects
     * are replaced with given values no matter of the property whose value
     * they represent.
     *
     * @param normalizeObj - new value for the parameter
     * @return the same {@link Harvester} with the {@link #normalizeObj}
     * parameter set
     */
    public Harvester rdfNormalizationObj(Map<String, String> normalizeObj) {
        if (normalizeObj != null && !normalizeObj.isEmpty()) {
            this.normalizeObj = normalizeObj;
        }
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #normalizeMissing} parameter.
     * {@link #normalizeMissing} contains pairs of property-value. Missing
     * properties are indexed with the given value.
     *
     * @param normalizeMissing - new value for the parameter
     * @return the same {@link Harvester} with the {@link #normalizeMissing}
     * parameter set
     */
    public Harvester rdfNormalizationMissing(Map<String, Object> normalizeMissing) {
        if (normalizeMissing != null && !normalizeMissing.isEmpty()) {
            this.normalizeMissing = normalizeMissing;
        }
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #blackMap} parameter. A blackMap
     * contains all the pairs property - list of objects that are not meant to
     * be indexed.
     *
     * @param blackMap - a new value for the parameter
     * @return the same {@link Harvester} with the {@link #blackMap}
     * parameter set
     */
    @SuppressWarnings("unchecked")
    public Harvester rdfBlackMap(Map<String, Object> blackMap) {
        if (blackMap != null && !blackMap.isEmpty()) {
            this.blackMap = new HashMap<String, Set<String>>();
            for (Map.Entry<String, Object> entry : blackMap.entrySet()) {
                this.blackMap.put(
                        entry.getKey(), new HashSet((List<String>) entry.getValue()));
            }
        }
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #whiteMap} parameter.  A whiteMap
     * contains all the pairs property - list of objects that are meant to be
     * indexed.
     *
     * @param whiteMap - a new value for the parameter
     * @return the same {@link Harvester} with the {@link #whiteMap}
     * parameter set
     */
    @SuppressWarnings("unchecked")
    public Harvester rdfWhiteMap(Map<String, Object> whiteMap) {
        if (whiteMap != null && !whiteMap.isEmpty()) {
            this.whiteMap = new HashMap<String, Set<String>>();
            for (Map.Entry<String, Object> entry : whiteMap.entrySet()) {
                this.whiteMap.put(
                        entry.getKey(), new HashSet((List<String>) entry.getValue()));
            }
        }
        return this;
    }

    public List<String> getUriDescriptionList() {
        return uriDescriptionList;
    }

    /**
     * Sets the {@link Harvester}'s {@link #uriDescriptionList} parameter.
     * Whenever {@link #uriDescriptionList} is set, all the objects represented
     * by URIs are replaced with the resource's label. The label is the first
     * of the properties in the given list, for which the resource has an object.
     *
     * @param uriList - a new value for the parameter
     * @return the same {@link Harvester} with the {@link #uriDescriptionList}
     * parameter set
     */
    public Harvester rdfURIDescription(String uriList) {
        uriList = uriList.substring(1, uriList.length() - 1);
        uriDescriptionList = Arrays.asList(uriList.split(","));
        return this;
    }


    /**
     * Sets the {@link Harvester}'s {@link #uriDescriptionList} parameter.
     * When it is set to true  a new property is added to each resource:
     * <http://www.w3.org/1999/02/22-rdf-syntax-ns#about>, having the value
     * equal to the resource's URI.
     *
     * @param rdfAddUriForResource - a new value for the parameter
     * @return the same {@link Harvester} with the {@link #addUriForResource}
     * parameter set
     */
    public Harvester rdfAddUriForResource(Boolean rdfAddUriForResource) {
        this.addUriForResource = rdfAddUriForResource;
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #syncConditions} parameter. It
     * represents the sync query's additional conditions for indexing. These
     * conditions are added within the graphs matching the time filter.
     * Use the ?resource variable to address the resource that should match
     * the conditions within the graph.
     *
     * @param syncCond - a new value for the parameter
     * @return the same {@link Harvester} with the {@link #syncConditions}
     * parameter set
     */
    public Harvester rdfSyncConditions(String syncCond) {
        this.syncConditions = syncCond;
        if (!syncCond.isEmpty() &&
                !syncCond.trim().endsWith(".")) {
            this.syncConditions += " . ";
        }
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #graphSyncConditions} parameter. It
     * represents the sync query's graph conditions for indexing. These
     * conditions are added to the graphs matching the time filter.
     * Use the ?graph variable to address the graph that should match
     * the conditions. In this context, ?resource is also bound.
     *
     * @param graphSyncConditions - a new value for the parameter
     * @return the same {@link Harvester} with the {@link #syncConditions}
     * parameter set
     */
    public Harvester rdfGraphSyncConditions(String graphSyncConditions) {
        this.graphSyncConditions = graphSyncConditions;
        if (!graphSyncConditions.isEmpty() &&
                !graphSyncConditions.trim().endsWith(".")) {
            this.graphSyncConditions += " . ";
        }
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #syncTimeProp} parameter. It
     * represents the sync query's time parameter used when filtering the
     * endpoint's last updates.
     *
     * @param syncTimeProp - a new value for the parameter
     * @return the same {@link Harvester} with the {@link #syncTimeProp}
     * parameter set
     */
    public Harvester rdfSyncTimeProp(String syncTimeProp) {
        this.syncTimeProp = syncTimeProp;
        return this;
    }

    /**
     * Sets the {@link Harvester}'s {@link #syncOldData} parameter. When this
     * parameter is set to true, the endpoint will be queried again without the
     * {@link #syncConditions} to update existing resources that were changed.
     * THe default value is true
     *
     * @param syncOldData - a new value for the parameter
     *                    return the same {@link Harvester} with the {@link #syncOldData}
     *                    parameter set
     */
    public Harvester rdfSyncOldData(Boolean syncOldData) {
        this.syncOldData = syncOldData;
        return this;
    }

    public Harvester client(RestHighLevelClient client) {
        this.client = client;
        return this;
    }

    public Harvester index(String indexName) {
        this.indexName = indexName;
        return this;
    }

    public Harvester statusIndex(String sIndex) {
        if (sIndex != null) this.statusIndex = sIndex;
        else this.statusIndex = this.indexName + "_status";
        return this;
    }

    public Harvester type(String typeName) {
        this.typeName = typeName;
        return this;
    }

    public Harvester river(River river) {
        this.riverName = river.getRiverName();
        this.river = river;
        return this;
    }

    public Harvester riverIndex(String riverIndex) {
        this.riverIndex = riverIndex;
        return this;
    }

    public Harvester rdfIndexType(String indexType) {
        if (indexType.equals("sync"))
            this.indexAll = false;
        return this;
    }

    public Harvester setIncrementally(boolean incrementally) {
        this.incrementally = incrementally;
        return this;
    }

    public Harvester rdfStartTime(String startTime) {
        this.lastupdateDate = startTime;
        return this;
    }

    private void setLastUpdate(Date date) {
        if (indexer.isUsingAPI()) return;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        BulkRequest bulkRequest = new BulkRequest();

        try {
            //TODO: status update
            bulkRequest.add(new IndexRequest(statusIndex, "last_update", riverName)
                    .source(
                            jsonBuilder().startObject()
                                    .field("updated_at", date.getTime() / 1000)
                                    .field("name", riverName)
                                    .endObject()
                    )
            );
        } catch (IOException e) {
            logger.error("Could not add the stats to ES. {}",
                    e.getMessage());
        }

        //TODO: move to async
        try {
            BulkResponse bulkResponse = client.bulk(bulkRequest, RequestOptions.DEFAULT);

            if (!bulkResponse.hasFailures()) {
                for (BulkItemResponse bulkItemResponse : bulkResponse) {
                    logger.debug("bulkR: [{},{},{}]", bulkItemResponse.getIndex(), bulkItemResponse.getType(), bulkItemResponse.getId());
                }
            } else {
                for (BulkItemResponse bulkItemResponse : bulkResponse) {
                    BulkItemResponse.Failure failure = bulkItemResponse.getFailure();
                    logger.debug("bulkR FAILURE: [{}]", failure.getCause());
                }
            }
        } catch (IOException e) {
            logger.error("Bulk error: [{}]", e.getMessage());
            logger.error(e.getMessage());
        }

    }

    private String getLastUpdate() {
        String res = "";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        if (indexer.isUsingAPI()) return sdf.format(new Date(0));

        //TODO: status update
        GetRequest getRequest = new GetRequest(this.statusIndex, "last_update", riverName);

        //TODO: move to async ?
        try {
            GetResponse getResponse = client.get(getRequest, RequestOptions.DEFAULT);

            if (!getResponse.isSourceEmpty()) {
                Integer updated_at = (Integer) getResponse.getSource().get("updated_at");
                Long updated = new Long(updated_at);
                res = sdf.format(updated);
            }

        } catch (Exception e) {
            logger.info("Could not get last_update, use Date(0)");
            res = sdf.format(new Date(0));
        }
        return res;
    }

    public void run() {
        startTime = System.currentTimeMillis();
        logger.setLevel(this.indexer.loglevel);
        Thread.currentThread().setName(riverName);
        indexWithPrefix = indexPrefix + indexName;

        try {
            client.ping(RequestOptions.DEFAULT);
        } catch (IOException | ElasticsearchException e) {
            logger.error("Could not connect to ES");
            stop();
            failed = true;
            logger.error("Harvesting {} stopped", riverName);
            indexer.runningHarvestersPoolRemove(this);
            return;
        }

        if (checkRiverNotExists()) {
            SearchRequest searchRequest = new SearchRequest(indexer.getRiverIndex());
            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
            searchSourceBuilder.query(QueryBuilders.matchAllQuery());
            searchRequest.source(searchSourceBuilder);

            try {
                SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);
                SearchHits hits = searchResponse.getHits();
                long totalHits = hits.getTotalHits().value;
                if (totalHits == 0) {
                    indexer.close();
                }
            } catch (IOException e) {
                indexer.close();
                logger.error(e.getMessage());
            }

            this.close();
        }

        try {
            while (!closed && !synced) {
                boolean success;

                if (lastupdateDate.isEmpty()) lastupdateDate = getLastUpdate();

                deleteTempIndexIfExists();

                if (incrementally) {
                    copyCurrentIndexAsTempIndex();
                }

                setHarvestState(HarvestStates.HARVESTING_ENDPOINT);
                if (indexAll && !synced)
                    success = runIndexAll();
                else
                    success = runSync();

                //TODO: async ?
                if (success && !stopped) {
                    updateRecord.setFinishState(UpdateStates.SUCCESS);
                    setLastUpdate(new Date(startTime));
                    renameIndex();

                    synced = true;

                    Harvester that = this;
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                    long duration = System.currentTimeMillis() - startTime;
                    String time = duration % 1000 + "ms";
                    if ((duration /= 1000) > 0)
                        time = duration % 60 + "s " + time;
                    if ((duration /= 60) > 0)
                        time = duration % 60 + "m " + time;
                    if ((duration /= 60) > 0)
                        time = duration % 24 + "h " + time;
                    if ((duration /= 24) > 0)
                        time = duration + "days " + time;
                    logger.info("===============================================================================");
                    logger.info("TOTAL TIME:  {}", time);
                    logger.info("===============================================================================");


                    if (!indexer.isUsingAPI()) {
                        // deleting river cluster from riverIndex
                        DeleteRequest deleteRequest = new DeleteRequest(riverIndex, "river", riverName);
                        client.deleteAsync(deleteRequest, RequestOptions.DEFAULT, new ActionListener<DeleteResponse>() {
                            @Override
                            public void onResponse(DeleteResponse deleteResponse) {
                                logger.info("Deleted river index entry: " + riverIndex + "/" + riverName);
                                //setClusterStatus("synced");
                                that.close();
                                indexer.closeHarvester(that);
                            }

                            @Override
                            public void onFailure(Exception e) {
                                logger.error("Could not delete river :" + riverIndex + "/" + riverName);
                                //setClusterStatus("synced");
                                logger.error("Reason: [{}]", e.getMessage());
                                that.close();
                                indexer.closeHarvester(that);
                            }
                        });
                    }
                }
            }

        } catch (Exception e) {
            logger.error(e.getMessage());
        }

        if (stopped) {
            rollback();
            logger.warn("Stopped {} harvest", indexName);
        }
        if (failed) {
            updateRecord.setFinishState(UpdateStates.FAILED);
            rollback();
            logger.warn("Failed {} harvest", indexName);
        }

        if (indexer.isUsingAPI() && Objects.nonNull(indexer.configManager)) {
            if (!updateRecord.getFinishState().equals(UpdateStates.STOPPED))
                updateRecord.setLastUpdateDuration(System.currentTimeMillis() - startTime);
            updateRecord.setLastUpdateStartDate(new Date(System.currentTimeMillis()));
            indexer.configManager.addUpdateRecordToIndex(indexName, updateRecord);
        }

        indexer.runningHarvestersPoolRemove(this);


        logger.info("Thread closed");
    }

    private void copyCurrentIndexAsTempIndex() throws CouldNotCloneIndex, CouldNotSearchForIndex, CouldNotSetSettingsOfIndex {
        if (!indexer.dashboardManager.indexExists(indexName)) return;
        indexer.dashboardManager.cloneIndexes(indexName, indexWithPrefix);

        indexer.dashboardManager.setWriteBlockOnIndex(false, indexWithPrefix);
    }

    private void deleteTempIndexIfExists() throws IOException, ElasticsearchException {
        try {
            AcknowledgedResponse delete = client.indices().delete(new DeleteIndexRequest(indexWithPrefix), RequestOptions.DEFAULT);
            if (delete.isAcknowledged()) logger.warn("Deleted {} before indexing", indexWithPrefix);
        } catch (IOException e) {
            logger.error("Could not delete index {} before indexing", indexWithPrefix);
            stop();
            failed = true;
            throw e;
        } catch (ElasticsearchException e) {
            if (e.status() != RestStatus.NOT_FOUND) {
                logger.error("Could not delete index {} before indexing", indexWithPrefix);
                stop();
                failed = true;
                throw e;
            }
        }
    }

    private void renameIndex() {
        setHarvestState(HarvestStates.SWITCHING_TO_NEW_INDEX);
        logger.info("Moving index from {} to {}", indexWithPrefix, indexName);
        //Setting as cloneable
        UpdateSettingsRequest settingsRequest = new UpdateSettingsRequest(indexWithPrefix);
        Settings settings = Settings.builder().put("index.blocks.write", true).build();
        settingsRequest.settings(settings);
        try {
            client.indices().putSettings(settingsRequest, RequestOptions.DEFAULT);
        } catch (ElasticsearchException | IOException e) {
            logger.error("Could not set index.blocks.write=true on index " + indexWithPrefix, e);
            return;
        }

        //Delete old index
        DeleteIndexRequest deleteRequest = new DeleteIndexRequest(indexName);
        try {
            client.indices().delete(deleteRequest, RequestOptions.DEFAULT);
        } catch (IOException e) {
            logger.error("Could not delete index " + indexName, e);
        } catch (ElasticsearchException e) {
            if (e.status() != RestStatus.NOT_FOUND)
                logger.error("Could not delete index " + indexName, e);
        }

        //Cloning
        ResizeRequest cloneRequest = new ResizeRequest(indexName, indexWithPrefix);
        cloneRequest.setResizeType(ResizeType.CLONE);
        try {
            ResizeResponse clone = client.indices().clone(cloneRequest, RequestOptions.DEFAULT);
            if (!clone.isAcknowledged() || !clone.isShardsAcknowledged()) {
                logger.error("Cloning index {} to {} was not successful:\n\t\t\t\t\t\t\t\t\t\t\t\t\t" +
                                "Acknowledged:{}\n\t\t\t\t\t\t\t\t\t\t\t\t\tShardsAcknowledged:{}"
                        , indexWithPrefix, indexName, clone.isAcknowledged(), clone.isShardsAcknowledged());
                return;
            }
        } catch (ElasticsearchException | IOException e) {
            logger.error("Could not clone index {} to {}", indexWithPrefix, indexName, e);
            return;
        }

        //delete temp index
        deleteRequest = new DeleteIndexRequest(indexWithPrefix);
        try {
            client.indices().delete(deleteRequest, RequestOptions.DEFAULT);
        } catch (IOException e) {
            logger.error("Could not delete index " + indexWithPrefix, e);
            return;
        } catch (ElasticsearchException e) {
            if (e.status() != RestStatus.NOT_FOUND) {
                logger.error("Could not delete index " + indexWithPrefix, e);
                return;
            }
        }
        logger.info("Moving index from {} to {} - Successful", indexWithPrefix, indexName);

    }

    public boolean runSync() {

        logger.info("Starting RDF synchronization: endpoint [{}], " +
                        "index name [{}], type name [{}]",
                rdfEndpoint, indexName, typeName);

        //setClusterStatus("indexing");
        boolean success = sync();

        logger.info("Ended synchronization from [{}], for endpoint [{}]," +
                        "index name [{}], type name [{}] with status {}",
                lastupdateDate, rdfEndpoint, indexName, typeName,
                success ? "Success" : "Failure");

        return success;
    }

    /**
     * Get a set of unique queryObjName returned from a select query
     * <p>
     * Used to retrieve sets of modified objects used in sync
     *
     * @param rdfQuery     query to execute
     * @param queryObjName name of the object returned
     * @return set of values for queryObjectName in the rdfQuery result
     */
    HashSet<String> executeSyncQuery(String rdfQuery, String queryObjName, int syncQueryCounter) {
        long startTime = System.currentTimeMillis();

        logger.info("Start executeSyncQuery");
        logger.info("QUERY:");
        logger.info(rdfQuery);
        HashSet<String> rdfUrls = new HashSet<>();

        Query query;
        try {
            query = QueryFactory.create(rdfQuery);
        } catch (QueryParseException qpe) {
            logger.warn(
                    "Could not parse [{}]. Please provide a relevant query. {}",
                    rdfQuery, qpe.getLocalizedMessage());
            return null;
        }
        //TODO: async?
        QueryExecution qExec = QueryExecutionFactory.sparqlService(
                rdfEndpoint, query);

        try {
            ResultSet results = qExec.execSelect();

            while (results.hasNext()) {
                QuerySolution sol = results.nextSolution();
                try {
                    String value = sol.getResource(queryObjName).toString();
                    rdfUrls.add(value);
                } catch (NoSuchElementException e) {

                    logger.error(
                            "Encountered a NoSuchElementException: "
                                    + e.getLocalizedMessage());
                    return null;
                }
            }
        } catch (Exception e) {

            logger.error(
                    "Encountered a [{}] while querying the endpoint for sync",
                    e.getLocalizedMessage());
            return null;
        } finally {
            qExec.close();
        }


        long endTime = System.currentTimeMillis();
        if (DEBUG_TIME) {
            logger.info("timeQuery: #" + syncQueryCounter + " : executeSyncQuery for rdfUrls took : {} ms", endTime - startTime);
        }


        return rdfUrls;
    }

    /**
     * Build a query returning all triples in which members of
     * uris are the subjects of the triplets.
     * <p>
     * If toDescribeURIs is true the query will automatically add logic
     * to retrieve the labels directly from the SPARQL endpoint.
     *
     * @param uris URIs for queried resources
     * @return a CONSTRUCT query string
     */
    private String getSyncQueryStr(Iterable<String> uris) {
        StringBuilder uriSetStrBuilder = new StringBuilder();
        String delimiter = "";

        uriSetStrBuilder.append("(");
        for (String uri : uris) {
            uriSetStrBuilder.append(delimiter).append(String.format("<%s>", uri));
            delimiter = ", ";
        }
        uriSetStrBuilder.append(")");

        String uriSet = uriSetStrBuilder.toString();

        /* Get base triplets having any element from uris as subject */
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("CONSTRUCT { ?s ?p ?o } WHERE {")
                .append("{?s ?p ?o")
                .append(String.format(" . FILTER (?s in %s )", uriSet));

        /* Perform uri label resolution only if desired */
        if (uriDescriptionList.isEmpty()) {
            queryBuilder.append("}}");
            return queryBuilder.toString();
        }

        /* Filter out properties having a label */
        int index = 0;
        for (String prop : uriDescriptionList) {
            index++;
            String filterTemplate = " . OPTIONAL { ?o <%s> ?o%d } "
                    + " . FILTER(!BOUND(?o%d))";
            queryBuilder.append(String.format(filterTemplate, prop, index, index));
        }
        queryBuilder.append("}");

        /* We need this redundant clause as UNION queries can't handle sub-selects
         * without a prior clause.
         */
        String redundantClause = "<http://www.w3.org/2000/01/rdf-schema#Class> "
                + "a <http://www.w3.org/2000/01/rdf-schema#Class>";

        /* Add labels for filtered out properties */
        for (String prop : uriDescriptionList) {
            /* Resolve ?o as str(?label) for the resource ?res
             * label is taken as being ?res <prop> ?label
             *
             * We need to take str(?label) in order to drop
             * language references of the terms so that the document
             * is indexed with a language present only in it's top-level
             * properties.
             *
             * As some Virtuoso versions do not allow the usage
             * of BIND so we have to create a sub-select in order to bind
             * ?o to str(?label)
             *
             * The sub-select works only with a prior clause.
             * We are using a redundant clause that is always true
             */
            String partQueryTemplate = " UNION "
                    + "{ "
                    + redundantClause + " . "
                    + "{ SELECT ?s ?p (str(?label) as ?o) { "
                    + "   ?s ?p ?res"
                    + "   . FILTER (?s in %s)"
                    + "   . ?res <%s> ?label }}}";
            queryBuilder.append(String.format(partQueryTemplate, uriSet, prop));
        }

        queryBuilder.append("}");
        return queryBuilder.toString();

    }


    /**
     * Remove the documents from ElasticSearch that are not present in
     * uris
     *
     * @param uris uris that should be present in the index.
     * @return true if the action completed, false if it failed during
     * the process.
     */
    private int removeMissingUris(Set<String> uris, String clusterId) {
        int searchKeepAlive = 60000;
        int count = 0;

        // TODO: async?
        final Scroll scroll = new Scroll(TimeValue.timeValueMinutes(1L));
        SearchRequest searchRequest = new SearchRequest();
        searchRequest.indices(indexName).types(typeName);

        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(termQuery("cluster_id", riverName));
        searchRequest.source(searchSourceBuilder);

        searchRequest.scroll(scroll);

        SearchResponse searchResponse = null;
        try {
            searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);
            String scrollId = searchResponse.getScrollId();
            SearchHit[] searchHits = searchResponse.getHits().getHits();

            //process the hits
            //noinspection Duplicates
            for (SearchHit hit : searchHits) {
                String hitClusterId;
                if (hit.getSourceAsMap().getOrDefault("cluster_id", clusterId).getClass() == ArrayList.class) {
                    ArrayList<String> arr = (ArrayList<String>) hit.getSourceAsMap().getOrDefault("cluster_id", clusterId);
                    hitClusterId = arr.get(0);

                } else {
                    hitClusterId = (String) hit.getSourceAsMap().getOrDefault("cluster_id", clusterId);
                }

                if (hitClusterId != clusterId) {
                    continue;
                }
                if (uris.contains(hit.getId()))
                    continue;

                DeleteRequest deleteRequest = new DeleteRequest(indexName, typeName, hit.getId());
                DeleteResponse deleteResponse = client.delete(deleteRequest, RequestOptions.DEFAULT);

                ReplicationResponse.ShardInfo shardInfo = deleteResponse.getShardInfo();
                if (shardInfo.getTotal() != shardInfo.getSuccessful()) {

                }
                if (shardInfo.getFailed() > 0) {
                    for (ReplicationResponse.ShardInfo.Failure failure : shardInfo.getFailures()) {
                        String reason = failure.reason();
                        logger.warn("Deletion failure: " + reason);
                    }
                }
                if (deleteResponse.getResult() == DocWriteResponse.Result.DELETED) {
                    count++;
                }
            }

            while (searchHits != null && searchHits.length > 0) {
                SearchScrollRequest scrollRequest = new SearchScrollRequest(scrollId);
                scrollRequest.scroll(scroll);
                searchResponse = client.searchScroll(scrollRequest, RequestOptions.DEFAULT);
                scrollId = searchResponse.getScrollId();
                searchHits = searchResponse.getHits().getHits();

                //process the hits
                //noinspection Duplicates
                for (SearchHit hit : searchHits) {
                    String hitClusterId;
                    if (hit.getSourceAsMap().getOrDefault("cluster_id", clusterId).getClass() == ArrayList.class) {
                        ArrayList<String> arr = (ArrayList<String>) hit.getSourceAsMap().getOrDefault("cluster_id", clusterId);
                        hitClusterId = arr.get(0);
                    } else {
                        hitClusterId = (String) hit.getSourceAsMap().getOrDefault("cluster_id", clusterId);
                    }

                    if (hitClusterId != clusterId) {
                        continue;
                    }
                    if (uris.contains(hit.getId()))
                        continue;

                    DeleteRequest deleteRequest = new DeleteRequest(indexName, typeName, hit.getId());
                    DeleteResponse deleteResponse = client.delete(deleteRequest, RequestOptions.DEFAULT);

                    ReplicationResponse.ShardInfo shardInfo = deleteResponse.getShardInfo();
                    if (shardInfo.getTotal() != shardInfo.getSuccessful()) {

                    }
                    if (shardInfo.getFailed() > 0) {
                        for (ReplicationResponse.ShardInfo.Failure failure : shardInfo.getFailures()) {
                            String reason = failure.reason();
                            logger.warn("Deletion failure: " + reason);
                        }
                    }
                    if (deleteResponse.getResult() == DocWriteResponse.Result.DELETED) {
                        count++;
                    }
                }
            }

            ClearScrollRequest clearScrollRequest = new ClearScrollRequest();
            clearScrollRequest.addScrollId(scrollId);
            ClearScrollResponse clearScrollResponse = client.clearScroll(clearScrollRequest, RequestOptions.DEFAULT);
            boolean succeeded = clearScrollResponse.isSucceeded();
        } catch (IOException e) {
            logger.error(e.getMessage());
        } catch (ElasticsearchStatusException es) {
            logger.warn("Index not found: [{}]", es.getMessage());
        } catch (ElasticsearchException exception) {
            if (exception.status() == RestStatus.CONFLICT) {
                logger.warn("There was a conflict: [{}]", exception.getMessage());
            } else {
                exception.printStackTrace();
            }
        }

        return count;
    }

    private void setClusterStatus(String status) {
        String statusIndex = indexName + "_status";
        boolean indexing = false;

        GetRequest getRequest = new GetRequest(statusIndex, "last_update", riverName);
        try {
            GetResponse getResponse = client.get(getRequest, RequestOptions.DEFAULT);
            if (getResponse.getSource().get("status") == "indexing") {
                indexing = true;
            }
        } catch (IOException e) {
            logger.error(e.getMessage());
        }

        if (!indexing) {
            Map<String, Object> jsonMap = new HashMap<>();
            jsonMap.put("status", status);
            UpdateRequest request = new UpdateRequest(statusIndex, "last_update", riverName)
                    .doc(jsonMap);
            try {
                UpdateResponse updateResponse = client.update(request, RequestOptions.DEFAULT);
                logger.info("Updating index {} status to: indexing", riverName);
            } catch (IOException e) {
                logger.error("{}", e);
            }
        } else {

        }

    }

    /**
     * Starts a harvester with predefined queries to synchronize with the
     * changes from the SPARQL endpoint
     */
    public boolean sync() {
        logger.info("Sync resources newer than {}", lastupdateDate);
        int rdfUrlssyncQueryCounter = 0;
        int modelSyncQueryCounter = 0;

        String rdfQueryTemplate =
                "PREFIX xsd:<http://www.w3.org/2001/XMLSchema#> "
                        + "SELECT DISTINCT ?resource WHERE { "
                        + " GRAPH ?graph { %s }"
                        + " ?graph <%s> ?time .  %s "
                        + " FILTER (?time > xsd:dateTime(\"%s\")) }";

        String queryStr = String.format(rdfQueryTemplate, syncConditions,
                syncTimeProp, graphSyncConditions,
                lastupdateDate);

        Set<String> syncUris = executeSyncQuery(queryStr, "resource", rdfUrlssyncQueryCounter);
        if (stopped) return false;
        rdfUrlssyncQueryCounter++;
        //TODO : if error retry
        if (syncUris == null) {
            logger.error("Errors occurred during sync procedure. Aborting!");
            logger.info("sleep for 30secs");
            try {
                TimeUnit.SECONDS.sleep(30);
            } catch (InterruptedException ex) {
                logger.info("interrupted");
            }
            logger.info("sleep done");
            return false;
        }

        /**
         * If desired, query for old data that has the sync conditions modified
         *
         * This option is useful in the case in which the application indexes
         * resources that match some conditions. In this case, if they are
         * modified and no longer match the initial conditions, they will not
         * be synchronized. When syncOldData is True, the modified resources
         * that no longer match the conditions are deleted.
         *
         *
         */

        int deleted = 0;
        int count = 0;
        if (this.syncOldData) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            queryStr = String.format(rdfQueryTemplate, syncConditions,
                    syncTimeProp, graphSyncConditions,
                    sdf.format(new Date(0)));

            HashSet<String> allIndexURIs = executeSyncQuery(queryStr, "resource", rdfUrlssyncQueryCounter);
            rdfUrlssyncQueryCounter++;

            if (allIndexURIs == null) {
                logger.error("Errors occurred during modified content sync query. Aborting!");
                return false;
            }
            deleted = removeMissingUris(allIndexURIs, this.rdfClusterId);
        }

        /* Prepare a series of bulk uris to be described so we can make
         * a smaller number of calls to the SPARQL endpoint. */
        ArrayList<ArrayList<String>> bulks = new ArrayList<ArrayList<String>>();
        ArrayList<String> currentBulk = new ArrayList<String>();

        for (String uri : syncUris) {
            if (stopped) return false;
            currentBulk.add(uri);

            if (currentBulk.size() == EEASettings.DEFAULT_BULK_SIZE) {
                bulks.add(currentBulk);
                currentBulk = new ArrayList<String>();
            }
        }

        if (currentBulk.size() > 0) {
            bulks.add(currentBulk);
        }

        /* Execute RDF queries for the resources in each bulk */
        ArrayList<ArrayList<String>> bulksWithErrors = new ArrayList<ArrayList<String>>();
        boolean isBulkWithErrors = false;
        ArrayList<String> urisWithErrors = new ArrayList<String>();
        ArrayList<String> urisUpdatedWithSuccess = new ArrayList<String>();

        int modelCounter = 0;

        while (true) {
            for (ArrayList<String> bulk : bulks) {
                if (stopped) return false;
                //TODO: break in 3 parts
                String syncQuery = getSyncQueryStr(bulk);
                logger.info("QUERY:");
                logger.info(syncQuery);

                try {
                    Query query = QueryFactory.create(syncQuery);

                    long startTime = System.currentTimeMillis();
                    QueryExecution qExec = QueryExecutionFactory.sparqlService(rdfEndpoint, query);

                    qExec.setTimeout(-1);

                    try {
                        Model constructModel = ModelFactory.createDefaultModel();

                        try {
                            qExec.execConstruct(constructModel);
                        } catch (ARQException exc) {
                            logger.error("com.hp.hpl.jena.sparql.ARQException: [{}]", exc);
                            return false;
                        }

                        long endTime = System.currentTimeMillis();

                        if (DEBUG_TIME) {
                            logger.info("timeQuery: " + modelSyncQueryCounter + " : modelSyncQuery took : {} ms",
                                    endTime - startTime
                            );
                        }

                        modelSyncQueryCounter++;

                        BulkRequest bulkRequest = new BulkRequest();

                        /**
                         *  When adding the model to ES do not use toDescribeURIs
                         *  as the query already returned the correct labels.
                         */
                        if (checkRiverNotExists()) {
                            logger.error("River doesn't exist anymore");

                            logger.error("INDEXING CANCELLED");
                            this.close();
                            return false;
                        }

                        ArrayList<String> urisWithESErrors = addModelToES(constructModel, bulkRequest, false, modelCounter);
                        if (closed) return false;

                        count += bulk.size() - urisWithESErrors.size();

                        if (urisWithESErrors.size() > 0) {
                            for (String uri : urisWithESErrors) {
                                urisWithErrors.add(uri);
                            }
                        }
                        for (String uri : bulk) {
                            boolean hasErrors = false;
                            for (String uriWithError : urisWithESErrors) {
                                if (uriWithError.indexOf(String.format("%s ", uri)) != -1) {
                                    hasErrors = true;
                                }
                            }
                            if (!hasErrors) {
                                urisUpdatedWithSuccess.add(uri);
                            }
                        }

                    } catch (Exception e) {

                        logger.error("Error while querying for modified content. {}", e.getLocalizedMessage());

                        if (!isBulkWithErrors) {
                            for (String uri : bulk) {
                                ArrayList<String> currentErrorBulk = new ArrayList<String>();
                                currentErrorBulk.add(uri);
                                bulksWithErrors.add(currentErrorBulk);
                            }
                        } else {
                            for (String uri : bulk) {
                                urisWithErrors.add(String.format("%s %s", uri, e.getLocalizedMessage()));
                            }
                        }

                        if (e.getMessage().equals("Future got interrupted")) {
                            return false;
                        }
                        if (e instanceof ElasticsearchStatusException) {
                            RestStatus restStatus = ((ElasticsearchStatusException) e).status();
                            if (restStatus.getStatus() == 404) {
                                logger.error("River doesn't exist anymore");
                                logger.error("INDEXING CANCELLED");
                                this.close();
                                return false;
                            }
                        }

                        logger.error(e.getMessage());

                    } finally {
                        qExec.close();
                    }
                } catch (QueryParseException qpe) {

                    logger.warn("Could not parse Sync query. Please provide a relevant query. {}", qpe.getLocalizedMessage());
                    if (!isBulkWithErrors) {
                        for (String uri : bulk) {
                            ArrayList<String> currentErrorBulk = new ArrayList<String>();
                            currentErrorBulk.add(uri);
                            bulksWithErrors.add(currentErrorBulk);
                        }
                    } else {
                        for (String uri : bulk) {
                            ArrayList<String> currentErrorBulk = new ArrayList<String>();
                            urisWithErrors.add(String.format("%s %s", uri, qpe.getLocalizedMessage()));
                        }
                    }
                    return false;
                }

            }

            if (bulksWithErrors.size() == 0) {
                break;
            }

            if (isBulkWithErrors) {
                break;
            }

            logger.warn("There were bulks with errors. Try again each resource one by one.");
            logger.warn("Resources with possible errors:");
            for (ArrayList<String> bulk : bulksWithErrors) {
                for (String uri : bulk) {
                    logger.warn(uri);
                }
            }

            isBulkWithErrors = true;
            bulks = bulksWithErrors;
        }


        logger.info("Finished synchronisation: Deleted {}, Updated {}/{}, Error {}",
                deleted, count, syncUris.size(), urisWithErrors.size());

        logger.info("Uris updated with success:");

        for (String uri : urisUpdatedWithSuccess) {
            logger.info(uri);
        }

        if (urisWithErrors.size() > 0) {


            logger.error("There were {} uris with errors:", urisWithErrors.size());
            for (String uri : urisWithErrors) {
                logger.error(uri);
            }
        }
        return true;
    }

    private boolean checkRiverNotExists() {
        if (indexer.isUsingAPI()) return false;
        GetRequest getRequest = new GetRequest(indexer.getRiverIndex(), "river", riverName);
        try {
            GetResponse getResponse = client.get(getRequest, RequestOptions.DEFAULT);
            if (getResponse.isExists()) {
                return false;
            } else {
                logger.error("River doesn't exist anymore");
                logger.error("INDEXING CANCELLED");
                //TODO: update global-search_status ? or remove indexed cluster?
                this.close();
                return true;
            }
        } catch (IOException e) {
            logger.error(e.getMessage());
            logger.error("River doesn't exist anymore");
            logger.error("INDEXING CANCELLED");
            this.close();
            return true;
        }
    }


    /**
     * Starts the harvester for queries and/or URLs
     */
    public boolean runIndexAll() {
        logger.info(
                "Starting RDF harvester: endpoint [{}], queries [{}]," +
                        "URIs [{}], index name [{}], typeName [{}]",
                rdfEndpoint, rdfQueries, rdfUris, indexName, typeName);

        while (true) {
            if (this.failed) return false;
            if (this.closed) {
                logger.info("Ended harvest for endpoint [{}], queries [{}]," +
                                "URIs [{}], index name {}, type name {}",
                        rdfEndpoint, rdfQueries, rdfUris, indexName, typeName);
                return true;
            }

            /**
             * Harvest from a SPARQL endpoint
             */
            if (!rdfQueries.isEmpty()) {
                harvestFromEndpoint();
                if (stopped) return false;
            }

            /**
             * Harvest from RDF dumps
             */
            harvestFromDumps();
            if (stopped) return false;

            closed = true;
        }
    }

    /**
     * Query SPARQL endpoint with a CONSTRUCT query
     *
     * @param qExec QueryExecution encapsulating the query
     * @return model retrieved by querying the endpoint
     */
    private Model getConstructModel(QueryExecution qExec) {
        logger.info("Executing CONSTRUCT");
        Model model = qExec.execConstruct(ModelFactory.createDefaultModel());
        logger.info("Executing CONSTRUCT - done");
        return model;
    }

    /**
     * Query SPARQL endpoint with a DESCRIBE query
     *
     * @param qExec QueryExecution encapsulating the query
     * @return model retrieved by querying the endpoint
     */
    private Model getDescribeModel(QueryExecution qExec) {
        logger.info("Executing DESCRIBE");
        Model model = qExec.execDescribe(ModelFactory.createDefaultModel());
        logger.info("Executing DESCRIBE - done");
        return model;
    }

    /**
     * Query SPARQL endpoint with a SELECT query
     *
     * @param qExec QueryExecution encapsulating the query
     * @return model retrieved by querying the endpoint
     */
    private Model getSelectModel(QueryExecution qExec) {
        Model model = ModelFactory.createDefaultModel();
        Graph graph = model.getGraph();

        logger.info("Executing SELECT");
        ResultSet results = qExec.execSelect();
        logger.info("Executing SELECT - done");
        while (results.hasNext()) {
            if (stopped) return null;
            QuerySolution sol = results.next();
            String subject;
            String predicate;
            RDFNode object;

            try {
                subject = sol.getResource("s").toString();
                predicate = sol.getResource("p").toString();
                object = sol.get("o");
            } catch (NoSuchElementException e) {
                logger.error("SELECT query does not return a (?s ?p ?o) Triple");
                continue;
            }

            Node objNode;
            if (object.isLiteral()) {
                Literal obj = object.asLiteral();
                objNode = NodeFactory.createLiteral(obj.getString(), obj.getLanguage(), obj.getDatatype());
            } else {
                objNode = NodeFactory.createLiteral(object.toString());
            }

            graph.add(new Triple(
                    NodeFactory.createURI(subject),
                    NodeFactory.createURI(predicate),
                    objNode));
        }

        return model;
    }

    /**
     * Query the SPARQL endpoint with a specified QueryExecution
     * and return the model
     *
     * @param qExec QueryExecution encapsulating the query
     * @return model retrieved by querying the endpoint
     */
    private Model getModel(QueryExecution qExec) {
        setHarvestState(HarvestStates.EXECUTING_QUERY);
        switch (rdfQueryType) {
            case CONSTRUCT:
                return getConstructModel(qExec);
            case DESCRIBE:
                return getDescribeModel(qExec);
            case SELECT:
                return getSelectModel(qExec);
        }
        return null;
    }

    /**
     * Add data to ES given a model from queries
     *
     * @param model executed queries transformed to model
     */
    private void uploadDataToES(Model model) {
        boolean retry;
        int retryCount = 0;
        do {
            retry = false;
            try {
                BulkRequest bulkRequest = new BulkRequest();
                if (model != null) {
                    addModelToES(model, bulkRequest, true);
                }
            } catch (QueryExceptionHTTP e) {
                if (retryCount++ < 5 && e.getResponseCode() >= 500) {
                    retry = true;
                    logger.error("Encountered an internal server error "
                            + "while harvesting. Retrying!");
                } else {
                    failed = true;
                    logger.error("Exception [{}] occurred while harvesting", e.getLocalizedMessage());
                    return;
                }
            }
        } while (retry && !stopped);
    }

    /**
     * Queries the {@link #rdfEndpoint(String)} with each of the {@link #rdfQueries}
     * and harvests the results of the query.
     */
    private void harvestFromEndpoint() {
        logger.info("Harvest from endpoint ---------------------------------------------------------------");
        Model model = ModelFactory.createDefaultModel();
        int queryNumber = 0;

        try {
            for (String rdfQuery : rdfQueries) {
                if (stopped) return;
                queryNumber++;

                QueryExecution qExec = getExecutableQuery(rdfQuery, queryNumber);

                model.add(executeQuery(qExec, queryNumber));

            }
            uploadDataToES(model);
        } catch (Exception e) {
            failed = true;
        }
    }

    private QueryExecution getExecutableQuery(String rdfQuery, int queryNumber) {
        Query query;
        QueryExecution qExec;
        try {
            logger.info("QUERY:");
            logger.info(rdfQuery);
            query = QueryFactory.create(rdfQuery);
        } catch (QueryParseException qpe) {
            logger.error(
                    "Could not parse query {}. \n [{}]. Please provide a relevant query. {}",
                    queryNumber, rdfQuery, qpe.getLocalizedMessage());
            throw qpe;
        }

        qExec = QueryExecutionFactory.sparqlService(rdfEndpoint, query);
        qExec.setTimeout(EEASettings.QUERY_TIMEOUT_IN_MILLISECONDS);
        return qExec;
    }

    private Model executeQuery(QueryExecution qExec, Integer queryNumber) {
        Model model;
        logger.info(
                "Harvesting {}/{} query on index [{}] and type [{}]",
                queryNumber, rdfQueries.size(), indexName, typeName);
        try {
            model = getModel(qExec);
        } catch (Exception e) {
            logger.error("Harvesting failed on {}. query on index [{}] and type [{}]",
                    queryNumber, indexName, typeName);
            logger.error("Exception: {}", e.getLocalizedMessage());
            logger.error("Query:\n{}", rdfQueries.get(queryNumber - 1));
            throw e;
        } finally {
            qExec.close();
        }
        return model;
    }

    /**
     * Harvests all the triplets from each URI in the @rdfUris list
     */
    private void harvestFromDumps() {
        for (String uri : rdfUris) {
            if (stopped) return;
            uri = uri.trim();
            if (uri.isEmpty()) continue;

            logger.info("Harvesting uri [{}]", uri);

            Model model = ModelFactory.createDefaultModel();
            Lang lang = RDFLanguages.RDFXML;
            try {

                logger.info("Creating model");
                setHarvestState(HarvestStates.CREATING_MODEL);
                RDFDataMgr.read(model, uri, lang);
                if (stopped) return;
                logger.info("Creating model - DONE");

                BulkRequest bulkRequest = new BulkRequest();

                addModelToES(model, bulkRequest, true);
            } catch (RiotException re) {
                logger.error("Illegal {} character [{}]", lang.getName(), re.getLocalizedMessage());
            } catch (Exception e) {
                logger.error("Exception when harvesting url: {}. Details: {}",
                        uri, e.getLocalizedMessage());
            }
        }
    }

    private Map<String, Object> addCountingToJsonMap(Map<String, Object> jsonMap) {
        Iterator it = jsonMap.entrySet().iterator();
        Map<String, Object> countingMap = new HashMap<String, Object>();
        ArrayList<Object> itemsCount = new ArrayList<Object>();

        //TODO: fix
        while (it.hasNext()) {
            itemsCount = new ArrayList<Object>();
            Map.Entry<String, Object> pair = (Map.Entry<String, Object>) it.next();

            if (pair.getValue() instanceof List<?>) {
                countingMap.put("items_count_" + pair.getKey(), ((List) pair.getValue()).size());
            } else {
                if ((pair.getValue() instanceof Number)) {
                    countingMap.put("items_count_" + pair.getKey(), pair.getValue());
                } else {
                    itemsCount.add(pair.getValue());
                    countingMap.put("items_count_" + pair.getKey(), itemsCount.size());
                }
            }
        }
        jsonMap.putAll(countingMap);
        return jsonMap;
    }

    /**
     * Get JSON map for a given resource by applying the river settings
     *
     * @param rs           resource being processed
     * @param properties   properties to be indexed
     * @param model        model returned by the indexing query
     * @param getPropLabel if set to true all URI property values will be indexed
     *                     as their label. The label is taken as the value of
     *                     one of the properties set in {@link #uriDescriptionList}.
     * @return map of properties to be indexed for res
     */
    private HashMap<String, HashMap<String, Object>> getJsonMap(Resource rs, Set<Property> properties, Model model,
                                                                boolean getPropLabel) {

        ESNormalizer esNormalizer = new ESNormalizer(rs, properties, model, getPropLabel, this);
        esNormalizer.setAddUriForResource(addUriForResource);
        esNormalizer.setNormalizeProp(normalizeProp);
        esNormalizer.setWhiteMap(whiteMap);
        esNormalizer.setBlackMap(blackMap);
        esNormalizer.setNormalizeObj(normalizeObj);
        esNormalizer.setAddLanguage(addLanguage);
        esNormalizer.setNormalizeMissing(normalizeMissing);
        esNormalizer.setLanguage(language);

        esNormalizer.process();

        return esNormalizer.getJsonMaps();
    }

    /**
     * Index all the resources in a Jena Model to ES
     *
     * @param model        the model to index
     * @param bulkRequest  a BulkRequestBuilder
     * @param getPropLabel if set to true all URI property values will be indexed
     *                     as their label. The label is taken as the value of
     *                     one of the properties set in {@link #uriDescriptionList}.
     */
    @SuppressWarnings("Duplicates")
    private void addModelToES(Model model, BulkRequest bulkRequest, boolean getPropLabel) {
        addModelToES(model, bulkRequest, getPropLabel, 0);

    }


    @SuppressWarnings("Duplicates")
    private ArrayList<String> addModelToES(Model model, BulkRequest bulkRequest, boolean getPropLabel, int modelCounter) {
        logger.info("Adding model to ES");
        setHarvestState(HarvestStates.INDEXING);
        ArrayList<String> urisWithESErrors = new ArrayList<>();
        long startTime = System.currentTimeMillis();
        long bulkLength = 0;
        HashSet<Property> properties = new HashSet<>();

        StmtIterator it = model.listStatements();
        while (it.hasNext()) {
            Statement st = it.nextStatement();
            Property prop = st.getPredicate();
            String property = prop.toString();


            if (rdfPropList.isEmpty()
                    || (isWhitePropList && rdfPropList.contains(property))
                    || (!isWhitePropList && !rdfPropList.contains(property))
                    || (normalizeProp.containsKey(property))) {
                properties.add(prop);
            }
        }

        ResIterator resIt = model.listSubjects();
        int jsonMapCounter = 0;
        while (resIt.hasNext()) {
            if (stopped) return null;
            Resource rs = resIt.nextResource();

            long startJsonMap = System.currentTimeMillis();

            HashMap<String, HashMap<String, Object>> jsonMap = getJsonMap(rs, properties, model, getPropLabel);
            long endJsonMap = System.currentTimeMillis();

            if (DEBUG_TIME) {
                logger.info("jsonMapTime : #" + modelCounter + "|" + jsonMapCounter + " : " + "{}",
                        endJsonMap - startJsonMap
                );
            }

            jsonMapCounter++;

            if (addCounting) {
                //TODO: repair hashmap to map
                //jsonMap = addCountingToJsonMap(jsonMap.get(""));
            }
            for (String lang : jsonMap.keySet()) {

                //TODO: prepareIndex - DONE ; make request async?
                IndexRequest indexRequest = new IndexRequest(indexWithPrefix, typeName, rs.toString()).source(jsonMap.get(lang));
                indexRequest = indexRequest.id(indexRequest.id() + "@" + lang);
                //.source(mapToString(jsonMap)));
                bulkRequest.add(indexRequest);
            }
            bulkLength++;

            // We want to execute the bulk for every  DEFAULT_BULK_SIZE requests
            if (bulkLength % EEASettings.DEFAULT_BULK_SIZE == 0) {
                BulkResponse bulkResponse = null;

                //TODO: make request async
                try {
                    bulkResponse = client.bulk(bulkRequest, RequestOptions.DEFAULT);
                } catch (IOException e) {
                    logger.error(e.getMessage());
                }

                if (bulkResponse.hasFailures()) {
                    urisWithESErrors = processBulkResponseFailure(bulkResponse);
                }

                // After executing, flush the BulkRequestBuilder.
                bulkRequest = new BulkRequest();
            }
        }

        // Execute remaining requests
        if (bulkRequest.numberOfActions() > 0) {
            //BulkResponse response = bulkRequest.execute().actionGet();
            BulkResponse response = null;
            try {
                response = client.bulk(bulkRequest, RequestOptions.DEFAULT);
            } catch (IOException e) {
                logger.error(e.getMessage());
            }

            // Handle failure by iterating through each bulk response item
            if (response != null && response.hasFailures()) {
                urisWithESErrors = processBulkResponseFailure(response);
            }
        }
        long documents = bulkLength - urisWithESErrors.size();
        updateRecord.addToIndexedESHits(documents);
        // Show time taken to index the documents
        logger.info("Indexed {} documents on {}/{} in {} seconds",
                documents, indexName, typeName,
                (System.currentTimeMillis() - startTime) / 1000.0);
        if (urisWithESErrors.size() > 0) {
            logger.info("Couldn't index {} documents", urisWithESErrors.size());
        }
        logger.info("Adding model to ES - Done");
        return urisWithESErrors;
    }


    /**
     * This method processes failures by iterating through each bulk response item
     *
     * @param response, a BulkResponse
     **/
    private ArrayList<String> processBulkResponseFailure(BulkResponse response) {
        ArrayList<String> urisWithESErrors = new ArrayList<String>();
        logger.warn("There were failures when executing bulk : " + response.buildFailureMessage());

        for (BulkItemResponse item : response.getItems()) {
            if (item.isFailed()) {
                if (logger.isDebugEnabled()) {
                    logger.info("Error {} occurred on index {}, type {}, id {} for {} operation "
                            , item.getFailureMessage(), item.getIndex(), item.getType(), item.getId()
                            , item.getOpType());
                }
                urisWithESErrors.add(String.format("%s %s", item.getId(), item.getFailureMessage()));
            }
        }
        return urisWithESErrors;
    }

    /**
     * Converts a map of results to a String JSON representation for it
     *
     * @param map a map that matches properties with an ArrayList of
     *            values
     * @return the JSON representation for the map, as a String
     */
    private String mapToString(Map<String, ArrayList<String>> map) {
        StringBuilder result = new StringBuilder("{");
        for (Map.Entry<String, ArrayList<String>> entry : map.entrySet()) {
            ArrayList<String> value = entry.getValue();
            if (value.size() == 1)
                result.append(String.format("\"%s\" : %s,\n",
                        entry.getKey(), value.get(0)));
            else
                result.append(String.format("\"%s\" : %s,\n",
                        entry.getKey(), value.toString()));
        }

        result.setCharAt(result.length() - 2, '}');
        return result.toString();
    }

    private void rollback() {
        logger.info("Rollback on {} harvest", indexName);
        DeleteIndexRequest deleteRequest = new DeleteIndexRequest(indexWithPrefix);
        try {
            client.indices().delete(deleteRequest, RequestOptions.DEFAULT);
        } catch (IOException e) {
            logger.error("Rollback: Could not delete index " + indexWithPrefix, e);
        } catch (ElasticsearchException e) {
            if (e.status() != RestStatus.NOT_FOUND) {
                logger.error("Rollback: Could not delete index " + indexWithPrefix, e);
            }
        }
    }

    @Override
    public void stop() {
        logger.warn("Stopping {} harvest", indexName);
        harvestState = HarvestStates.STOPPING;
        if (startTime != 0) updateRecord.setLastUpdateDuration(System.currentTimeMillis() - startTime);
        updateRecord.setFinishState(UpdateStates.STOPPED);
        closed = true;
        stopped = true;
    }

    @Override
    protected void finalize() throws Throwable {
        indexer.runningHarvestersPoolRemove(this);
        super.finalize();
    }
}