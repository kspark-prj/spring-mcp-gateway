package com.mcp.gateway.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.gateway.domain.McpApiKeyEntity;
import com.mcp.gateway.domain.McpServerEntity;
import com.mcp.gateway.dto.jsonrpc.JsonRpcError;
import com.mcp.gateway.dto.jsonrpc.JsonRpcRequest;
import com.mcp.gateway.dto.jsonrpc.JsonRpcResponse;
import com.mcp.gateway.repository.McpServerRepository;
import com.mcp.gateway.security.SsrfProtectionValidator;
import com.mcp.gateway.transport.http.McpHttpSessionManager;
import com.mcp.gateway.transport.stdio.StdioProcessManager;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class McpRoutingService {

    private static final Logger log = LoggerFactory.getLogger(McpRoutingService.class);

    private final ToolRegistryService toolRegistryService;
    private final ResourceRegistryService resourceRegistryService;
    private final ResourceTemplateRegistryService resourceTemplateRegistryService;
    private final PromptRegistryService promptRegistryService;
    private final McpServerRepository serverRepository;
    private final ApiKeyPermissionService apiKeyPermissionService;
    private final StdioProcessManager stdioProcessManager;
    private final SsrfProtectionValidator ssrfProtectionValidator;
    private final DynamicCircuitBreakerManager dynamicCircuitBreakerManager;
    private final AuditLogService auditLogService;
    private final McpHttpSessionManager httpSessionManager;
    private final ObjectMapper objectMapper;

    public McpRoutingService(ToolRegistryService toolRegistryService,
                             ResourceRegistryService resourceRegistryService,
                             ResourceTemplateRegistryService resourceTemplateRegistryService,
                             PromptRegistryService promptRegistryService,
                             McpServerRepository serverRepository,
                             ApiKeyPermissionService apiKeyPermissionService,
                             StdioProcessManager stdioProcessManager,
                             SsrfProtectionValidator ssrfProtectionValidator,
                             DynamicCircuitBreakerManager dynamicCircuitBreakerManager,
                             AuditLogService auditLogService,
                             McpHttpSessionManager httpSessionManager,
                             ObjectMapper objectMapper) {
        this.toolRegistryService = toolRegistryService;
        this.resourceRegistryService = resourceRegistryService;
        this.resourceTemplateRegistryService = resourceTemplateRegistryService;
        this.promptRegistryService = promptRegistryService;
        this.serverRepository = serverRepository;
        this.apiKeyPermissionService = apiKeyPermissionService;
        this.stdioProcessManager = stdioProcessManager;
        this.ssrfProtectionValidator = ssrfProtectionValidator;
        this.dynamicCircuitBreakerManager = dynamicCircuitBreakerManager;
        this.auditLogService = auditLogService;
        this.httpSessionManager = httpSessionManager;
        this.objectMapper = objectMapper;
    }

    public Mono<JsonRpcResponse> routeRequest(JsonRpcRequest request, McpApiKeyEntity apiKey) {
        long startTime = System.currentTimeMillis();
        Long apiKeyId = apiKey != null ? apiKey.getId() : null;
        String requestIdStr = request != null && request.getId() != null ? String.valueOf(request.getId()) : null;

        String rawPayload = null;
        try {
            if (request != null) {
                rawPayload = objectMapper.writeValueAsString(request);
            }
        } catch (Exception e) {
            rawPayload = request != null ? request.toString() : null;
        }
        final String finalPayload = rawPayload;

        if (request == null || request.getMethod() == null) {
            JsonRpcResponse err = JsonRpcResponse.error(null, JsonRpcError.INVALID_REQUEST, "Invalid JSON-RPC request structure");
            recordAuditLog(requestIdStr, apiKeyId, null, null, finalPayload, err, System.currentTimeMillis() - startTime);
            return Mono.just(err);
        }

        String method = request.getMethod();

        Mono<JsonRpcResponse> responseMono;
        switch (method) {
            case "initialize":
                responseMono = handleInitialize(request);
                break;
            case "ping":
                responseMono = Mono.just(JsonRpcResponse.success(request.getId(), Map.of()));
                break;
            case "tools/list":
                responseMono = handleToolsList(request, apiKey);
                break;
            case "tools/call":
                responseMono = handleToolsCall(request, apiKey);
                break;
            case "resources/list":
                responseMono = handleResourcesList(request);
                break;
            case "resources/read":
                responseMono = handleResourcesRead(request);
                break;
            case "resources/templates/list":
                responseMono = handleResourcesTemplatesList(request);
                break;
            case "prompts/list":
                responseMono = handlePromptsList(request);
                break;
            case "prompts/get":
                responseMono = handlePromptsGet(request);
                break;
            case "notifications/initialized":
            case "notifications/cancelled":
                responseMono = handleCancellationNotification(request);
                break;
            default:
                responseMono = Mono.just(JsonRpcResponse.error(request.getId(), JsonRpcError.METHOD_NOT_FOUND, "Unsupported MCP method: " + method));
                break;
        }

        return responseMono.doOnNext(response -> {
            long executionTimeMs = System.currentTimeMillis() - startTime;
            String toolName = extractToolOrResourceName(request);
            // 글로벌 요청이거나 서버 특정 요청이 아닌 경우에만 여기서 기록
            if (!isServerSpecificMethod(method)) {
                recordAuditLog(requestIdStr, apiKeyId, null, toolName, finalPayload, response, executionTimeMs);
            }
        });
    }

    private boolean isServerSpecificMethod(String method) {
       // return "tools/call".equals(method) || "resources/read".equals(method) || "prompts/get".equals(method);
    	 return "tools/call".equals(method) ;
    }



    private void recordAuditLog(String requestId, Long apiKeyId, Long serverId, String toolName,
                                 String rawPayload, JsonRpcResponse response, long executionTimeMs) {
        if (auditLogService == null) return;
        int status = 200;
        String errorMessage = null;
        if (response != null && response.getError() != null) {
            int code = response.getError().getCode();
            if (code == JsonRpcError.UNAUTHORIZED) {
                status = 401;
            } else if (code == JsonRpcError.INVALID_PARAMS || code == JsonRpcError.INVALID_REQUEST) {
                status = 400;
            } else if (code == JsonRpcError.METHOD_NOT_FOUND) {
                status = 404;
            } else {
                status = 500;
            }
            errorMessage = response.getError().getMessage();
        }
        auditLogService.recordAuditLog(requestId, apiKeyId, serverId, toolName, "POST", rawPayload, status, executionTimeMs, errorMessage).subscribe();
    }

    private String extractToolOrResourceName(JsonRpcRequest request) {
        if (request == null) return null;
        if ("tools/call".equals(request.getMethod()) && request.getParams() != null) {
            return (String) request.getParams().get("name");
        }
        if ("resources/read".equals(request.getMethod()) && request.getParams() != null) {
            return (String) request.getParams().get("uri");
        }
        if ("prompts/get".equals(request.getMethod()) && request.getParams() != null) {
            return (String) request.getParams().get("name");
        }
        return request.getMethod();
    }

    private Long extractServerId(JsonRpcRequest request) {
        // Can be resolved dynamically if needed, returns null if global
        return null;
    }

    private Mono<JsonRpcResponse> handleInitialize(JsonRpcRequest request) {
        Map<String, Object> result = Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(
                        "tools", Map.of("listChanged", true),
                        "resources", Map.of("listChanged", true),
                        "prompts", Map.of("listChanged", true)
                ),
                "serverInfo", Map.of("name", "Spring-MCP-Gateway", "version", "1.0.0")
        );
        return Mono.just(JsonRpcResponse.success(request.getId(), result));
    }

    private Mono<JsonRpcResponse> handleToolsList(JsonRpcRequest request, McpApiKeyEntity apiKey) {
        return toolRegistryService.getUnifiedToolCatalog()
                .flatMap(allTools -> Flux.fromIterable(allTools)
                        .flatMap(tool -> {
                            String serverName = (String) tool.get("serverName");
                            String originalName = (String) tool.get("originalName");
                            return serverRepository.findByName(serverName)
                                    .flatMap(server -> apiKeyPermissionService.isToolAllowed(apiKey, server.getId(), originalName))
                                    .filter(Boolean::booleanValue)
                                    .map(allowed -> tool);
                        })
                        .collectList()
                        .map(allowedTools -> JsonRpcResponse.success(request.getId(), Map.of("tools", allowedTools))));
    }

    private Mono<JsonRpcResponse> handleResourcesList(JsonRpcRequest request) {
        return resourceRegistryService.getUnifiedResourceCatalog()
                .map(resources -> JsonRpcResponse.success(request.getId(), Map.of("resources", resources)));
    }

    private Mono<JsonRpcResponse> handleResourcesTemplatesList(JsonRpcRequest request) {
        return resourceTemplateRegistryService.getUnifiedTemplateCatalog()
                .map(templates -> JsonRpcResponse.success(request.getId(), Map.of("resourceTemplates", templates)));
    }

    private Mono<JsonRpcResponse> handlePromptsList(JsonRpcRequest request) {
        return promptRegistryService.getUnifiedPromptCatalog()
                .map(prompts -> JsonRpcResponse.success(request.getId(), Map.of("prompts", prompts)));
    }

    @SuppressWarnings("unchecked")
    private Mono<JsonRpcResponse> handleResourcesRead(JsonRpcRequest request) {
        Map<String, Object> params = request.getParams();
        if (params == null || !params.containsKey("uri")) {
            return Mono.just(JsonRpcResponse.error(request.getId(), JsonRpcError.INVALID_PARAMS, "Missing 'uri' in resources/read params"));
        }

        String namespacedUri = (String) params.get("uri");
        int separatorIdx = namespacedUri.indexOf(ResourceRegistryService.NAMESPACE_SEPARATOR);
        if (separatorIdx <= 0) {
            return Mono.just(JsonRpcResponse.error(request.getId(), JsonRpcError.INVALID_PARAMS, "Resource URI must be namespaced ({server_name}__{original_uri})"));
        }

        String serverName = namespacedUri.substring(0, separatorIdx);
        String originalUri = namespacedUri.substring(separatorIdx + ResourceRegistryService.NAMESPACE_SEPARATOR.length());

        return serverRepository.findByName(serverName)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Target MCP Server not found: " + serverName)))
                .flatMap(server -> {
                    Map<String, Object> upstreamParams = new HashMap<>(params);
                    upstreamParams.put("uri", originalUri);

                    Map<String, Object> upstreamRequestMap = Map.of(
                            "jsonrpc", "2.0",
                            "id", request.getId() != null ? request.getId() : UUID.randomUUID().toString(),
                            "method", "resources/read",
                            "params", upstreamParams
                    );

                    return dispatchToUpstreamGeneric(request.getId(), server, upstreamRequestMap);
                })
                .onErrorResume(ex -> {
                    log.error("Routing error for resource read [{}]: {}", namespacedUri, ex.getMessage(), ex);
                    return Mono.just(JsonRpcResponse.error(request.getId(), JsonRpcError.INTERNAL_ERROR, ex.getMessage()));
                });
    }

    @SuppressWarnings("unchecked")
    private Mono<JsonRpcResponse> handlePromptsGet(JsonRpcRequest request) {
        Map<String, Object> params = request.getParams();
        if (params == null || !params.containsKey("name")) {
            return Mono.just(JsonRpcResponse.error(request.getId(), JsonRpcError.INVALID_PARAMS, "Missing 'name' in prompts/get params"));
        }

        String namespacedPromptName = (String) params.get("name");
        int separatorIdx = namespacedPromptName.indexOf(PromptRegistryService.NAMESPACE_SEPARATOR);
        if (separatorIdx <= 0) {
            return Mono.just(JsonRpcResponse.error(request.getId(), JsonRpcError.INVALID_PARAMS, "Prompt name must be namespaced ({server_name}__{original_name})"));
        }

        String serverName = namespacedPromptName.substring(0, separatorIdx);
        String originalPromptName = namespacedPromptName.substring(separatorIdx + PromptRegistryService.NAMESPACE_SEPARATOR.length());

        return serverRepository.findByName(serverName)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Target MCP Server not found: " + serverName)))
                .flatMap(server -> {
                    Map<String, Object> upstreamParams = new HashMap<>(params);
                    upstreamParams.put("name", originalPromptName);

                    Map<String, Object> upstreamRequestMap = Map.of(
                            "jsonrpc", "2.0",
                            "id", request.getId() != null ? request.getId() : UUID.randomUUID().toString(),
                            "method", "prompts/get",
                            "params", upstreamParams
                    );

                    return dispatchToUpstreamGeneric(request.getId(), server, upstreamRequestMap);
                })
                .onErrorResume(ex -> {
                    log.error("Routing error for prompt get [{}]: {}", namespacedPromptName, ex.getMessage(), ex);
                    return Mono.just(JsonRpcResponse.error(request.getId(), JsonRpcError.INTERNAL_ERROR, ex.getMessage()));
                });
    }

    @SuppressWarnings("unchecked")
    private Mono<JsonRpcResponse> handleToolsCall(JsonRpcRequest request, McpApiKeyEntity apiKey) {
        long startTime = System.currentTimeMillis();
        String requestIdStr = request != null && request.getId() != null ? String.valueOf(request.getId()) : null;
        Long apiKeyId = apiKey != null ? apiKey.getId() : null;
        String rawPayload = null;
        try { rawPayload = objectMapper.writeValueAsString(request); } catch (Exception e) {}
        final String finalPayload = rawPayload;

        Map<String, Object> params = request.getParams();
        if (params == null || !params.containsKey("name")) {
            JsonRpcResponse err = JsonRpcResponse.error(request.getId(), JsonRpcError.INVALID_PARAMS, "Missing 'name' in tools/call params");
            recordAuditLog(requestIdStr, apiKeyId, null, extractToolOrResourceName(request), finalPayload, err, System.currentTimeMillis() - startTime);
            return Mono.just(err);
        }

        String namespacedToolName = (String) params.get("name");
        int separatorIdx = namespacedToolName.indexOf(ToolRegistryService.NAMESPACE_SEPARATOR);
        if (separatorIdx <= 0) {
            JsonRpcResponse err = JsonRpcResponse.error(request.getId(), JsonRpcError.INVALID_PARAMS, "Tool name must be namespaced ({server_name}__{tool_name})");
            recordAuditLog(requestIdStr, apiKeyId, null, extractToolOrResourceName(request), finalPayload, err, System.currentTimeMillis() - startTime);
            return Mono.just(err);
        }

        String serverName = namespacedToolName.substring(0, separatorIdx);
        String originalToolName = namespacedToolName.substring(separatorIdx + ToolRegistryService.NAMESPACE_SEPARATOR.length());

        return serverRepository.findByName(serverName)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Target MCP Server not found: " + serverName)))
                .flatMap(server -> {
                    Long serverId = server.getId(); // <-- 여기서 정확한 TSID Long 서버 ID 획득!

                    if (Boolean.FALSE.equals(server.getEnabled())) {
                        JsonRpcResponse err = JsonRpcResponse.error(request.getId(), JsonRpcError.SERVER_ERROR, "Target MCP Server is disabled");
                        recordAuditLog(requestIdStr, apiKeyId, serverId, extractToolOrResourceName(request), finalPayload, err, System.currentTimeMillis() - startTime);
                        return Mono.just(err);
                    }

                    return apiKeyPermissionService.isToolAllowed(apiKey, serverId, originalToolName)
                            .flatMap(allowed -> {
                                if (!allowed) {
                                    JsonRpcResponse err = JsonRpcResponse.error(request.getId(), JsonRpcError.UNAUTHORIZED, "API Key unauthorized for tool: " + namespacedToolName);
                                    recordAuditLog(requestIdStr, apiKeyId, serverId, extractToolOrResourceName(request), finalPayload, err, System.currentTimeMillis() - startTime);
                                    return Mono.just(err);
                                }

                                Map<String, Object> upstreamParams = new HashMap<>(params != null ? params : Map.of());
                                upstreamParams.put("name", originalToolName);

                                Map<String, Object> upstreamRequestMap = Map.of(
                                        "jsonrpc", "2.0",
                                        "id", request.getId() != null ? request.getId() : UUID.randomUUID().toString(),
                                        "method", "tools/call",
                                        "params", upstreamParams
                                );

                                return dispatchToUpstreamGeneric(request.getId(), server, upstreamRequestMap)
                                        .doOnNext(response -> {
                                            // 정상/에러 응답 완료 시 정확한 serverId를 포함하여 감사 로그 기록
                                            long executionTimeMs = System.currentTimeMillis() - startTime;
                                            recordAuditLog(requestIdStr, apiKeyId, serverId, extractToolOrResourceName(request), finalPayload, response, executionTimeMs);
                                        });
                            });
                })
                .onErrorResume(ex -> {
                    log.error("Routing error for tool call [{}]: {}", namespacedToolName, ex.getMessage(), ex);
                    JsonRpcResponse err = JsonRpcResponse.error(request.getId(), JsonRpcError.INTERNAL_ERROR, ex.getMessage());
                    recordAuditLog(requestIdStr, apiKeyId, null, extractToolOrResourceName(request), finalPayload, err, System.currentTimeMillis() - startTime);
                    return Mono.just(err);
                });
    }

    private Mono<JsonRpcResponse> dispatchToUpstreamGeneric(Object requestId, McpServerEntity server, Map<String, Object> upstreamRequestMap) {
        if ("HTTP".equalsIgnoreCase(server.getTransportType())) {
            return dispatchToHttpUpstream(requestId, server, upstreamRequestMap);
        } else if ("STDIO".equalsIgnoreCase(server.getTransportType())) {
            return dispatchToStdioUpstream(requestId, server, upstreamRequestMap);
        }
        return Mono.just(JsonRpcResponse.error(requestId, JsonRpcError.INTERNAL_ERROR, "Unsupported transport type: " + server.getTransportType()));
    }

    @SuppressWarnings("unchecked")
    private Mono<JsonRpcResponse> dispatchToHttpUpstream(Object requestId, McpServerEntity server, Map<String, Object> upstreamRequestMap) {
        try {
            ssrfProtectionValidator.validateUrl(server.getEndpointUrl());
        } catch (IllegalArgumentException e) {
            return Mono.just(JsonRpcResponse.error(requestId, JsonRpcError.INVALID_PARAMS, e.getMessage()));
        }

        CircuitBreaker circuitBreaker = dynamicCircuitBreakerManager.getCircuitBreaker(server);

        return httpSessionManager.sendRequest(server.getEndpointUrl(), upstreamRequestMap)
                .timeout(Duration.ofSeconds(30))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .map(respMap -> {
                    JsonRpcResponse response = new JsonRpcResponse();
                    response.setId(requestId);
                    response.setResult(respMap.get("result"));
                    if (respMap.containsKey("error") && respMap.get("error") instanceof Map errMap) {
                        int code = (int) errMap.getOrDefault("code", -32000);
                        String msg = (String) errMap.getOrDefault("message", "Upstream Error");
                        response.setError(new JsonRpcError(code, msg));
                    }
                    return response;
                })
                .onErrorResume(ex -> {
                    log.error("HTTP Upstream [{}] failed: {}", server.getName(), ex.getMessage());
                    return Mono.just(JsonRpcResponse.error(requestId, JsonRpcError.SERVER_ERROR, "Upstream HTTP Call Failed: " + ex.getMessage()));
                });
    }

    @SuppressWarnings("unchecked")
    private Mono<JsonRpcResponse> dispatchToStdioUpstream(Object requestId, McpServerEntity server, Map<String, Object> upstreamRequestMap) {
        return Mono.fromCallable(() -> {
            log.info("📌 [DISPATCH-STDIO-DEBUG] dispatchToStdioUpstream() called: server={}, requestId={}, method={}", server.getName(), requestId, upstreamRequestMap.get("method"));
            try {
                String reqJson = objectMapper.writeValueAsString(upstreamRequestMap);
                log.info("📌 [DISPATCH-STDIO-DEBUG] Sending to [{}] stdin: {}", server.getName(), reqJson.length() > 500 ? reqJson.substring(0, 500) + "..." : reqJson);
                stdioProcessManager.sendInput(server.getId(), reqJson);
                log.info("📌 [DISPATCH-STDIO-DEBUG] Waiting for response from [{}] (timeout=30s)...", server.getName());
                String respJson = stdioProcessManager.pollResponse(server.getId(), 30000);
                if (respJson != null) {
                    log.info("📌 [DISPATCH-STDIO-DEBUG] Got response from [{}]: {}", server.getName(), respJson.length() > 500 ? respJson.substring(0, 500) + "..." : respJson);
                    Map<String, Object> respMap = objectMapper.readValue(respJson, Map.class);
                    JsonRpcResponse response = new JsonRpcResponse();
                    response.setId(requestId);
                    response.setResult(respMap.get("result"));
                    if (respMap.containsKey("error") && respMap.get("error") instanceof Map errMap) {
                        int code = (int) errMap.getOrDefault("code", -32000);
                        String msg = (String) errMap.getOrDefault("message", "Upstream Error");
                        log.warn("📌 [DISPATCH-STDIO-DEBUG] Upstream [{}] returned error: code={}, message={}", server.getName(), code, msg);
                        response.setError(new JsonRpcError(code, msg));
                    }
                    return response;
                }
                log.warn("📌 [DISPATCH-STDIO-DEBUG] TIMEOUT: No response from [{}] within 30s", server.getName());
            } catch (Exception e) {
                log.error("📌 [DISPATCH-STDIO-DEBUG] EXCEPTION in dispatchToStdioUpstream [{}]: {} - {}", server.getName(), e.getClass().getSimpleName(), e.getMessage(), e);
            }
            return JsonRpcResponse.error(requestId, JsonRpcError.SERVER_ERROR, "Upstream STDIO Call Failed");
        });
    }

    private Mono<JsonRpcResponse> handleCancellationNotification(JsonRpcRequest request) {
        log.info("Received request cancellation notification for request ID: {}", request.getParams() != null ? request.getParams().get("requestId") : "unknown");
        return Mono.empty();
    }
}