package com.mcp.gateway.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.mcp.gateway.config.TsidConfig;
import com.mcp.gateway.domain.McpServerEntity;
import com.mcp.gateway.domain.McpToolsEntity;
import com.mcp.gateway.repository.McpServerRepository;
import com.mcp.gateway.repository.McpServerToolHashRepository;
import com.mcp.gateway.repository.McpToolsRepository;
import com.mcp.gateway.transport.http.McpHttpSessionManager;
import com.mcp.gateway.transport.stdio.StdioProcessManager;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Upstream tool list synchronization service.
 *
 * <h2>동기화 트리거 시점</h2>
 * <ol>
 *   <li><b>애플리케이션 기동 시</b>: {@link GatewayStartupInitializer}가 호출</li>
 *   <li><b>대시보드 수동 새로고침</b>: {@code AdminToolController}가 호출</li>
 * </ol>
 *
 * <h2>핵심 정책</h2>
 * <ul>
 *   <li>수집된 도구 목록의 SHA-256 해시값을 계산하여 기존 저장값과 비교,
 *       변동이 없으면 DB Upsert를 생략하여 시스템 부하를 원천 차단.</li>
 *   <li>변동 감지 시 Bulk Upsert 수행. {@code is_enabled} 플래그는 기존 값을 유지하고
 *       신규 도구만 기본값 {@code true}로 등록.</li>
 *   <li>업스트림에서 사라진 도구(obsolete)는 DB에서 물리 삭제.</li>
 *   <li>동기화 완료 후 Caffeine 캐시를 즉시 무효화하여 최신 상태 반영.</li>
 * </ul>
 */
@Service
public class ToolSyncService {

    private static final Logger log = LoggerFactory.getLogger(ToolSyncService.class);

    private final McpServerRepository serverRepository;
    private final McpToolsRepository toolsRepository;
    private final McpServerToolHashRepository hashRepository;
    private final McpHttpSessionManager httpSessionManager;
    private final StdioProcessManager stdioProcessManager;
    private final ObjectMapper objectMapper;
    /** toolRegistryCache — Caffeine, 무기한(expiry 없음) */
    private final Cache<String, List<McpToolsEntity>> toolRegistryCache;

