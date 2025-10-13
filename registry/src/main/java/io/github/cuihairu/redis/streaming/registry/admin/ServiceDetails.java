package io.github.cuihairu.redis.streaming.registry.admin;

import java.util.List;
import java.util.Map;

/**
 * 服务详细信息
 */
public class ServiceDetails {
    private String serviceName;
    private int totalInstances;
    private int healthyInstances;
    private int enabledInstances;
    private List<InstanceDetails> instances;
    private Map<String, Object> aggregatedMetrics;
    private long lastUpdateTime;

    // Constructors
    public ServiceDetails() {}

    public ServiceDetails(String serviceName) {
        this.serviceName = serviceName;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    // Getters and Setters
    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public int getTotalInstances() {
        return totalInstances;
    }

    public void setTotalInstances(int totalInstances) {
        this.totalInstances = totalInstances;
    }

    public int getHealthyInstances() {
        return healthyInstances;
    }

    public void setHealthyInstances(int healthyInstances) {
        this.healthyInstances = healthyInstances;
    }

    public int getEnabledInstances() {
        return enabledInstances;
    }

    public void setEnabledInstances(int enabledInstances) {
        this.enabledInstances = enabledInstances;
    }

    public List<InstanceDetails> getInstances() {
        return instances;
    }

    public void setInstances(List<InstanceDetails> instances) {
        this.instances = instances;
        updateStatistics();
    }

    public Map<String, Object> getAggregatedMetrics() {
        return aggregatedMetrics;
    }

    public void setAggregatedMetrics(Map<String, Object> aggregatedMetrics) {
        this.aggregatedMetrics = aggregatedMetrics;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public void setLastUpdateTime(long lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }

    /**
     * 更新统计信息
     */
    private void updateStatistics() {
        if (instances == null) {
            totalInstances = healthyInstances = enabledInstances = 0;
            return;
        }

        totalInstances = instances.size();
        healthyInstances = (int) instances.stream().filter(InstanceDetails::isHealthy).count();
        enabledInstances = (int) instances.stream().filter(InstanceDetails::isEnabled).count();
        lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * 获取健康率
     */
    public double getHealthyRate() {
        return totalInstances > 0 ? (double) healthyInstances / totalInstances * 100 : 0;
    }

    /**
     * 获取启用率
     */
    public double getEnabledRate() {
        return totalInstances > 0 ? (double) enabledInstances / totalInstances * 100 : 0;
    }

    @Override
    public String toString() {
        return "ServiceDetails{" +
                "serviceName='" + serviceName + '\'' +
                ", totalInstances=" + totalInstances +
                ", healthyInstances=" + healthyInstances +
                ", enabledInstances=" + enabledInstances +
                ", healthyRate=" + String.format("%.1f", getHealthyRate()) + "%" +
                '}';
    }
}