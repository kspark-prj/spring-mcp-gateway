package com.mcp.gateway.controller.admin;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;

import com.mcp.gateway.repository.McpAdminUserRepository;

import reactor.core.publisher.Mono;

/**
 * Controller handling Admin Web Dashboard Login, Logout, Session verification, and SecurityContext management.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    private final McpAdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final ServerSecurityContextRepository securityContextRepository;

    public AdminAuthController(McpAdminUserRepository adminUserRepository,
                               PasswordEncoder passwordEncoder,
                               ServerSecurityContextRepository securityContextRepository) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityContextRepository = securityContextRepository;
    }

    /**
     * Checks current session authentication status.
     */
    @GetMapping("/me")
    public Mono<ResponseEntity<Map<String, Object>>> me(ServerWebExchange exchange) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth != null && auth.isAuthenticated() && !auth.getPrincipal().equals("anonymousUser"))
                .map(auth -> ResponseEntity.ok(Map.of(
                        "authenticated", (Object) true,
                        "username", auth.getName()
                )))
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("authenticated", false)));
    }

    /**
     * Authenticates administrator credentials and persists SecurityContext in WebSession.
     */
    @PostMapping("/login")
    public Mono<ResponseEntity<Map<String, Object>>> login(@RequestBody Map<String, String> creds, ServerWebExchange exchange) {
        String username = creds.get("username");
        String password = creds.get("password");

        if (username == null || password == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", (Object) "아이디와 비밀번호를 입력해주세요.")));
        }

        return adminUserRepository.findByUsername(username)
                .flatMap(user -> {
                    if (passwordEncoder.matches(password, user.getPasswordHash())) {
                        UsernamePasswordAuthenticationToken authResult = new UsernamePasswordAuthenticationToken(
                                user.getUsername(),
                                null,
                                List.of(new SimpleGrantedAuthority(user.getRole()))
                        );
                        SecurityContext securityContext = new SecurityContextImpl(authResult);

                        // ★ 핵심 수정: WebSession을 명시적으로 가져와서 SecurityContext를 세션에 저장
                        return exchange.getSession()
                                .flatMap(session -> {
                                    // Spring Security가 세션에서 Context를 읽어갈 때 사용하는 기본 키 이름
                                    session.getAttributes().put("SPRING_SECURITY_CONTEXT", securityContext);

                                    return Mono.just(ResponseEntity.ok(Map.of(
                                            "message", (Object) "로그인 성공",
                                            "username", user.getUsername()
                                    )));
                                });
                    }
                    return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(Map.of("error", (Object) "아이디 또는 비밀번호가 올바르지 않습니다.")));
                })
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", (Object) "아이디 또는 비밀번호가 올바르지 않습니다.")));
    }

    /**
     * Invalidates security context and invalidates current WebSession.
     */
    @PostMapping("/logout")
    public Mono<ResponseEntity<Map<String, String>>> logout(ServerWebExchange exchange) {
        return securityContextRepository.save(exchange, null)
                .then(exchange.getSession().flatMap(WebSession::invalidate))
                .then(Mono.just(ResponseEntity.ok(Map.of("message", "로그아웃 되었습니다."))));
    }
}
