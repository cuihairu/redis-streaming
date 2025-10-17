package io.github.cuihairu.redis.streaming.mq.admin.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class MessageEntry {
    private String id;
    private int partitionId;
    private Map<String, Object> fields;
}

