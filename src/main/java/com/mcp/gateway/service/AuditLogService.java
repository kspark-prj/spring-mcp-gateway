package com.mcp.gateway.service;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mcp.gateway.domain.McpAuditLogEntity;
import com.mcp.gateway.repository.McpAuditLogRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Service for non-blocking asynchronous audit log persistence and dynamic PII / Auth secret masking.
 * <p>
 * Enforces MANDATORY permanent redaction of authentication credentials (API keys, passwords, tokens)
 * regardless of global PII masking setting, while applying configurable masking for general PII.
 * </p>
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    // Regex patterns for general PII masking
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?i)[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+");
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\b\\d{2,3}[-.]?\\d{3,4}[-.]?\\d{4}\\b");

    // Regex patterns for MANDATORY authentication secret redaction
    private static final Pattern AUTH_HEADER_PATTERN = Pattern.compile("(?i)(authorization|x-api-key|password|secret|token|bearer)\\s*[:=]\\s*[\"']?([^\"'\\s,]+)[\"']?");
    private static final Pattern JSON_SECRET_KEY_PATTERN = Pattern.compile("(?i)\"(password|secret|apiKey|api_key|token|access_token|authorization)\"\\s*:\\s*\"[^\"]+\"");

    private final McpAuditLogRepository auditLogRepository;
    private final SystemSettingsService systemSettingsService;

    public AuditLogService(McpAuditLogRepository auditLogRepository, SystemSettingsService systemSettingsService) {
        this.auditLogRepository = auditLogRepository;
        this.systemSettingsService = systemSettingsService;
    }

    /**
     * Asynchronously records an audit log entry in a non-blocking reactive pipeline.
     */
    public Mono<Void> recordAuditLog(String requestId, UUID apiKeyId, UUID serverId, String toolName,
                                     String httpMethod, String rawPayload, int status, long executionTimeMs, String errorMessage) {

        return systemSettingsService.isPiiMaskingEnabled()
                .flatMap(piiEnabled -> {
                    String sanitizedPayload = sanitizePayload(rawPayload, piiEnabled);

                    McpAuditLogEntity audit = new McpAuditLogEntity();
                    audit.setRequestId(requestId != null ? requestId : UUID.randomUUID().toString());
                    audit.setApiKeyId(apiKeyId);
                    audit.setServerId(serverId);
                    audit.setToolName(toolName);
                    audit.setHttpMethod(httpMethod != null ? httpMethod : "POST");
                    audit.setRequestPayload(sanitizedPayload);
                    audit.setResponseStatus(status);
                    audit.setExecutionTimeMs(executionTimeMs);
                    audit.setPiiMasked(piiEnabled);
                    audit.setErrorMessage(errorMessage);
                    audit.setCreatedAt(Instant.now());
                    audit.setNew(true);

                    return auditLogRepository.save(audit)
                            .then();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(err -> log.error("Failed to persist audit log entry", err))
                .onErrorResume(err -> Mono.empty());
    }

    /**
     * Sanitizes input payload string by applying mandatory secret redaction and optional PII masking.
     *
     * @param payload raw payload text
     * @param maskPii whether general PII masking is enabled
     * @return sanitized string
     */
    public String sanitizePayload(String payload, boolean maskPii) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }

        // 1. ALWAYS perform mandatory authentication secret redaction
        String sanitized = AUTH_HEADER_PATTERN.matcher(payload).replaceAll("$1: \"[REDACTED_SECRET]\"");
        sanitized = JSON_SECRET_KEY_PATTERN.matcher(sanitized).replaceAll("\"$1\": \"[REDACTED_SECRET]\"");

        // 2. Perform general PII masking if toggle is enabled
        if (maskPii) {
            sanitized = EMAIL_PATTERN.matcher(sanitized).replaceAll("***@***.com");
            sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll("***-***-****");
        }

        return sanitized;
    }

    /**
     * Fetches recent audit logs for administration view.
     */
    public Flux<McpAuditLogEntity> getRecentAuditLogs() {
        return auditLogRepository.findTop10ByOrderByCreatedAtDesc();
    }
}
