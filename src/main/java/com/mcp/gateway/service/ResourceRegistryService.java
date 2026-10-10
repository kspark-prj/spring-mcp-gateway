package com.mcp.gateway.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mcp.gateway.domain.McpServerEntity;
import com.mcp.gateway.repository.McpServerRepository;
import com.mcp.gateway.transport.http.McpHttpSessionManager;

import reactor.core.publisher.Mono;

@Service
public class ResourceRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ResourceRegistryService.class);
    public static final String NAMESPACE_SEPARATOR = "__";
    private static final String CATALOG_CACHE_KEY = "unified_resource_catalog";

    private final McpServerRepository serverRepository;
    private final McpHttpSessionManager httpSessionManager;
    private final ObjectMapper objectMapper;

    private final Cache<String, List<Map<String, Object>>> resourceRegistryCache;

    public ResourceRegistryService(McpServerRepository serverRepository,
                                   McpHttpSessionManager httpSessionManager,
                                   ObjectMapper objectMapper) {
        this.serverRepository = serverRepository;
        this.httpSessionManager = httpSessionManager;
        this.objectMapper = objectMapper;
        this.resourceRegistryCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .maximumSize(100)
                .build();
    }

    public void evictCache() {
        resourceRegistryCache.invalidateAll();
        log.info("Evested Resource Registry cache.");
    }

    @SuppressWarnings("unchecked")
    public Mono<List<Map<String, Object>>> getUnifiedResourceCatalog() {
        List<Map<String, Object>> cached = resourceRegistryCache.getIfPresent(CATALOG_CACHE_KEY);
        if (cached != null) {
            return Mono.just(cached);
        }

        return serverRepository.findByEnabledTrue()
                .flatMap(this::fetchResourcesForServer)
                .collectList()
                .map(lists -> {
                    List<Map<String, Object>> unified = new ArrayList<>();
                    for (List<Map<String, Object>> list : lists) {
                        unified.addAll(list);
                    }
                    resourceRegistryCache.put(CATALOG_CACHE_KEY, unified);
                    return unified;
                });
    }

    private Mono<List<Map<String, Object>>> fetchResourcesForServer(McpServerEntity server) {
        if (!"HTTP".equalsIgnoreCase(server.getTransportType())) {
            return Mono.just(Collections.emptyList());
        }

        Map<String, Object> requestMap = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "resources/list"
        );

        return httpSessionManager.sendRequest(server.getEndpointUrl(), requestMap)
                .map(responseMap -> extractAndNamespaceResources(server.getName(), responseMap))
                .onErrorResume(ex -> {
                    log.error("Failed to fetch resources from HTTP server [{}]: {}", server.getName(), ex.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractAndNamespaceResources(String serverName, Map<String, Object> jsonRpcResponse) {
        if (jsonRpcResponse == null || !jsonRpcResponse.containsKey("result")) {
            return Collections.emptyList();
        }
        Object resultObj = jsonRpcResponse.get("result");
        if (!(resultObj instanceof Map)) {
            return Collections.emptyList();
        }
        Map<String, Object> resultMap = (Map<String, Object>) resultObj;
        Object resourcesObj = resultMap.get("resources");
        if (!(resourcesObj instanceof List)) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> originalResources = (List<Map<String, Object>>) resourcesObj;
        List<Map<String, Object>> namespacedResources = new ArrayList<>();

        for (Map<String, Object> resource : originalResources) {
            Map<String, Object> copy = new HashMap<>(resource);
            String originalUri = (String) copy.get("uri");
            // 네임스페이스 규칙 적용 (예: local_test__file:///data.txt)
            String namespacedUri = serverName + NAMESPACE_SEPARATOR + originalUri;
            copy.put("uri", namespacedUri);
            copy.put("originalUri", originalUri);
            copy.put("serverName", serverName);
            namespacedResources.add(copy);
        }

        return namespacedResources;
    }
}