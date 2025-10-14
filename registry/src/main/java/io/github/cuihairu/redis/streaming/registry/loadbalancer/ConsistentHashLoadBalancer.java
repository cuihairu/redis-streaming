package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Ketama-like consistent hash with virtual nodes.
 * Context requires key "hashKey".
 */
public class ConsistentHashLoadBalancer implements LoadBalancer {
    private final int virtualNodes;

    public ConsistentHashLoadBalancer() { this(128); }
    public ConsistentHashLoadBalancer(int virtualNodes) { this.virtualNodes = Math.max(1, virtualNodes); }

    @Override
    public ServiceInstance choose(String serviceName, List<ServiceInstance> candidates, Map<String, Object> context) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);
        String key = context != null ? String.valueOf(context.get("hashKey")) : null;
        if (key == null || key.equals("null")) {
            // fallback: first
            return candidates.get(0);
        }
        SortedMap<Long, ServiceInstance> ring = buildRing(candidates);
        long h = hash(key);
        SortedMap<Long, ServiceInstance> tail = ring.tailMap(h);
        Long node = tail.isEmpty() ? ring.firstKey() : tail.firstKey();
        return ring.get(node);
    }

    private SortedMap<Long, ServiceInstance> buildRing(List<ServiceInstance> candidates) {
        SortedMap<Long, ServiceInstance> ring = new TreeMap<>();
        for (ServiceInstance ins : candidates) {
            String base = ins.getUniqueId() != null ? ins.getUniqueId() : (ins.getServiceName()+":"+ins.getInstanceId());
            for (int i = 0; i < virtualNodes; i++) {
                long h = hash(base + "#" + i);
                ring.put(h, ins);
            }
        }
        return ring;
    }

    private static long hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            // take first 8 bytes as unsigned long
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (d[i] & 0xff);
            }
            return h & 0x7fffffffffffffffL;
        } catch (Exception e) {
            return s.hashCode() & 0x7fffffff;
        }
    }
}

