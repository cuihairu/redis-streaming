package io.github.cuihairu.redis.streaming.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * Configuration information model
 * Encapsulates complete configuration information
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfigInfo {
    
    /**
     * Configuration ID
     */
    private String dataId;

    /**
     * Configuration group
     */
    private String group;

    /**
     * Configuration content
     */
    private String content;

    /**
     * Configuration version
     */
    private String version;

    /**
     * Configuration description
     */
    private String description;

    /**
     * Update time
     */
    private LocalDateTime updateTime;

    /**
     * Creation time
     */
    private LocalDateTime createTime;
}
