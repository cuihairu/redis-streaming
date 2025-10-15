package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.*;
import org.redisson.api.*;
import org.redisson.codec.JsonJacksonCodec;
import io.github.cuihairu.redis.streaming.config.event.ConfigChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 基于Redis的配置服务实现
 */
public class RedisConfigService implements ConfigService, ConfigManager {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisConfigService.class);
    
    private final RedissonClient redissonClient;
    private final ConfigServiceConfig config;
    private volatile boolean running = false;
    
    // 监听器管理
    private final Map<String, Set<ConfigChangeListener>> listeners = new ConcurrentHashMap<>();
    private final Map<String, RTopic> subscriptions = new ConcurrentHashMap<>();
    
    // 配置历史保留数量（实例级，可由配置注入）
    private final int maxHistorySize;
    private final String clientId = "client-" + java.util.UUID.randomUUID();
    
    public RedisConfigService(RedissonClient redissonClient) {
        this(redissonClient, new ConfigServiceConfig());
    }
    
    public RedisConfigService(RedissonClient redissonClient, ConfigServiceConfig config) {
        this.redissonClient = redissonClient;
        this.config = config != null ? config : new ConfigServiceConfig();
        this.maxHistorySize = this.config.getHistorySize();
    }
    
    @Override
    public String getConfig(String dataId, String group) {
        if (!running) {
            throw new IllegalStateException("ConfigService is not running");
        }
        
        try {
            RMap<String, String> configMap = redissonClient.getMap(
                config.getConfigKey(group, dataId), org.redisson.client.codec.StringCodec.INSTANCE);
            String v = configMap.get("content");
            return v;
        } catch (Exception e) {
            logger.error("Failed to get config {}:{}", group, dataId, e);
            throw new RuntimeException("Failed to get config", e);
        }
    }
    
    @Override
    public boolean publishConfig(String dataId, String group, String content) {
        return publishConfig(dataId, group, content, null);
    }
    
    @Override
    public boolean publishConfig(String dataId, String group, String content, String description) {
        if (!running) {
            throw new IllegalStateException("ConfigService is not running");
        }
        
        try {
            String configKey = config.getConfigKey(group, dataId);
            String historyKey = config.getConfigHistoryKey(group, dataId);
            String newVersion = generateVersion();
            long nowMs = System.currentTimeMillis();

            // Build entry JSON for Lua to decode (keeps write path unified)
            java.util.Map<String,String> entry = ConfigEntryCodec.toMap(content, newVersion, description, nowMs, nowMs);
            String entryJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(entry);

            String lua = "local key=KEYS[1]; local hist=KEYS[2]; local dataId=ARGV[1]; local grp=ARGV[2]; local entryJson=ARGV[3]; local now=ARGV[4]; local maxhist=tonumber(ARGV[5]); "
                    + "local existed=redis.call('EXISTS', key); local oldc=redis.call('HGET', key, 'content'); local oldv=redis.call('HGET', key, 'version'); local oldu=redis.call('HGET', key, 'updateTime'); "
                    + "if oldc then local ctime=nil; if oldu then ctime=tonumber(oldu) end; if not ctime then ctime=tonumber(now) end; local rec=cjson.encode({dataId=dataId,group=grp,content=oldc,version=oldv,operation='UPDATED',changeTime=ctime,operator='system'}); redis.call('LPUSH', hist, rec); redis.call('LTRIM', hist, 0, maxhist-1); end; "
                    + "local e=cjson.decode(entryJson); if existed==0 and e.createTime then redis.call('HSET', key, 'createTime', tostring(e.createTime)); end; "
                    + "if e.content then redis.call('HSET', key, 'content', e.content); else redis.call('HDEL', key, 'content'); end; "
                    + "if e.version then redis.call('HSET', key, 'version', e.version); end; "
                    + "if e.description and e.description~=cjson.null and e.description~='' then redis.call('HSET', key, 'description', e.description); else redis.call('HDEL', key, 'description'); end; "
                    + "if e.updateTime then redis.call('HSET', key, 'updateTime', tostring(e.updateTime)); end; return existed;";

            // Use StringCodec to pass raw strings to Lua (avoid JSON codec wrapping strings)
            RScript script = redissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE);
            script.eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.INTEGER,
                    java.util.Arrays.asList(configKey, historyKey),
                    dataId, group, entryJson, String.valueOf(nowMs), String.valueOf(maxHistorySize));

            publishConfigChangeEvent(dataId, group, content, newVersion);
            logger.info("Config published: {}:{}, version: {}", group, dataId, newVersion);
            return true;
            } catch (Exception e) {
                // Fallback: perform non-Lua update to keep tests and basic semantics working
                try {
                    String configKey = config.getConfigKey(group, dataId);
                    String newVersion = generateVersion();
                    long nowMs = System.currentTimeMillis();

                    RMap<String, String> map = redissonClient.getMap(configKey, org.redisson.client.codec.StringCodec.INSTANCE);
                    Map<String, String> all = java.util.Collections.emptyMap();
                    try { all = map.readAllMap(); } catch (Exception ignore) {}
                    String oldContent = all.get("content");
                    String oldVersion = all.get("version");
                    String oldUpdateTime = all.get("updateTime");
                    if (oldContent != null) {
                    java.time.LocalDateTime ct = null;
                    try { if (oldUpdateTime != null) ct = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(Long.parseLong(oldUpdateTime)), java.time.ZoneId.systemDefault()); } catch (Exception ignore) {}
                    if (ct == null) ct = java.time.LocalDateTime.now();
                    saveConfigHistory(dataId, group, oldContent, oldVersion, ct, "UPDATED");
                    }
                if (!all.containsKey("createTime")) {
                    map.fastPut("createTime", String.valueOf(nowMs));
                }
                if (content != null) {
                    map.fastPut("content", content);
                } else {
                    try { map.fastRemove("content"); } catch (Exception ignore) {}
                }
                if (description != null && !description.isEmpty()) {
                    map.fastPut("description", description);
                } else {
                    try { map.fastRemove("description"); } catch (Exception ignore) {}
                }
                map.fastPut("version", newVersion);
                map.fastPut("updateTime", String.valueOf(nowMs));

                publishConfigChangeEvent(dataId, group, content, newVersion);
                logger.warn("Lua publish failed, applied Java fallback for {}:{}", group, dataId, e);
                return true;
            } catch (Exception e2) {
                logger.error("Failed to publish config {}:{} (fallback also failed)", group, dataId, e2);
                return false;
            }
        }
    }
    
    @Override
    public boolean removeConfig(String dataId, String group) {
        if (!running) {
            throw new IllegalStateException("ConfigService is not running");
        }
        
        try {
            String configKey = config.getConfigKey(group, dataId);
            String historyKey = config.getConfigHistoryKey(group, dataId);
            long nowMs = System.currentTimeMillis();
            String lua = "local key=KEYS[1]; local hist=KEYS[2]; local dataId=ARGV[1]; local grp=ARGV[2]; local now=ARGV[3]; local maxhist=tonumber(ARGV[4]); "
                    + "if redis.call('EXISTS', key)==0 then return 0 end; local oldc=redis.call('HGET', key, 'content'); local oldv=redis.call('HGET', key, 'version'); "
                    + "if oldc then local rec=cjson.encode({dataId=dataId,group=grp,content=oldc,version=oldv,operation='DELETED',changeTime=tonumber(now),operator='system'}); redis.call('LPUSH', hist, rec); redis.call('LTRIM', hist, 0, maxhist-1); end; redis.call('DEL', key); return 1;";
            RScript script = redissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE);
            Long deleted = script.eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.INTEGER,
                    java.util.Arrays.asList(configKey, historyKey), dataId, group, String.valueOf(nowMs), String.valueOf(maxHistorySize));

            redissonClient.getSet(config.getConfigSubscribersKey(group, dataId)).delete();

            if (deleted != null && deleted > 0) {
                publishConfigChangeEvent(dataId, group, null, null);
                logger.info("Config removed: {}:{}", group, dataId);
                return true;
            }
            return false;
        } catch (Exception e) {
            // Fallback: remove using regular commands and record history best-effort
            try {
                String configKey = config.getConfigKey(group, dataId);
                RMap<String, String> map = redissonClient.getMap(configKey, org.redisson.client.codec.StringCodec.INSTANCE);
                Map<String, String> all = java.util.Collections.emptyMap();
                try { all = map.readAllMap(); } catch (Exception ignore) {}
                String oldContent = all.get("content");
                String oldVersion = all.get("version");
                if (oldContent != null) {
                    saveConfigHistory(dataId, group, oldContent, oldVersion, java.time.LocalDateTime.now(), "DELETED");
                }
                try { map.delete(); } catch (Exception ignore) {}
                try { redissonClient.getSet(config.getConfigSubscribersKey(group, dataId)).delete(); } catch (Exception ignore) {}
                publishConfigChangeEvent(dataId, group, null, null);
                logger.warn("Lua remove failed, applied Java fallback for {}:{}", group, dataId, e);
                return true;
            } catch (Exception e2) {
                logger.error("Failed to remove config {}:{} (fallback also failed)", group, dataId, e2);
                return false;
            }
        }
    }
    
    @Override
    public void addListener(String dataId, String group, ConfigChangeListener listener) {
        if (!running) {
            throw new IllegalStateException("ConfigService is not running");
        }
        
        String listenerKey = group + ":" + dataId;
        
        // 添加监听器
        listeners.computeIfAbsent(listenerKey, k -> ConcurrentHashMap.newKeySet()).add(listener);
        
        // 如果是第一个监听器，创建Redis订阅
        if (!subscriptions.containsKey(listenerKey)) {
            RTopic topic = redissonClient.getTopic(
                config.getConfigChangeChannelKey(group, dataId), new JsonJacksonCodec());

            topic.addListener(ConfigChangeEvent.class, (channel, message) -> {
                handleConfigChangeEvent(dataId, group, message);
            });
            
            subscriptions.put(listenerKey, topic);
            
            // 添加到订阅者列表
            RSet<String> subscribersSet = redissonClient.getSet(
                config.getConfigSubscribersKey(group, dataId));
            subscribersSet.add(clientId);
            
            logger.info("Added config listener for: {}:{}", group, dataId);
        }
        
        // 立即通知当前配置
        try {
            String configKey = config.getConfigKey(group, dataId);
            RMap<String, String> map = redissonClient.getMap(configKey, org.redisson.client.codec.StringCodec.INSTANCE);
            Map<String, String> all = map.readAllMap();
            io.github.cuihairu.redis.streaming.config.ConfigInfo info = ConfigEntryCodec.parseInfo(dataId, group, all);
            if (info != null && info.getContent() != null) {
                listener.onConfigChange(dataId, group, info.getContent(), info.getVersion());
            }
        } catch (Exception e) {
            logger.warn("Failed to notify current config for: {}:{}", group, dataId, e);
        }
    }
    
    @Override
    public void removeListener(String dataId, String group, ConfigChangeListener listener) {
        String listenerKey = group + ":" + dataId;
        
        Set<ConfigChangeListener> configListeners = listeners.get(listenerKey);
        if (configListeners != null) {
            configListeners.remove(listener);
            
            // 如果没有监听器了，取消Redis订阅
            if (configListeners.isEmpty()) {
                listeners.remove(listenerKey);
                
                RTopic topic = subscriptions.remove(listenerKey);
                if (topic != null) {
                    topic.removeAllListeners();
                    logger.info("Removed config listener for: {}:{}", group, dataId);
                }
                
                // 从订阅者列表中移除
                RSet<String> subscribersSet = redissonClient.getSet(
                    config.getConfigSubscribersKey(group, dataId));
                try { subscribersSet.remove(clientId); } catch (Exception ignore) {}
            }
        }
    }
    
    @Override
    public List<ConfigHistory> getConfigHistory(String dataId, String group, int size) {
        if (!running) {
            throw new IllegalStateException("ConfigService is not running");
        }
        
        try {
            if (size <= 0) return java.util.Collections.emptyList();
            RList<String> historyList = redissonClient.getList(
                config.getConfigHistoryKey(group, dataId), org.redisson.client.codec.StringCodec.INSTANCE);
            int actualSize = Math.min(size, historyList.size());
            if (actualSize <= 0) return java.util.Collections.emptyList();
            List<String> historyData = historyList.range(0, actualSize - 1);
            return historyData.stream()
                    .map(this::buildConfigHistory)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
                    
        } catch (Exception e) {
            logger.error("Failed to get config history for {}:{}", group, dataId, e);
            return Collections.emptyList();
        }
    }

    @Override
    public int trimHistoryBySize(String dataId, String group, int maxSize) {
        if (!running) throw new IllegalStateException("ConfigService is not running");
        try {
            String historyKey = config.getConfigHistoryKey(group, dataId);
            String lua = "local key=KEYS[1]; local max=tonumber(ARGV[1]); local len=redis.call('LLEN', key); if max<0 then redis.call('DEL', key); return len; end; if len<=max then return 0; end; redis.call('LTRIM', key, 0, max-1); return len-max;";
            RScript script = redissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE);
            Long removed = script.eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.INTEGER,
                    java.util.Collections.singletonList(historyKey), String.valueOf(maxSize));
            return removed == null ? 0 : removed.intValue();
        } catch (Exception e) {
            logger.error("Failed to trim history by size for {}:{}", group, dataId, e);
            return 0;
        }
    }

    @Override
    public int trimHistoryByAge(String dataId, String group, java.time.Duration maxAge) {
        if (!running) throw new IllegalStateException("ConfigService is not running");
        try {
            long cutoff = System.currentTimeMillis() - (maxAge == null ? 0 : maxAge.toMillis());
            String historyKey = config.getConfigHistoryKey(group, dataId);
            // Iterate from tail (oldest) while entries are older than cutoff, then trim those tail entries
            String lua = "local key=KEYS[1]; local cutoff=tonumber(ARGV[1]); local len=redis.call('LLEN', key); if len==0 then return 0 end; "
                    + "local idx=len-1; while idx>=0 do local it=redis.call('LINDEX', key, idx); if not it then break end; local obj=nil; pcall(function() obj=cjson.decode(it) end); local ct=nil; if obj and obj['changeTime'] then ct=tonumber(obj['changeTime']) end; if ct and ct<cutoff then idx=idx-1 else break end; end; "
                    + "local trimTo=idx; if trimTo>=0 then redis.call('LTRIM', key, 0, trimTo); return len-(trimTo+1); else redis.call('DEL', key); return len; end;";
            RScript script = redissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE);
            Long removed = script.eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.INTEGER,
                    java.util.Collections.singletonList(historyKey), String.valueOf(cutoff));
            return removed == null ? 0 : removed.intValue();
        } catch (Exception e) {
            logger.error("Failed to trim history by age for {}:{}", group, dataId, e);
            return 0;
        }
    }
    
    @Override
    public void start() {
        if (running) {
            return;
        }
        
        running = true;
        logger.info("RedisConfigService started");
    }
    
    @Override
    public void stop() {
        if (!running) {
            return;
        }
        
        running = false;
        
        // 清理所有订阅
        for (Map.Entry<String, RTopic> entry : subscriptions.entrySet()) {
            try {
                entry.getValue().removeAllListeners();
            } catch (Exception e) {
                logger.warn("Failed to cleanup config subscription for: {}", entry.getKey(), e);
            }
        }
        
        subscriptions.clear();
        listeners.clear();
        
        logger.info("RedisConfigService stopped");
    }
    
    @Override
    public boolean isRunning() {
        return running;
    }
    
    /**
     * 生成配置版本号
     */
    private static final java.util.concurrent.atomic.AtomicLong LAST_TS = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger(0);
    private String generateVersion() {
        long now = System.currentTimeMillis();
        long last = LAST_TS.getAndUpdate(prev -> Math.max(prev, now));
        if (now == last) {
            int s = SEQ.updateAndGet(v -> (v >= 9999 ? 0 : v + 1));
            return now + "-" + s;
        } else {
            SEQ.set(0);
            return now + "-0";
        }
    }
    
    /**
     * 生成客户端ID
     */
    private String generateClientId() {
        return "client-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID();
    }
    
    /**
     * 保存配置历史记录
     */
    private void saveConfigHistory(String dataId, String group, String content, String version, 
                                 LocalDateTime changeTime) {
        saveConfigHistory(dataId, group, content, version, changeTime, "UPDATED");
    }
    
    /**
     * 保存配置历史记录
     */
    private void saveConfigHistory(String dataId, String group, String content, String version, 
                                 LocalDateTime changeTime, String operation) {
        try {
            String key = config.getConfigHistoryKey(group, dataId);
            RList<String> list = redissonClient.getList(key, org.redisson.client.codec.StringCodec.INSTANCE);
            Map<String, Object> record = new HashMap<>();
            record.put("dataId", dataId);
            record.put("group", group);
            record.put("content", content);
            record.put("version", version);
            record.put("operation", operation);
            record.put("changeTime", changeTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            record.put("operator", "system");
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(record);
            list.add(0, json);
            if (list.size() > maxHistorySize) {
                list.trim(0, maxHistorySize - 1);
            }
        } catch (Exception e) {
            logger.warn("Failed to save config history for {}:{}", group, dataId, e);
        }
    }
    
    /**
     * 发布配置变更事件
     */
    private void publishConfigChangeEvent(String dataId, String group, String content, String version) {
        try {
            RTopic topic = redissonClient.getTopic(config.getConfigChangeChannelKey(group, dataId), new JsonJacksonCodec());
            ConfigChangeEvent evt = new ConfigChangeEvent(dataId, group, content, version, System.currentTimeMillis());
            topic.publish(evt);
            // Also deliver locally to in-process listeners to reduce timing flakiness
            try { handleConfigChangeEvent(dataId, group, evt); } catch (Exception ignore) {}
            
        } catch (Exception e) {
            logger.warn("Failed to publish config change event for {}:{}", group, dataId, e);
        }
    }
    
    /**
     * 处理配置变更事件
     */
    private void handleConfigChangeEvent(String dataId, String group, ConfigChangeEvent message) {
        try {
            String content = message.getContent();
            String version = message.getVersion();
            
            String listenerKey = group + ":" + dataId;
            Set<ConfigChangeListener> configListeners = listeners.get(listenerKey);
            
            if (configListeners != null && !configListeners.isEmpty()) {
                for (ConfigChangeListener listener : configListeners) {
                    try {
                        listener.onConfigChange(dataId, group, content, version);
                    } catch (Exception e) {
                        logger.error("Error in config change listener", e);
                    }
                }
            }
            
            logger.debug("Processed config change event: {}:{}, version: {}", group, dataId, version);
            
        } catch (Exception e) {
            logger.error("Failed to handle config change event for {}:{}", group, dataId, e);
        }
    }
    
    /**
     * 构建配置历史对象
     */
    @SuppressWarnings("unchecked")
    private ConfigHistory buildConfigHistory(Object dataObj) {
        try {
            Map<String, Object> data;
            if (dataObj instanceof String) {
                data = new com.fasterxml.jackson.databind.ObjectMapper().readValue((String) dataObj, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            } else {
                data = (Map<String, Object>) dataObj;
            }
            String dataId = String.valueOf(data.get("dataId"));
            String group = String.valueOf(data.get("group"));
            String content = data.get("content") != null ? String.valueOf(data.get("content")) : null;
            String version = data.get("version") != null ? String.valueOf(data.get("version")) : null;
            String operation = data.get("operation") != null ? String.valueOf(data.get("operation")) : null;
            String operator = data.get("operator") != null ? String.valueOf(data.get("operator")) : null;
            Object changeTimeObj = data.get("changeTime");
            
            LocalDateTime changeTime = null;
            if (changeTimeObj instanceof Long) {
                changeTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli((Long) changeTimeObj), ZoneId.systemDefault());
            }
            
            return ConfigHistory.builder()
                    .dataId(dataId)
                    .group(group)
                    .content(content)
                    .version(version)
                    .description(operation)
                    .operator(operator)
                    .changeTime(changeTime)
                    .build();
                    
        } catch (Exception e) {
            logger.error("Failed to build config history from dataObj: {}", dataObj, e);
            return null;
        }
    }
}
