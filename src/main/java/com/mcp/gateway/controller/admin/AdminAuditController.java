package com.mcp.gateway.controller.admin;

import com.mcp.gateway.domain.McpAuditLogEntity;
import com.mcp.gateway.service.AuditLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Controller for Administrator inspection of execution audit logs and real-time traffic statistics.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuditController {

    private final AuditLogService auditLogService;

    public AdminAuditController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping("/audit-logs")
    public Flux<McpAuditLogEntity> getAuditLogs() {
        return auditLogService.getRecentAuditLogs();
    }

    @GetMapping("/stats")
    public Mono<Map<String, Object>> getStats() {
        return auditLogService.getRecentAuditLogs()
                .collectList()
                .map(logs -> {
                    long totalRequests = logs.size();
                    long errors = logs.stream().filter(l -> l.getResponseStatus() >= 400).count();
                    double avgLatency = logs.stream().mapToLong(McpAuditLogEntity::getExecutionTimeMs).average().orElse(0.0);
                    return Map.of(
                            "totalRequests", totalRequests,
                            "errorCount", errors,
                            "avgLatencyMs", Math.round(avgLatency),
                            "successRate", totalRequests > 0 ? Math.round(((double)(totalRequests - errors) / totalRequests) * 100) : 100
                    );
                });
    }
}
