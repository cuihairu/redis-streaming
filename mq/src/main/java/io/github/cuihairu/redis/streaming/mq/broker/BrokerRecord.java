package io.github.cuihairu.redis.streaming.mq.broker;

import java.util.Map;

/** Minimal backend record representation returned by Broker.readGroup. */
public class BrokerRecord {
    private final String id;
    private final Map<String, Object> data;

    public BrokerRecord(String id, Map<String, Object> data) {
        this.id = id;
        this.data = data;
    }

    public String getId() { return id; }
    public Map<String, Object> getData() { return data; }
}