    public ToolSyncService(McpServerRepository serverRepository,
                           McpToolsRepository toolsRepository,
                           McpServerToolHashRepository hashRepository,
                           McpHttpSessionManager httpSessionManager,
                           StdioProcessManager stdioProcessManager,
                           ObjectMapper objectMapper,
                           Cache<String, List<McpToolsEntity>> toolRegistryCache) {
        this.serverRepository = serverRepository;
        this.toolsRepository = toolsRepository;
        this.hashRepository = hashRepository;
        this.httpSessionManager = httpSessionManager;
        this.stdioProcessManager = stdioProcessManager;
        this.objectMapper = objectMapper;
        this.toolRegistryCache = toolRegistryCache;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 모든 활성화된 업스트림 서버에 대해 도구 목록 동기화를 수행한다.
     * 기동 시 및 대시보드 수동 전체 새로고침 시 호출된다.
     *
     * @return 전체 동기화된 서버 수
     */
    public Mono<Integer> syncAllServers() {
        log.info("🔄 [ToolSync] Starting full tool sync for all enabled servers...");
        return serverRepository.findByEnabledTrue()
                .flatMap(server -> syncServerTools(server)
                        .onErrorResume(ex -> {
                            log.error("❌ [ToolSync] Failed to sync server [{}]: {}", server.getName(), ex.getMessage(), ex);
                            return Mono.just(0);
                        }))
                .reduce(0, Integer::sum)
                .doOnNext(total -> {
                    log.info("✅ [ToolSync] Full sync complete. Total tools synced: {}", total);
                    evictAndRebuildCache();
                });
    }

    /**
     * 특정 서버에 대해서만 도구 목록 동기화를 수행한다.
     * 대시보드 수동 단일 서버 새로고침 시 호출된다.
     *
     * @param serverId 동기화할 서버 ID
     * @return 동기화된 도구 수
     */
    public Mono<Integer> syncServer(Long serverId) {
        return serverRepository.findById(serverId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Server not found: " + serverId)))
                .flatMap(server -> syncServerTools(server)
                        .doOnNext(count -> {
                            log.info("✅ [ToolSync] Server [{}] synced: {} tools", server.getName(), count);
                            evictAndRebuildCache();
                        }));
    }

    /**
     * Caffeine 캐시를 무효화하고 DB로부터 최신 활성 도구 목록으로 재구성한다.
     * {@code is_enabled=true}인 도구만 캐시에 적재.
     */
    public void evictAndRebuildCache() {
        log.info("🗑️  [ToolSync] Evicting tool registry cache and rebuilding from DB...");
        toolRegistryCache.invalidateAll();
        // 비동기 재구성은 다음 요청 시 getUnifiedToolCatalog()가 처리하도록 lazy 방식 채택.
        // 또는 즉시 warm-up을 원하면 아래 주석 해제:
        // rebuildCacheNow().subscribe();
    }

    /**
     * 캐시를 즉시 DB로부터 warm-up한다.
     */
    public Mono<List<McpToolsEntity>> rebuildCacheNow() {
        return toolsRepository.findByIsEnabledTrue()
                .collectList()
                .doOnNext(tools -> {
                    toolRegistryCache.put(ToolRegistryService.CATALOG_CACHE_KEY, tools);
                    log.info("♨️  [ToolSync] Cache rebuilt: {} enabled tools loaded", tools.size());
                });
    }

    // ═══════════════════════════════════════════════════════════════════
    // Core Sync Logic
    // ═══════════════════════════════════════════════════════════════════

    private Mono<Integer> syncServerTools(McpServerEntity server) {
        log.info("🔄 [ToolSync] Fetching tools from upstream server [{}] (transport={})",
                server.getName(), server.getTransportType());

        return fetchRawToolsFromUpstream(server)
                .flatMap(rawTools -> {
                    if (rawTools.isEmpty()) {
                        log.warn("⚠️  [ToolSync] No tools returned from server [{}]. Skipping sync.", server.getName());
                        return Mono.just(0);
                    }

                    String currentHash = computeListHash(rawTools);
                    log.info("🔍 [ToolSync] Server [{}]: {} tools fetched, list_hash={}", server.getName(), rawTools.size(), currentHash);

                    // 해시 비교 — 변동 없으면 Upsert 스킵
                    return hashRepository.findByServerId(server.getId())
                            .flatMap(existing -> {
                                if (currentHash.equals(existing.getListHash())) {
                                    log.info("✅ [ToolSync] Server [{}]: No changes detected (hash match). Skipping Bulk Upsert.", server.getName());
                                    return Mono.just(rawTools.size());
                                }
                                log.info("🔄 [ToolSync] Server [{}]: Changes detected (old={}, new={}). Performing Bulk Upsert.",
                                        server.getName(), existing.getListHash(), currentHash);
                                return performBulkUpsert(server, rawTools, currentHash);
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                // 최초 동기화 (해시 레코드 없음)
                                log.info("🆕 [ToolSync] Server [{}]: First-time sync. Performing initial Bulk Upsert.", server.getName());
                                return performBulkUpsert(server, rawTools, currentHash);
                            }));
                });
    }

    /**
     * Bulk Upsert 수행:
     * <ol>
     *   <li>각 도구를 Upsert (is_enabled 보존, 신규는 true)</li>
     *   <li>업스트림에서 사라진 obsolete 도구 삭제</li>
     *   <li>서버 해시 레코드 갱신</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private Mono<Integer> performBulkUpsert(McpServerEntity server, List<Map<String, Object>> rawTools, String listHash) {
        List<Mono<Void>> upserts = new ArrayList<>();
        List<String> currentNamespacedNames = new ArrayList<>();

        for (Map<String, Object> tool : rawTools) {
            String toolName = (String) tool.get("name");        // 원본 이름 (extractAndNamespaceTools 이전)
            String namespacedName = server.getName() + "__" + toolName;
            String description = (String) tool.getOrDefault("description", "");
            String inputSchemaJson;
            try {
                Object schemaObj = tool.get("inputSchema");
                inputSchemaJson = schemaObj != null ? objectMapper.writeValueAsString(schemaObj) : "{}";
            } catch (Exception e) {
                inputSchemaJson = "{}";
            }
            String toolHash = computeToolHash(toolName, description, inputSchemaJson);

            currentNamespacedNames.add(namespacedName);

            final String finalToolName = toolName;
            final String finalNamespacedName = namespacedName;
            final String finalDescription = description;
            final String finalInputSchema = inputSchemaJson;
            final String finalToolHash = toolHash;

            upserts.add(
                    toolsRepository.upsertTool(
                            TsidConfig.nextLong(),
                            server.getId(),
                            server.getName(),
                            finalToolName,
                            finalNamespacedName,
                            finalDescription,
                            finalInputSchema,
                            finalToolHash
                    )
            );
        }

        final List<String> finalNamespacedNames = currentNamespacedNames;

        return Flux.merge(upserts)
                .then(
                        // obsolete 도구 삭제 (업스트림에서 사라진 도구)
                        finalNamespacedNames.isEmpty()
                                ? Mono.empty()
                                : toolsRepository.deleteObsoleteTools(server.getId(), finalNamespacedNames)
                                        .doOnNext(deleted -> {
                                            if (deleted > 0) {
                                                log.info("🗑️  [ToolSync] Server [{}]: Removed {} obsolete tools.", server.getName(), deleted);
                                            }
                                        }).then()
                )
                .then(
                        // 서버 해시 갱신
                        hashRepository.upsertHash(server.getId(), listHash, rawTools.size())
                )
                .thenReturn(rawTools.size())
                .doOnNext(count -> log.info("✅ [ToolSync] Server [{}]: Bulk Upsert complete ({} tools).", server.getName(), count));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Upstream Fetching
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 업스트림 서버에서 원시 도구 목록을 가져온다.
     * 반환값은 원본 이름({@code name}은 네임스페이스 미포함) 그대로.
     */
    private Mono<List<Map<String, Object>>> fetchRawToolsFromUpstream(McpServerEntity server) {
        if ("HTTP".equalsIgnoreCase(server.getTransportType())) {
            return fetchToolsFromHttpServer(server);
        } else if ("STDIO".equalsIgnoreCase(server.getTransportType())) {
            return fetchToolsFromStdioServer(server);
        }
        return Mono.just(Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    private Mono<List<Map<String, Object>>> fetchToolsFromHttpServer(McpServerEntity server) {
        Map<String, Object> jsonRpcReq = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/list",
                "params", Map.of()
        );
        return httpSessionManager.sendRequest(server.getEndpointUrl(), jsonRpcReq)
                .timeout(Duration.ofSeconds(10))
                .map(resp -> extractRawTools(server.getName(), resp))
                .onErrorResume(ex -> {
                    log.warn("⚠️  [ToolSync] HTTP server [{}] failed: {}", server.getName(), ex.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    @SuppressWarnings("unchecked")
    private Mono<List<Map<String, Object>>> fetchToolsFromStdioServer(McpServerEntity server) {
        return Mono.fromCallable(() -> {
            try {
                Map<String, String> env;
                String envJson = server.getEnvironmentVariables();
                if (envJson == null || envJson.isBlank()) {
                    env = Collections.emptyMap();
                } else {
                    env = objectMapper.readValue(envJson, new TypeReference<Map<String, String>>() {});
                }

                StdioProcessManager.ManagedProcess mp = stdioProcessManager.startProcess(
                        server.getId(), server.getName(), server.getCommandLine(), env);

                // MCP initialize 핸드셰이크
                String initReqJson = objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0", "id", UUID.randomUUID().toString(),
                        "method", "initialize",
                        "params", Map.of(
                                "protocolVersion", "2024-11-05",
                                "capabilities", Map.of(),
                                "clientInfo", Map.of("name", "spring-mcp-gateway", "version", "1.0.0")
                        )
                ));
                stdioProcessManager.sendInput(server.getId(), initReqJson);
                String initResp = stdioProcessManager.pollResponse(server.getId(), 10000);
                if (initResp == null) {
                    log.warn("⚠️  [ToolSync] STDIO server [{}]: initialize timeout.", server.getName());
                    return Collections.<Map<String, Object>>emptyList();
                }

                // notifications/initialized
                stdioProcessManager.sendInput(server.getId(),
                        objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")));

                // tools/list
                String reqJson = objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0", "id", UUID.randomUUID().toString(),
                        "method", "tools/list", "params", Map.of()
                ));
                stdioProcessManager.sendInput(server.getId(), reqJson);
                String respJson = stdioProcessManager.pollResponse(server.getId(), 5000);
                if (respJson != null) {
                    Map<String, Object> respMap = objectMapper.readValue(respJson, Map.class);
                    return extractRawTools(server.getName(), respMap);
                }
            } catch (Exception e) {
                log.warn("⚠️  [ToolSync] STDIO server [{}] exception: {}", server.getName(), e.getMessage(), e);
            }
            return Collections.<Map<String, Object>>emptyList();
        });
    }

    /**
     * JSON-RPC 응답에서 도구 목록을 추출한다. name은 원본 그대로 반환.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractRawTools(String serverName, Map<String, Object> jsonRpcResponse) {
        if (jsonRpcResponse == null || !jsonRpcResponse.containsKey("result")) return Collections.emptyList();
        Object resultObj = jsonRpcResponse.get("result");
        if (!(resultObj instanceof Map)) return Collections.emptyList();
        Map<String, Object> resultMap = (Map<String, Object>) resultObj;
        Object toolsObj = resultMap.get("tools");
        if (!(toolsObj instanceof List)) return Collections.emptyList();
        List<Map<String, Object>> tools = (List<Map<String, Object>>) toolsObj;
        log.info("📋 [ToolSync] Server [{}]: Extracted {} tools from upstream response", serverName, tools.size());
        return tools;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Hash Computation
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 전체 도구 목록의 SHA-256 해시를 계산한다.
     * 도구 이름 기준 정렬 후 직렬화하여 순서에 무관한 결정론적 해시를 보장.
     */
    private String computeListHash(List<Map<String, Object>> tools) {
        try {
            // 도구 이름 기준 정렬 → 결정론적 직렬화
            List<String> sortedNames = tools.stream()
                    .map(t -> String.valueOf(t.get("name")))
                    .sorted()
                    .collect(Collectors.toList());
            String serialized = objectMapper.writeValueAsString(sortedNames);
            return sha256Hex(serialized);
        } catch (Exception e) {
            log.warn("⚠️  [ToolSync] Failed to compute list hash, using random fallback.", e);
            return UUID.randomUUID().toString();
        }
    }

    /**
     * 단일 도구의 SHA-256 해시를 계산한다.
     * 도구 메타데이터 변경(설명, 스키마) 감지용.
     */
    private String computeToolHash(String toolName, String description, String inputSchema) {
        try {
            return sha256Hex(toolName + "|" + (description != null ? description : "") + "|" + (inputSchema != null ? inputSchema : "{}"));
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 computation failed", e);
        }
    }
}
