package com.mcp.gateway.domain;

import java.time.Instant;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * Entity representing a single discovered MCP tool from an upstream server.
 * <p>
 * Persisted in {@code mcp_tools} table to support:
 * <ul>
 *   <li>Hash-based change detection (avoids unnecessary Bulk Upsert when tools haven't changed)</li>
 *   <li>Administrator-controlled {@code is_enabled} toggle (survives sync without reset)</li>
 *   <li>Infinite Caffeine cache backed by DB-persisted data</li>
 * </ul>
 * Uses TSID(Long) primary key via {@link BaseTsidEntity}.
 * </p>
 */
@Table("mcp_tools")
public class McpToolsEntity extends BaseTsidEntity {

	@JsonSerialize(using = ToStringSerializer.class)
    @Column("server_id")
    private Long serverId;

    @Column("server_name")
    private String serverName;

    /** 업스트림 원본 도구 이름 (네임스페이스 미포함) */
    @Column("tool_name")
    private String toolName;

    /** {server_name}__{tool_name} 형태의 전역 고유 도구 식별자 */
    @Column("namespaced_name")
    private String namespacedName;

    @Column("description")
    private String description;

    /** JSON 직렬화된 inputSchema */
    @Column("input_schema")
    private String inputSchema;

    /** SHA-256(tool_name || description || inputSchema) — 변경 감지 해시값 */
    @Column("tool_hash")
    private String toolHash;

    /**
     * 관리자 수동 토글 플래그.
     * 동기화(Bulk Upsert) 시 절대 초기화되지 않으며, 신규 도구 등록 시 기본값 {@code true}.
     */
    @Column("is_enabled")
    private Boolean isEnabled = true;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public McpToolsEntity() {
    }

    // ── Getters & Setters ────────────────────────────────────────────────────

    public Long getServerId() {
        return serverId;
    }

    public void setServerId(Long serverId) {
        this.serverId = serverId;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getNamespacedName() {
        return namespacedName;
    }

    public void setNamespacedName(String namespacedName) {
        this.namespacedName = namespacedName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(String inputSchema) {
        this.inputSchema = inputSchema;
    }

    public String getToolHash() {
        return toolHash;
    }

    public void setToolHash(String toolHash) {
        this.toolHash = toolHash;
    }

    public Boolean getIsEnabled() {
        return isEnabled;
    }

    public void setIsEnabled(Boolean isEnabled) {
        this.isEnabled = isEnabled;
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
