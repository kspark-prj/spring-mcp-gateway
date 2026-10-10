package com.mcp.gateway.repository;

import com.mcp.gateway.domain.McpSystemSettingEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link McpSystemSettingEntity}.
 */
@Repository
public interface McpSystemSettingRepository extends R2dbcRepository<McpSystemSettingEntity, UUID> {

    Mono<McpSystemSettingEntity> findBySettingKey(String settingKey);
}
