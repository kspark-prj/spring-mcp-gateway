package com.mcp.gateway.domain;

import java.time.Instant;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Entity representing an administrator user account for web dashboard access.
 * Uses TSID(Long) primary key via {@link BaseTsidEntity}.
 */
@Table("mcp_admin_users")
public class McpAdminUserEntity extends BaseTsidEntity {

    @Column("username")
    private String username;

    @Column("password_hash")
    private String passwordHash;

    @Column("role")
    private String role;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public McpAdminUserEntity() {
    }

    public McpAdminUserEntity(Long id, String username, String passwordHash, String role, Instant createdAt, Instant updatedAt) {
        setId(id);
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
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
