package com.mcp.gateway.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity mapping API Key to server and allowed tools authorization rules.
 */
@Table("mcp_key_server_permissions")
public class McpKeyServerPermissionEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("api_key_id")
    private UUID apiKeyId;

    @Column("server_id")
    private UUID serverId;

    @Column("allowed_tools_json")
    private String allowedToolsJson;

    @Column("created_at")
    private Instant createdAt;

    @Transient
    private boolean isNew = false;

    public McpKeyServerPermissionEntity() {
    }

    public McpKeyServerPermissionEntity(UUID id, UUID apiKeyId, UUID serverId, String allowedToolsJson, Instant createdAt) {
        this.id = id;
        this.apiKeyId = apiKeyId;
        this.serverId = serverId;
        this.allowedToolsJson = allowedToolsJson;
        this.createdAt = createdAt;
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

    public UUID getApiKeyId() {
        return apiKeyId;
    }

    public void setApiKeyId(UUID apiKeyId) {
        this.apiKeyId = apiKeyId;
    }

    public UUID getServerId() {
        return serverId;
    }

    public void setServerId(UUID serverId) {
        this.serverId = serverId;
    }

    public String getAllowedToolsJson() {
        return allowedToolsJson;
    }

    public void setAllowedToolsJson(String allowedToolsJson) {
        this.allowedToolsJson = allowedToolsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
