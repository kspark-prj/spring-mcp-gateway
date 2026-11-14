package com.mcp.gateway.repository;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;

import com.mcp.gateway.domain.McpKeyServerPermissionEntity;

import reactor.core.publisher.Mono;

/**
 * Reactive R2DBC repository for {@link McpKeyServerPermissionEntity}.
 */
@Repository
public interface McpKeyServerPermissionRepository extends R2dbcRepository<McpKeyServerPermissionEntity, Long> {

    Mono<McpKeyServerPermissionEntity> findByApiKeyIdAndServerId(Long apiKeyId, Long serverId);
}
