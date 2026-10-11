package com.mcp.gateway.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.mcp.gateway.domain.McpApiKeyEntity;
import com.mcp.gateway.domain.McpKeyServerPermissionEntity;
import com.mcp.gateway.repository.McpApiKeyRepository;
import com.mcp.gateway.repository.McpKeyServerPermissionRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service managing client API key authentication, SHA-256 fingerprint verification,
 * fine-grained tool authorization policies, and in-memory rate limiting.
 */
@Service
public class ApiKeyPermissionService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyPermissionService.class);

    private final McpApiKeyRepository apiKeyRepository;
    private final McpKeyServerPermissionRepository permissionRepository;
    private final Cache<String, McpApiKeyEntity> apiKeyCache;
    private final ObjectMapper objectMapper;

    // Lightweight In-Memory Token Bucket / Rate Limiter per API Key ID (keyId -> window reset timestamp & count)
    private final ConcurrentHashMap<Long, RateLimitCounter> rateLimitMap = new ConcurrentHashMap<>();

    public ApiKeyPermissionService(McpApiKeyRepository apiKeyRepository,
                                 McpKeyServerPermissionRepository permissionRepository,
                                 Cache<String, McpApiKeyEntity> apiKeyCache,
                                 ObjectMapper objectMapper) {
        this.apiKeyRepository = apiKeyRepository;
        this.permissionRepository = permissionRepository;
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

    /**
     * Verifies if an authenticated API key is authorized to invoke a specific tool on a server.
     *
     * @param apiKey authenticated API key entity
     * @param serverId target server UUID
     * @param toolName target tool name (un-namespaced or namespaced)
     * @return Mono emitting true if access is granted
     */
    public Mono<Boolean> isToolAllowed(McpApiKeyEntity apiKey, Long serverId, String toolName) {
        if (apiKey == null) {
            return Mono.just(false);
        }
        // Master API keys bypass permission checks
        if (Boolean.TRUE.equals(apiKey.getMasterKey())) {
            return Mono.just(true);
        }

        return permissionRepository.findByApiKeyIdAndServerId(apiKey.getId(), serverId)
                .map(permission -> {
                    String json = permission.getAllowedToolsJson();
                    if (json == null || json.isBlank() || json.contains("\"*\"")) {
                        return true;
                    }
                    try {
                        List<String> allowed = objectMapper.readValue(json, new TypeReference<List<String>>() {});
                        return allowed.contains("*") || allowed.contains(toolName);
                    } catch (Exception e) {
                        log.error("Failed to parse allowed tools JSON: {}", json, e);
                        return false;
                    }
                })
                .defaultIfEmpty(false);
    }

    /**
     * Retrieves all server/tool permission mappings for a specified API Key.
     */
    public Flux<McpKeyServerPermissionEntity> getPermissionsForApiKey(Long apiKeyId) {
        return permissionRepository.findByApiKeyId(apiKeyId);
    }

    /**
     * Saves or updates tool permissions for an API Key on a specific server.
     *
     * @param apiKeyId API key ID
     * @param serverId Server ID
     * @param allowedTools List of allowed tool names (or "*" for wildcard)
     * @return Mono emitting saved permission entity
     */
    public Mono<McpKeyServerPermissionEntity> saveOrUpdatePermission(Long apiKeyId, Long serverId, List<String> allowedTools) {
        if (apiKeyId == null || serverId == null) {
            return Mono.error(new IllegalArgumentException("apiKeyId and serverId must not be null"));
        }

        String json;
        try {
            List<String> tools = (allowedTools == null || allowedTools.isEmpty()) ? List.of("*") : allowedTools;
            json = objectMapper.writeValueAsString(tools);
        } catch (Exception e) {
            json = "[\"*\"]";
        }
        final String finalJson = json;

        return permissionRepository.findByApiKeyIdAndServerId(apiKeyId, serverId)
                .flatMap(existing -> {
                    existing.setAllowedToolsJson(finalJson);
                    return permissionRepository.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    McpKeyServerPermissionEntity newEntity = new McpKeyServerPermissionEntity(
                            null, apiKeyId, serverId, finalJson, Instant.now()
                    );
                    newEntity.setNew(true);
                    return permissionRepository.save(newEntity);
                }));
    }

    /**
     * Deletes permission mapping for an API key on a specific server.
     */
    public Mono<Void> deletePermission(Long apiKeyId, Long serverId) {
        return permissionRepository.deleteByApiKeyIdAndServerId(apiKeyId, serverId);
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
