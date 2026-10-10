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
public class PromptRegistryService {

    private static final Logger log = LoggerFactory.getLogger(PromptRegistryService.class);
    public static final String NAMESPACE_SEPARATOR = "__";
    private static final String CATALOG_CACHE_KEY = "unified_prompt_catalog";

    private final McpServerRepository serverRepository;
    private final McpHttpSessionManager httpSessionManager;
    private final ObjectMapper objectMapper;

    private final Cache<String, List<Map<String, Object>>> promptRegistryCache;

    public PromptRegistryService(McpServerRepository serverRepository,
                                 McpHttpSessionManager httpSessionManager,
                                 ObjectMapper objectMapper) {
        this.serverRepository = serverRepository;
        this.httpSessionManager = httpSessionManager;
        this.objectMapper = objectMapper;
        this.promptRegistryCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .maximumSize(100)
                .build();
    }

    public void evictCache() {
        promptRegistryCache.invalidateAll();
        log.info("Evicted Prompt Registry cache.");
    }

    @SuppressWarnings("unchecked")
    public Mono<List<Map<String, Object>>> getUnifiedPromptCatalog() {
        List<Map<String, Object>> cached = promptRegistryCache.getIfPresent(CATALOG_CACHE_KEY);
        if (cached != null) {
            return Mono.just(cached);
        }

        return serverRepository.findByEnabledTrue()
                .flatMap(this::fetchPromptsForServer)
                .collectList()
                .map(lists -> {
                    List<Map<String, Object>> unified = new ArrayList<>();
                    for (List<Map<String, Object>> list : lists) {
                        unified.addAll(list);
                    }
                    promptRegistryCache.put(CATALOG_CACHE_KEY, unified);
                    return unified;
                });
    }

    private Mono<List<Map<String, Object>>> fetchPromptsForServer(McpServerEntity server) {
        if (!"HTTP".equalsIgnoreCase(server.getTransportType())) {
            return Mono.just(Collections.emptyList());
        }

        Map<String, Object> requestMap = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "prompts/list"
        );

        return httpSessionManager.sendRequest(server.getEndpointUrl(), requestMap)
                .map(responseMap -> extractAndNamespacePrompts(server.getName(), responseMap))
                .onErrorResume(ex -> {
                    log.error("Failed to fetch prompts from HTTP server [{}]: {}", server.getName(), ex.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractAndNamespacePrompts(String serverName, Map<String, Object> jsonRpcResponse) {
        if (jsonRpcResponse == null || !jsonRpcResponse.containsKey("result")) {
            return Collections.emptyList();
        }
        Object resultObj = jsonRpcResponse.get("result");
        if (!(resultObj instanceof Map)) {
            return Collections.emptyList();
        }
        Map<String, Object> resultMap = (Map<String, Object>) resultObj;
        Object promptsObj = resultMap.get("prompts");
        if (!(promptsObj instanceof List)) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> originalPrompts = (List<Map<String, Object>>) promptsObj;
        List<Map<String, Object>> namespacedPrompts = new ArrayList<>();

        for (Map<String, Object> prompt : originalPrompts) {
            Map<String, Object> copy = new HashMap<>(prompt);
            String originalName = (String) copy.get("name");
            String namespacedName = serverName + NAMESPACE_SEPARATOR + originalName;
            copy.put("name", namespacedName);
            copy.put("originalName", originalName);
            copy.put("serverName", serverName);
            namespacedPrompts.add(copy);
        }

        return namespacedPrompts;
    }
}