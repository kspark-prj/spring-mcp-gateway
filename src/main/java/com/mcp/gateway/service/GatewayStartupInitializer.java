package com.mcp.gateway.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Application startup initializer that triggers the initial tool synchronization
 * across all enabled upstream MCP servers.
 *
 * <p>
 * Executes after the Spring context is fully initialized (after Flyway migration),
 * fetching tool lists from all active servers, computing SHA-256 hashes,
 * performing Bulk Upsert where changes are detected, and loading the Caffeine cache.
 * </p>
 *
 * <p>
 * This fulfills <b>Rule 1 — Trigger Point 1</b>: 게이트웨이 기동 시 초기 도구 목록 동기화.
 * </p>
 */
@Component
public class GatewayStartupInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GatewayStartupInitializer.class);

    private final ToolSyncService toolSyncService;
    private final ToolRegistryService toolRegistryService;

    public GatewayStartupInitializer(ToolSyncService toolSyncService,
                                     ToolRegistryService toolRegistryService) {
        this.toolSyncService = toolSyncService;
        this.toolRegistryService = toolRegistryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("🚀 [Startup] Gateway initializing — triggering upstream tool sync...");
        toolSyncService.syncAllServers()
                .doOnNext(count -> log.info("🚀 [Startup] Tool sync complete: {} total tools synced across all servers.", count))
                .then(toolSyncService.rebuildCacheNow())
                .doOnNext(tools -> log.info("🚀 [Startup] Caffeine cache warmed up: {} enabled tools cached.", tools.size()))
                .subscribe(
                        tools -> log.info("✅ [Startup] Initialization complete."),
                        ex -> log.error("❌ [Startup] Initialization failed during tool sync: {}", ex.getMessage(), ex)
                );
    }
}
