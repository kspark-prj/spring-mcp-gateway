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
import com.mcp.gateway.domain.McpToolsEntity;
import com.mcp.gateway.repository.McpServerRepository;
import com.mcp.gateway.repository.McpToolsRepository;
import com.mcp.gateway.transport.http.McpHttpSessionManager;
import com.mcp.gateway.transport.stdio.StdioProcessManager;

import reactor.core.publisher.Mono;

/**
 * Runtime tool catalog service providing near-zero-latency tool discovery
 * through an unbounded Caffeine in-memory cache.
 *
 * <h2>캐시 우선 전략 (Rule 2)</h2>
 * <ul>
 *   <li>런타임 도구 탐색 및 호출 시점에는 DB 조회를 절대 수행하지 않는다.</li>
 *   <li>무기한 Caffeine 캐시를 우선 참조하여 0ms에 가까운 응답 성능을 보장한다.</li>
 *   <li>캐시 미스(cold start 또는 evict 후 첫 호출) 시에만 DB에서 로드한다.</li>
 *   <li>캐시는 {@link ToolSyncService}가 동기화 완료 후 명시적으로 evict/rebuild한다.</li>
 * </ul>
 *
 * <h2>is_enabled 필터링 (Rule 3)</h2>
 * <ul>
 *   <li>{@code is_enabled = true}인 도구만 캐시에 적재 및 LLM에 노출한다.</li>
 *   <li>비활성 도구의 스키마 노출을 완전 차단한다.</li>
 * </ul>
 */
@Service
public class ToolRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryService.class);

    public static final String NAMESPACE_SEPARATOR = "__";
    /** Caffeine 캐시 키: 전체 활성 도구 목록 */
    public static final String CATALOG_CACHE_KEY = "UNIFIED_TOOL_CATALOG";

    private final McpToolsRepository toolsRepository;
    /** toolRegistryCache — 무기한 Caffeine 캐시 (expiry 없음) */
    private final Cache<String, List<McpToolsEntity>> toolRegistryCache;

    // ── 하위 호환을 위해 유지: AdminServerController.getDiscoveredTools()가
    //    Map 형태 응답을 기대하므로 변환 메서드 제공
    private final ObjectMapper objectMapper;

    public ToolRegistryService(McpToolsRepository toolsRepository,
                               Cache<String, List<McpToolsEntity>> toolRegistryCache,
                               ObjectMapper objectMapper) {
        this.toolsRepository = toolsRepository;
        this.toolRegistryCache = toolRegistryCache;
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 활성화된({@code is_enabled=true}) 전체 도구 목록을 {@link McpToolsEntity} 형태로 반환한다.
     *
     * <p>
     * <b>캐시 우선 전략</b>: Caffeine 캐시 히트 시 DB 조회 없이 즉시 반환.
     * 캐시 미스 시 DB 로드 후 캐시 저장.
     * </p>
     *
     * @return is_enabled=true인 도구 목록 Mono
     */
    public Mono<List<McpToolsEntity>> getEnabledToolEntities() {
        List<McpToolsEntity> cached = toolRegistryCache.getIfPresent(CATALOG_CACHE_KEY);
        if (cached != null) {
            log.debug("⚡ [ToolRegistry] Cache hit: {} enabled tools returned from Caffeine.", cached.size());
            return Mono.just(cached);
        }

        log.info("🔍 [ToolRegistry] Cache miss — loading enabled tools from DB...");
        return toolsRepository.findByIsEnabledTrue()
                .collectList()
                .doOnNext(tools -> {
                    toolRegistryCache.put(CATALOG_CACHE_KEY, tools);
                    log.info("💾 [ToolRegistry] DB load complete: {} enabled tools cached.", tools.size());
                });
    }

    /**
     * 활성화된 도구 목록을 {@code tools/list} 응답 호환 형태의 {@code Map<String, Object>}로 반환한다.
     * <p>
     * MCP JSON-RPC 프로토콜 응답 및 관리자 조회용.
     * 캐시 우선 전략 동일 적용 ({@link #getEnabledToolEntities()} 위임).
     * </p>
     *
     * @return namespaced tool definition map list
     */
    public Mono<List<Map<String, Object>>> getUnifiedToolCatalog() {
        return getEnabledToolEntities()
                .map(tools -> {
                    List<Map<String, Object>> result = new ArrayList<>(tools.size());
                    for (McpToolsEntity tool : tools) {
                        result.add(toToolMap(tool));
                    }
                    return result;
                });
    }

    /**
     * 특정 도구가 활성화 상태인지 확인한다 (namespaced_name 기준).
     * 캐시 또는 DB에서 조회하여 판단.
     *
     * @param namespacedName {@code serverName__toolName}
     * @return {@code true}이면 활성화 상태
     */
    public Mono<Boolean> isToolEnabled(String namespacedName) {
        // 캐시에서 먼저 확인
        List<McpToolsEntity> cached = toolRegistryCache.getIfPresent(CATALOG_CACHE_KEY);
        if (cached != null) {
            boolean enabled = cached.stream()
                    .anyMatch(t -> namespacedName.equals(t.getNamespacedName()));
            return Mono.just(enabled);
        }
        // 캐시 미스 시 DB 단건 조회
        return toolsRepository.findByNamespacedName(namespacedName)
                .map(tool -> Boolean.TRUE.equals(tool.getIsEnabled()))
                .defaultIfEmpty(false);
    }

    /**
     * 캐시를 즉시 무효화한다.
     * {@link ToolSyncService} 및 {@code AdminToolController}에서 호출.
     */
    public void evictCache() {
        toolRegistryCache.invalidateAll();
        log.info("🗑️  [ToolRegistry] Tool registry cache evicted.");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════════════════════

    /**
     * {@link McpToolsEntity}를 MCP tools/list 응답 호환 Map으로 변환한다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toToolMap(McpToolsEntity tool) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", tool.getNamespacedName());
        map.put("originalName", tool.getToolName());
        map.put("serverName", tool.getServerName());
        if (tool.getDescription() != null) {
            map.put("description", tool.getDescription());
        }
        // inputSchema 역직렬화 (Map 형태로 변환)
        if (tool.getInputSchema() != null && !tool.getInputSchema().isBlank()) {
            try {
                Object schema = objectMapper.readValue(tool.getInputSchema(),
                        new TypeReference<Map<String, Object>>() {});
                map.put("inputSchema", schema);
            } catch (Exception e) {
                map.put("inputSchema", Map.of());
            }
        } else {
            map.put("inputSchema", Map.of());
        }
        return map;
    }
}
