package com.mcp.gateway.repository;

import com.mcp.gateway.domain.McpAdminUserEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link McpAdminUserEntity}.
 */
@Repository
public interface McpAdminUserRepository extends R2dbcRepository<McpAdminUserEntity, UUID> {

    Mono<McpAdminUserEntity> findByUsername(String username);
}
