package com.mcp.gateway.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing execution audit logs for gateway traffic monitoring and security analysis.
 */
@Table("mcp_audit_logs")
public class McpAuditLogEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("request_id")
    private String requestId;

    @Column("api_key_id")
    private UUID apiKeyId;

    @Column("server_id")
    private UUID serverId;

    @Column("tool_name")
    private String toolName;

    @Column("http_method")
    private String httpMethod;

    @Column("request_payload")
    private String requestPayload;

    @Column("response_status")
    private Integer responseStatus;

    @Column("execution_time_ms")
    private Long executionTimeMs;

    @Column("pii_masked")
    private Boolean piiMasked;

    @Column("error_message")
    private String errorMessage;

    @Column("created_at")
    private Instant createdAt;

    @Transient
    private boolean isNew = false;

    public McpAuditLogEntity() {
    }

    public McpAuditLogEntity(UUID id, String requestId, UUID apiKeyId, UUID serverId,
                             String toolName, String httpMethod, String requestPayload,
                             Integer responseStatus, Long executionTimeMs, Boolean piiMasked,
                             String errorMessage, Instant createdAt) {
        this.id = id;
        this.requestId = requestId;
        this.apiKeyId = apiKeyId;
        this.serverId = serverId;
        this.toolName = toolName;
        this.httpMethod = httpMethod;
        this.requestPayload = requestPayload;
        this.responseStatus = responseStatus;
        this.executionTimeMs = executionTimeMs;
        this.piiMasked = piiMasked;
        this.errorMessage = errorMessage;
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

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
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

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getRequestPayload() {
        return requestPayload;
    }

    public void setRequestPayload(String requestPayload) {
        this.requestPayload = requestPayload;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }

    public Long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(Long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    public Boolean getPiiMasked() {
        return piiMasked;
    }

    public void setPiiMasked(Boolean piiMasked) {
        this.piiMasked = piiMasked;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
