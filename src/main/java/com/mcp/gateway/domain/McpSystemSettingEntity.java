package com.mcp.gateway.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing global system settings and dynamic toggles (e.g. PII masking).
 */
@Table("mcp_system_settings")
public class McpSystemSettingEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("setting_key")
    private String settingKey;

    @Column("setting_value")
    private String settingValue;

    @Column("description")
    private String description;

    @Column("updated_at")
    private Instant updatedAt;

    @Transient
    private boolean isNew = false;

    public McpSystemSettingEntity() {
    }

    public McpSystemSettingEntity(UUID id, String settingKey, String settingValue, String description, Instant updatedAt) {
        this.id = id;
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.description = description;
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

    public String getSettingKey() {
        return settingKey;
    }

    public void setSettingKey(String settingKey) {
        this.settingKey = settingKey;
    }

    public String getSettingValue() {
        return settingValue;
    }

    public void setSettingValue(String settingValue) {
        this.settingValue = settingValue;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
