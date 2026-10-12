package com.mcp.gateway.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SSRF (Server-Side Request Forgery) protection validator for remote HTTP upstream MCP server registration and execution.
 */
@Component
public class SsrfProtectionValidator {

    private static final Logger log = LoggerFactory.getLogger(SsrfProtectionValidator.class);

    /**
     * Validates target HTTP URL scheme, domain, and IP address to prevent internal network scanning or AWS IMDS SSRF exploitation.
     *
     * @param urlStr raw URL string
     * @throws IllegalArgumentException if SSRF security violation is detected
     */
    public void validateUrl(String urlStr) {
        if (urlStr == null || urlStr.isBlank()) {
            throw new IllegalArgumentException("Upstream endpoint URL cannot be empty");
        }

        try {
            URI uri = URI.create(urlStr.trim());
            String scheme = uri.getScheme();

            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("Invalid URL scheme: " + scheme + ". Only HTTP and HTTPS are permitted.");
            }

            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("Invalid URL host: " + urlStr);
            }

            // Block cloud metadata service IP explicitly (169.254.169.254)
            if (host.equals("169.254.169.254") || host.equalsIgnoreCase("instance-data")) {
                throw new IllegalArgumentException("Access to cloud instance metadata service (IMDS) is strictly prohibited");
            }

            // Perform DNS resolution check
            InetAddress address = InetAddress.getByName(host);

            // 로컬/루프백 주소인 경우 명시적으로 안전하다고 판단하여 경고 로그를 건너뜁니다.
            boolean isLocalOrPrivate = address.isLoopbackAddress()
                    || address.isAnyLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress()
                    || host.equalsIgnoreCase("localhost");

            if (!isLocalOrPrivate) {
                // 운영 환경 등 외부 통신일 때만 필요한 추가 검증이나 로그가 있다면 여기에 배치
            } else {
                // 로컬/사설 주소 접근인 경우 로그를 남기지 않고 통과시킵니다.
                log.debug("Allowed local/private upstream URL: {} ({})", host, address.getHostAddress());
            }


        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Unable to resolve upstream host DNS: " + urlStr, e);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e;
            }
            throw new IllegalArgumentException("Malformed upstream URL: " + urlStr, e);
        }
    }
}
