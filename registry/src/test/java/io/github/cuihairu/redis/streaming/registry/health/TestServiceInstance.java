package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.Protocol;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;

import java.net.URI;
import java.util.Collections;
import java.util.Map;

class TestServiceInstance implements ServiceInstance {
    private final String serviceName;
    private final String instanceId;
    private final String host;
    private final int port;
    private final Protocol protocol;

    TestServiceInstance(String serviceName, String instanceId, String host, int port, Protocol protocol) {
        this.serviceName = serviceName;
        this.instanceId = instanceId;
        this.host = host;
        this.port = port;
        this.protocol = protocol;
    }

    @Override
    public String getServiceName() {
        return serviceName;
    }

    @Override
    public String getInstanceId() {
        return instanceId;
    }

    @Override
    public String getHost() {
        return host;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public Map<String, String> getMetadata() {
        return Collections.emptyMap();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public Protocol getProtocol() {
        return protocol;
    }

    @Override
    public URI getUri() {
        return ServiceInstance.super.getUri();
    }
}

