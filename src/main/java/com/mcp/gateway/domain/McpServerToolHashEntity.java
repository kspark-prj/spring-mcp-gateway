package com.mcp.gateway.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * Entity tracking the SHA-256 hash of the complete tool list per upstream server.
 * <p>
 * Used for hash-based change detection to skip Bulk Upsert when tool lists are unchanged,
 * eliminating unnecessary DB I/O and lock contention on every sync trigger.
 * </p>
 */
@Table("mcp_server_tool_hash")
public class McpServerToolHashEntity {

    /** server_id is both PK and FK (no TSID auto-generation — reuses mcp_servers.id) */
    @Id
    @Column("server_id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long serverId;

    /** SHA-256 of the full serialized tool list for this server */
    @Column("list_hash")
    private String listHash;

    @Column("tool_count")
    private Integer toolCount;

    @Column("synced_at")
    private Instant syncedAt;

    public McpServerToolHashEntity() {
    }

    public McpServerToolHashEntity(Long serverId, String listHash, Integer toolCount, Instant syncedAt) {
        this.serverId = serverId;
        this.listHash = listHash;
        this.toolCount = toolCount;
        this.syncedAt = syncedAt;
    }

    // ── Getters & Setters ────────────────────────────────────────────────────

    public Long getServerId() {
        return serverId;
    }

    public void setServerId(Long serverId) {
        this.serverId = serverId;
    }

    public String getListHash() {
        return listHash;
    }

    public void setListHash(String listHash) {
        this.listHash = listHash;
    }

    public Integer getToolCount() {
        return toolCount;
    }

    public void setToolCount(Integer toolCount) {
        this.toolCount = toolCount;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }

    public void setSyncedAt(Instant syncedAt) {
        this.syncedAt = syncedAt;
    }
}
