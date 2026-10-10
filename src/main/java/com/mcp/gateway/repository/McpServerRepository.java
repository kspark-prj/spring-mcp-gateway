package com.mcp.gateway.repository;

import com.mcp.gateway.domain.McpServerEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link McpServerEntity}.
 */
@Repository
public interface McpServerRepository extends R2dbcRepository<McpServerEntity, UUID> {

    Mono<McpServerEntity> findByName(String name);

    Flux<McpServerEntity> findByEnabledTrue();
}
