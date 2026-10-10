package com.mcp.gateway.repository;

import com.mcp.gateway.domain.McpKeyServerPermissionEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link McpKeyServerPermissionEntity}.
 */
@Repository
public interface McpKeyServerPermissionRepository extends R2dbcRepository<McpKeyServerPermissionEntity, UUID> {

    Flux<McpKeyServerPermissionEntity> findByApiKeyId(UUID apiKeyId);

    Mono<McpKeyServerPermissionEntity> findByApiKeyIdAndServerId(UUID apiKeyId, UUID serverId);

    Mono<Void> deleteByApiKeyIdAndServerId(UUID apiKeyId, UUID serverId);

    Mono<Void> deleteByApiKeyId(UUID apiKeyId);
}
