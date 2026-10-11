package com.mcp.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mcp.gateway.repository.McpApiKeyRepository;

class ApiKeyPermissionServiceTest {

    private ApiKeyPermissionService service;
    private McpApiKeyRepository apiKeyRepository;

    @BeforeEach
    void setUp() {
        apiKeyRepository = Mockito.mock(McpApiKeyRepository.class);
        service = new ApiKeyPermissionService(
                apiKeyRepository,
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

}
