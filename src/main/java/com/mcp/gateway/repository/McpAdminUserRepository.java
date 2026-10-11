package com.mcp.gateway.repository;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;

import com.mcp.gateway.domain.McpAdminUserEntity;

import reactor.core.publisher.Mono;

/**
 * Reactive R2DBC repository for {@link McpAdminUserEntity}.
 */
@Repository
public interface McpAdminUserRepository extends R2dbcRepository<McpAdminUserEntity, Long> {

    Mono<McpAdminUserEntity> findByUsername(String username);
}
