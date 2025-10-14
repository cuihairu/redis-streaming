package io.github.cuihairu.redis.streaming.registry.lua;

import org.redisson.api.RedissonClient;
import org.redisson.api.RScript;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 注册中心Lua脚本执行器
 * <p>
 * 使用脚本缓存优化：
 * 1. 在初始化时通过 scriptLoad() 预加载脚本到 Redis
 * 2. 执行时使用 evalSha() 传输 SHA-1 摘要而非完整脚本
 * 3. 处理 NOSCRIPT 错误（Redis 重启/脚本被清理）时自动重新加载
 */
public class RegistryLuaScriptExecutor {

    private static final Logger logger = LoggerFactory.getLogger(RegistryLuaScriptExecutor.class);

    private final RedissonClient redissonClient;
    private final RScript script;

    // 脚本 SHA-1 缓存（volatile 保证可见性）
    private volatile String heartbeatUpdateScriptSha;
    private volatile String cleanupExpiredInstancesScriptSha;
    private volatile String cleanupExpiredInstancesWithSnapshotsScriptSha;
    private volatile String getActiveInstancesScriptSha;
    private volatile String registerInstanceScriptSha;
    private volatile String deregisterInstanceScriptSha;
    private volatile String getInstancesByMetadataScriptSha;

    // 心跳更新脚本（支持 metadata 和 metrics 分离更新）
    private static final String HEARTBEAT_UPDATE_SCRIPT = """
            -- 多模式心跳更新脚本
            local heartbeat_key = KEYS[1]    -- ZSet
            local instance_key = KEYS[2]     -- Hash
            local instance_id = ARGV[1]
            local heartbeat_time = ARGV[2]
            local update_mode = ARGV[3]      -- "heartbeat_only" | "metrics_update" | "metadata_update" | "full_update"
            local metadata_json = ARGV[4]    -- 可选，metadata JSON
            local metrics_json = ARGV[5]     -- 可选，metrics JSON
            local ttl_seconds = ARGV[6]      -- 可选，实例Hash TTL（仅用于临时实例）

            -- 参数校验
            if not heartbeat_key or not instance_key or not instance_id or not heartbeat_time then
                return redis.error_reply("Missing required parameters")
            end

            -- 检查实例是否存在（心跳更新不应该创建新实例）
            if redis.call('EXISTS', instance_key) == 0 then
                return 0  -- 实例不存在，可能已注销
            end

            -- 总是更新心跳时间戳（ARGV参数为字符串，需转换为数字）
            local heartbeat_num = tonumber(heartbeat_time)
            if not heartbeat_num then
                return redis.error_reply("Invalid heartbeat_time: must be a number")
            end
            redis.call('ZADD', heartbeat_key, heartbeat_num, instance_id)
            redis.call('HSET', instance_key, 'lastHeartbeatTime', heartbeat_time)

            -- 刷新实例Hash的TTL（仅对临时实例生效）。
            -- 规则：ephemeral=true 或未设置表示临时实例，需要滑动过期；ephemeral=false 表示永久实例，不设置TTL。
            if ttl_seconds and ttl_seconds ~= '' then
                local ttl_num = tonumber(ttl_seconds)
                if ttl_num and ttl_num > 0 then
                    local ephemeral = redis.call('HGET', instance_key, 'ephemeral')
                    if ephemeral == nil or ephemeral == 'true' then
                        redis.call('EXPIRE', instance_key, ttl_num)
                    end
                end
            end

            -- 根据不同模式执行对应的更新
            if update_mode == 'heartbeat_only' then
                -- 只更新时间戳，已完成
                return 1

            elseif update_mode == 'metrics_update' then
                -- 只更新 metrics
                if metrics_json and metrics_json ~= '' then
                    redis.call('HSET', instance_key, 'metrics', metrics_json)
                    redis.call('HSET', instance_key, 'lastMetricsUpdate', heartbeat_time)
                end
                return 1

            elseif update_mode == 'metadata_update' then
                -- 只更新 metadata
                if metadata_json and metadata_json ~= '' then
                    redis.call('HSET', instance_key, 'metadata', metadata_json)
                    redis.call('HSET', instance_key, 'lastMetadataUpdate', heartbeat_time)
                end
                return 1

            elseif update_mode == 'full_update' then
                -- 同时更新 metadata 和 metrics
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

    // 清理过期实例脚本
    private static final String CLEANUP_EXPIRED_INSTANCES_SCRIPT = """
            -- 清理过期实例脚本（仅清理临时实例）
            local heartbeat_key = KEYS[1]
            local service_name = ARGV[1]
            local current_time = ARGV[2]
            local timeout_ms = ARGV[3]
            local key_prefix = ARGV[4]

