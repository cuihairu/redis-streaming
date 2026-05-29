package io.github.cuihairu.redis.streaming.registry.lua;

import org.redisson.api.RedissonClient;
import org.redisson.api.RScript;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Registry Lua script executor
 * <p>
 * Uses script caching optimization:
 * 1. Preloads scripts to Redis via scriptLoad() during initialization
 * 2. Uses evalSha() to transmit SHA-1 digests instead of full scripts during execution
 * 3. Automatically reloads when NOSCRIPT errors occur (Redis restart/scripts cleared)
 */
public class RegistryLuaScriptExecutor {

    private static final Logger logger = LoggerFactory.getLogger(RegistryLuaScriptExecutor.class);

    private final RedissonClient redissonClient;
    private final RScript script;

    // Script SHA-1 cache (volatile ensures visibility)
    private volatile String heartbeatUpdateScriptSha;
    private volatile String cleanupExpiredInstancesScriptSha;
    private volatile String cleanupExpiredInstancesWithSnapshotsScriptSha;
    private volatile String getActiveInstancesScriptSha;
    private volatile String registerInstanceScriptSha;
    private volatile String deregisterInstanceScriptSha;
    private volatile String getInstancesByMetadataScriptSha;

    // Heartbeat update script (supports separate metadata and metrics updates)
    private static final String HEARTBEAT_UPDATE_SCRIPT = """
            -- Multi-mode heartbeat update script
            local heartbeat_key = KEYS[1]    -- ZSet
            local instance_key = KEYS[2]     -- Hash
            local instance_id = ARGV[1]
            local heartbeat_time = ARGV[2]
            local update_mode = ARGV[3]      -- "heartbeat_only" | "metrics_update" | "metadata_update" | "full_update"
            local metadata_json = ARGV[4]    -- Optional, metadata JSON
            local metrics_json = ARGV[5]     -- Optional, metrics JSON
            local ttl_seconds = ARGV[6]      -- Optional, instance Hash TTL (only for ephemeral instances)

            -- Parameter validation
            if not heartbeat_key or not instance_key or not instance_id or not heartbeat_time then
                return redis.error_reply("Missing required parameters")
            end

            -- Check if instance exists (heartbeat update should not create new instances)
            if redis.call('EXISTS', instance_key) == 0 then
                return 0  -- Instance does not exist, may have been deregistered
            end

            -- Always update heartbeat timestamp (ARGV parameters are strings, need to convert to number)
            local heartbeat_num = tonumber(heartbeat_time)
            if not heartbeat_num then
                return redis.error_reply("Invalid heartbeat_time: must be a number")
            end
            redis.call('ZADD', heartbeat_key, heartbeat_num, instance_id)
            redis.call('HSET', instance_key, 'lastHeartbeatTime', heartbeat_time)

            -- Refresh instance Hash TTL (only effective for ephemeral instances).
            -- Rule: ephemeral=true or unset means ephemeral instance, requires sliding expiration; ephemeral=false means persistent instance, no TTL.
            if ttl_seconds and ttl_seconds ~= '' then
                local ttl_num = tonumber(ttl_seconds)
                if ttl_num and ttl_num > 0 then
                    local ephemeral = redis.call('HGET', instance_key, 'ephemeral')
                    if ephemeral == nil or ephemeral == 'true' then
                        redis.call('EXPIRE', instance_key, ttl_num)
                    end
                end
            end

            -- Execute corresponding updates based on different modes
            if update_mode == 'heartbeat_only' then
                -- Only update timestamp, done
                return 1

            elseif update_mode == 'metrics_update' then
                -- Only update metrics
                if metrics_json and metrics_json ~= '' then
                    redis.call('HSET', instance_key, 'metrics', metrics_json)
                    redis.call('HSET', instance_key, 'lastMetricsUpdate', heartbeat_time)
                end
                return 1

            elseif update_mode == 'metadata_update' then
                -- Only update metadata
                if metadata_json and metadata_json ~= '' then
                    redis.call('HSET', instance_key, 'metadata', metadata_json)
                    redis.call('HSET', instance_key, 'lastMetadataUpdate', heartbeat_time)
                end
                return 1

            elseif update_mode == 'full_update' then
                -- Update both metadata and metrics
                if metadata_json and metadata_json ~= '' then
                    redis.call('HSET', instance_key, 'metadata', metadata_json)
                    redis.call('HSET', instance_key, 'lastMetadataUpdate', heartbeat_time)
                end
                if metrics_json and metrics_json ~= '' then
                    redis.call('HSET', instance_key, 'metrics', metrics_json)
                    redis.call('HSET', instance_key, 'lastMetricsUpdate', heartbeat_time)
                end
                return 1
            end

            return 0
            """;

