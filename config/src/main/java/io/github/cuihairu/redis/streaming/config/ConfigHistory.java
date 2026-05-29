package io.github.cuihairu.redis.streaming.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * Configuration history record
 * Used to store historical version information of configurations
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfigHistory {
    
    /**
     * Configuration ID
     */
    private String dataId;

    /**
     * Configuration group
     */
    private String group;

    /**
     * Historical configuration content
     */
    private String content;

    /**
     * Historical version number
     */
    private String version;

    /**
     * Change description
     */
    private String description;

    /**
     * Change time
     */
    private LocalDateTime changeTime;

    /**
     * Operator
     */
    private String operator;
}
