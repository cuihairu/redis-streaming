package io.github.cuihairu.redis.streaming.registry.heartbeat;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

/**
 * Heartbeat configuration
 */
@Setter
@Getter
public class HeartbeatConfig {

    /**
     * Base heartbeat interval (only updates timestamp)
     */
    private Duration heartbeatInterval = Duration.ofSeconds(3);

    /**
     * Whether to enable metadata change detection
     * <p>Default false, because metadata (region, version, etc.) rarely changes</p>
     * <p>If the application dynamically modifies metadata at runtime, this option can be enabled</p>
     */
    private boolean enableMetadataChangeDetection = false;

    /**
     * Metadata minimum update interval (seconds)
     * <p>Prevents frequent metadata updates, default 600 seconds (10 minutes)</p>
     * <p>Only effective when enableMetadataChangeDetection=true</p>
     */
    private int metadataUpdateIntervalSeconds = 600;
}
