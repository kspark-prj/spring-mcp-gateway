package com.mcp.gateway.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.mcp.gateway.domain.McpSystemSettingEntity;
import com.mcp.gateway.repository.McpSystemSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Service for managing dynamic global system settings and PII masking toggles.
 * Integrates short-TTL Caffeine caching for sub-millisecond retrieval performance.
 */
@Service
public class SystemSettingsService {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingsService.class);

    public static final String PII_MASKING_KEY = "pii_masking_enabled";

    private final McpSystemSettingRepository systemSettingRepository;
    private final Cache<String, String> systemSettingsCache;

    public SystemSettingsService(McpSystemSettingRepository systemSettingRepository,
                                 Cache<String, String> systemSettingsCache) {
        this.systemSettingRepository = systemSettingRepository;
        this.systemSettingsCache = systemSettingsCache;
    }

    /**
     * Retrieves setting value by key, attempting Caffeine cache lookup before database fallback.
     *
     * @param key setting key identifier
     * @param defaultValue default value fallback
     * @return Mono emitting the setting string value
     */
    public Mono<String> getSetting(String key, String defaultValue) {
        String cachedValue = systemSettingsCache.getIfPresent(key);
        if (cachedValue != null) {
            return Mono.just(cachedValue);
        }

        return systemSettingRepository.findBySettingKey(key)
                .map(entity -> {
                    systemSettingsCache.put(key, entity.getSettingValue());
                    return entity.getSettingValue();
                })
                .defaultIfEmpty(defaultValue);
    }

    /**
     * Checks if general PII masking is dynamically enabled.
     *
     * @return Mono emitting true if PII masking toggle is active
     */
    public Mono<Boolean> isPiiMaskingEnabled() {
        return getSetting(PII_MASKING_KEY, "true")
                .map(val -> Boolean.parseBoolean(val.trim()));
    }

    /**
     * Updates or creates a system setting and invalidates the Caffeine cache entry.
     *
     * @param key setting key identifier
     * @param value setting string value
     * @param description description summary
     * @return Mono emitting updated entity
     */
    public Mono<McpSystemSettingEntity> updateSetting(String key, String value, String description) {
        return systemSettingRepository.findBySettingKey(key)
                .flatMap(entity -> {
                    entity.setSettingValue(value);
                    if (description != null) {
                        entity.setDescription(description);
                    }
                    entity.setUpdatedAt(Instant.now());
                    return systemSettingRepository.save(entity);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    McpSystemSettingEntity newEntity = new McpSystemSettingEntity();
                    newEntity.setSettingKey(key);
                    newEntity.setSettingValue(value);
                    newEntity.setDescription(description);
                    newEntity.setUpdatedAt(Instant.now());
                    newEntity.setNew(true);
                    return systemSettingRepository.save(newEntity);
                }))
                .doOnSuccess(saved -> {
                    systemSettingsCache.put(key, value);
                    log.info("System setting updated: [{}] = [{}]", key, value);
                });
    }
}
