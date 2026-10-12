package com.mcp.gateway.controller.admin;

import com.mcp.gateway.domain.McpServerEntity;
import com.mcp.gateway.repository.McpServerRepository;
import com.mcp.gateway.repository.McpServerToolHashRepository;
import com.mcp.gateway.repository.McpToolsRepository;
import com.mcp.gateway.security.SsrfProtectionValidator;
import com.mcp.gateway.service.DynamicCircuitBreakerManager;
import com.mcp.gateway.service.ToolRegistryService;
import com.mcp.gateway.service.ToolSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServerControllerTest {

    @Mock
    private McpServerRepository serverRepository;

    @Mock
    private SsrfProtectionValidator ssrfValidator;

    @Mock
    private DynamicCircuitBreakerManager circuitBreakerManager;

    @Mock
    private ToolRegistryService toolRegistryService;

    @Mock
    private McpToolsRepository toolsRepository;

    @Mock
    private McpServerToolHashRepository hashRepository;

    @Mock
    private ToolSyncService toolSyncService;

    private AdminServerController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminServerController(
                serverRepository,
                ssrfValidator,
                circuitBreakerManager,
                toolRegistryService,
                toolsRepository,
                hashRepository,
                toolSyncService
        );
    }

    @Test
    @DisplayName("서버명 변경 시 도구 네임스페이스 갱신, 해시 삭제, 캐시 재구성이 호출되어야 한다")
    void testUpdateServerNameChanged() {
        long serverId = 100L;
        McpServerEntity existing = new McpServerEntity();
        existing.setId(serverId);
        existing.setName("old-server");
        existing.setTransportType("HTTP");
        existing.setEndpointUrl("http://localhost:9000/mcp");

        McpServerEntity updateReq = new McpServerEntity();
        updateReq.setName("new-server");
        updateReq.setTransportType("HTTP");
        updateReq.setEndpointUrl("http://localhost:9000/mcp");

        when(serverRepository.findById(serverId)).thenReturn(Mono.just(existing));
        when(serverRepository.save(any(McpServerEntity.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(toolsRepository.updateServerNameAndNamespace(eq(serverId), eq("new-server"))).thenReturn(Mono.just(3L));
        when(hashRepository.deleteByServerId(serverId)).thenReturn(Mono.empty());
        when(toolSyncService.rebuildCacheNow()).thenReturn(Mono.just(Collections.emptyList()));

        Mono<ResponseEntity<Object>> resultMono = controller.updateServer(serverId, updateReq);

        StepVerifier.create(resultMono)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    McpServerEntity saved = (McpServerEntity) response.getBody();
                    assertThat(saved.getName()).isEqualTo("new-server");
                })
                .verifyComplete();

        verify(circuitBreakerManager).refreshCache(any(McpServerEntity.class));
        verify(circuitBreakerManager).evictCache("old-server");
        verify(toolsRepository).updateServerNameAndNamespace(serverId, "new-server");
        verify(hashRepository).deleteByServerId(serverId);
        verify(toolSyncService).rebuildCacheNow();
    }

    @Test
    @DisplayName("서버명이 변경되지 않은 경우 도구 네임스페이스 갱신이 호출되지 않아야 한다")
    void testUpdateServerNameUnchanged() {
        long serverId = 100L;
        McpServerEntity existing = new McpServerEntity();
        existing.setId(serverId);
        existing.setName("same-server");
        existing.setTransportType("HTTP");
        existing.setEndpointUrl("http://localhost:9000/mcp");

        McpServerEntity updateReq = new McpServerEntity();
        updateReq.setName("same-server");
        updateReq.setTransportType("HTTP");
        updateReq.setEndpointUrl("http://localhost:9000/mcp");

        when(serverRepository.findById(serverId)).thenReturn(Mono.just(existing));
        when(serverRepository.save(any(McpServerEntity.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        Mono<ResponseEntity<Object>> resultMono = controller.updateServer(serverId, updateReq);

        StepVerifier.create(resultMono)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                })
                .verifyComplete();

        verify(circuitBreakerManager).refreshCache(any(McpServerEntity.class));
        verify(circuitBreakerManager, never()).evictCache(anyString());
        verify(toolsRepository, never()).updateServerNameAndNamespace(anyLong(), anyString());
        verify(hashRepository, never()).deleteByServerId(anyLong());
        verify(toolSyncService, never()).rebuildCacheNow();
    }
}
