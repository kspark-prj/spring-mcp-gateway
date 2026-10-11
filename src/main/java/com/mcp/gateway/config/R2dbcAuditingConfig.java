package com.mcp.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.mapping.event.BeforeConvertCallback;

import com.mcp.gateway.domain.BaseTsidEntity;

import reactor.core.publisher.Mono;

@Configuration
public class R2dbcAuditingConfig {

    @Bean
    public BeforeConvertCallback<BaseTsidEntity> tsidAuditing() {
        return (entity, table) -> {
            // id가 없으면 자동으로 TSID 롱값 할당
            if (entity.getId() == null) {
                entity.assignNewTsid();
            }
            return Mono.just(entity);
        };
    }
}
