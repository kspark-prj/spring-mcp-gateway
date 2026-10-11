package com.mcp.gateway.domain;

import java.time.Instant;

import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Entity mapping API Key to server and allowed tools authorization rules.
 */
@Table("mcp_key_server_permissions")
public class McpKeyServerPermissionEntity extends BaseTsidEntity {


    @Column("api_key_id")
    private Long apiKeyId;

    @Column("server_id")
    private Long serverId;

    @Column("allowed_tools_json")
    private String allowedToolsJson;

    @Column("created_at")
    private Instant createdAt;

    @Transient
    private boolean isNew = false;

    public McpKeyServerPermissionEntity() {
    }

    public McpKeyServerPermissionEntity(Long id, Long apiKeyId, Long serverId, String allowedToolsJson, Instant createdAt) {
    	 setId(id);
        this.apiKeyId = apiKeyId;
        this.serverId = serverId;
        this.allowedToolsJson = allowedToolsJson;
        this.createdAt = createdAt;
    }


    public Long getApiKeyId() {
        return apiKeyId;
    }

    public void setApiKeyId(Long apiKeyId) {
        this.apiKeyId = apiKeyId;
    }

    public Long getServerId() {
        return serverId;
    }

    public void setServerId(Long serverId) {
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
