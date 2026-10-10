package com.mcp.gateway.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing an aggregated MCP upstream server (HTTP or STDIO).
 * Implements {@link Persistable} to ensure accurate R2DBC INSERT/UPDATE differentiation.
 */
@Table("mcp_servers")
public class McpServerEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("name")
    private String name;

    @Column("transport_type")
    private String transportType; // 'HTTP' or 'STDIO'

    @Column("endpoint_url")
    private String endpointUrl;

    @Column("command_line")
    private String commandLine;

    @Column("environment_variables")
    private String environmentVariables;

    @Column("health_status")
    private String healthStatus; // 'HEALTHY', 'UNHEALTHY', 'DOWN'

    @Column("retry_count")
    private Integer retryCount;

    @Column("enabled")
    private Boolean enabled;

    @Column("failure_rate_threshold")
    private Float failureRateThreshold = 50.0f;

    @Column("sliding_window_size")
    private Integer slidingWindowSize = 10;

    @Column("minimum_number_of_calls")
    private Integer minimumNumberOfCalls = 5;

    @Column("wait_duration_in_open_state")
    private Integer waitDurationInOpenState = 10;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Transient
    private boolean isNew = false;

    public McpServerEntity() {
    }

    public McpServerEntity(UUID id, String name, String transportType, String endpointUrl,
                           String commandLine, String environmentVariables, String healthStatus,
                           Integer retryCount, Boolean enabled, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.transportType = transportType;
        this.endpointUrl = endpointUrl;
        this.commandLine = commandLine;
        this.environmentVariables = environmentVariables;
        this.healthStatus = healthStatus;
        this.retryCount = retryCount;
        this.enabled = enabled;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTransportType() {
        return transportType;
    }

    public void setTransportType(String transportType) {
        this.transportType = transportType;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public String getCommandLine() {
        return commandLine;
    }

    public void setCommandLine(String commandLine) {
        this.commandLine = commandLine;
    }

    public String getEnvironmentVariables() {
        return environmentVariables;
    }

    public void setEnvironmentVariables(String environmentVariables) {
        this.environmentVariables = environmentVariables;
    }

    public String getHealthStatus() {
        return healthStatus;
    }

    public void setHealthStatus(String healthStatus) {
        this.healthStatus = healthStatus;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Float getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public void setFailureRateThreshold(Float failureRateThreshold) {
        this.failureRateThreshold = failureRateThreshold;
    }

    public Integer getSlidingWindowSize() {
        return slidingWindowSize;
    }

    public void setSlidingWindowSize(Integer slidingWindowSize) {
        this.slidingWindowSize = slidingWindowSize;
    }

    public Integer getMinimumNumberOfCalls() {
        return minimumNumberOfCalls;
    }

    public void setMinimumNumberOfCalls(Integer minimumNumberOfCalls) {
        this.minimumNumberOfCalls = minimumNumberOfCalls;
    }

    public Integer getWaitDurationInOpenState() {
        return waitDurationInOpenState;
    }

    public void setWaitDurationInOpenState(Integer waitDurationInOpenState) {
        this.waitDurationInOpenState = waitDurationInOpenState;
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
