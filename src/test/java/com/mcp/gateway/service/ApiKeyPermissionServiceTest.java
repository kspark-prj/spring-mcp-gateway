package com.mcp.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mcp.gateway.config.TsidConfig;
import com.mcp.gateway.domain.McpApiKeyEntity;
import com.mcp.gateway.domain.McpKeyServerPermissionEntity;
import com.mcp.gateway.repository.McpApiKeyRepository;
import com.mcp.gateway.repository.McpKeyServerPermissionRepository;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ApiKeyPermissionServiceTest {

    private ApiKeyPermissionService service;
    private McpApiKeyRepository apiKeyRepository;
    private McpKeyServerPermissionRepository permissionRepository;

    @BeforeEach
    void setUp() {
        apiKeyRepository = Mockito.mock(McpApiKeyRepository.class);
        permissionRepository = Mockito.mock(McpKeyServerPermissionRepository.class);
        service = new ApiKeyPermissionService(
                apiKeyRepository,
                permissionRepository,
                Caffeine.newBuilder().build(),
                new ObjectMapper()
        );
    }

    @Test
    @DisplayName("SHA-256 hash generation produces consistent hex fingerprint")
    void testHashApiKey() {
        String rawKey = "mcp-gateway-master-key-2026";
        String hash = service.hashApiKey(rawKey);
        assertNotNull(hash);
        assertEquals(64, hash.length());
        assertEquals("b3fdafe948dae92812d73cfa0f2f3e52916f2a679b6c14619743e8f739292b97", hash);
    }

    @Test
    @DisplayName("Master API key grants unrestricted tool execution access")
    void testMasterKeyToolAccess() {
        McpApiKeyEntity masterKey = new McpApiKeyEntity();
        masterKey.setId(TsidConfig.nextLong());
        masterKey.setMasterKey(true);
        masterKey.setEnabled(true);

        StepVerifier.create(service.isToolAllowed(masterKey, TsidConfig.nextLong(), "any__tool"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("Non-master key grants access to specific allowed tools and denies unlisted tools")
    void testSpecificToolPermissionAllowedAndDenied() {
        Long keyId = TsidConfig.nextLong();
        Long serverId = TsidConfig.nextLong();

        McpApiKeyEntity key = new McpApiKeyEntity();
        key.setId(keyId);
        key.setMasterKey(false);
        key.setEnabled(true);

        McpKeyServerPermissionEntity perm = new McpKeyServerPermissionEntity(
        		TsidConfig.nextLong(), keyId, serverId, "[\"get_weather\", \"search\"]", Instant.now()
        );

        when(permissionRepository.findByApiKeyIdAndServerId(keyId, serverId)).thenReturn(Mono.just(perm));

        StepVerifier.create(service.isToolAllowed(key, serverId, "get_weather"))
                .expectNext(true)
                .verifyComplete();

        StepVerifier.create(service.isToolAllowed(key, serverId, "delete_db"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("saveOrUpdatePermission creates new permission if not existing, or updates if present")
    void testSaveOrUpdatePermission() {
        Long keyId = TsidConfig.nextLong();
        Long serverId = TsidConfig.nextLong();

        when(permissionRepository.findByApiKeyIdAndServerId(keyId, serverId)).thenReturn(Mono.empty());
        when(permissionRepository.save(any(McpKeyServerPermissionEntity.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.saveOrUpdatePermission(keyId, serverId, List.of("toolA", "toolB")))
                .assertNext(saved -> {
                    assertEquals(keyId, saved.getApiKeyId());
                    assertEquals(serverId, saved.getServerId());
                    assertTrue(saved.getAllowedToolsJson().contains("toolA"));
                    assertTrue(saved.getAllowedToolsJson().contains("toolB"));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("deletePermission calls repository deleteByApiKeyIdAndServerId")
    void testDeletePermission() {
        Long keyId = TsidConfig.nextLong();
        Long serverId = TsidConfig.nextLong();

        when(permissionRepository.deleteByApiKeyIdAndServerId(keyId, serverId)).thenReturn(Mono.empty());

        StepVerifier.create(service.deletePermission(keyId, serverId))
                .verifyComplete();

        verify(permissionRepository).deleteByApiKeyIdAndServerId(keyId, serverId);
    }
}