            -- 参数校验
            if not heartbeat_key or not service_name or not current_time or not timeout_ms or not key_prefix then
                return redis.error_reply("Missing required parameters")
            end

            -- ARGV参数为字符串，需转换为数字进行算术运算
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

                    -- 获取实例的 ephemeral 属性
                    local ephemeral = redis.call('HGET', instance_key, 'ephemeral')

                    -- 只清理临时实例（ephemeral=true 或未设置）
                    -- 永久实例（ephemeral=false）只标记不健康，不删除
                    if ephemeral == nil or ephemeral == 'true' then
                        -- 从心跳索引中删除
                        redis.call('ZREM', heartbeat_key, instance_id)
                        -- 删除实例详情
                        redis.call('DEL', instance_key)
                        table.insert(expired_instances, instance_id)
                    else
                        -- 永久实例只标记为不健康
                        redis.call('HSET', instance_key, 'healthy', 'false')
                    end
                end
            end

            return expired_instances
            """;

    // 清理过期实例（携带实例快照）脚本：返回 [id1, json1, id2, json2, ...]
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

    // 获取活跃实例脚本
    private static final String GET_ACTIVE_INSTANCES_SCRIPT = """
            -- 获取活跃实例脚本
            local heartbeat_key = KEYS[1]
            local current_time = ARGV[1]
            local timeout_ms = ARGV[2]

            -- 参数校验
            if not heartbeat_key or not current_time or not timeout_ms then
                return redis.error_reply("Missing required parameters")
            end

            -- ARGV参数为字符串，需转换为数字进行算术运算
            local current_time_num = tonumber(current_time)
            local timeout_ms_num = tonumber(timeout_ms)
            if not current_time_num or not timeout_ms_num then
                return redis.error_reply("Invalid numeric parameters")
            end
            local active_threshold = current_time_num - timeout_ms_num
            local active_instances = redis.call('ZRANGEBYSCORE', heartbeat_key, active_threshold, '+inf', 'WITHSCORES')

            return active_instances
            """;

    // 实例注册脚本（使用新格式：metadata 和 metrics 作为 JSON 字符串）
    private static final String REGISTER_INSTANCE_SCRIPT = """
            -- 实例注册脚本
            local services_key = KEYS[1]        -- 服务索引Set
            local heartbeat_key = KEYS[2]       -- 心跳ZSet
            local instance_key = KEYS[3]        -- 实例详情Hash

            local service_name = ARGV[1]
            local instance_id = ARGV[2]
            local heartbeat_time = ARGV[3]
            local instance_data_json = ARGV[4]
            local ttl_seconds = ARGV[5]

            -- 参数校验
            if not services_key or not heartbeat_key or not instance_key then
                return redis.error_reply("Missing required KEYS")
            end
            if not service_name or not instance_id or not heartbeat_time or not instance_data_json then
                return redis.error_reply("Missing required ARGV")
            end

            -- 添加服务到服务索引
            redis.call('SADD', services_key, service_name)

            -- 添加心跳时间戳（ARGV参数为字符串，需转换为数字）
            local heartbeat_num = tonumber(heartbeat_time)
            if not heartbeat_num then
                return redis.error_reply("Invalid heartbeat_time: must be a number")
            end
            redis.call('ZADD', heartbeat_key, heartbeat_num, instance_id)

            -- 解析实例数据
            local instance_data = cjson.decode(instance_data_json)

            -- 存储基础字段（非 metadata 和 metrics）
            for key, value in pairs(instance_data) do
                if value ~= cjson.null and key ~= 'metadata' and key ~= 'metrics' then
                    if type(value) == "table" then
                        redis.call('HSET', instance_key, key, cjson.encode(value))
                    else
                        redis.call('HSET', instance_key, key, tostring(value))
                    end
                end
            end

