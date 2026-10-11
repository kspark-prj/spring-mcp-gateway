package com.mcp.gateway.domain;

import java.time.Instant;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Entity representing global system settings and dynamic toggles (e.g. PII masking).
 */
@Table("mcp_system_settings")
public class McpSystemSettingEntity extends BaseTsidEntity {


    @Column("setting_key")
    private String settingKey;

    @Column("setting_value")
    private String settingValue;

    @Column("description")
    private String description;

    @Column("updated_at")
    private Instant updatedAt;

    public McpSystemSettingEntity() {
    }

    public McpSystemSettingEntity(Long id, String settingKey, String settingValue, String description, Instant updatedAt) {
    	setId(id);
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.description = description;
        this.updatedAt = updatedAt;
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
