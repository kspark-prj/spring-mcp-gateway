package com.mcp.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Aggregated Hybrid MCP Proxy Gateway Application Entry Point.
 * <p>
 * Integrates local STDIO MCP servers and remote HTTP MCP servers under a unified
 * Streamable HTTP protocol endpoint (/mcp). Supports reactive WebFlux execution,
 * R2DBC database integration, Caffeine local caching, and Resilience4j fault tolerance.
 * </p>
 *
 * @author Senior Backend Engineer
 * @version 1.0.0
 */
@SpringBootApplication
public class McpGatewayApplication {

    public static void main(String[] args) {
        // Enforce Java Virtual Threads for concurrency handling
        System.setProperty("spring.threads.virtual.enabled", "true");
        SpringApplication.run(McpGatewayApplication.class, args);
    }
}
