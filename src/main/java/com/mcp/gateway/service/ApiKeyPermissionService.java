package com.mcp.gateway.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.mcp.gateway.domain.McpApiKeyEntity;
import com.mcp.gateway.repository.McpApiKeyRepository;

import reactor.core.publisher.Mono;

/**
 * Service managing client API key authentication, SHA-256 fingerprint verification,
 * fine-grained tool authorization policies, and in-memory rate limiting.
 */
@Service
public class ApiKeyPermissionService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyPermissionService.class);

    private final McpApiKeyRepository apiKeyRepository;
    private final Cache<String, McpApiKeyEntity> apiKeyCache;
    private final ObjectMapper objectMapper;

    // Lightweight In-Memory Token Bucket / Rate Limiter per API Key ID (keyId -> window reset timestamp & count)
    private final ConcurrentHashMap<Long, RateLimitCounter> rateLimitMap = new ConcurrentHashMap<>();

    public ApiKeyPermissionService(McpApiKeyRepository apiKeyRepository,
                                 Cache<String, McpApiKeyEntity> apiKeyCache,
                                 ObjectMapper objectMapper) {
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyCache = apiKeyCache;
        this.objectMapper = objectMapper;
    }

    /**
     * Computes hex-encoded SHA-256 hash fingerprint of raw API Key string.
     *
     * @param rawKey raw API Key value from request header
     * @return SHA-256 hex string
     */
    public String hashApiKey(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawKey.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    /**
     * Authenticates raw API Key via Caffeine cache or database lookup.
     *
     * @param rawApiKey raw X-API-KEY header value
     * @return Mono emitting validated {@link McpApiKeyEntity}, or empty if invalid/disabled/expired
     */
    public Mono<McpApiKeyEntity> authenticateApiKey(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            return Mono.empty();
        }

        String hash = hashApiKey(rawApiKey);
        McpApiKeyEntity cached = apiKeyCache.getIfPresent(hash);
        if (cached != null) {
            if (isKeyValid(cached)) {
                return Mono.just(cached);
            } else {
                apiKeyCache.invalidate(hash);
                return Mono.empty();
            }
        }

        return apiKeyRepository.findByKeyHashSha256(hash)
                .filter(this::isKeyValid)
                .doOnSuccess(entity -> {
                    if (entity != null) {
                        apiKeyCache.put(hash, entity);
                    }
                });
    }

    /**
     * Verifies whether key is enabled and non-expired.
     */
    private boolean isKeyValid(McpApiKeyEntity key) {
        if (key == null || Boolean.FALSE.equals(key.getEnabled())) {
            return false;
        }
        if (key.getExpiresAt() != null && Instant.now().isAfter(key.getExpiresAt())) {
            return false;
        }
        return true;
    }

    /**
     * Checks if API key passes in-memory rate limit per minute.
     *
     * @param apiKey API key entity
     * @return true if request is within rate limit
     */
    public boolean checkRateLimit(McpApiKeyEntity apiKey) {
        if (apiKey == null) return false;
        // 마스터키인 경우 Rate Limit 제한 해제
        if (Boolean.TRUE.equals(apiKey.getMasterKey())) {
            return true;
        }

        int limit = apiKey.getRateLimitPerMinute() != null ? apiKey.getRateLimitPerMinute() : 60;
        long currentMinute = System.currentTimeMillis() / 60000;

        RateLimitCounter counter = rateLimitMap.compute(apiKey.getId(), (id, existing) -> {
            if (existing == null || existing.minuteWindow != currentMinute) {
                return new RateLimitCounter(currentMinute, new AtomicInteger(1));
            } else {
                existing.count.incrementAndGet();
                return existing;
            }
        });

        return counter.count.get() <= limit;
    }





    private static class RateLimitCounter {
        final long minuteWindow;
        final AtomicInteger count;

        RateLimitCounter(long minuteWindow, AtomicInteger count) {
            this.minuteWindow = minuteWindow;
            this.count = count;
        }
    }
}