    // Cleanup expired instances script
    private static final String CLEANUP_EXPIRED_INSTANCES_SCRIPT = """
            -- Cleanup expired instances script (only cleans up ephemeral instances)
            local heartbeat_key = KEYS[1]
            local service_name = ARGV[1]
            local current_time = ARGV[2]
            local timeout_ms = ARGV[3]
            local key_prefix = ARGV[4]

            -- Parameter validation
            if not heartbeat_key or not service_name or not current_time or not timeout_ms or not key_prefix then
                return redis.error_reply("Missing required parameters")
            end

            -- ARGV parameters are strings, need to convert to numbers for arithmetic operations
            local current_time_num = tonumber(current_time)
            local timeout_ms_num = tonumber(timeout_ms)
            if not current_time_num or not timeout_ms_num then
                return redis.error_reply("Invalid numeric parameters")
            end
            local expired_threshold = current_time_num - timeout_ms_num
            local expired_candidates = redis.call('ZRANGEBYSCORE', heartbeat_key, 0, expired_threshold)
            local expired_instances = {}

            if #expired_candidates > 0 then
                for i = 1, #expired_candidates do
                    local instance_id = expired_candidates[i]
                    local instance_key = key_prefix .. ':services:' .. service_name .. ':instance:' .. instance_id

                    -- Get the ephemeral property of the instance
                    local ephemeral = redis.call('HGET', instance_key, 'ephemeral')

                    -- Only clean up ephemeral instances (ephemeral=true or unset)
                    -- Persistent instances (ephemeral=false) are only marked unhealthy, not deleted
                    if ephemeral == nil or ephemeral == 'true' then
                        -- Remove from heartbeat index
                        redis.call('ZREM', heartbeat_key, instance_id)
                        -- Delete instance details
                        redis.call('DEL', instance_key)
                        table.insert(expired_instances, instance_id)
                    else
                        -- Persistent instance only marked as unhealthy
                        redis.call('HSET', instance_key, 'healthy', 'false')
                    end
                end
            end

            return expired_instances
            """;

    // Cleanup expired instances (with instance snapshots) script: returns [id1, json1, id2, json2, ...]
    private static final String CLEANUP_EXPIRED_INSTANCES_WITH_SNAPSHOTS_SCRIPT = """
            local heartbeat_key = KEYS[1]
            local service_name = ARGV[1]
            local current_time = ARGV[2]
            local timeout_ms = ARGV[3]
            local key_prefix = ARGV[4]

            if not heartbeat_key or not service_name or not current_time or not timeout_ms or not key_prefix then
                return redis.error_reply("Missing required parameters")
            end

            local current_time_num = tonumber(current_time)
            local timeout_ms_num = tonumber(timeout_ms)
            if not current_time_num or not timeout_ms_num then
                return redis.error_reply("Invalid numeric parameters")
            end

            local expired_threshold = current_time_num - timeout_ms_num
            local expired_candidates = redis.call('ZRANGEBYSCORE', heartbeat_key, 0, expired_threshold)
            local result = {}

            if #expired_candidates > 0 then
                for i = 1, #expired_candidates do
                    local instance_id = expired_candidates[i]
                    local instance_key = key_prefix .. ':services:' .. service_name .. ':instance:' .. instance_id
                    local ephemeral = redis.call('HGET', instance_key, 'ephemeral')

                    if ephemeral == nil or ephemeral == 'true' then
                        local flat = redis.call('HGETALL', instance_key)
                        local snap = {}
                        for j = 1, #flat, 2 do
                            snap[flat[j]] = flat[j+1]
                        end
                        redis.call('ZREM', heartbeat_key, instance_id)
                        redis.call('DEL', instance_key)
                        table.insert(result, instance_id)
                        table.insert(result, cjson.encode(snap))
                    else
                        redis.call('HSET', instance_key, 'healthy', 'false')
                    end
                end
            end

            return result
            """;

    // Get active instances script
    private static final String GET_ACTIVE_INSTANCES_SCRIPT = """
            -- Get active instances script
            local heartbeat_key = KEYS[1]
            local current_time = ARGV[1]
            local timeout_ms = ARGV[2]

            -- Parameter validation
            if not heartbeat_key or not current_time or not timeout_ms then
                return redis.error_reply("Missing required parameters")
            end

            -- ARGV parameters are strings, need to convert to numbers for arithmetic operations
            local current_time_num = tonumber(current_time)
            local timeout_ms_num = tonumber(timeout_ms)
            if not current_time_num or not timeout_ms_num then
                return redis.error_reply("Invalid numeric parameters")
            end
            local active_threshold = current_time_num - timeout_ms_num
            local active_instances = redis.call('ZRANGEBYSCORE', heartbeat_key, active_threshold, '+inf', 'WITHSCORES')

            return active_instances
            """;

