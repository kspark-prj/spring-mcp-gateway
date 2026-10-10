package com.mcp.gateway.repository;

import com.mcp.gateway.domain.McpApiKeyEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link McpApiKeyEntity}.
 */
@Repository
public interface McpApiKeyRepository extends R2dbcRepository<McpApiKeyEntity, UUID> {

    Mono<McpApiKeyEntity> findByKeyHashSha256(String keyHashSha256);
}