            -- 存储 metadata（作为 JSON 字符串）
            if instance_data.metadata and instance_data.metadata ~= cjson.null then
                redis.call('HSET', instance_key, 'metadata', cjson.encode(instance_data.metadata))
            end

            -- 存储 metrics（作为 JSON 字符串）
            if instance_data.metrics and instance_data.metrics ~= cjson.null then
                redis.call('HSET', instance_key, 'metrics', cjson.encode(instance_data.metrics))
            end

            -- 添加心跳时间到Hash中（冗余存储）
            redis.call('HSET', instance_key, 'lastHeartbeatTime', heartbeat_time)

            -- 设置TTL（仅对临时实例生效；ARGV参数需转换为整数）
            if ttl_seconds and ttl_seconds ~= '' then
                local ttl_num = tonumber(ttl_seconds)
                if ttl_num and ttl_num > 0 then
                    -- 从提交的数据判断是否临时实例（默认临时）
                    local ephemeral = instance_data['ephemeral']
                    if ephemeral == nil or ephemeral == true or ephemeral == 'true' then
                        redis.call('EXPIRE', instance_key, ttl_num)
                    end
                end
            end

            return 'OK'
            """;

    // 实例注销脚本
    private static final String DEREGISTER_INSTANCE_SCRIPT = """
            -- 实例注销脚本
            local services_key = KEYS[1]        -- 服务索引Set
            local heartbeat_key = KEYS[2]       -- 心跳ZSet
            local instance_key = KEYS[3]        -- 实例详情Hash

            local service_name = ARGV[1]
            local instance_id = ARGV[2]

            -- 参数校验
            if not services_key or not heartbeat_key or not instance_key then
                return redis.error_reply("Missing required KEYS")
            end
            if not service_name or not instance_id then
                return redis.error_reply("Missing required ARGV")
            end

            -- 从心跳索引中移除
            redis.call('ZREM', heartbeat_key, instance_id)

            -- 删除实例详情
            redis.call('DEL', instance_key)

            -- 检查心跳索引是否为空
            local remaining_instances = redis.call('ZCARD', heartbeat_key)
            if remaining_instances == 0 then
                -- 心跳索引为空，删除心跳Key
                redis.call('DEL', heartbeat_key)
                -- 从服务索引中移除该服务
                redis.call('SREM', services_key, service_name)
            end

            return 'OK'
            """;

    // 根据 metadata/metrics 过滤获取实例脚本（支持比较运算符）
    private static final String GET_INSTANCES_BY_METADATA_SCRIPT = """
            -- 根据 metadata/metrics 过滤获取实例脚本（支持比较运算符）
            -- 注意：metadata 和 metrics 现在都以 JSON 字符串格式存储
            local heartbeat_key = KEYS[1]       -- 心跳ZSet
            local key_prefix = ARGV[1]          -- Redis key前缀
            local service_name = ARGV[2]        -- 服务名
            local current_time = ARGV[3]        -- 当前时间戳
            local timeout_ms = ARGV[4]          -- 超时时间(毫秒)
            local filters_json = ARGV[5]        -- 过滤条件JSON，格式: {"metadata":{},"metrics":{}}

            -- 参数校验
            if not heartbeat_key or not key_prefix or not service_name then
                return redis.error_reply("Missing required parameters")
            end
            if not current_time or not timeout_ms then
                return redis.error_reply("Missing time parameters")
            end

            -- 转换时间参数为数字
            local current_time_num = tonumber(current_time)
            local timeout_ms_num = tonumber(timeout_ms)
            if not current_time_num or not timeout_ms_num then
                return redis.error_reply("Invalid time parameters: must be numbers")
            end

            -- 解析过滤条件的 key（支持运算符）
            -- 格式：field:op，例如 "age:>=" 返回 ("age", ">=")
            -- 如果没有运算符，返回 (field, "==")
            local function parse_filter_key(filter_key)
                -- 从后往前找最后一个冒号
                local last_colon = 0
                for i = 1, #filter_key do
                    if string.sub(filter_key, i, i) == ':' then
                        last_colon = i
                    end
                end

