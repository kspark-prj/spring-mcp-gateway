package com.mcp.gateway.transport.http;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 저오버헤드 HTTP 및 SSE 스트림 포워딩을 위한 Netty 데이터 버퍼 패스스루 프록시입니다.
 * <p>
 * 리액티브 배압(Backpressure) 스트리밍을 보장하고, 최대 페이로드 크기 제한(10MB)을 강제하며,
 * 읽기 타임아웃을 적용하고, DirectByteBuf 메모리 누수를 방지하기 위해
 * 명시적인 메모리 버퍼 해제(DataBufferUtils.release)를 보장합니다.
 * </p>
 */
@Component
public class NettyDataBufferPassthroughProxy {

    private static final Logger log = LoggerFactory.getLogger(NettyDataBufferPassthroughProxy.class);

    // 최대 페이로드 제한: 10 메가바이트
    public static final long MAX_PAYLOAD_BYTES = 10 * 1024 * 1024;

    // 배압 제어를 위한 최대 미읽음 청크 버퍼 크기 제한
    private static final int BACKPRESSURE_BUFFER_SIZE = 256;

    /**
     * 메모리 해제 및 최대 바이트 수 검증을 포함하여 들어오는 DataBuffer 스트림을 안전하게 처리합니다.
     *
     * @param bodyStream DataBuffer 청크들의 리액티브 Flux 스트림
     * @param timeoutMs 밀리초 단위의 읽기 타임아웃
     * @return 전체 집계된 페이로드의 바이트 배열을 방출하는 Mono
     */
    public Mono<byte[]> aggregateAndRelease(Flux<DataBuffer> bodyStream, long timeoutMs) {
        AtomicLong byteCounter = new AtomicLong(0);

        return DataBufferUtils.join(bodyStream, (int) MAX_PAYLOAD_BYTES)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(dataBuffer -> {
                    try {
                        int readable = dataBuffer.readableByteCount();
                        if (byteCounter.addAndGet(readable) > MAX_PAYLOAD_BYTES) {
                            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "페이로드가 최대 10MB 제한을 초과했습니다.");
                        }
                        byte[] bytes = new byte[readable];
                        dataBuffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                })
                .defaultIfEmpty(new byte[0])
                .onErrorMap(DataBufferLimitException.class,
                        ex -> new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "페이로드가 최대 10MB 제한을 초과했습니다.", ex))
                .doOnError(ex -> log.error("Netty DataBuffer 스트림 소비 중 에러 또는 타임아웃 발생", ex));
    }

    /**
     * 기본 타임아웃(30초)을 적용하는 오버로드된 패스스루 스트림 메서드입니다.
     * 인자를 하나만 받는 기존 호출부와 호환됩니다.
     *
     * @param sourceStream WebClient 응답으로부터 들어오는 원본 DataBuffer Flux
     * @return 메모리가 관리되고 배압이 조절되는 DataBuffer 스트림
     */
    public Flux<DataBuffer> safePassthrough(Flux<DataBuffer> sourceStream) {
        return safePassthrough(sourceStream, 30000L); // 기본 타임아웃 30초 설정
    }

    /**
     * 청크별 버퍼 해제 보호와 명시적 리액티브 배압 제어가 적용된
     * SSE / 청크 HTTP 포워딩용 패스스루 Flux 스트림입니다.
     *
     * @param sourceStream WebClient 응답으로부터 들어오는 원본 DataBuffer Flux
     * @param idleTimeoutMs 스트림 청크에 대한 유휴 타임아웃 지속 시간 (밀리초)
     * @return 메모리가 관리되고 배압이 조절되는 DataBuffer 스트림
     */
    public Flux<DataBuffer> safePassthrough(Flux<DataBuffer> sourceStream, long idleTimeoutMs) {
        return sourceStream
                // 수신 속도가 느릴 때 메모리가 과도하게 쌓이지 않도록 버퍼 크기 제한 (배압 적용)
                .onBackpressureBuffer(
                        BACKPRESSURE_BUFFER_SIZE,
                        buffer -> DataBufferUtils.release(buffer), // 버퍼 초과 시 드롭되는 데이터의 메모리 누수 방지 해제
                        BufferOverflowStrategy.ERROR
                )
                // 스트림 전송 중 지정된 시간 동안 데이터가 들어오지 않으면 타임아웃 처리
                .timeout(Duration.ofMillis(idleTimeoutMs))
                // 다운스트림 취소나 에러 등으로 인해 버려지는 데이터 버퍼를 명시적으로 해제
                .doOnDiscard(DataBuffer.class, buffer -> {
                    log.warn("DataBuffer가 폐기되었습니다. 메모리를 명시적으로 해제합니다.");
                    DataBufferUtils.release(buffer);
                })
                .doOnError(ex -> log.error("스트림 패스스루(SSE/HTTP 청크) 중 에러 발생", ex));
    }
}