    // Instance registration script (uses new format: metadata and metrics as JSON strings)
    private static final String REGISTER_INSTANCE_SCRIPT = """
            -- Instance registration script
            local services_key = KEYS[1]        -- Service index Set
            local heartbeat_key = KEYS[2]       -- Heartbeat ZSet
            local instance_key = KEYS[3]        -- Instance details Hash

            local service_name = ARGV[1]
            local instance_id = ARGV[2]
            local heartbeat_time = ARGV[3]
            local instance_data_json = ARGV[4]
            local ttl_seconds = ARGV[5]

            -- Parameter validation
            if not services_key or not heartbeat_key or not instance_key then
                return redis.error_reply("Missing required KEYS")
            end
            if not service_name or not instance_id or not heartbeat_time or not instance_data_json then
                return redis.error_reply("Missing required ARGV")
            end

            -- Add service to service index
            redis.call('SADD', services_key, service_name)

            -- Add heartbeat timestamp (ARGV parameters are strings, need to convert to number)
            local heartbeat_num = tonumber(heartbeat_time)
            if not heartbeat_num then
                return redis.error_reply("Invalid heartbeat_time: must be a number")
            end
            redis.call('ZADD', heartbeat_key, heartbeat_num, instance_id)

            -- Parse instance data
            local instance_data = cjson.decode(instance_data_json)

            -- Store base fields (non-metadata and non-metrics)
            for key, value in pairs(instance_data) do
                if value ~= cjson.null and key ~= 'metadata' and key ~= 'metrics' then
                    if type(value) == "table" then
                        redis.call('HSET', instance_key, key, cjson.encode(value))
                    else
                        redis.call('HSET', instance_key, key, tostring(value))
                    end
                end
            end

            -- Store metadata (as JSON string)
            if instance_data.metadata and instance_data.metadata ~= cjson.null then
                redis.call('HSET', instance_key, 'metadata', cjson.encode(instance_data.metadata))
            end

            -- Store metrics (as JSON string)
            if instance_data.metrics and instance_data.metrics ~= cjson.null then
                redis.call('HSET', instance_key, 'metrics', cjson.encode(instance_data.metrics))
            end

            -- Add heartbeat time to Hash (redundant storage)
            redis.call('HSET', instance_key, 'lastHeartbeatTime', heartbeat_time)

            -- Set TTL (only effective for ephemeral instances; ARGV parameter needs to convert to integer)
            if ttl_seconds and ttl_seconds ~= '' then
                local ttl_num = tonumber(ttl_seconds)
                if ttl_num and ttl_num > 0 then
                    -- Determine from submitted data if ephemeral instance (default ephemeral)
                    local ephemeral = instance_data['ephemeral']
                    if ephemeral == nil or ephemeral == true or ephemeral == 'true' then
                        redis.call('EXPIRE', instance_key, ttl_num)
                    end
                end
            end

            return 'OK'
            """;

    // Instance deregistration script
    private static final String DEREGISTER_INSTANCE_SCRIPT = """
            -- Instance deregistration script
            local services_key = KEYS[1]        -- Service index Set
            local heartbeat_key = KEYS[2]       -- Heartbeat ZSet
            local instance_key = KEYS[3]        -- Instance details Hash

            local service_name = ARGV[1]
            local instance_id = ARGV[2]

            -- Parameter validation
            if not services_key or not heartbeat_key or not instance_key then
                return redis.error_reply("Missing required KEYS")
            end
            if not service_name or not instance_id then
                return redis.error_reply("Missing required ARGV")
            end

            -- Remove from heartbeat index
            redis.call('ZREM', heartbeat_key, instance_id)

            -- Delete instance details
            redis.call('DEL', instance_key)

            -- Check if heartbeat index is empty
            local remaining_instances = redis.call('ZCARD', heartbeat_key)
            if remaining_instances == 0 then
                -- Heartbeat index is empty, delete heartbeat key
                redis.call('DEL', heartbeat_key)
                -- Remove the service from service index
                redis.call('SREM', services_key, service_name)
            end

            return 'OK'
            """;

