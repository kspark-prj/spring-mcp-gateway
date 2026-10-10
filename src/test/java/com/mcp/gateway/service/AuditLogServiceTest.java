package com.mcp.gateway.service;

import com.mcp.gateway.repository.McpAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogServiceTest {

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        McpAuditLogRepository repository = Mockito.mock(McpAuditLogRepository.class);
        SystemSettingsService settingsService = Mockito.mock(SystemSettingsService.class);
        auditLogService = new AuditLogService(repository, settingsService);
    }

    @Test
    @DisplayName("Mandatory secret redaction strips raw API keys and passwords unconditionally")
    void testMandatorySecretRedaction() {
        String payload = "{\"apiKey\":\"mcp-gateway-master-key-2026\", \"password\":\"admin1\", \"email\":\"user@example.com\"}";
        String sanitized = auditLogService.sanitizePayload(payload, false);

        assertFalse(sanitized.contains("mcp-gateway-master-key-2026"));
        assertFalse(sanitized.contains("admin1"));
        assertTrue(sanitized.contains("[REDACTED_SECRET]"));
        // General email preserved when PII toggle is false
        assertTrue(sanitized.contains("user@example.com"));
    }

    @Test
    @DisplayName("General PII masking masks email and phone numbers when PII toggle is true")
    void testGeneralPiiMasking() {
        String payload = "Contact user at john.doe@domain.org or 010-1234-5678";
        String sanitized = auditLogService.sanitizePayload(payload, true);

        assertFalse(sanitized.contains("john.doe@domain.org"));
        assertFalse(sanitized.contains("010-1234-5678"));
        assertTrue(sanitized.contains("***@***.com"));
        assertTrue(sanitized.contains("***-***-****"));
    }
}
