package com.mcp.gateway.transport.http;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import reactor.core.publisher.Mono;

/**
 * Manages MCP Streamable HTTP session lifecycle per upstream server.
 * <p>
 * Implements the MCP 2024-11-05 protocol handshake sequence:
 * 1. Send {@code initialize} JSON-RPC request to upstream server
 * 2. Capture {@code Mcp-Session-Id} response header
 * 3. Send {@code notifications/initialized} with session header
 * 4. Cache session ID for subsequent requests ({@code tools/list}, {@code tools/call}, etc.)
 * </p>
 * <p>
 * Sessions are cached with a 5-minute TTL. If a request returns 400 (indicating session
 * expiry or invalidation), the session is evicted and re-initialized on the next attempt.
 * </p>
 */
@Component
public class McpHttpSessionManager {

    private static final Logger log = LoggerFactory.getLogger(McpHttpSessionManager.class);

    private static final String MCP_SESSION_ID_HEADER = "Mcp-Session-Id";
    private static final String MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    /**
     * Cache mapping server endpoint URL → active Mcp-Session-Id.
     * Sessions expire after 5 minutes to force periodic re-initialization.
     */
    private final Cache<String, String> sessionCache;

    public McpHttpSessionManager(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.sessionCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(500)
                .build();
    }

    /**
     * Retrieves or establishes an MCP session for the given endpoint URL.
     * If a cached session exists, it is returned immediately. Otherwise, the full
     * initialize → notifications/initialized handshake is performed.
     *
     * @param endpointUrl upstream MCP server endpoint URL
     * @return Mono emitting the Mcp-Session-Id (may be empty string if server doesn't use sessions)
     */
    public Mono<String> getOrCreateSession(String endpointUrl) {
        String cached = sessionCache.getIfPresent(endpointUrl);
        if (cached != null) {
            return Mono.just(cached);
        }
        return performInitialize(endpointUrl);
    }

    /**
     * Sends a JSON-RPC request to the upstream MCP server with proper session headers.
     * If the server returns 400 Bad Request (session expired/invalid), the session is
     * evicted and re-initialized, then the request is retried once.
     *
     * @param endpointUrl upstream MCP server endpoint URL
     * @param jsonRpcRequest the JSON-RPC 2.0 request map to send
     * @return Mono emitting the parsed JSON-RPC response map
     */
    public Mono<Map<String, Object>> sendRequest(String endpointUrl, Map<String, Object> jsonRpcRequest) {
        return getOrCreateSession(endpointUrl)
                .flatMap(sessionId -> doPost(endpointUrl, sessionId, jsonRpcRequest))
                .onErrorResume(WebClientResponseException.BadRequest.class, ex -> {
                    log.warn("Received 400 from [{}], evicting session and re-initializing", endpointUrl);
                    sessionCache.invalidate(endpointUrl);
                    return performInitialize(endpointUrl)
                            .flatMap(newSessionId -> doPost(endpointUrl, newSessionId, jsonRpcRequest));
                });
    }

    /**
     * Evicts the cached session for a given endpoint URL.
     * Use this when the upstream server is known to have reset or been reconfigured.
     *
     * @param endpointUrl upstream MCP server endpoint URL
     */
    public void invalidateSession(String endpointUrl) {
        sessionCache.invalidate(endpointUrl);
        log.info("Evicted MCP session for endpoint: {}", endpointUrl);
    }

    /**
     * Performs the MCP initialize handshake:
     * 1. POST initialize request
     * 2. Extract Mcp-Session-Id from response headers
     * 3. POST notifications/initialized with session header
     * 4. Cache the session ID
     */
    private Mono<String> performInitialize(String endpointUrl) {
        Map<String, Object> initRequest = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", PROTOCOL_VERSION,
                        "capabilities", Map.of(),
                        "clientInfo", Map.of(
                                "name", "Spring-MCP-Gateway",
                                "version", "1.0.0"
                        )
                )
        );

        return webClient.post()
                .uri(endpointUrl)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .bodyValue(initRequest)
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException(
                                        "Initialize failed with status " + response.statusCode() + ": " + body)));
                    }

                    // Extract Mcp-Session-Id from response header (may be absent for stateless servers)
                    String sessionId = response.headers().asHttpHeaders().getFirst(MCP_SESSION_ID_HEADER);
                    if (sessionId == null) {
                        sessionId = "";
                    }

                    final String finalSessionId = sessionId;
                    return response.bodyToMono(String.class)
                            .map(responseStr -> parseResponseBody(responseStr))
                            .thenReturn(finalSessionId);
                })
                .flatMap(sessionId -> sendInitializedNotification(endpointUrl, sessionId)
                        .thenReturn(sessionId))
                .doOnSuccess(sessionId -> {
                    sessionCache.put(endpointUrl, sessionId);
                    log.info("MCP session established for [{}], sessionId={}", endpointUrl,
                            sessionId.isEmpty() ? "(stateless)" : sessionId);
                })
                .doOnError(ex -> log.error("MCP initialize handshake failed for [{}]: {}", endpointUrl, ex.getMessage()))
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * Sends the {@code notifications/initialized} notification to complete the handshake.
     */
    private Mono<Void> sendInitializedNotification(String endpointUrl, String sessionId) {
        Map<String, Object> notification = Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized"
        );

        WebClient.RequestBodySpec spec = webClient.post()
                .uri(endpointUrl)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");

        if (sessionId != null && !sessionId.isEmpty()) {
            spec = spec.header(MCP_SESSION_ID_HEADER, sessionId);
        }

        return spec.bodyValue(notification)
                .retrieve()
                .toBodilessEntity()
                .doOnError(ex -> log.warn("notifications/initialized failed for [{}]: {}", endpointUrl, ex.getMessage()))
                .onErrorResume(ex -> Mono.empty()) // Notification failures are non-fatal
                .then();
    }

    /**
     * Executes a POST request to the upstream MCP server with session headers.
     */
    private Mono<Map<String, Object>> doPost(String endpointUrl, String sessionId, Map<String, Object> jsonRpcRequest) {
        WebClient.RequestBodySpec spec = webClient.post()
                .uri(endpointUrl)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");

        if (sessionId != null && !sessionId.isEmpty()) {
            spec = spec.header(MCP_SESSION_ID_HEADER, sessionId);
        }

        return spec.bodyValue(jsonRpcRequest)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseResponseBody);
    }

    /**
     * Helper method to parse text/event-stream or standard JSON response into a Map.
     */
    private Map<String, Object> parseResponseBody(String responseStr) {
        try {
            if (responseStr == null || responseStr.isBlank()) {
                return Collections.emptyMap();
            }
            String jsonToParse = responseStr.trim();

            // SSE 형식(event: message \n data: {...})인 경우 처리
            if (jsonToParse.contains("data:")) {
                String[] lines = jsonToParse.split("\r?\n");
                for (String line : lines) {
                    if (line.startsWith("data:")) {
                        jsonToParse = line.substring(5).trim();
                        break;
                    }
                }
            }

            return objectMapper.readValue(jsonToParse, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Failed to parse upstream response string: {}", responseStr, e);
            return Collections.emptyMap();
        }
    }
}