    // Get instances by metadata/metrics filter script (supports comparison operators)
    private static final String GET_INSTANCES_BY_METADATA_SCRIPT = """
            -- Get instances by metadata/metrics filter script (supports comparison operators)
            -- Note: metadata and metrics are now stored as JSON strings
            local heartbeat_key = KEYS[1]       -- Heartbeat ZSet
            local key_prefix = ARGV[1]          -- Redis key prefix
            local service_name = ARGV[2]        -- Service name
            local current_time = ARGV[3]        -- Current timestamp
            local timeout_ms = ARGV[4]          -- Timeout (milliseconds)
            local filters_json = ARGV[5]        -- Filter conditions JSON, format: {"metadata":{},"metrics":{}}

            -- Parameter validation
            if not heartbeat_key or not key_prefix or not service_name then
                return redis.error_reply("Missing required parameters")
            end
            if not current_time or not timeout_ms then
                return redis.error_reply("Missing time parameters")
            end

            -- Convert time parameters to numbers
            local current_time_num = tonumber(current_time)
            local timeout_ms_num = tonumber(timeout_ms)
            if not current_time_num or not timeout_ms_num then
                return redis.error_reply("Invalid time parameters: must be numbers")
            end

            -- Parse filter condition keys (supports operators)
            -- Format: field:op, e.g. "age:>=" returns ("age", ">=")
            -- If no operator, returns (field, "==")
            local function parse_filter_key(filter_key)
                -- Find the last colon from the end
                local last_colon = 0
                for i = 1, #filter_key do
                    if string.sub(filter_key, i, i) == ':' then
                        last_colon = i
                    end
                end

                if last_colon > 0 then
                    local possible_op = string.sub(filter_key, last_colon + 1)
                    -- Check if it is a valid operator
                    if possible_op == '==' or possible_op == '!=' or
                       possible_op == '>' or possible_op == '>=' or
                       possible_op == '<' or possible_op == '<=' then
                        return string.sub(filter_key, 1, last_colon - 1), possible_op
                    end
                end

                -- Default to equals operator
                return filter_key, '=='
            end

            -- Compare two values
            -- Try numeric comparison first, fall back to string comparison (lexicographic order)
            local function compare_values(op, actual, expected)
                -- Try numeric conversion
                local actual_num = tonumber(actual)
                local expected_num = tonumber(expected)

                if actual_num and expected_num then
                    -- Numeric comparison
                    if op == '==' then
                        return actual_num == expected_num
                    elseif op == '!=' then
                        return actual_num ~= expected_num
                    elseif op == '>' then
                        return actual_num > expected_num
                    elseif op == '>=' then
                        return actual_num >= expected_num
                    elseif op == '<' then
                        return actual_num < expected_num
                    elseif op == '<=' then
                        return actual_num <= expected_num
                    end
                else
                    -- String comparison (lexicographic order)
                    if op == '==' then
                        return actual == expected
                    elseif op == '!=' then
                        return actual ~= expected
                    elseif op == '>' then
                        return actual > expected
                    elseif op == '>=' then
                        return actual >= expected
                    elseif op == '<' then
                        return actual < expected
                    elseif op == '<=' then
                        return actual <= expected
                    end
                end

                return false
            end

            -- Check filter conditions for a single data source (metadata or metrics)
            local function check_filters(data_json, filters)
                if not filters or next(filters) == nil then
                    return true  -- No filter conditions, pass directly
                end

                if not data_json then
                    return false  -- Needs filtering but data does not exist
                end

                -- Parse JSON
                local data = cjson.decode(data_json)

                -- Check all filter conditions (AND relationship)
                for filter_key, filter_value in pairs(filters) do
                    local field, op = parse_filter_key(filter_key)
                    local actual_value = data[field]

                    if not actual_value then
                        return false  -- Field does not exist
                    end

                    if not compare_values(op, actual_value, filter_value) then
                        return false  -- Does not match
                    end
                end

                return true
            end

            -- Calculate active instances threshold
            local active_threshold = current_time_num - timeout_ms_num

            -- Get all active instance IDs
            local active_instance_ids = redis.call('ZRANGEBYSCORE', heartbeat_key, active_threshold, '+inf')

            -- Parse filter conditions
            local metadata_filters = {}
            local metrics_filters = {}

            if filters_json and filters_json ~= '' then
                local all_filters = cjson.decode(filters_json)
                -- Compatible with old format: if it is a simple map, treat as metadata filter
                if all_filters.metadata or all_filters.metrics then
                    metadata_filters = all_filters.metadata or {}
                    metrics_filters = all_filters.metrics or {}
                else
                    -- Old format compatibility: treat directly as metadata filter
                    metadata_filters = all_filters
                end
            end

            -- List of matched instance IDs
            local matched_instances = {}

            -- Iterate each active instance, check if it matches filter conditions
            for i = 1, #active_instance_ids do
                local instance_id = active_instance_ids[i]
                local instance_key = key_prefix .. ':services:' .. service_name .. ':instance:' .. instance_id

                -- If no filter conditions, add directly
                if next(metadata_filters) == nil and next(metrics_filters) == nil then
                    table.insert(matched_instances, instance_id)
                else
                    -- Get the instance's metadata and metrics JSON
                    local metadata_json = redis.call('HGET', instance_key, 'metadata')
                    local metrics_json = redis.call('HGET', instance_key, 'metrics')

                    -- Check metadata filter conditions
                    local metadata_match = check_filters(metadata_json, metadata_filters)

                    -- Check metrics filter conditions
                    local metrics_match = check_filters(metrics_json, metrics_filters)

                    -- Add to result only if all conditions match (AND relationship)
                    if metadata_match and metrics_match then
                        table.insert(matched_instances, instance_id)
                    end
                end
            end

            return matched_instances
            """;

