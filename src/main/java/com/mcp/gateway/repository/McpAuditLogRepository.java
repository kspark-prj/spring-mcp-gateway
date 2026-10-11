package com.mcp.gateway.repository;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;

import com.mcp.gateway.domain.McpAuditLogEntity;

import reactor.core.publisher.Flux;

/**
 * Reactive R2DBC repository for {@link McpAuditLogEntity}.
 */
@Repository
public interface McpAuditLogRepository extends R2dbcRepository<McpAuditLogEntity, Long> {

    Flux<McpAuditLogEntity> findTop10ByOrderByCreatedAtDesc();
}
