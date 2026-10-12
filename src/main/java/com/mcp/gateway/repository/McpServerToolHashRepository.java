package com.mcp.gateway.repository;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import com.mcp.gateway.domain.McpServerToolHashEntity;

import reactor.core.publisher.Mono;

/**
 * R2DBC reactive repository for {@link McpServerToolHashEntity}.
 * <p>
 * Provides hash-based change detection at the server level.
 * When the computed hash of the current tool list matches the stored hash,
 * the entire Bulk Upsert pipeline is skipped to eliminate unnecessary DB I/O.
 * </p>
 */
@Repository
public interface McpServerToolHashRepository extends ReactiveCrudRepository<McpServerToolHashEntity, Long> {

    /** 서버 ID 기준 해시 레코드 조회 */
    Mono<McpServerToolHashEntity> findByServerId(Long serverId);

    /**
     * 서버별 도구 목록 해시 Upsert.
     * <p>
     * 서버 최초 동기화 시 INSERT, 이후 동기화 시 list_hash / tool_count / synced_at 갱신.
     * </p>
     */
    @Modifying
    @Query("""
            INSERT INTO mcp_server_tool_hash (server_id, list_hash, tool_count, synced_at)
            VALUES (:serverId, :listHash, :toolCount, NOW())
            ON CONFLICT (server_id) DO UPDATE SET
                list_hash  = EXCLUDED.list_hash,
                tool_count = EXCLUDED.tool_count,
                synced_at  = NOW()
            """)
    Mono<Void> upsertHash(Long serverId, String listHash, Integer toolCount);

    /** 서버 ID 기준 해시 레코드 삭제 (서버 설정 변경 시 다음 동기화 강제 재실행용) */
    @Modifying
    @Query("DELETE FROM mcp_server_tool_hash WHERE server_id = :serverId")
    Mono<Void> deleteByServerId(Long serverId);
}
