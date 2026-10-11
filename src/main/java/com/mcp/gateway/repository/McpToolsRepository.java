package com.mcp.gateway.repository;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import com.mcp.gateway.domain.McpToolsEntity;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * R2DBC reactive repository for {@link McpToolsEntity}.
 * <p>
 * Supports hash-based Bulk Upsert pattern and is_enabled filtering for runtime tool discovery.
 * </p>
 */
@Repository
public interface McpToolsRepository extends ReactiveCrudRepository<McpToolsEntity, Long> {

    /** 특정 서버의 모든 도구 조회 */
    Flux<McpToolsEntity> findByServerId(Long serverId);

    /** 특정 서버의 활성화된 도구만 조회 (is_enabled = true) */
    Flux<McpToolsEntity> findByServerIdAndIsEnabledTrue(Long serverId);

    /** 전체 활성화 도구 조회 — Caffeine 캐시 재구성 시 사용 */
    Flux<McpToolsEntity> findByIsEnabledTrue();

    /** namespaced_name 기준 단건 조회 */
    Mono<McpToolsEntity> findByNamespacedName(String namespacedName);

    /** 특정 서버의 도구 중 namespaced_name으로 단건 조회 */
    Mono<McpToolsEntity> findByServerIdAndToolName(Long serverId, String toolName);

    /** 특정 서버의 모든 도구 삭제 (동기화 전 클린업 시 사용) */
    @Modifying
    @Query("DELETE FROM mcp_tools WHERE server_id = :serverId")
    Mono<Void> deleteByServerId(Long serverId);

    /**
     * 도구 메타데이터 Upsert (신규 도구: is_enabled = true 기본값, 기존 도구: is_enabled 보존).
     * <p>
     * ON CONFLICT(namespaced_name)로 기존 레코드 감지 후
     * tool_hash, description, input_schema, updated_at 만 갱신하고
     * is_enabled 는 절대 덮어쓰지 않는다.
     * </p>
     */
    @Modifying
    @Query("""
            INSERT INTO mcp_tools
                (id, server_id, server_name, tool_name, namespaced_name, description, input_schema, tool_hash, is_enabled, created_at, updated_at)
            VALUES
                (:id, :serverId, :serverName, :toolName, :namespacedName, :description, :inputSchema, :toolHash, true, NOW(), NOW())
            ON CONFLICT (namespaced_name) DO UPDATE SET
                description  = EXCLUDED.description,
                input_schema = EXCLUDED.input_schema,
                tool_hash    = EXCLUDED.tool_hash,
                updated_at   = NOW()
            """)
    Mono<Void> upsertTool(Long id, Long serverId, String serverName, String toolName,
                           String namespacedName, String description, String inputSchema, String toolHash);

    /**
     * 특정 서버에서 더 이상 제공되지 않는 도구 정리.
     * 현재 업스트림에 존재하는 namespaced_name 목록에 없는 도구를 삭제한다.
     */
    @Modifying
    @Query("DELETE FROM mcp_tools WHERE server_id = :serverId AND namespaced_name NOT IN (:namespacedNames)")
    Mono<Long> deleteObsoleteTools(Long serverId, java.util.Collection<String> namespacedNames);
}
