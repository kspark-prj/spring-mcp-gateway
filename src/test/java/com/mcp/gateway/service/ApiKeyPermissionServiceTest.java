package com.mcp.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mcp.gateway.domain.McpApiKeyEntity;
import com.mcp.gateway.domain.McpKeyServerPermissionEntity;
import com.mcp.gateway.repository.McpApiKeyRepository;
import com.mcp.gateway.repository.McpKeyServerPermissionRepository;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ApiKeyPermissionServiceTest {

    private ApiKeyPermissionService service;
    private McpApiKeyRepository apiKeyRepository;
    private McpKeyServerPermissionRepository keyServerPermissionRepository;

    @BeforeEach
    void setUp() {
        apiKeyRepository = Mockito.mock(McpApiKeyRepository.class);
        keyServerPermissionRepository = Mockito.mock(McpKeyServerPermissionRepository.class);
        service = new ApiKeyPermissionService(
                apiKeyRepository,
                keyServerPermissionRepository,
                Caffeine.newBuilder().build(),
                new ObjectMapper()
        );
    }

    private McpApiKeyEntity scopedKey(Long id) {
        return new McpApiKeyEntity(id, "scoped", "hash", false, true, 60, null, null, null);
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
    @DisplayName("Master key is allowed to call any tool on any server without a permission row")
    void testMasterKeyBypassesToolCheck() {
        McpApiKeyEntity masterKey = new McpApiKeyEntity(1L, "master", "hash", true, true, 60, null, null, null);
        StepVerifier.create(service.isToolAllowed(masterKey, 99L, "anything"))
                .expectNext(true)
                .verifyComplete();
        Mockito.verifyNoInteractions(keyServerPermissionRepository);
    }

    @Test
    @DisplayName("Scoped key with no permission row for the server is denied")
    void testScopedKeyWithoutPermissionRowIsDenied() {
        Mockito.when(keyServerPermissionRepository.findByApiKeyIdAndServerId(2L, 5L)).thenReturn(Mono.empty());
        StepVerifier.create(service.isToolAllowed(scopedKey(2L), 5L, "search"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("Scoped key may only call tools present in its allowed_tools_json list")
    void testScopedKeyToolAllowListIsEnforced() {
        McpKeyServerPermissionEntity permission = new McpKeyServerPermissionEntity(
                10L, 2L, 5L, "[\"search\",\"read\"]", null);
        Mockito.when(keyServerPermissionRepository.findByApiKeyIdAndServerId(2L, 5L))
                .thenReturn(Mono.just(permission));

        StepVerifier.create(service.isToolAllowed(scopedKey(2L), 5L, "search"))
                .expectNext(true)
                .verifyComplete();
        StepVerifier.create(service.isToolAllowed(scopedKey(2L), 5L, "delete"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("Scoped key with a permission row has server access regardless of tool list")
    void testScopedKeyHasServerAccessWhenPermissionRowExists() {
        McpKeyServerPermissionEntity permission = new McpKeyServerPermissionEntity(
                10L, 2L, 5L, "[]", null);
        Mockito.when(keyServerPermissionRepository.findByApiKeyIdAndServerId(2L, 5L))
                .thenReturn(Mono.just(permission));

        StepVerifier.create(service.hasServerAccess(scopedKey(2L), 5L))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("Scoped key without any permission row has no server access")
    void testScopedKeyWithoutPermissionRowHasNoServerAccess() {
        Mockito.when(keyServerPermissionRepository.findByApiKeyIdAndServerId(2L, 7L)).thenReturn(Mono.empty());
        StepVerifier.create(service.hasServerAccess(scopedKey(2L), 7L))
                .expectNext(false)
                .verifyComplete();
    }

}