    public RegistryLuaScriptExecutor(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.script = redissonClient.getScript(StringCodec.INSTANCE); // Use StringCodec to ensure parameters are correctly passed to Lua
        // Preload all scripts during initialization
        initScripts();
    }

    /**
     * Initialize scripts: load into Redis and cache SHA-1 digests
     */
    private void initScripts() {
        try {
            heartbeatUpdateScriptSha = script.scriptLoad(HEARTBEAT_UPDATE_SCRIPT);
            cleanupExpiredInstancesScriptSha = script.scriptLoad(CLEANUP_EXPIRED_INSTANCES_SCRIPT);
            cleanupExpiredInstancesWithSnapshotsScriptSha = script.scriptLoad(CLEANUP_EXPIRED_INSTANCES_WITH_SNAPSHOTS_SCRIPT);
            getActiveInstancesScriptSha = script.scriptLoad(GET_ACTIVE_INSTANCES_SCRIPT);
            registerInstanceScriptSha = script.scriptLoad(REGISTER_INSTANCE_SCRIPT);
            deregisterInstanceScriptSha = script.scriptLoad(DEREGISTER_INSTANCE_SCRIPT);
            getInstancesByMetadataScriptSha = script.scriptLoad(GET_INSTANCES_BY_METADATA_SCRIPT);

            logger.info("Lua scripts loaded successfully - heartbeat: {}, cleanup: {}, cleanup_snap: {}, active: {}, register: {}, deregister: {}, metadata_filter: {}",
                    heartbeatUpdateScriptSha, cleanupExpiredInstancesScriptSha, cleanupExpiredInstancesWithSnapshotsScriptSha,
                    getActiveInstancesScriptSha, registerInstanceScriptSha,
                    deregisterInstanceScriptSha, getInstancesByMetadataScriptSha);
        } catch (Exception e) {
            logger.error("Failed to load Lua scripts, will fallback to eval mode", e);
            // Do not throw exception, allow fallback to eval mode
        }
    }

    /**
     * Reload the specified script (when a NOSCRIPT error is encountered)
     */
    private synchronized String reloadScript(String scriptContent) {
        try {
            return script.scriptLoad(scriptContent);
        } catch (Exception e) {
            logger.error("Failed to reload script", e);
            throw new RuntimeException("Script reload failed", e);
        }
    }

