package com.mcp.gateway.controller.admin;

import com.mcp.gateway.domain.McpApiKeyEntity;
import com.mcp.gateway.repository.McpApiKeyRepository;
import com.mcp.gateway.service.ApiKeyPermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Controller for Administrator management of Client API Keys and access permissions.
 */
@RestController
@RequestMapping("/api/admin/api-keys")
public class AdminApiKeyController {

    private final McpApiKeyRepository apiKeyRepository;
    private final ApiKeyPermissionService apiKeyPermissionService;

    public AdminApiKeyController(McpApiKeyRepository apiKeyRepository, ApiKeyPermissionService apiKeyPermissionService) {
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyPermissionService = apiKeyPermissionService;
    }

    @GetMapping
    public Flux<McpApiKeyEntity> getAllApiKeys() {
        return apiKeyRepository.findAll();
    }

    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> createApiKey(@RequestBody Map<String, Object> req) {
        String keyName = (String) req.getOrDefault("keyName", "New API Key");
        Boolean isMaster = (Boolean) req.getOrDefault("masterKey", false);
        Integer rateLimit = (Integer) req.getOrDefault("rateLimitPerMinute", 60);

        String rawKey = "mcp-key-" + UUID.randomUUID().toString().replace("-", "");
        String keyHash = apiKeyPermissionService.hashApiKey(rawKey);

        McpApiKeyEntity entity = new McpApiKeyEntity();
        entity.setKeyName(keyName);
        entity.setKeyHashSha256(keyHash);
        entity.setMasterKey(isMaster);
        entity.setEnabled(true);
        entity.setRateLimitPerMinute(rateLimit);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setNew(true);

        return apiKeyRepository.save(entity)
                .map(saved -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                        "id", saved.getId(),
                        "keyName", saved.getKeyName(),
                        "rawApiKey", rawKey, // Raw key returned ONCE upon creation
                        "keyHashSha256", saved.getKeyHashSha256(),
                        "masterKey", saved.getMasterKey(),
                        "rateLimitPerMinute", saved.getRateLimitPerMinute(),
                        "createdAt", saved.getCreatedAt()
                )));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteApiKey(@PathVariable UUID id) {
        return apiKeyRepository.deleteById(id).then(Mono.just(ResponseEntity.noContent().build()));
    }

    @GetMapping("/{id}/permissions")
    public Flux<com.mcp.gateway.domain.McpKeyServerPermissionEntity> getPermissions(@PathVariable UUID id) {
        return apiKeyPermissionService.getPermissionsForApiKey(id);
    }

    @PostMapping("/{id}/permissions")
    public Mono<ResponseEntity<com.mcp.gateway.domain.McpKeyServerPermissionEntity>> savePermission(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> req) {
        String serverIdStr = (String) req.get("serverId");
        if (serverIdStr == null || serverIdStr.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        UUID serverId = UUID.fromString(serverIdStr);

        Object allowedToolsObj = req.get("allowedTools");
        java.util.List<String> allowedTools = java.util.Collections.emptyList();
        if (allowedToolsObj instanceof java.util.List<?> list) {
            allowedTools = list.stream().map(Object::toString).toList();
        } else if (allowedToolsObj instanceof String str && !str.isBlank()) {
            allowedTools = java.util.Arrays.stream(str.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        return apiKeyPermissionService.saveOrUpdatePermission(id, serverId, allowedTools)
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}/permissions/{serverId}")
    public Mono<ResponseEntity<Void>> deletePermission(
            @PathVariable UUID id,
            @PathVariable UUID serverId) {
        return apiKeyPermissionService.deletePermission(id, serverId)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}
