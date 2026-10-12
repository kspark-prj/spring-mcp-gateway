package com.mcp.gateway.controller;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.gateway.domain.McpApiKeyEntity;
import com.mcp.gateway.dto.jsonrpc.JsonRpcError;
import com.mcp.gateway.dto.jsonrpc.JsonRpcRequest;
import com.mcp.gateway.dto.jsonrpc.JsonRpcResponse;
import com.mcp.gateway.security.ApiKeyAuthenticationWebFilter;
import com.mcp.gateway.service.AuditLogService;
import com.mcp.gateway.service.McpRoutingService;
import com.mcp.gateway.transport.http.NettyDataBufferPassthroughProxy;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Single-path Streamable HTTP Controller (/mcp) for MCP protocol transactions.
 * <p>
 * Handles POST JSON-RPC 2.0 requests, optional GET SSE streams, and DELETE session teardown.
 * Validates protocol headers and passes requests through to {@link McpRoutingService}.
 * </p>
 */
@RestController
@RequestMapping("/mcp")
public class McpStreamableHttpController {

    private static final Logger log = LoggerFactory.getLogger(McpStreamableHttpController.class);

    private final McpRoutingService routingService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final NettyDataBufferPassthroughProxy bufferProxy;

    public McpStreamableHttpController(McpRoutingService routingService,
                                       AuditLogService auditLogService,
                                       ObjectMapper objectMapper,
                                       NettyDataBufferPassthroughProxy bufferProxy) {
        this.routingService = routingService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.bufferProxy = bufferProxy;
    }

    /**
     * Primary POST endpoint for JSON-RPC 2.0 request processing over Streamable HTTP.
     * Uses NettyDataBufferPassthroughProxy to enforce 10MB payload size limit and guarantee memory release.
     */
    @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE, "application/json-rpc"},
            produces = {MediaType.APPLICATION_JSON_VALUE, "text/event-stream"})
    public Mono<ResponseEntity<JsonRpcResponse>> handlePost(
            @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion,
            ServerWebExchange exchange) {

        McpApiKeyEntity apiKey = exchange.getAttribute(ApiKeyAuthenticationWebFilter.AUTH_API_KEY_ATTR);

        return bufferProxy.aggregateAndRelease(exchange.getRequest().getBody(), 30000L)
                .flatMap(bytes -> {
                    String rawBody = new String(bytes, StandardCharsets.UTF_8);
                    try {
                        JsonRpcRequest request = objectMapper.readValue(bytes, JsonRpcRequest.class);
                        return routingService.routeRequest(request, apiKey)
                                .map(response -> ResponseEntity.ok()
                                        .header("MCP-Protocol-Version", protocolVersion != null ? protocolVersion : "2024-11-05")
                                        .body(response));
                    } catch (Exception e) {
                        log.error("Failed to parse incoming MCP JSON-RPC payload", e);
                        JsonRpcResponse errResponse = JsonRpcResponse.error(null, JsonRpcError.PARSE_ERROR, "Parse error: " + e.getMessage());
                        if (auditLogService != null) {
                            auditLogService.recordAuditLog(null, apiKey != null ? apiKey.getId() : null, null, "unknown",
                                    "POST", rawBody, 400, 0, "Parse error: " + e.getMessage()).subscribe();
                        }
                        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errResponse));
                    }
                })
                .onErrorResume(ResponseStatusException.class, ex -> {
                    log.warn("Incoming payload rejected: {}", ex.getReason());
                    JsonRpcResponse errResponse = JsonRpcResponse.error(null, JsonRpcError.INVALID_REQUEST, ex.getReason());
                    return Mono.just(ResponseEntity.status(ex.getStatusCode()).body(errResponse));
                });
    }

    /**
     * GET endpoint for SSE streaming.
     * Uses NettyDataBufferPassthroughProxy to stream SSE events directly to client with memory release protection.
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<DataBuffer>> handleGet(ServerWebExchange exchange) {
        byte[] ssePing = "event: ping\ndata: {\"status\":\"ok\"}\n\n".getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(ssePing);
        Flux<DataBuffer> stream = bufferProxy.safePassthrough(Flux.just(buffer));

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("Connection", "keep-alive")
                .body(stream);
    }

    /**
     * DELETE endpoint for explicit session cleanup.
     */
    @DeleteMapping
    public Mono<ResponseEntity<Void>> handleDelete() {
        return Mono.just(ResponseEntity.noContent().build());
    }
}
