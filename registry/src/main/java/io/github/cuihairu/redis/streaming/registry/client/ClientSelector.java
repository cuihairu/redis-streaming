package io.github.cuihairu.redis.streaming.registry.client;

import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.filter.FilterBuilder;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.LoadBalancer;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Facade to select one instance with server-side filters and client-side load balancing.
 * Provides fallback steps if no candidates matched.
 */
public class ClientSelector {
    private final NamingService namingService;
    private final ClientSelectorConfig config;

    public ClientSelector(NamingService namingService) {
        this(namingService, new ClientSelectorConfig());
    }

    public ClientSelector(NamingService namingService, ClientSelectorConfig config) {
        this.namingService = Objects.requireNonNull(namingService);
        this.config = config != null ? config : new ClientSelectorConfig();
    }

    public ServiceInstance select(String serviceName,
                                  Map<String, String> metadataFilters,
                                  Map<String, String> metricsFilters,
                                  LoadBalancer loadBalancer,
                                  Map<String, Object> context) {
        // 1) strict: metadata + metrics
        List<ServiceInstance> c = safeList(
                ((io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService) namingService)
                        .getHealthyInstancesByFilters(serviceName, metadataFilters, metricsFilters));
        if (!c.isEmpty()) return loadBalancer.choose(serviceName, c, context);

        if (!config.isEnableFallback()) return null;

        // 2) drop metrics filters
        if (config.isFallbackDropMetricsFiltersFirst()) {
            c = safeList(
                    ((io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService) namingService)
                            .getHealthyInstancesByFilters(serviceName, metadataFilters, null));
            if (!c.isEmpty()) return loadBalancer.choose(serviceName, c, context);
        }

        // 3) drop metadata filters
        if (config.isFallbackDropMetadataFiltersNext()) {
            c = safeList(
                    ((io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService) namingService)
                            .getHealthyInstancesByFilters(serviceName, Collections.emptyMap(), metricsFilters));
            if (!c.isEmpty()) return loadBalancer.choose(serviceName, c, context);
        }

        // 4) all healthy
        if (config.isFallbackUseAllHealthyLast()) {
            c = safeList(namingService.getHealthyInstances(serviceName));
            if (!c.isEmpty()) return loadBalancer.choose(serviceName, c, context);
        }

        return null;
    }

    private static List<ServiceInstance> safeList(List<ServiceInstance> list) {
        return list != null ? list : List.of();
    }
}

