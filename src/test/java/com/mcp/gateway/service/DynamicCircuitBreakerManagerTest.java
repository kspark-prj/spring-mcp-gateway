package com.mcp.gateway.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.mcp.gateway.domain.McpServerEntity;
import com.mcp.gateway.repository.McpServerRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DynamicCircuitBreakerManagerTest {

    private DynamicCircuitBreakerManager manager;
    private CircuitBreakerRegistry registry;
    private McpServerRepository serverRepository;

    @BeforeEach
    void setUp() {
        CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50.0f)
                .build();
        registry = CircuitBreakerRegistry.of(defaultConfig);
        serverRepository = Mockito.mock(McpServerRepository.class);
        manager = new DynamicCircuitBreakerManager(registry, serverRepository, Caffeine.newBuilder().build());
    }

    @Test
    @DisplayName("getCircuitBreaker preserves single state instance per server")
    void testGetCircuitBreakerPreservesState() {
        McpServerEntity server = new McpServerEntity();
        server.setId(UUID.randomUUID());
        server.setName("test-server");
        server.setFailureRateThreshold(60.0f);
        server.setSlidingWindowSize(20);

        CircuitBreaker cb1 = manager.getCircuitBreaker(server);
        assertNotNull(cb1);
        assertEquals("test-server", cb1.getName());

        CircuitBreaker cb2 = manager.getCircuitBreaker(server);
        assertSame(cb1, cb2, "Subsequent calls must return the same singleton CircuitBreaker instance");
    }

    @Test
    @DisplayName("refreshCache updates settings and replaces instance in registry")
    void testRefreshCacheEvictsAndReloads() {
        McpServerEntity server1 = new McpServerEntity();
        server1.setId(UUID.randomUUID());
        server1.setName("server-alpha");
        server1.setFailureRateThreshold(50.0f);

        CircuitBreaker cb1 = manager.getCircuitBreaker(server1);
        assertNotNull(cb1);

        McpServerEntity server1Updated = new McpServerEntity();
        server1Updated.setId(server1.getId());
        server1Updated.setName("server-alpha");
        server1Updated.setFailureRateThreshold(80.0f);
        server1Updated.setSlidingWindowSize(30);

        manager.refreshCache(server1Updated);

        CircuitBreaker cb2 = manager.getCircuitBreaker(server1Updated);
        assertNotNull(cb2);
        assertNotSame(cb1, cb2, "Refreshing cache should replace the registry instance with updated configuration");
        assertEquals(80.0f, cb2.getCircuitBreakerConfig().getFailureRateThreshold());
    }

    @Test
    @DisplayName("evictCache removes server from registry and cache")
    void testEvictCache() {
        McpServerEntity server = new McpServerEntity();
        server.setId(UUID.randomUUID());
        server.setName("server-to-delete");

        manager.getCircuitBreaker(server);
        assertTrue(registry.find("server-to-delete").isPresent());

        manager.evictCache("server-to-delete");
        assertFalse(registry.find("server-to-delete").isPresent());
    }
}
