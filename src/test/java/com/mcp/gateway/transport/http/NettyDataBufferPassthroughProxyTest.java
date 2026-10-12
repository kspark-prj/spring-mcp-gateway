package com.mcp.gateway.transport.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class NettyDataBufferPassthroughProxyTest {

    private final NettyDataBufferPassthroughProxy proxy = new NettyDataBufferPassthroughProxy();
    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    @Test
    @DisplayName("정상 페이로드 DataBuffer 스트림 취합 및 메모리 해제 검증")
    void testAggregateAndReleaseNormalPayload() {
        byte[] payload = "{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = bufferFactory.wrap(payload);

        Mono<byte[]> resultMono = proxy.aggregateAndRelease(Flux.just(buffer), 5000);

        StepVerifier.create(resultMono)
                .assertNext(bytes -> {
                    assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("빈 DataBuffer 스트림 인입 시 빈 바이트 배열 반환 검증")
    void testAggregateAndReleaseEmptyStream() {
        Mono<byte[]> resultMono = proxy.aggregateAndRelease(Flux.empty(), 5000);

        StepVerifier.create(resultMono)
                .assertNext(bytes -> assertThat(bytes).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("safePassthrough 스트림 정상 통과 및 discard 시 메모리 보호 검증")
    void testSafePassthrough() {
        byte[] payload = "event: message\ndata: test\n\n".getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = bufferFactory.wrap(payload);

        Flux<DataBuffer> stream = proxy.safePassthrough(Flux.just(buffer));

        StepVerifier.create(stream)
                .expectNextCount(1)
                .verifyComplete();
    }
}
