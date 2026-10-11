package com.mcp.gateway.controller.admin;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mcp.gateway.domain.McpToolsEntity;
import com.mcp.gateway.repository.McpToolsRepository;
import com.mcp.gateway.service.ToolRegistryService;
import com.mcp.gateway.service.ToolSyncService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Administrator Tool Registry Management Controller.
 *
 * <h2>제공 기능</h2>
 * <ul>
 *   <li><b>도구 목록 조회</b>: 서버별 또는 전체 도구 목록 (is_enabled 포함)</li>
 *   <li><b>수동 새로고침</b>: 대시보드에서 특정 서버 또는 전체 서버 도구 목록 즉시 동기화 (Rule 1-②)</li>
 *   <li><b>is_enabled 토글</b>: 도구 개별 활성/비활성 제어 → 캐시 즉시 무효화 (Rule 2, 3)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/tools")
public class AdminToolController {

    private final McpToolsRepository toolsRepository;
    private final ToolSyncService toolSyncService;
    private final ToolRegistryService toolRegistryService;

    public AdminToolController(McpToolsRepository toolsRepository,
                               ToolSyncService toolSyncService,
                               ToolRegistryService toolRegistryService) {
        this.toolsRepository = toolsRepository;
        this.toolSyncService = toolSyncService;
        this.toolRegistryService = toolRegistryService;
    }

    // ─────────────────────────────────────────────────────────────────
    // 도구 목록 조회
    // ─────────────────────────────────────────────────────────────────

    /**
     * 전체 도구 목록 조회 (is_enabled 상태 포함, DB 직접 조회).
     * 대시보드 도구 관리 화면 렌더링용.
     *
     * @param serverId    (옵션) 특정 서버 필터링
     * @param enabledOnly (옵션) true이면 is_enabled=true인 도구만
     */
    @GetMapping
    public Flux<McpToolsEntity> getAllTools(
            @RequestParam(required = false) Long serverId,
            @RequestParam(defaultValue = "false") boolean enabledOnly) {

        if (serverId != null) {
            return enabledOnly
                    ? toolsRepository.findByServerIdAndIsEnabledTrue(serverId)
                    : toolsRepository.findByServerId(serverId);
        }
        return enabledOnly
                ? toolsRepository.findByIsEnabledTrue()
                : toolsRepository.findAll();
    }

