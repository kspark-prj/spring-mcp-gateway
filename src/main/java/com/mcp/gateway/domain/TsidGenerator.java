package com.mcp.gateway.domain;

import com.github.f4b6a3.tsid.TsidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Centralized TSID generator that binds to the application.yml {@code mcp.gateway.tsid.node-id} setting.
 * <p>
 * Ensures that each server instance in a cluster produces unique, time-sorted Long IDs
 * by using different node-id values per instance. The node-id is injected via environment variable
 * {@code TSID_NODE_ID} (default: 0).
 * </p>
 */
@Component
public class TsidGenerator {

    private static final Logger log = LoggerFactory.getLogger(TsidGenerator.class);

    private static TsidFactory factory;

    @Value("${mcp.gateway.tsid.node-id:0}")
    private int nodeId;

    @PostConstruct
    public void init() {
        factory = TsidFactory.builder()
                .withNode(nodeId)
                .build();
        log.info("TSID Generator initialized with node-id: {}", nodeId);
    }

    /**
     * Generates a new unique TSID as a Long value.
     *
     * @return unique Long ID
     */
    public static long nextLong() {
        if (factory == null) {
            // Fallback for cases where Spring context hasn't initialized yet (e.g., unit tests)
            factory = TsidFactory.builder().withNode(0).build();
        }
        return factory.create().toLong();
    }
}
