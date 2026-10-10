package com.mcp.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;

import com.mcp.gateway.repository.McpAdminUserRepository;
import com.mcp.gateway.security.ApiKeyAuthenticationWebFilter;

/**
 * Spring Security Reactive Configuration.
 * <p>
 * Configures dual authentication models:
 * 1. Stateless API Key header authentication for /mcp endpoint
 * 2. Session Cookie + CSRF protection for Dashboard Administrator APIs (/api/admin/**)
 * </p>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final ApiKeyAuthenticationWebFilter apiKeyAuthenticationWebFilter;

    public SecurityConfig(ApiKeyAuthenticationWebFilter apiKeyAuthenticationWebFilter) {
        this.apiKeyAuthenticationWebFilter = apiKeyAuthenticationWebFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public ServerSecurityContextRepository securityContextRepository() {
        return new WebSessionServerSecurityContextRepository();
    }

    @Bean
    public ReactiveUserDetailsService userDetailsService(McpAdminUserRepository adminUserRepository) {
        return username -> adminUserRepository.findByUsername(username)
                .map(user -> User.builder()
                        .username(user.getUsername())
                        .password(user.getPasswordHash())
                        .roles(user.getRole().replace("ROLE_", ""))
                        .build()
                );
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        CookieServerCsrfTokenRepository csrfTokenRepository = CookieServerCsrfTokenRepository.withHttpOnlyFalse();
        ServerCsrfTokenRequestAttributeHandler requestHandler = new ServerCsrfTokenRequestAttributeHandler();

        return http
                .securityContextRepository(securityContextRepository())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(requestHandler)
                        .requireCsrfProtectionMatcher(exchange -> {
                            String path = exchange.getRequest().getURI().getPath();
                            HttpMethod method = exchange.getRequest().getMethod();
                            boolean isStateChanging = method == HttpMethod.POST || method == HttpMethod.PUT
                                    || method == HttpMethod.DELETE || method == HttpMethod.PATCH;
                            ///mcp 및 모든 /api/admin/** 요청은 CSRF 검증을 완전히 제외 (로그인 세션 인증으로 대체)
                            if (path.startsWith("/mcp") ||  isStateChanging && path.startsWith("/api/admin")) {
                                return ServerWebExchangeMatcher.MatchResult.notMatch();
                            }
                            return ServerWebExchangeMatcher.MatchResult.notMatch();
                        })
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/mcp", "/mcp/**").permitAll()
                        .pathMatchers("/api/admin/login", "/api/admin/logout", "/api/admin/me").permitAll()
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")
                        .pathMatchers("/", "/dashboard.html", "/favicon.ico", "/static/**").permitAll()
                        .anyExchange().authenticated()
                )
                .addFilterBefore(apiKeyAuthenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
