package com.mcp.gateway.config;

import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mcp.gateway.domain.McpApiKeyEntity;
import com.mcp.gateway.domain.McpToolsEntity;

/**
 * In-Memory Caffeine Cache Configuration.
 *
 * <h2>toolRegistryCache — 무기한(Unbounded TTL) 전략</h2>
 * <p>
 * 도구 레지스트리 캐시는 시간 기반 만료(TTL)를 적용하지 않는다.
 * 갱신은 다음 두 시점에만 명시적 evict + rebuild로 처리된다:
 * <ol>
 *   <li>게이트웨이 기동 시: {@code GatewayStartupInitializer}</li>
 *   <li>대시보드 수동 새로고침 또는 is_enabled 토글: {@code AdminToolController}</li>
 * </ol>
 * 런타임 요청은 무거운 DB 조회 없이 캐시에서만 처리하여 0ms에 가까운 응답을 보장한다.
 * </p>
 *
 * <h2>캐시 타입 변경 이력</h2>
 * <p>
 * {@code Cache<String, List<Map<String, Object>>>} → {@code Cache<String, List<McpToolsEntity>>}
 * <br>
 * is_enabled 토글 정보를 캐시 항목에 포함하기 위해 엔티티 타입으로 변경.
 * </p>
 */
@Configuration
public class CaffeineCacheConfig {

    /**
     * Cache for validated API Key metadata by SHA-256 hash fingerprint.
     */
    @Bean
    public Cache<String, McpApiKeyEntity> apiKeyCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .maximumSize(2000)
                .recordStats()
                .build();
    }

    /**
     * Cache for merged tool catalog (is_enabled=true 도구만 적재).
     *
     * <p>
     * <b>무기한 캐시 정책</b>: TTL 없음. 명시적 evict(Sync/Toggle 시점)에 의해서만 갱신.
     * maximumSize를 설정하여 힙 메모리 압박을 방지한다.
     * </p>
     */
    @Bean
    public Cache<String, List<McpToolsEntity>> toolRegistryCache() {
        return Caffeine.newBuilder()
                .maximumSize(1)   // CATALOG_CACHE_KEY 단일 키 전략 — 키 1개에 도구 목록 전체 적재
                .recordStats()
                .build();
    }

    /**
     * Cache for global system configuration settings (e.g. PII masking toggle).
     */
    @Bean
    public Cache<String, String> systemSettingsCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .maximumSize(2000)
                .recordStats()
                .build();
    }

    /**
     * Cache for upstream MCP server entities and circuit breaker settings by server name.
     */
    @Bean
    public Cache<String, com.mcp.gateway.domain.McpServerEntity> serverConfigCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .maximumSize(2000)
                .recordStats()
                .build();
    }
}
