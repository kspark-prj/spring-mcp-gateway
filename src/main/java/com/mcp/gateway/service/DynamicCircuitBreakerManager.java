package com.mcp.gateway.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.mcp.gateway.domain.McpServerEntity;
import com.mcp.gateway.repository.McpServerRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Dynamic Manager for upstream MCP Server CircuitBreakers and Caffeine configuration cache.
 * <p>
 * Ensures Resilience4j {@link CircuitBreakerRegistry} maintains and shares circuit breaker failure states
 * per server instance at the singleton level, while using Caffeine Cache to eliminate per-request DB lookup overhead.
 * </p>
 */
@Service
public class DynamicCircuitBreakerManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicCircuitBreakerManager.class);

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final McpServerRepository serverRepository;
    private final Cache<String, McpServerEntity> serverConfigCache;

    public DynamicCircuitBreakerManager(CircuitBreakerRegistry circuitBreakerRegistry,
                                         McpServerRepository serverRepository,
                                         Cache<String, McpServerEntity> serverConfigCache) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.serverRepository = serverRepository;
        this.serverConfigCache = serverConfigCache;
    }

    /**
     * Eager loading on application startup: loads all active servers into cache and pre-registers circuit breakers.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initEagerLoading() {
        log.info("Performing eager loading of active MCP upstream servers into Caffeine cache and CircuitBreakerRegistry...");
        serverRepository.findAll()
                .doOnNext(this::registerAndCacheServer)
                .doOnComplete(() -> log.info("Successfully eager-loaded all active server CircuitBreakers into registry"))
                .doOnError(err -> log.error("Error during eager loading of server CircuitBreakers", err))
                .subscribe();
    }

    /**
     * Retrieves or creates a singleton {@link CircuitBreaker} instance for the given server entity.
     * <p>
     * Resilience4j {@link CircuitBreakerRegistry} guarantees that once an instance is created for a given server name,
     * its failure stats, sliding window, and state (CLOSED/OPEN/HALF_OPEN) are preserved and shared across requests.
     * </p>
     *
     * @param server MCP server entity
     * @return CircuitBreaker instance
     */
    public CircuitBreaker getCircuitBreaker(McpServerEntity server) {
        if (server == null || server.getName() == null) {
            throw new IllegalArgumentException("Server entity and server name must not be null");
        }
        McpServerEntity cached = serverConfigCache.getIfPresent(server.getName());
        if (cached == null) {
            serverConfigCache.put(server.getName(), server);
            cached = server;
        }
        return getOrCreateCircuitBreaker(cached);
    }

    /**
     * Reactive lookup for CircuitBreaker by server name: checks Caffeine Cache first, falls back to DB if missing.
     *
     * @param serverName server unique name
     * @return Mono emitting CircuitBreaker
     */
    public Mono<CircuitBreaker> getCircuitBreaker(String serverName) {
        McpServerEntity cached = serverConfigCache.getIfPresent(serverName);
        if (cached != null) {
            return Mono.just(getOrCreateCircuitBreaker(cached));
        }

        return serverRepository.findByName(serverName)
                .map(server -> {
                    serverConfigCache.put(serverName, server);
                    return getOrCreateCircuitBreaker(server);
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    // Default fallback if server not found in DB
                    CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(serverName);
                    return cb;
                }));
    }

    /**
     * Refreshes cache and registry when server is created or updated in dashboard.
     * Evicts old cache entry, removes old CircuitBreaker from registry, and pre-loads new instance with updated DB settings.
     *
     * @param server updated server entity
     */
    public void refreshCache(McpServerEntity server) {
        if (server == null || server.getName() == null) return;
        String name = server.getName();
        log.info("Refreshing Caffeine cache and CircuitBreakerRegistry for server: {}", name);
        serverConfigCache.invalidate(name);
        circuitBreakerRegistry.remove(name);
        serverConfigCache.put(name, server);
        getOrCreateCircuitBreaker(server);
    }

    /**
     * Evicts cache entry and removes CircuitBreaker instance from registry when server is deleted.
     *
     * @param serverName server name to remove
     */
    public void evictCache(String serverName) {
        if (serverName == null || serverName.isBlank()) return;
        log.info("Evicting Caffeine cache and CircuitBreakerRegistry entry for server: {}", serverName);
        serverConfigCache.invalidate(serverName);
        circuitBreakerRegistry.remove(serverName);
    }

    private CircuitBreaker getOrCreateCircuitBreaker(McpServerEntity server) {
        String name = server.getName();
        
        return circuitBreakerRegistry.find(name).orElseGet(() -> {
            float failureRate = server.getFailureRateThreshold() != null ? server.getFailureRateThreshold() : 50.0f;
            int slidingWindow = server.getSlidingWindowSize() != null ? server.getSlidingWindowSize() : 10;
            int minCalls = server.getMinimumNumberOfCalls() != null ? server.getMinimumNumberOfCalls() : 5;
            int waitDurationSec = server.getWaitDurationInOpenState() != null ? server.getWaitDurationInOpenState() : 10;

            CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                    .failureRateThreshold(failureRate)
                    .slidingWindowSize(slidingWindow)
                    .minimumNumberOfCalls(minCalls)
                    .waitDurationInOpenState(Duration.ofSeconds(waitDurationSec))
                    .permittedNumberOfCallsInHalfOpenState(3)
                    .build();

            log.info("Creating new CircuitBreaker for server [{}] with parameters: failureRate={}%, slidingWindow={}, minCalls={}, waitDuration={}s",
                    name, failureRate, slidingWindow, minCalls, waitDurationSec);

            return circuitBreakerRegistry.circuitBreaker(name, config);
        });
    }

    private void registerAndCacheServer(McpServerEntity server) {
        if (server != null && server.getName() != null) {
            serverConfigCache.put(server.getName(), server);
            getOrCreateCircuitBreaker(server);
        }
    }
}
