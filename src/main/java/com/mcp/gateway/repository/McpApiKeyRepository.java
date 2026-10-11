package com.mcp.gateway.repository;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;

import com.mcp.gateway.domain.McpApiKeyEntity;

import reactor.core.publisher.Mono;

/**
 * Reactive R2DBC repository for {@link McpApiKeyEntity}.
 */
@Repository
public interface McpApiKeyRepository extends R2dbcRepository<McpApiKeyEntity, Long > {

    Mono<McpApiKeyEntity> findByKeyHashSha256(String keyHashSha256);
}
