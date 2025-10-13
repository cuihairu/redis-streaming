package io.github.cuihairu.streaming.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 配置信息模型
 * 封装配置的完整信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfigInfo {
    
    /**
     * 配置ID
     */
    private String dataId;
    
    /**
     * 配置组
     */
    private String group;
    
    /**
     * 配置内容
     */
    private String content;
    
    /**
     * 配置版本
     */
    private String version;
    
    /**
     * 配置描述
     */
    private String description;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}