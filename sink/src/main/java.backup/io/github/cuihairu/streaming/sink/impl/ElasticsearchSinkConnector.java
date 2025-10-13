package io.github.cuihairu.redis.streaming.sink.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.sink.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch sink connector for writing data to Elasticsearch clusters
 */
@Slf4j
public class ElasticsearchSinkConnector extends AbstractSinkConnector {

    private static final String HOSTS_PROPERTY = "hosts";
    private static final String INDEX_PROPERTY = "index";
    private static final String TYPE_PROPERTY = "type";
    private static final String INDEX_PATTERN_PROPERTY = "index.pattern";
    private static final String DATE_FORMAT_PROPERTY = "date.format";

    private ElasticsearchClient client;
    private RestClient restClient;
    private String defaultIndex;
    private String indexPattern;
    private String documentType;
    private DateTimeFormatter dateFormatter;
    private ObjectMapper objectMapper;

    public ElasticsearchSinkConnector(SinkConfiguration configuration) {
        super(configuration);
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected void doStart() throws Exception {
        // Validate configuration
        configuration.validate();

        // Parse configuration
        String hosts = (String) configuration.getProperty(HOSTS_PROPERTY, "localhost:9200");
        this.defaultIndex = (String) configuration.getProperty(INDEX_PROPERTY, "streaming-data");
        this.indexPattern = (String) configuration.getProperty(INDEX_PATTERN_PROPERTY);
        this.documentType = (String) configuration.getProperty(TYPE_PROPERTY, "_doc");
        String dateFormat = (String) configuration.getProperty(DATE_FORMAT_PROPERTY, "yyyy-MM-dd");
        this.dateFormatter = DateTimeFormatter.ofPattern(dateFormat);

        // Create Elasticsearch client
        createElasticsearchClient(hosts);

        log.info("Elasticsearch sink connector initialized with hosts: {}, default index: {}",
                hosts, defaultIndex);
    }

    @Override
    protected void doStop() throws Exception {
        if (client != null) {
            if (restClient != null) {
                restClient.close();
            }
        }
    }

    @Override
    protected SinkResult doWrite(Collection<SinkRecord> records) throws Exception {
        if (records.isEmpty()) {
            return SinkResult.success(0, "No records to write");
        }

        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        List<SinkRecord> recordList = new ArrayList<>(records);

        // Build bulk request
        for (SinkRecord record : recordList) {
            try {
                String index = determineIndex(record);
                String documentId = record.getKey(); // Use record key as document ID, or null for auto-generated

                Map<String, Object> document = prepareDocument(record);

                bulkBuilder.operations(op -> op
                        .index(idx -> {
                            idx.index(index).document(document);
                            if (documentId != null) {
                                idx.id(documentId);
                            }
                            return idx;
                        })
                );

            } catch (Exception e) {
                log.error("Error preparing document for record: {}", record, e);
                // Continue with other records
            }
        }

        // Execute bulk request
        BulkRequest bulkRequest = bulkBuilder.build();
        BulkResponse bulkResponse = client.bulk(bulkRequest);

        // Process response
        return processBulkResponse(bulkResponse, recordList);
    }

    @Override
    protected void doFlush() throws Exception {
        // Elasticsearch auto-flushes, but we can force a refresh if needed
        // This is optional and depends on use case
        log.debug("Flush requested for Elasticsearch connector: {}", getName());
    }

    private void createElasticsearchClient(String hosts) {
        String[] hostArray = hosts.split(",");
        HttpHost[] httpHosts = new HttpHost[hostArray.length];

        for (int i = 0; i < hostArray.length; i++) {
            String[] parts = hostArray[i].trim().split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9200;
            httpHosts[i] = new HttpHost(host, port);
        }

        this.restClient = RestClient.builder(httpHosts).build();

        ElasticsearchTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper(objectMapper));

        this.client = new ElasticsearchClient(transport);
    }

    private String determineIndex(SinkRecord record) {
        if (indexPattern != null) {
            // Use pattern with date substitution
            String date = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
                    .format(java.time.ZonedDateTime.ofInstant(record.getTimestamp(), java.time.ZoneId.systemDefault()));
            return indexPattern.replace("{date}", date)
                    .replace("{topic}", record.getTopic() != null ? record.getTopic() : "unknown");
        }
        return defaultIndex;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> prepareDocument(SinkRecord record) throws Exception {
        Map<String, Object> document;

        if (record.getValue() instanceof Map) {
            document = (Map<String, Object>) record.getValue();
        } else if (record.getValue() instanceof String) {
            // Try to parse as JSON
            try {
                document = objectMapper.readValue((String) record.getValue(), Map.class);
            } catch (Exception e) {
                // Treat as plain text
                document = Map.of("message", record.getValue());
            }
        } else {
            // Convert to JSON and back to Map
            String json = objectMapper.writeValueAsString(record.getValue());
            document = objectMapper.readValue(json, Map.class);
        }

        // Add metadata fields
        if (record.getTimestamp() != null) {
            document.put("@timestamp", record.getTimestamp().toString());
        }

        if (record.getTopic() != null) {
            document.put("@topic", record.getTopic());
        }

        if (record.getHeaders() != null && !record.getHeaders().isEmpty()) {
            document.put("@headers", record.getHeaders());
        }

        return document;
    }

    private SinkResult processBulkResponse(BulkResponse bulkResponse, List<SinkRecord> records) {
        List<SinkError> errors = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        List<BulkResponseItem> items = bulkResponse.items();
        for (int i = 0; i < items.size() && i < records.size(); i++) {
            BulkResponseItem item = items.get(i);
            SinkRecord record = records.get(i);

            if (item.error() != null) {
                failureCount++;
                SinkError error = new SinkError(
                        record,
                        "Elasticsearch error: " + item.error().reason(),
                        null,
                        item.error().type()
                );
                errors.add(error);
            } else {
                successCount++;
            }
        }

        if (failureCount == 0) {
            return SinkResult.success(successCount, "All records written successfully");
        } else if (successCount > 0) {
            return SinkResult.partialSuccess(successCount, failureCount, errors);
        } else {
            return SinkResult.failure(failureCount, "All records failed", errors);
        }
    }
}