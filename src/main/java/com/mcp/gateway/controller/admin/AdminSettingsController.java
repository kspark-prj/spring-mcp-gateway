package com.mcp.gateway.controller.admin;

import com.mcp.gateway.domain.McpSystemSettingEntity;
import com.mcp.gateway.service.SystemSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Controller for Administrator management of global system settings and dynamic PII toggle.
 */
@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {

    private final SystemSettingsService settingsService;

    public AdminSettingsController(SystemSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> getSettings() {
        return settingsService.isPiiMaskingEnabled()
                .map(pii -> ResponseEntity.ok(Map.of("piiMaskingEnabled", pii)));
    }

    @PostMapping("/pii-toggle")
    public Mono<ResponseEntity<McpSystemSettingEntity>> togglePiiMasking(@RequestBody Map<String, Boolean> req) {
        boolean enabled = req.getOrDefault("enabled", true);
        return settingsService.updateSetting(SystemSettingsService.PII_MASKING_KEY, String.valueOf(enabled), "Dynamic PII masking toggle")
                .map(ResponseEntity::ok);
    }
}
