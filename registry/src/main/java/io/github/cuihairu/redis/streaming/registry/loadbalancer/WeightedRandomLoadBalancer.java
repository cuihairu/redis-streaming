package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Weighted random by instance.getWeight() or metadata weight if present.
 */
public class WeightedRandomLoadBalancer implements LoadBalancer {
    @Override
    public ServiceInstance choose(String serviceName, List<ServiceInstance> candidates, Map<String, Object> context) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        int total = 0;
        int[] prefix = new int[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            ServiceInstance ins = candidates.get(i);
            int w = ins.getWeight();
            try {
                String mdw = ins.getMetadata().get("weight");
                if (mdw != null) w = Integer.parseInt(mdw);
            } catch (Exception ignore) {}
            if (w < 1) w = 1;
            total += w;
            prefix[i] = total;
        }
        int r = ThreadLocalRandom.current().nextInt(total) + 1;
        int lo = 0, hi = prefix.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (prefix[mid] >= r) hi = mid; else lo = mid + 1;
        }
        return candidates.get(lo);
    }
}

