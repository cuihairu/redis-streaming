package io.github.cuihairu.redis.streaming.registry.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.Protocol;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralized codec for Registry instance entries to/from Redis Hash fields.
 */
public final class InstanceEntryCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InstanceEntryCodec() {}

    /** Build instance entry map for registration/update. */
    public static Map<String, Object> buildInstanceData(ServiceInstance instance, long currentTime) {
        Map<String, Object> map = new HashMap<>();
        map.put("host", instance.getHost());
        map.put("port", instance.getPort());
        map.put("protocol", instance.getProtocol().getName());
        map.put("enabled", instance.isEnabled());
        map.put("healthy", instance.isHealthy());
        map.put("weight", instance.getWeight());
        map.put("ephemeral", instance.isEphemeral());
        map.put("metadata", instance.getMetadata());
        map.put("registrationTime", currentTime);
        map.put("lastHeartbeatTime", currentTime);
        map.put("lastMetadataUpdate", currentTime);
        return map;
    }

    /** Parse instance map from Redis Hash (String values). */
    public static ServiceInstance parseInstance(String serviceName, String instanceId, Map<String, String> data) {
        try {
            String host = data.get("host");
            String portStr = data.get("port");
            String protocolName = data.get("protocol");
            String enabledStr = data.get("enabled");
            String healthyStr = data.get("healthy");
            String weightStr = data.get("weight");
            String ephemeralStr = data.get("ephemeral");
            String registrationTimeStr = data.get("registrationTime");
            String lastHeartbeatTimeStr = data.get("lastHeartbeatTime");

            if (host == null || portStr == null) {
                return null;
            }

            int port = Integer.parseInt(portStr);
            Protocol protocol = parseProtocol(protocolName);
            boolean enabled = enabledStr != null ? Boolean.parseBoolean(enabledStr) : true;
            boolean healthy = healthyStr != null ? Boolean.parseBoolean(healthyStr) : true;
            int weight = weightStr != null ? Integer.parseInt(weightStr) : 1;
            boolean ephemeral = ephemeralStr != null ? Boolean.parseBoolean(ephemeralStr) : true;

            LocalDateTime registrationTime = null;
            if (registrationTimeStr != null) {
                registrationTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(Long.parseLong(registrationTimeStr)), ZoneId.systemDefault());
            }
            LocalDateTime lastHeartbeatTime = null;
            if (lastHeartbeatTimeStr != null) {
                lastHeartbeatTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(Long.parseLong(lastHeartbeatTimeStr)), ZoneId.systemDefault());
            }

            Map<String, String> metadata = new HashMap<>();
            // Prefer JSON metadata field
            String metadataJson = data.get("metadata");
            if (metadataJson != null && !metadataJson.isEmpty()) {
                try {
                    Map<String, Object> parsed = MAPPER.readValue(metadataJson, new TypeReference<Map<String, Object>>(){});
                    for (Map.Entry<String, Object> e : parsed.entrySet()) {
                        if (e.getValue() != null) metadata.put(e.getKey(), String.valueOf(e.getValue()));
                    }
                } catch (Exception ignore) {
                    metadataJson = null;
                }
            }
            // Fallback legacy: metadata_* prefixed fields
            if (metadataJson == null) {
                for (Map.Entry<String, String> e : data.entrySet()) {
                    String k = e.getKey();
                    if (k.startsWith("metadata_")) {
                        metadata.put(k.substring("metadata_".length()), e.getValue());
                    }
                }
            }

            return DefaultServiceInstance.builder()
                    .serviceName(serviceName)
                    .instanceId(instanceId)
                    .host(host)
                    .port(port)
                    .protocol(protocol)
                    .enabled(enabled)
                    .healthy(healthy)
                    .weight(weight)
                    .ephemeral(ephemeral)
                    .metadata(metadata)
                    .registrationTime(registrationTime != null ? registrationTime : LocalDateTime.now())
                    .lastHeartbeatTime(lastHeartbeatTime)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private static Protocol parseProtocol(String protocolName) {
        if (protocolName == null) return StandardProtocol.HTTP;
        try { return StandardProtocol.fromName(protocolName); } catch (IllegalArgumentException e) { return StandardProtocol.HTTP; }
    }
}