                if last_colon > 0 then
                    local possible_op = string.sub(filter_key, last_colon + 1)
                    -- 检查是否是有效的运算符
                    if possible_op == '==' or possible_op == '!=' or
                       possible_op == '>' or possible_op == '>=' or
                       possible_op == '<' or possible_op == '<=' then
                        return string.sub(filter_key, 1, last_colon - 1), possible_op
                    end
                end

                -- 默认使用等于运算符
                return filter_key, '=='
            end

            -- 比较两个值
            -- 优先尝试数值比较，失败则使用字符串比较（字典序）
            local function compare_values(op, actual, expected)
                -- 尝试数值转换
                local actual_num = tonumber(actual)
                local expected_num = tonumber(expected)

                if actual_num and expected_num then
                    -- 数值比较
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
                    -- 字符串比较（字典序）
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

            -- 检查单个数据源（metadata 或 metrics）的过滤条件
            local function check_filters(data_json, filters)
                if not filters or next(filters) == nil then
                    return true  -- 没有过滤条件，直接通过
                end

                if not data_json then
                    return false  -- 需要过滤但数据不存在
                end

                -- 解析 JSON
                local data = cjson.decode(data_json)

                -- 检查所有过滤条件（AND 关系）
                for filter_key, filter_value in pairs(filters) do
                    local field, op = parse_filter_key(filter_key)
                    local actual_value = data[field]

                    if not actual_value then
                        return false  -- 字段不存在
                    end

                    if not compare_values(op, actual_value, filter_value) then
                        return false  -- 不匹配
                    end
                end

                return true
            end

            -- 计算活跃实例阈值
            local active_threshold = current_time_num - timeout_ms_num

            -- 获取所有活跃的实例ID
            local active_instance_ids = redis.call('ZRANGEBYSCORE', heartbeat_key, active_threshold, '+inf')

            -- 解析过滤条件
            local metadata_filters = {}
            local metrics_filters = {}

            if filters_json and filters_json ~= '' then
                local all_filters = cjson.decode(filters_json)
                -- 兼容旧格式：如果是简单的 map，当做 metadata 过滤
                if all_filters.metadata or all_filters.metrics then
                    metadata_filters = all_filters.metadata or {}
                    metrics_filters = all_filters.metrics or {}
                else
                    -- 旧格式兼容：直接当做 metadata 过滤
                    metadata_filters = all_filters
                end
            end

            -- 匹配的实例ID列表
            local matched_instances = {}

            -- 遍历每个活跃实例，检查是否匹配过滤条件
            for i = 1, #active_instance_ids do
                local instance_id = active_instance_ids[i]
                local instance_key = key_prefix .. ':services:' .. service_name .. ':instance:' .. instance_id

                -- 如果没有任何过滤条件，直接添加
                if next(metadata_filters) == nil and next(metrics_filters) == nil then
                    table.insert(matched_instances, instance_id)
                else
                    -- 获取实例的 metadata 和 metrics JSON
                    local metadata_json = redis.call('HGET', instance_key, 'metadata')
                    local metrics_json = redis.call('HGET', instance_key, 'metrics')

                    -- 检查 metadata 过滤条件
                    local metadata_match = check_filters(metadata_json, metadata_filters)

                    -- 检查 metrics 过滤条件
                    local metrics_match = check_filters(metrics_json, metrics_filters)

                    -- 所有条件都匹配才添加到结果（AND 关系）
                    if metadata_match and metrics_match then
                        table.insert(matched_instances, instance_id)
                    end
                end
            end

