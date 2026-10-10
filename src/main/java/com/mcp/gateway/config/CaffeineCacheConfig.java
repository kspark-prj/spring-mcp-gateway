package com.mcp.gateway.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mcp.gateway.domain.McpApiKeyEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * In-Memory Caffeine Cache Configuration.
 * <p>
 * Implements short TTL (60s) and bounded size limit (maximumSize(2000)) to prevent heap memory
 * pressure and guarantee continuous self-healing operational state without external Redis dependency.
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
     * Cache for merged tool catalog lists.
     */
    @Bean
    public Cache<String, List<Map<String, Object>>> toolRegistryCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(2000)
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
