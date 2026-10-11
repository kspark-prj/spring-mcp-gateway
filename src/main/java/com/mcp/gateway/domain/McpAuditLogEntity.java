package com.mcp.gateway.domain;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Entity representing execution audit logs for gateway traffic monitoring and security analysis.
 * Uses TSID(Long) primary key via {@link BaseTsidEntity}. FK references also use Long.
 */
@Table("mcp_audit_logs")
public class McpAuditLogEntity extends BaseTsidEntity {

    @Column("request_id")
    private String requestId;

    @Column("api_key_id")
    private Long apiKeyId;

    @Column("server_id")
    private Long serverId;

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

    public McpAuditLogEntity() {
    }

    public McpAuditLogEntity(Long id, String requestId, Long apiKeyId, Long serverId,
                             String toolName, String httpMethod, String requestPayload,
                             Integer responseStatus, Long executionTimeMs, Boolean piiMasked,
                             String errorMessage, Instant createdAt) {
        setId(id);
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

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
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
