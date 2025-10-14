package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smooth Weighted Round Robin (Nginx-like).
 * Maintains per-service currentWeight state.
 */
public class WeightedRoundRobinLoadBalancer implements LoadBalancer {
    private static class NodeState {
        int weight;
        int current;
    }

    // serviceName -> (instanceId -> state)
    private final ConcurrentHashMap<String, Map<String, NodeState>> state = new ConcurrentHashMap<>();

    @Override
    public synchronized ServiceInstance choose(String serviceName, List<ServiceInstance> candidates, Map<String, Object> context) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        Map<String, NodeState> svc = state.computeIfAbsent(serviceName, k -> new HashMap<>());

        int total = 0;
        ServiceInstance best = null;
        NodeState bestState = null;

        for (ServiceInstance ins : candidates) {
            String id = ins.getInstanceId();
            NodeState ns = svc.computeIfAbsent(id, k -> new NodeState());
            ns.weight = Math.max(1, extractWeight(ins));
            ns.current += ns.weight;
            total += ns.weight;

            if (best == null || ns.current > bestState.current) {
                best = ins;
                bestState = ns;
            }
        }

        // decrement current of chosen by total
        if (bestState != null) {
            bestState.current -= total;
        }

        // cleanup removed instances
        svc.keySet().retainAll(candidates.stream().map(ServiceInstance::getInstanceId).toList());

        return best;
    }

    private int extractWeight(ServiceInstance ins) {
        int w = ins.getWeight();
        try {
            String mdw = ins.getMetadata().get("weight");
            if (mdw != null) w = Integer.parseInt(mdw);
        } catch (Exception ignore) {}
        return w;
    }
}

