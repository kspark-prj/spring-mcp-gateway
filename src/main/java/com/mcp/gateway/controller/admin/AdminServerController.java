package com.mcp.gateway.controller.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mcp.gateway.domain.McpServerEntity;
import com.mcp.gateway.repository.McpServerRepository;
import com.mcp.gateway.repository.McpServerToolHashRepository;
import com.mcp.gateway.repository.McpToolsRepository;
import com.mcp.gateway.security.SsrfProtectionValidator;
import com.mcp.gateway.service.DynamicCircuitBreakerManager;
import com.mcp.gateway.service.ToolRegistryService;
import com.mcp.gateway.service.ToolSyncService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Controller for Administrator management of upstream MCP servers (HTTP / STDIO).
 */
@RestController
@RequestMapping("/api/admin/servers")
public class AdminServerController {

    private final McpServerRepository serverRepository;
    private final SsrfProtectionValidator ssrfValidator;
    private final DynamicCircuitBreakerManager circuitBreakerManager;
    private final ToolRegistryService toolRegistryService;
    private final McpToolsRepository toolsRepository;
    private final McpServerToolHashRepository hashRepository;
    private final ToolSyncService toolSyncService;

    public AdminServerController(McpServerRepository serverRepository,
                                 SsrfProtectionValidator ssrfValidator,
                                 DynamicCircuitBreakerManager circuitBreakerManager,
                                 ToolRegistryService toolRegistryService,
                                 McpToolsRepository toolsRepository,
                                 McpServerToolHashRepository hashRepository,
                                 ToolSyncService toolSyncService) {
        this.serverRepository = serverRepository;
        this.ssrfValidator = ssrfValidator;
        this.circuitBreakerManager = circuitBreakerManager;
        this.toolRegistryService = toolRegistryService;
        this.toolsRepository = toolsRepository;
        this.hashRepository = hashRepository;
        this.toolSyncService = toolSyncService;
    }

    @GetMapping
    public Flux<McpServerEntity> getAllServers() {
        return serverRepository.findAll();
    }

    @GetMapping("/tools")
    public Mono<List<Map<String, Object>>> getDiscoveredTools() {
        return toolRegistryService.getUnifiedToolCatalog();
    }

    @PostMapping
    public Mono<ResponseEntity<Object>> createServer(@RequestBody McpServerEntity server) {
        if (server.getName() == null || server.getName().isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body("Server name is required"));
        }
        if ("HTTP".equalsIgnoreCase(server.getTransportType())) {
            try {
                ssrfValidator.validateUrl(server.getEndpointUrl());
            } catch (IllegalArgumentException e) {
                return Mono.just(ResponseEntity.badRequest().body(e.getMessage()));
            }
        }

        if (server.getFailureRateThreshold() == null) server.setFailureRateThreshold(50.0f);
        if (server.getSlidingWindowSize() == null) server.setSlidingWindowSize(10);
        if (server.getMinimumNumberOfCalls() == null) server.setMinimumNumberOfCalls(5);
        if (server.getWaitDurationInOpenState() == null) server.setWaitDurationInOpenState(10);

        server.setCreatedAt(Instant.now());
        server.setUpdatedAt(Instant.now());
        server.setHealthStatus("HEALTHY");
        server.setRetryCount(0);
        server.setNew(true);

        return serverRepository.save(server)
                .map(saved -> {
                    circuitBreakerManager.refreshCache(saved);
                    return ResponseEntity.status(HttpStatus.CREATED).body((Object) saved);
                });
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<Object>> updateServer(@PathVariable long id, @RequestBody McpServerEntity update) {
        return serverRepository.findById(id)
                .flatMap(existing -> {
                    if ("HTTP".equalsIgnoreCase(update.getTransportType())) {
                        try {
                            ssrfValidator.validateUrl(update.getEndpointUrl());
                        } catch (IllegalArgumentException e) {
                            return Mono.just(ResponseEntity.badRequest().body((Object) e.getMessage()));
                        }
                    }

                    String oldName = existing.getName();
                    boolean nameChanged = update.getName() != null
                            && !update.getName().isBlank()
                            && !oldName.equals(update.getName());

                    existing.setName(update.getName());
                    existing.setTransportType(update.getTransportType());
                    existing.setEndpointUrl(update.getEndpointUrl());
                    existing.setCommandLine(update.getCommandLine());
                    existing.setEnvironmentVariables(update.getEnvironmentVariables());
                    existing.setEnabled(update.getEnabled() != null ? update.getEnabled() : existing.getEnabled());

                    if (update.getFailureRateThreshold() != null) existing.setFailureRateThreshold(update.getFailureRateThreshold());
                    if (update.getSlidingWindowSize() != null) existing.setSlidingWindowSize(update.getSlidingWindowSize());
                    if (update.getMinimumNumberOfCalls() != null) existing.setMinimumNumberOfCalls(update.getMinimumNumberOfCalls());
                    if (update.getWaitDurationInOpenState() != null) existing.setWaitDurationInOpenState(update.getWaitDurationInOpenState());

                    existing.setUpdatedAt(Instant.now());
                    return serverRepository.save(existing)
                            .flatMap(saved -> {
                                circuitBreakerManager.refreshCache(saved);
                                if (nameChanged) {
                                    circuitBreakerManager.evictCache(oldName);
                                    return toolsRepository.updateServerNameAndNamespace(saved.getId(), saved.getName())
                                            .then(hashRepository.deleteByServerId(saved.getId()))
                                            .then(toolSyncService.rebuildCacheNow())
                                            .thenReturn(ResponseEntity.ok((Object) saved));
                                }
                                return Mono.just(ResponseEntity.ok((Object) saved));
                            });
                })
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteServer(@PathVariable long id) {
        return serverRepository.findById(id)
                .flatMap(server -> {
                    String serverName = server.getName();
                    return serverRepository.deleteById(id)
                            .then(Mono.fromRunnable(() -> circuitBreakerManager.evictCache(serverName)))
                            .then(toolsRepository.deleteByServerId(id))
                            .then(hashRepository.deleteByServerId(id))
                            .then(toolSyncService.rebuildCacheNow())
                            .then(Mono.just(ResponseEntity.noContent().<Void>build()));
                })
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