    /**
     * 특정 도구 단건 조회 (Long ID 기준).
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<McpToolsEntity>> getTool(@PathVariable Long id) {
        return toolsRepository.findById(id)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // ─────────────────────────────────────────────────────────────────
    // 수동 동기화 (Rule 1-②: 대시보드 수동 새로고침)
    // ─────────────────────────────────────────────────────────────────

    /**
     * 전체 서버 도구 목록 수동 동기화 트리거.
     * 해시 기반 변경 감지: 변동이 없는 서버는 Upsert를 건너뛰어 부하를 최소화한다.
     * 동기화 완료 후 Caffeine 캐시를 즉시 evict하고 최신 상태로 재구성한다.
     */
    @PostMapping("/sync")
    public Mono<ResponseEntity<Map<String, Object>>> syncAllTools() {
        return toolSyncService.syncAllServers()
                .flatMap(totalSynced ->
                        toolSyncService.rebuildCacheNow()
                                .map(tools -> {
                                    Map<String, Object> body = new HashMap<>();
                                    body.put("status", "SUCCESS");
                                    body.put("totalToolsSynced", totalSynced);
                                    body.put("cachedEnabledTools", tools.size());
                                    body.put("syncedAt", Instant.now().toString());
                                    return ResponseEntity.ok(body);
                                })
                )
                .onErrorResume(ex -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("status", "FAILED");
                    body.put("error", ex.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body));
                });
    }

    /**
     * 특정 서버의 도구 목록 수동 동기화 트리거 (대시보드 서버별 새로고침 버튼).
     * 해시 비교 후 변동이 있을 때만 Bulk Upsert.
     * 완료 후 Caffeine 캐시 즉시 evict + rebuild.
     *
     * @param serverId 동기화할 서버 ID
     */
    @PostMapping("/sync/{serverId}")
    public Mono<ResponseEntity<Map<String, Object>>> syncServerTools(@PathVariable Long serverId) {
        return toolSyncService.syncServer(serverId)
                .flatMap(toolCount ->
                        toolSyncService.rebuildCacheNow()
                                .map(tools -> {
                                    Map<String, Object> body = new HashMap<>();
                                    body.put("status", "SUCCESS");
                                    body.put("serverId", serverId);
                                    body.put("toolsSynced", toolCount);
                                    body.put("cachedEnabledTools", tools.size());
                                    body.put("syncedAt", Instant.now().toString());
                                    return ResponseEntity.ok(body);
                                })
                )
                .onErrorResume(ex -> {
                    String msg = ex.getMessage();
                    HttpStatus status = msg != null && msg.contains("not found")
                            ? HttpStatus.NOT_FOUND : HttpStatus.INTERNAL_SERVER_ERROR;
                    Map<String, Object> body = new HashMap<>();
                    body.put("status", "FAILED");
                    body.put("error", msg != null ? msg : "Unknown error");
                    return Mono.just(ResponseEntity.status(status).body(body));
                });
    }

    // ─────────────────────────────────────────────────────────────────
    // is_enabled 토글 (Rule 2, 3)
    // ─────────────────────────────────────────────────────────────────

    /**
     * 특정 도구의 {@code is_enabled} 상태를 토글 또는 직접 지정한다.
     * 변경 즉시 Caffeine 캐시를 evict하고 최신 DB 상태로 재구성.
     *
     * @param id      대상 도구의 TSID Long ID
     * @param request {@code {"isEnabled": true/false}} 또는 {@code {"toggle": true}}
     */
    @PatchMapping("/{id}/enabled")
    public Mono<ResponseEntity<McpToolsEntity>> updateToolEnabled(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {

        return toolsRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Tool not found: " + id)))
                .flatMap(tool -> {
                    Boolean newEnabledValue;
                    Object toggleObj = request.get("toggle");
                    Object isEnabledObj = request.get("isEnabled");

                    if (Boolean.TRUE.equals(toggleObj)) {
                        newEnabledValue = !Boolean.TRUE.equals(tool.getIsEnabled());
                    } else if (isEnabledObj instanceof Boolean) {
                        newEnabledValue = (Boolean) isEnabledObj;
                    } else {
                        return Mono.error(new IllegalArgumentException(
                                "Request body must contain 'isEnabled' (Boolean) or 'toggle' (true)"));
                    }

                    tool.setIsEnabled(newEnabledValue);
                    tool.setUpdatedAt(Instant.now());
                    return toolsRepository.save(tool);
                })
                .doOnNext(saved -> {
                    // 캐시 즉시 무효화 + 재구성 (비동기)
                    toolRegistryService.evictCache();
                    toolSyncService.rebuildCacheNow().subscribe(tools -> {}, ex -> {});
                })
                .map(ResponseEntity::ok)
                .onErrorResume(ex -> {
                    String msg = ex.getMessage();
                    HttpStatus status = msg != null && msg.contains("not found")
                            ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
                    return Mono.just(ResponseEntity.status(status).build());
                });
    }

    /**
     * 특정 서버의 모든 도구를 일괄 활성화 또는 비활성화한다.
     * 완료 후 캐시 즉시 evict + rebuild.
     *
     * @param serverId  대상 서버 ID
     * @param isEnabled true: 전체 활성화, false: 전체 비활성화
     */
    @PatchMapping("/server/{serverId}/enabled")
    public Mono<ResponseEntity<Map<String, Object>>> bulkUpdateServerToolsEnabled(
            @PathVariable Long serverId,
            @RequestParam boolean isEnabled) {

        return toolsRepository.findByServerId(serverId)
                .flatMap(tool -> {
                    tool.setIsEnabled(isEnabled);
                    tool.setUpdatedAt(Instant.now());
                    return toolsRepository.save(tool);
                })
                .count()
                .doOnNext(count -> {
                    toolRegistryService.evictCache();
                    toolSyncService.rebuildCacheNow().subscribe(tools -> {}, ex -> {});
                })
                .map(count -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("serverId", serverId);
                    body.put("isEnabled", isEnabled);
                    body.put("updatedCount", count);
                    body.put("updatedAt", Instant.now().toString());
                    return ResponseEntity.ok(body);
                })
                .onErrorResume(ex -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("status", "FAILED");
                    body.put("error", ex.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body));
                });
    }
}