            return matched_instances
            """;

    public RegistryLuaScriptExecutor(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.script = redissonClient.getScript(StringCodec.INSTANCE); // 使用StringCodec确保参数正确传递给Lua
        // 初始化时预加载所有脚本
        initScripts();
    }

    /**
     * 初始化脚本：加载到 Redis 并缓存 SHA-1
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
            // 不抛异常，允许降级到 eval 模式
        }
    }

    /**
     * 重新加载指定脚本（当遇到 NOSCRIPT 错误时）
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
     * 执行心跳更新（使用 SHA 缓存优化，支持 metadata 和 metrics 分离更新）
     *
     * @param heartbeatKey 心跳Key (ZSet)
     * @param instanceKey 实例Key (Hash)
     * @param instanceId 实例ID
     * @param heartbeatTime 心跳时间戳
     * @param updateMode 更新模式："heartbeat_only", "metrics_update", "metadata_update", "full_update"
     * @param metadataJson metadata JSON（可为 null）
     * @param metricsJson metrics JSON（可为 null）
     * @param ttlSeconds TTL秒数（仅对临时实例生效）
     */
    public void executeHeartbeatUpdate(String heartbeatKey, String instanceKey, String instanceId,
                                         long heartbeatTime, String updateMode,
                                         String metadataJson, String metricsJson,
                                         int ttlSeconds) {
        try {
            // 优先使用 evalSha（传输 40 字节 SHA vs ~800 字节脚本）
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
                        // Redis 重启或脚本被清理，重新加载
                        logger.warn("Script not found in Redis cache, reloading...");
                        heartbeatUpdateScriptSha = reloadScript(HEARTBEAT_UPDATE_SCRIPT);
                        // 重试一次
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

            // 降级：直接使用 eval（初始化失败时）
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
     * 执行清理过期实例（使用 SHA 缓存优化）
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
     * 执行清理过期实例（返回 [id,json] 对序列）
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
     * 执行获取活跃实例（使用 SHA 缓存优化）
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
     * 执行实例注册（使用 SHA 缓存优化）
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
     * 执行实例注销（使用 SHA 缓存优化）
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
     * 根据 metadata 过滤获取服务实例（使用 SHA 缓存优化）
     *
     * @deprecated 使用 {@link #executeGetInstancesByFilters(String, String, String, long, long, String, String)} 代替
     * @param heartbeatKey 心跳Key
     * @param keyPrefix Redis key前缀
     * @param serviceName 服务名
     * @param currentTime 当前时间戳
     * @param timeoutMs 超时时间(毫秒)
     * @param metadataFiltersJson metadata过滤条件JSON，格式：{"key1":"value1","key2":"value2"}
     * @return 匹配的实例ID列表
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public List<String> executeGetInstancesByMetadata(String heartbeatKey, String keyPrefix, String serviceName,
                                                       long currentTime, long timeoutMs, String metadataFiltersJson) {
        // 向后兼容：metadata 过滤转换为新格式
        return executeGetInstancesByFilters(heartbeatKey, keyPrefix, serviceName,
                currentTime, timeoutMs, metadataFiltersJson, null);
    }

    /**
     * 根据 metadata/metrics 过滤获取服务实例（使用 SHA 缓存优化）
     *
     * @param heartbeatKey 心跳Key
     * @param keyPrefix Redis key前缀
     * @param serviceName 服务名
     * @param currentTime 当前时间戳
     * @param timeoutMs 超时时间(毫秒)
     * @param metadataFiltersJson metadata过滤条件JSON，格式：{"key1":"value1","key2":"value2"}，可为null
     * @param metricsFiltersJson metrics过滤条件JSON，格式：{"key1":"value1","key2":"value2"}，可为null
     * @return 匹配的实例ID列表
     */
    @SuppressWarnings("unchecked")
    public List<String> executeGetInstancesByFilters(String heartbeatKey, String keyPrefix, String serviceName,
                                                      long currentTime, long timeoutMs,
                                                      String metadataFiltersJson, String metricsFiltersJson) {
        try {
            // 构造新格式的过滤条件 JSON
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
     * 构造过滤条件 JSON
     *
     * @param metadataFiltersJson metadata 过滤条件
     * @param metricsFiltersJson metrics 过滤条件
     * @return 组合后的过滤条件 JSON，格式：{"metadata":{...},"metrics":{...}}
     */
    private String buildFiltersJson(String metadataFiltersJson, String metricsFiltersJson) {
        try {
            // 如果两者都为空，返回空
            if ((metadataFiltersJson == null || metadataFiltersJson.isEmpty()) &&
                (metricsFiltersJson == null || metricsFiltersJson.isEmpty())) {
                return "";
            }

            // 如果只有 metadata，为了兼容旧格式，直接返回
            if (metricsFiltersJson == null || metricsFiltersJson.isEmpty()) {
                return metadataFiltersJson;
            }

            // 构造新格式
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
