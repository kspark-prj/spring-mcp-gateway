package com.mcp.gateway.repository;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;

import com.mcp.gateway.domain.McpSystemSettingEntity;

import reactor.core.publisher.Mono;

/**
 * Reactive R2DBC repository for {@link McpSystemSettingEntity}.
 */
@Repository
public interface McpSystemSettingRepository extends R2dbcRepository<McpSystemSettingEntity, Long> {

    Mono<McpSystemSettingEntity> findBySettingKey(String settingKey);
}
