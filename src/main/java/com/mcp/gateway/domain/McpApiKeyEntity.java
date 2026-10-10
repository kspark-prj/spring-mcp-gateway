package com.mcp.gateway.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing an API key credential used by clients.
 */
@Table("mcp_api_keys")
public class McpApiKeyEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("key_name")
    private String keyName;

    @Column("key_hash_sha256")
    private String keyHashSha256;

    @Column("master_key")
    private Boolean masterKey;

    @Column("enabled")
    private Boolean enabled;

    @Column("rate_limit_per_minute")
    private Integer rateLimitPerMinute;

    @Column("expires_at")
    private Instant expiresAt;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Transient
    private boolean isNew = false;

    public McpApiKeyEntity() {
    }

    public McpApiKeyEntity(UUID id, String keyName, String keyHashSha256, Boolean masterKey,
                           Boolean enabled, Integer rateLimitPerMinute, Instant expiresAt,
                           Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.keyName = keyName;
        this.keyHashSha256 = keyHashSha256;
        this.masterKey = masterKey;
        this.enabled = enabled;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    @Override
    public boolean isNew() {
        return isNew || id == null;
    }

    public void setNew(boolean isNew) {
        this.isNew = isNew;
    }

    public String getKeyName() {
        return keyName;
    }

    public void setKeyName(String keyName) {
        this.keyName = keyName;
    }

    public String getKeyHashSha256() {
        return keyHashSha256;
    }

    public void setKeyHashSha256(String keyHashSha256) {
        this.keyHashSha256 = keyHashSha256;
    }

    public Boolean getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(Boolean masterKey) {
        this.masterKey = masterKey;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(Integer rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
