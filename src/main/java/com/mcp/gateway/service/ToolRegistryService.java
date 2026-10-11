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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.mcp.gateway.domain.McpServerEntity;
import com.mcp.gateway.repository.McpServerRepository;
import com.mcp.gateway.transport.http.McpHttpSessionManager;
import com.mcp.gateway.transport.stdio.StdioProcessManager;

import reactor.core.publisher.Mono;

/**
 * Service managing aggregated tool catalog discovery, merging, and namespace prefixing ({server_name}__{tool_name}).
 */
@Service
public class ToolRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryService.class);
    public static final String NAMESPACE_SEPARATOR = "__";
    private static final String CATALOG_CACHE_KEY = "UNIFIED_TOOL_CATALOG";

    private final McpServerRepository serverRepository;
    private final StdioProcessManager stdioProcessManager;
    private final Cache<String, List<Map<String, Object>>> toolRegistryCache;
    private final McpHttpSessionManager httpSessionManager;
    private final ObjectMapper objectMapper;

    public ToolRegistryService(McpServerRepository serverRepository,
                               StdioProcessManager stdioProcessManager,
                               Cache<String, List<Map<String, Object>>> toolRegistryCache,
                               McpHttpSessionManager httpSessionManager,
                               ObjectMapper objectMapper) {
        this.serverRepository = serverRepository;
        this.stdioProcessManager = stdioProcessManager;
        this.toolRegistryCache = toolRegistryCache;
        this.httpSessionManager = httpSessionManager;
        this.objectMapper = objectMapper;
    }

    /**
     * Retrieves the merged list of tools across all active upstream MCP servers, namespaced with {server_name}__{tool_name}.
     *
     * @return Mono emitting list of namespaced tool definition maps
     */
    public Mono<List<Map<String, Object>>> getUnifiedToolCatalog() {
    	log.info("🔍 [Debug] getUnifiedToolCatalog() called!"); // 여기에 로그 추가

        List<Map<String, Object>> cachedCatalog = toolRegistryCache.getIfPresent(CATALOG_CACHE_KEY);
        if (cachedCatalog != null) {
            log.info("🔍 [Debug] Returning from Cache!"); // 캐시 타는지 확인
            return Mono.just(cachedCatalog);
        }

        return serverRepository.findByEnabledTrue()
                .doOnNext(server -> log.info("🔍 [Debug] Found active server from DB: {}", server.getName())) // DB 조회 결과 확인
                .flatMap(this::fetchToolsForServer)
                .collectList()
                .map(lists -> {
                    List<Map<String, Object>> unified = new ArrayList<>();
                    for (List<Map<String, Object>> list : lists) {
                        unified.addAll(list);
                    }
                    toolRegistryCache.put(CATALOG_CACHE_KEY, unified);
                    return unified;
                });
    }

    private Mono<List<Map<String, Object>>> fetchToolsForServer(McpServerEntity server) {
        if ("HTTP".equalsIgnoreCase(server.getTransportType())) {
            return fetchToolsFromHttpServer(server);
        } else if ("STDIO".equalsIgnoreCase(server.getTransportType())) {
            return fetchToolsFromStdioServer(server);
        }
        return Mono.just(Collections.emptyList());
    }

    private Mono<List<Map<String, Object>>> fetchToolsFromHttpServer(McpServerEntity server) {
        Map<String, Object> jsonRpcReq = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/list",
                "params", Map.of()
        );

        return httpSessionManager.sendRequest(server.getEndpointUrl(), jsonRpcReq)
                .timeout(Duration.ofSeconds(10))
                .map(resp -> extractAndNamespaceTools(server.getName(), resp))
                .onErrorResume(ex -> {
                    log.warn("Failed to fetch tools from HTTP server [{}] ({}): {}", server.getName(), server.getEndpointUrl(), ex.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    @SuppressWarnings("unchecked")
    private Mono<List<Map<String, Object>>> fetchToolsFromStdioServer(McpServerEntity server) {
        return Mono.fromCallable(() -> {
            log.info("📌 [TOOL-FETCH-DEBUG] fetchToolsFromStdioServer() START: server={}, id={}, commandLine={}", server.getName(), server.getId(), server.getCommandLine());
            try {
                log.info("📌 [TOOL-FETCH-DEBUG] Step 0: Starting STDIO process for [{}]", server.getName());
                StdioProcessManager.ManagedProcess mp = stdioProcessManager.startProcess(
                        server.getId(),
                        server.getName(),
                        server.getCommandLine(),
                        parseEnv(server.getEnvironmentVariables())
                );
                log.info("📌 [TOOL-FETCH-DEBUG] Step 0 DONE: Process started for [{}], processAlive={}", server.getName(), mp.process != null && mp.process.isAlive());

                // Step 1: MCP initialize 핸드셰이크 수행
                String initReqJson = objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", UUID.randomUUID().toString(),
                        "method", "initialize",
                        "params", Map.of(
                                "protocolVersion", "2024-11-05",
                                "capabilities", Map.of(),
                                "clientInfo", Map.of(
                                        "name", "spring-mcp-gateway",
                                        "version", "1.0.0"
                                )
                        )
                ));
                log.info("📌 [TOOL-FETCH-DEBUG] Step 1: Sending initialize to [{}]: {}", server.getName(), initReqJson);
                stdioProcessManager.sendInput(server.getId(), initReqJson);
                log.info("📌 [TOOL-FETCH-DEBUG] Step 1: Waiting for initialize response from [{}] (timeout=10s)...", server.getName());
                String initResp = stdioProcessManager.pollResponse(server.getId(), 10000);
                if (initResp == null) {
                    log.warn("📌 [TOOL-FETCH-DEBUG] Step 1 FAILED: STDIO server [{}] did not respond to initialize within 10s timeout", server.getName());
                    return Collections.<Map<String, Object>>emptyList();
                }
                log.info("📌 [TOOL-FETCH-DEBUG] Step 1 OK: initialize response from [{}]: {}", server.getName(), initResp);

                // Step 2: notifications/initialized 알림 전송
                String notifyJson = objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "method", "notifications/initialized"
                ));
                log.info("📌 [TOOL-FETCH-DEBUG] Step 2: Sending notifications/initialized to [{}]", server.getName());
                stdioProcessManager.sendInput(server.getId(), notifyJson);
                log.info("📌 [TOOL-FETCH-DEBUG] Step 2 OK: notifications/initialized sent to [{}]", server.getName());

                // Step 3: tools/list 요청 전송
                String reqJson = objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", UUID.randomUUID().toString(),
                        "method", "tools/list",
                        "params", Map.of()
                ));
                log.info("📌 [TOOL-FETCH-DEBUG] Step 3: Sending tools/list to [{}]: {}", server.getName(), reqJson);
                stdioProcessManager.sendInput(server.getId(), reqJson);
                log.info("📌 [TOOL-FETCH-DEBUG] Step 3: Waiting for tools/list response from [{}] (timeout=5s)...", server.getName());
                String respJson = stdioProcessManager.pollResponse(server.getId(), 5000);
                if (respJson != null) {
                    log.info("📌 [TOOL-FETCH-DEBUG] Step 3 OK: tools/list response from [{}]: {}", server.getName(), respJson.length() > 1000 ? respJson.substring(0, 1000) + "..." : respJson);
                    Map<String, Object> respMap = objectMapper.readValue(respJson, Map.class);
                    List<Map<String, Object>> tools = extractAndNamespaceTools(server.getName(), respMap);
                    log.info("📌 [TOOL-FETCH-DEBUG] FINAL: Extracted {} tools from [{}]", tools.size(), server.getName());
                    return tools;
                }
                log.warn("📌 [TOOL-FETCH-DEBUG] Step 3 FAILED: tools/list response was null (timeout) for [{}]", server.getName());
            } catch (Exception e) {
                log.warn("📌 [TOOL-FETCH-DEBUG] EXCEPTION in fetchToolsFromStdioServer [{}]: {} - {}", server.getName(), e.getClass().getSimpleName(), e.getMessage(), e);
            }
            log.warn("📌 [TOOL-FETCH-DEBUG] fetchToolsFromStdioServer() returning EMPTY list for [{}]", server.getName());
            return Collections.<Map<String, Object>>emptyList();
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractAndNamespaceTools(String serverName, Map<String, Object> jsonRpcResponse) {
    	// ▼▼▼ 여기에 응답값을 통째로 찍어보는 로그 추가 ▼▼▼
        log.info("🔍 [Upstream Response Debug] Server: {}, Response: {}", serverName, jsonRpcResponse);

        if (jsonRpcResponse == null || !jsonRpcResponse.containsKey("result")) {
            log.warn("❌ [Debug] 'result' key missing in response from server: {}", serverName);
            return Collections.emptyList();
        }
        Object resultObj = jsonRpcResponse.get("result");
        if (!(resultObj instanceof Map)) {
            log.warn("❌ [Debug] 'result' is not a Map from server: {}", serverName);
            return Collections.emptyList();
        }
        Map<String, Object> resultMap = (Map<String, Object>) resultObj;
        Object toolsObj = resultMap.get("tools");
        if (!(toolsObj instanceof List)) {
            log.warn("❌ [Debug] 'tools' is not a List from server: {}", serverName);
            return Collections.emptyList();
        }

        List<Map<String, Object>> originalTools = (List<Map<String, Object>>) toolsObj;
        log.info("✅ [Debug] Successfully parsed {} tools from server: {}", originalTools.size(), serverName);

        List<Map<String, Object>> namespacedTools = new ArrayList<>();

        for (Map<String, Object> tool : originalTools) {
            Map<String, Object> copy = new HashMap<>(tool);
            String originalName = (String) copy.get("name");
            String namespacedName = serverName + NAMESPACE_SEPARATOR + originalName;
            copy.put("name", namespacedName);
            copy.put("originalName", originalName);
            copy.put("serverName", serverName);
            namespacedTools.add(copy);
        }

        return namespacedTools;
    }

    private Map<String, String> parseEnv(String envJson) {
        if (envJson == null || envJson.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(envJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
