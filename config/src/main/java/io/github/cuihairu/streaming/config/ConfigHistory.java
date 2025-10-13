package io.github.cuihairu.streaming.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 配置历史记录
 * 用于存储配置的历史版本信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfigHistory {
    
    /**
     * 配置ID
     */
    private String dataId;
    
    /**
     * 配置组
     */
    private String group;
    
    /**
     * 历史配置内容
     */
    private String content;
    
    /**
     * 历史版本号
     */
    private String version;
    
    /**
     * 变更描述
     */
    private String description;
    
    /**
     * 变更时间
     */
    private LocalDateTime changeTime;
    
    /**
     * 操作人
     */
    private String operator;
}