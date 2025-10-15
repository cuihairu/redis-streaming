package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.impl.ConfigEntryCodec;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigEntryCodecTest {

    @Test
    void testParseInfo() {
        long now = System.currentTimeMillis();
        Map<String,String> map = new java.util.HashMap<>();
        map.put("content", "v1");
        map.put("version", "ts-1");
        map.put("description", "init");
        map.put("updateTime", String.valueOf(now));
        map.put("createTime", String.valueOf(now - 1000));

        ConfigInfo info = ConfigEntryCodec.parseInfo("d","g", map);
        assertEquals("v1", info.getContent());
        assertEquals("ts-1", info.getVersion());
        assertEquals("init", info.getDescription());
        assertNotNull(info.getUpdateTime());
        assertNotNull(info.getCreateTime());
    }
}