    /**
     * Execute heartbeat update (with SHA caching optimization, supports separate metadata and metrics updates)
     *
     * @param heartbeatKey heartbeat key (ZSet)
     * @param instanceKey instance key (Hash)
     * @param instanceId instance ID
     * @param heartbeatTime heartbeat timestamp
     * @param updateMode update mode: "heartbeat_only", "metrics_update", "metadata_update", "full_update"
     * @param metadataJson metadata JSON (can be null)
     * @param metricsJson metrics JSON (can be null)
     * @param ttlSeconds TTL in seconds (only effective for ephemeral instances)
     */
    public void executeHeartbeatUpdate(String heartbeatKey, String instanceKey, String instanceId,
                                         long heartbeatTime, String updateMode,
                                         String metadataJson, String metricsJson,
                                         int ttlSeconds) {
        try {
            // Prefer evalSha (transmits 40-byte SHA vs ~800-byte script)
            if (heartbeatUpdateScriptSha != null) {
                try {
                    script.evalSha(RScript.Mode.READ_WRITE, heartbeatUpdateScriptSha, RScript.ReturnType.VALUE,
                            List.of(heartbeatKey, instanceKey),
                            instanceId, String.valueOf(heartbeatTime), updateMode,
                            metadataJson != null ? metadataJson : "",
                            metricsJson != null ? metricsJson : "",
                            String.valueOf(ttlSeconds));
                    return;
                } catch (RedisException e) {
                    if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                        // Redis restarted or script was cleared, reload
                        logger.warn("Script not found in Redis cache, reloading...");
                        heartbeatUpdateScriptSha = reloadScript(HEARTBEAT_UPDATE_SCRIPT);
                        // Retry once
                        script.evalSha(RScript.Mode.READ_WRITE, heartbeatUpdateScriptSha, RScript.ReturnType.VALUE,
                                List.of(heartbeatKey, instanceKey),
                                instanceId, String.valueOf(heartbeatTime), updateMode,
                                metadataJson != null ? metadataJson : "",
                                metricsJson != null ? metricsJson : "",
                                String.valueOf(ttlSeconds));
                        return;
                    }
                    throw e;
                }
            }

            // Fallback: use eval directly (when initialization failed)
            logger.debug("Using eval fallback for heartbeat update");
            script.eval(RScript.Mode.READ_WRITE, HEARTBEAT_UPDATE_SCRIPT, RScript.ReturnType.VALUE,
                    List.of(heartbeatKey, instanceKey),
                    instanceId, String.valueOf(heartbeatTime), updateMode,
                    metadataJson != null ? metadataJson : "",
                    metricsJson != null ? metricsJson : "",
                    String.valueOf(ttlSeconds));
        } catch (Exception e) {
            logger.error("Failed to execute heartbeat update script", e);
            throw new RuntimeException("Heartbeat update failed", e);
        }
    }

    /**
     * Execute cleanup of expired instances (with SHA caching optimization)
     */
    @SuppressWarnings("unchecked")
    public List<String> executeCleanupExpiredInstances(String heartbeatKey, String serviceName,
                                                        long currentTime, long timeoutMs, String keyPrefix) {
        try {
            if (cleanupExpiredInstancesScriptSha != null) {
                try {
                    return (List<String>) script.evalSha(RScript.Mode.READ_WRITE, cleanupExpiredInstancesScriptSha, RScript.ReturnType.MULTI,
                            List.of(heartbeatKey),
                            serviceName, String.valueOf(currentTime), String.valueOf(timeoutMs), keyPrefix);
                } catch (RedisException e) {
                    if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                        logger.warn("Cleanup script not found in Redis cache, reloading...");
                        cleanupExpiredInstancesScriptSha = reloadScript(CLEANUP_EXPIRED_INSTANCES_SCRIPT);
                        return (List<String>) script.evalSha(RScript.Mode.READ_WRITE, cleanupExpiredInstancesScriptSha, RScript.ReturnType.MULTI,
                                List.of(heartbeatKey),
                                serviceName, String.valueOf(currentTime), String.valueOf(timeoutMs), keyPrefix);
                    }
                    throw e;
                }
            }

            return (List<String>) script.eval(RScript.Mode.READ_WRITE, CLEANUP_EXPIRED_INSTANCES_SCRIPT, RScript.ReturnType.MULTI,
                    List.of(heartbeatKey),
                    serviceName, String.valueOf(currentTime), String.valueOf(timeoutMs), keyPrefix);
        } catch (Exception e) {
            logger.error("Failed to execute cleanup expired instances script", e);
            throw new RuntimeException("Cleanup expired instances failed", e);
        }
    }

    /**
     * Execute cleanup of expired instances (returns [id,json] pair sequence)
     */
    @SuppressWarnings("unchecked")
    public List<Object> executeCleanupExpiredInstancesWithSnapshots(String heartbeatKey, String serviceName,
                                                                    long currentTime, long timeoutMs, String keyPrefix) {
        try {
            if (cleanupExpiredInstancesWithSnapshotsScriptSha != null) {
                try {
                    return (List<Object>) script.evalSha(RScript.Mode.READ_WRITE, cleanupExpiredInstancesWithSnapshotsScriptSha, RScript.ReturnType.MULTI,
                            List.of(heartbeatKey),
                            serviceName, String.valueOf(currentTime), String.valueOf(timeoutMs), keyPrefix);
                } catch (RedisException e) {
                    if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                        logger.warn("Cleanup-with-snapshots script not found, reloading...");
                        cleanupExpiredInstancesWithSnapshotsScriptSha = reloadScript(CLEANUP_EXPIRED_INSTANCES_WITH_SNAPSHOTS_SCRIPT);
                        return (List<Object>) script.evalSha(RScript.Mode.READ_WRITE, cleanupExpiredInstancesWithSnapshotsScriptSha, RScript.ReturnType.MULTI,
                                List.of(heartbeatKey),
                                serviceName, String.valueOf(currentTime), String.valueOf(timeoutMs), keyPrefix);
                    }
                    throw e;
                }
            }

            return (List<Object>) script.eval(RScript.Mode.READ_WRITE, CLEANUP_EXPIRED_INSTANCES_WITH_SNAPSHOTS_SCRIPT, RScript.ReturnType.MULTI,
                    List.of(heartbeatKey),
                    serviceName, String.valueOf(currentTime), String.valueOf(timeoutMs), keyPrefix);
        } catch (Exception e) {
            logger.error("Failed to execute cleanup expired instances with snapshots", e);
            throw new RuntimeException("Cleanup expired instances with snapshots failed", e);
        }
    }

    /**
     * Execute get active instances (with SHA caching optimization)
     */
    @SuppressWarnings("unchecked")
    public List<Object> executeGetActiveInstances(String heartbeatKey, long currentTime, long timeoutMs) {
        try {
            if (getActiveInstancesScriptSha != null) {
                try {
                    return (List<Object>) script.evalSha(RScript.Mode.READ_ONLY, getActiveInstancesScriptSha, RScript.ReturnType.MULTI,
                            List.of(heartbeatKey),
                            String.valueOf(currentTime), String.valueOf(timeoutMs));
                } catch (RedisException e) {
                    if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                        logger.warn("Get active instances script not found in Redis cache, reloading...");
                        getActiveInstancesScriptSha = reloadScript(GET_ACTIVE_INSTANCES_SCRIPT);
                        return (List<Object>) script.evalSha(RScript.Mode.READ_ONLY, getActiveInstancesScriptSha, RScript.ReturnType.MULTI,
                                List.of(heartbeatKey),
                                String.valueOf(currentTime), String.valueOf(timeoutMs));
                    }
                    throw e;
                }
            }

            return (List<Object>) script.eval(RScript.Mode.READ_ONLY, GET_ACTIVE_INSTANCES_SCRIPT, RScript.ReturnType.MULTI,
                    List.of(heartbeatKey),
                    String.valueOf(currentTime), String.valueOf(timeoutMs));
        } catch (Exception e) {
            logger.error("Failed to execute get active instances script", e);
            throw new RuntimeException("Get active instances failed", e);
        }
    }

    /**
     * Execute instance registration (with SHA caching optimization)
     */
    public String executeRegisterInstance(String servicesKey, String heartbeatKey, String instanceKey,
                                          String serviceName, String instanceId, long heartbeatTime,
                                          String instanceDataJson, int ttlSeconds) {
        try {
            if (registerInstanceScriptSha != null) {
                try {
                    return script.evalSha(RScript.Mode.READ_WRITE, registerInstanceScriptSha, RScript.ReturnType.VALUE,
                            List.of(servicesKey, heartbeatKey, instanceKey),
                            serviceName, instanceId, String.valueOf(heartbeatTime), instanceDataJson, String.valueOf(ttlSeconds));
                } catch (RedisException e) {
                    if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                        logger.warn("Register instance script not found in Redis cache, reloading...");
                        registerInstanceScriptSha = reloadScript(REGISTER_INSTANCE_SCRIPT);
                        return script.evalSha(RScript.Mode.READ_WRITE, registerInstanceScriptSha, RScript.ReturnType.VALUE,
                                List.of(servicesKey, heartbeatKey, instanceKey),
                                serviceName, instanceId, String.valueOf(heartbeatTime), instanceDataJson, String.valueOf(ttlSeconds));
                    }
                    throw e;
                }
            }

            return script.eval(RScript.Mode.READ_WRITE, REGISTER_INSTANCE_SCRIPT, RScript.ReturnType.VALUE,
                    List.of(servicesKey, heartbeatKey, instanceKey),
                    serviceName, instanceId, String.valueOf(heartbeatTime), instanceDataJson, String.valueOf(ttlSeconds));
        } catch (Exception e) {
            logger.error("Failed to execute register instance script", e);
            throw new RuntimeException("Register instance failed", e);
        }
    }

    /**
     * Execute instance deregistration (with SHA caching optimization)
     */
    public String executeDeregisterInstance(String servicesKey, String heartbeatKey, String instanceKey,
                                           String serviceName, String instanceId) {
        try {
            if (deregisterInstanceScriptSha != null) {
                try {
                    return script.evalSha(RScript.Mode.READ_WRITE, deregisterInstanceScriptSha, RScript.ReturnType.VALUE,
                            List.of(servicesKey, heartbeatKey, instanceKey),
                            serviceName, instanceId);
                } catch (RedisException e) {
                    if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                        logger.warn("Deregister instance script not found in Redis cache, reloading...");
                        deregisterInstanceScriptSha = reloadScript(DEREGISTER_INSTANCE_SCRIPT);
                        return script.evalSha(RScript.Mode.READ_WRITE, deregisterInstanceScriptSha, RScript.ReturnType.VALUE,
                                List.of(servicesKey, heartbeatKey, instanceKey),
                                serviceName, instanceId);
                    }
                    throw e;
                }
            }

            return script.eval(RScript.Mode.READ_WRITE, DEREGISTER_INSTANCE_SCRIPT, RScript.ReturnType.VALUE,
                    List.of(servicesKey, heartbeatKey, instanceKey),
                    serviceName, instanceId);
        } catch (Exception e) {
            logger.error("Failed to execute deregister instance script", e);
            throw new RuntimeException("Deregister instance failed", e);
        }
    }

    /**
     * Get service instances filtered by metadata (with SHA caching optimization)
     *
     * @deprecated Use {@link #executeGetInstancesByFilters(String, String, String, long, long, String, String)} instead
     * @param heartbeatKey heartbeat key
     * @param keyPrefix Redis key prefix
     * @param serviceName service name
     * @param currentTime current timestamp
     * @param timeoutMs timeout in milliseconds
     * @param metadataFiltersJson metadata filter conditions JSON, format: {"key1":"value1","key2":"value2"}
     * @return list of matching instance IDs
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public List<String> executeGetInstancesByMetadata(String heartbeatKey, String keyPrefix, String serviceName,
                                                       long currentTime, long timeoutMs, String metadataFiltersJson) {
        // Backward compatibility: convert metadata filter to new format
        return executeGetInstancesByFilters(heartbeatKey, keyPrefix, serviceName,
                currentTime, timeoutMs, metadataFiltersJson, null);
    }

    /**
     * Get service instances filtered by metadata/metrics (with SHA caching optimization)
     *
     * @param heartbeatKey heartbeat key
     * @param keyPrefix Redis key prefix
     * @param serviceName service name
     * @param currentTime current timestamp
     * @param timeoutMs timeout in milliseconds
     * @param metadataFiltersJson metadata filter conditions JSON, format: {"key1":"value1","key2":"value2"}, can be null
     * @param metricsFiltersJson metrics filter conditions JSON, format: {"key1":"value1","key2":"value2"}, can be null
     * @return list of matching instance IDs
     */
    @SuppressWarnings("unchecked")
    public List<String> executeGetInstancesByFilters(String heartbeatKey, String keyPrefix, String serviceName,
                                                      long currentTime, long timeoutMs,
                                                      String metadataFiltersJson, String metricsFiltersJson) {
        try {
            // Build new format filter conditions JSON
            String filtersJson = buildFiltersJson(metadataFiltersJson, metricsFiltersJson);

            if (getInstancesByMetadataScriptSha != null) {
                try {
                    return (List<String>) script.evalSha(RScript.Mode.READ_ONLY, getInstancesByMetadataScriptSha, RScript.ReturnType.MULTI,
                            List.of(heartbeatKey),
                            keyPrefix, serviceName, String.valueOf(currentTime), String.valueOf(timeoutMs),
                            filtersJson != null ? filtersJson : "");
                } catch (RedisException e) {
                    if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                        logger.warn("Get instances by filters script not found in Redis cache, reloading...");
                        getInstancesByMetadataScriptSha = reloadScript(GET_INSTANCES_BY_METADATA_SCRIPT);
                        return (List<String>) script.evalSha(RScript.Mode.READ_ONLY, getInstancesByMetadataScriptSha, RScript.ReturnType.MULTI,
                                List.of(heartbeatKey),
                                keyPrefix, serviceName, String.valueOf(currentTime), String.valueOf(timeoutMs),
                                filtersJson != null ? filtersJson : "");
                    }
                    throw e;
                }
            }

            return (List<String>) script.eval(RScript.Mode.READ_ONLY, GET_INSTANCES_BY_METADATA_SCRIPT, RScript.ReturnType.MULTI,
                    List.of(heartbeatKey),
                    keyPrefix, serviceName, String.valueOf(currentTime), String.valueOf(timeoutMs),
                    filtersJson != null ? filtersJson : "");
        } catch (Exception e) {
            logger.error("Failed to execute get instances by filters script", e);
            throw new RuntimeException("Get instances by filters failed", e);
        }
    }

    /**
     * Build filter conditions JSON
     *
     * @param metadataFiltersJson metadata filter conditions
     * @param metricsFiltersJson metrics filter conditions
     * @return combined filter conditions JSON, format: {"metadata":{...},"metrics":{...}}
     */
    private String buildFiltersJson(String metadataFiltersJson, String metricsFiltersJson) {
        try {
            // If both are empty, return empty
            if ((metadataFiltersJson == null || metadataFiltersJson.isEmpty()) &&
                (metricsFiltersJson == null || metricsFiltersJson.isEmpty())) {
                return "";
            }

            // If only metadata, return directly for backward compatibility with old format
            if (metricsFiltersJson == null || metricsFiltersJson.isEmpty()) {
                return metadataFiltersJson;
            }

            // Build new format
            StringBuilder sb = new StringBuilder();
            sb.append("{");

            if (metadataFiltersJson != null && !metadataFiltersJson.isEmpty()) {
                sb.append("\"metadata\":").append(metadataFiltersJson);
            }

            if (metricsFiltersJson != null && !metricsFiltersJson.isEmpty()) {
                if (sb.length() > 1) {
                    sb.append(",");
                }
                sb.append("\"metrics\":").append(metricsFiltersJson);
            }

            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            logger.error("Failed to build filters JSON", e);
            return "";
        }
    }
}
