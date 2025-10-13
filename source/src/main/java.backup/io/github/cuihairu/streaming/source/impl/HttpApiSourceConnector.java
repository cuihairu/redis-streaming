package io.github.cuihairu.redis.streaming.source.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.source.SourceConfiguration;
import io.github.cuihairu.redis.streaming.source.SourceHealthStatus;
import io.github.cuihairu.redis.streaming.source.SourceRecord;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicNameValuePair;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * HTTP API source connector for polling data from HTTP endpoints
 */
@Slf4j
public class HttpApiSourceConnector extends AbstractSourceConnector {

    private static final String URL_PROPERTY = "url";
    private static final String METHOD_PROPERTY = "method";
    private static final String HEADERS_PROPERTY = "headers";
    private static final String BODY_PROPERTY = "body";
    private static final String RESPONSE_PATH_PROPERTY = "response.path";
    private static final String TOPIC_PROPERTY = "topic";

    private CloseableHttpClient httpClient;
    private ObjectMapper objectMapper;
    private String url;
    private String method;
    private Map<String, String> headers;
    private String requestBody;
    private String responsePath;
    private String topic;

    public HttpApiSourceConnector(SourceConfiguration configuration) {
        super(configuration);
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected void doStart() throws Exception {
        // Validate configuration
        configuration.validate();

        // Initialize HTTP client
        this.httpClient = HttpClients.createDefault();

        // Parse configuration
        this.url = (String) configuration.getProperty(URL_PROPERTY);
        this.method = (String) configuration.getProperty(METHOD_PROPERTY, "GET");
        this.requestBody = (String) configuration.getProperty(BODY_PROPERTY);
        this.responsePath = (String) configuration.getProperty(RESPONSE_PATH_PROPERTY);
        this.topic = (String) configuration.getProperty(TOPIC_PROPERTY, "http-api-data");

        // Parse headers
        Object headersObj = configuration.getProperty(HEADERS_PROPERTY);
        if (headersObj instanceof Map) {
            this.headers = (Map<String, String>) headersObj;
        } else if (headersObj instanceof String) {
            this.headers = parseHeadersString((String) headersObj);
        } else {
            this.headers = new HashMap<>();
        }

        log.info("HTTP API source connector initialized: {} {}", method, url);

        // Start scheduled polling if configured
        startScheduledPolling();
    }

    @Override
    protected void doStop() throws Exception {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    @Override
    protected List<SourceRecord> doPoll() throws Exception {
        List<SourceRecord> records = new ArrayList<>();

        try {
            String response = executeHttpRequest();

            if (response != null && !response.trim().isEmpty()) {
                // Parse JSON response
                JsonNode jsonNode = objectMapper.readTree(response);

                // Extract data from response path if specified
                JsonNode dataNode = responsePath != null ? extractFromPath(jsonNode, responsePath) : jsonNode;

                if (dataNode != null) {
                    if (dataNode.isArray()) {
                        // Handle array response
                        for (JsonNode item : dataNode) {
                            SourceRecord record = createSourceRecord(item);
                            records.add(record);
                        }
                    } else {
                        // Handle single object response
                        SourceRecord record = createSourceRecord(dataNode);
                        records.add(record);
                    }
                }
            }

            updateHealthStatus(SourceHealthStatus.healthy("Successfully polled data from " + url));

        } catch (Exception e) {
            updateHealthStatus(SourceHealthStatus.degraded("Error polling from " + url + ": " + e.getMessage()));
            throw e;
        }

        return records;
    }

    private String executeHttpRequest() throws IOException, ParseException {
        CloseableHttpResponse response = null;

        try {
            if ("POST".equalsIgnoreCase(method)) {
                HttpPost httpPost = new HttpPost(url);

                // Add headers
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    httpPost.addHeader(header.getKey(), header.getValue());
                }

                // Add request body
                if (requestBody != null && !requestBody.trim().isEmpty()) {
                    StringEntity entity = new StringEntity(requestBody);
                    httpPost.setEntity(entity);
                }

                response = httpClient.execute(httpPost);
            } else {
                HttpGet httpGet = new HttpGet(url);

                // Add headers
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    httpGet.addHeader(header.getKey(), header.getValue());
                }

                response = httpClient.execute(httpGet);
            }

            int statusCode = response.getCode();
            if (statusCode >= 200 && statusCode < 300) {
                HttpEntity entity = response.getEntity();
                return entity != null ? EntityUtils.toString(entity) : null;
            } else {
                throw new IOException("HTTP request failed with status: " + statusCode);
            }

        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private JsonNode extractFromPath(JsonNode root, String path) {
        String[] pathParts = path.split("\\.");
        JsonNode current = root;

        for (String part : pathParts) {
            if (current == null) {
                return null;
            }

            if (part.contains("[") && part.contains("]")) {
                // Handle array access like "data[0]"
                String fieldName = part.substring(0, part.indexOf("["));
                String indexStr = part.substring(part.indexOf("[") + 1, part.indexOf("]"));

                current = current.get(fieldName);
                if (current != null && current.isArray()) {
                    try {
                        int index = Integer.parseInt(indexStr);
                        current = current.get(index);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            } else {
                current = current.get(part);
            }
        }

        return current;
    }

    private SourceRecord createSourceRecord(JsonNode dataNode) {
        Map<String, String> recordHeaders = new HashMap<>();
        recordHeaders.put("http.url", url);
        recordHeaders.put("http.method", method);
        recordHeaders.put("timestamp", Instant.now().toString());

        return new SourceRecord(
                getName(),
                topic,
                null, // No specific key
                dataNode.toString(),
                recordHeaders
        );
    }

    private Map<String, String> parseHeadersString(String headersStr) {
        Map<String, String> parsed = new HashMap<>();

        if (headersStr != null && !headersStr.trim().isEmpty()) {
            String[] headerPairs = headersStr.split(",");
            for (String pair : headerPairs) {
                String[] keyValue = pair.split(":", 2);
                if (keyValue.length == 2) {
                    parsed.put(keyValue[0].trim(), keyValue[1].trim());
                }
            }
        }

        return parsed;
    }
}