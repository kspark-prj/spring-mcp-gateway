package com.mcp.gateway.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import com.mcp.gateway.config.TsidConfig;


/**
 * Abstract base entity providing TSID(Long) primary key generation and {@link Persistable} support
 * for Spring Data R2DBC manual ID assignment (INSERT vs UPDATE differentiation).
 * <p>
 * All concrete entities extend this class and inherit a cluster-safe, time-sorted Long PK.
 * The TSID is generated via {@link TsidGenerator} which binds to the application.yml node-id setting.
 * </p>
 */
public abstract class BaseTsidEntity implements Persistable<Long> {

    @Id
    private Long id;

    @Transient
    private boolean isNew = false;

    protected BaseTsidEntity() {
    }

    @Override
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @Override
    public boolean isNew() {
        return isNew || id == null;
    }

    public void setNew(boolean isNew) {
        this.isNew = isNew;
    }

    /**
     * Assigns a new TSID and marks this entity as new for INSERT.
     */
    public void assignNewTsid() {
        if (this.id == null) {
            this.id = TsidConfig.nextLong();
        }
        this.isNew = true;
    }

}
