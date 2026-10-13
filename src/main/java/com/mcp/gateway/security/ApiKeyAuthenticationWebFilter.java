package com.mcp.gateway.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.mcp.gateway.service.ApiKeyPermissionService;

import reactor.core.publisher.Mono;

/**
 * WebFilter enforcing X-API-KEY authentication for MCP protocol gateway endpoints (/mcp).
 * <p>
 * Registered strictly inside Spring Security filter chain (SecurityWebFilterChain) to prevent duplicate execution.
 * </p>
 */
public class ApiKeyAuthenticationWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationWebFilter.class);

    public static final String X_API_KEY_HEADER = "X-API-KEY";
    public static final String AUTH_API_KEY_ATTR = "AUTHENTICATED_API_KEY";

    private final ApiKeyPermissionService apiKeyPermissionService;

    public ApiKeyAuthenticationWebFilter(ApiKeyPermissionService apiKeyPermissionService) {
        this.apiKeyPermissionService = apiKeyPermissionService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Only enforce X-API-KEY filter on /mcp requests
        if (!path.equals("/mcp") && !path.startsWith("/mcp/")) {
            return chain.filter(exchange);
        }

        String rawApiKey = exchange.getRequest().getHeaders().getFirst(X_API_KEY_HEADER);

        if (rawApiKey == null || rawApiKey.isBlank()) {
            log.warn("Missing X-API-KEY header for MCP request: {}", path);
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "Missing X-API-KEY header");
        }

        return apiKeyPermissionService.authenticateApiKey(rawApiKey)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Invalid or expired X-API-KEY provided for MCP request");
                    return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired X-API-KEY")
                            .then(Mono.empty());
                }))
                .flatMap(apiKey -> {
                    if (!apiKeyPermissionService.checkRateLimit(apiKey)) {
                        log.warn("Rate limit exceeded for API Key ID: {}", apiKey.getId());
                        return writeErrorResponse(exchange, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
                    }

                    // Attach API Key entity to exchange attributes for downstream handlers
                    exchange.getAttributes().put(AUTH_API_KEY_ATTR, apiKey);

                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            apiKey.getKeyName(),
                            rawApiKey,
                            List.of(new SimpleGrantedAuthority(Boolean.TRUE.equals(apiKey.getMasterKey()) ? "ROLE_MASTER_API" : "ROLE_API_USER"))
                    );

                    return chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
                });
    }

    private Mono<Void> writeErrorResponse(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String jsonError = String.format("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"%s\"},\"id\":null}", message);
        byte[] bytes = jsonError.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
