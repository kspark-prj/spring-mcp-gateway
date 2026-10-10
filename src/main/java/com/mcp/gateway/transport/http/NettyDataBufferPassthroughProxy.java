package com.mcp.gateway.transport.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Netty DataBuffer Passthrough Proxy for low-overhead HTTP & SSE stream forwarding.
 * <p>
 * Ensures reactive backpressure streaming, enforces maximum payload size limits (10MB),
 * applies read timeouts, and guarantees explicit memory buffer release (DataBufferUtils.release)
 * to prevent DirectByteBuf memory leaks.
 * </p>
 */
@Component
public class NettyDataBufferPassthroughProxy {

    private static final Logger log = LoggerFactory.getLogger(NettyDataBufferPassthroughProxy.class);

    // Maximum payload limit: 10 Megabytes
    public static final long MAX_PAYLOAD_BYTES = 10 * 1024 * 1024;

    /**
     * Safely processes incoming DataBuffer stream with memory release and max byte count validation.
     *
     * @param bodyStream reactive Flux of DataBuffer chunks
     * @param timeoutMs read timeout in milliseconds
     * @return Mono emitting byte array of total aggregated payload
     */
    public Mono<byte[]> aggregateAndRelease(Flux<DataBuffer> bodyStream, long timeoutMs) {
        AtomicLong byteCounter = new AtomicLong(0);

        return DataBufferUtils.join(bodyStream, (int) MAX_PAYLOAD_BYTES)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(dataBuffer -> {
                    try {
                        int readable = dataBuffer.readableByteCount();
                        if (byteCounter.addAndGet(readable) > MAX_PAYLOAD_BYTES) {
                            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Payload exceeds maximum 10MB limit");
                        }
                        byte[] bytes = new byte[readable];
                        dataBuffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                })
                .doOnError(ex -> log.error("Error or timeout consuming Netty DataBuffer stream", ex));
    }

    /**
     * Passthrough Flux stream for SSE / Chunked HTTP forwarding with per-chunk buffer release protection.
     *
     * @param sourceStream raw DataBuffer Flux from WebClient response
     * @return memory-managed DataBuffer stream
     */
    public Flux<DataBuffer> safePassthrough(Flux<DataBuffer> sourceStream) {
        return sourceStream
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                .doOnError(ex -> log.error("Error during streaming passthrough", ex));
    }
}
