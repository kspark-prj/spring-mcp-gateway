package com.mcp.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.github.f4b6a3.tsid.Tsid;
import com.github.f4b6a3.tsid.TsidFactory;

import jakarta.annotation.PostConstruct;

@Configuration
@ConfigurationProperties(prefix = "mcp.gateway.tsid")
public class TsidConfig {

    // application.yml의 node-id가 이 변수에 자동으로 매핑됩니다.
    private int nodeId = 0;

    private static TsidFactory tsidFactory;

    // Setter (Spring @ConfigurationProperties 바인딩을 위해 필수)
    public void setNodeId(int nodeId) {
        this.nodeId = nodeId;
    }

    @PostConstruct
    public void init() {
        // 주입받은 node-id를 적용하여 TsidFactory 초기화
        tsidFactory = TsidFactory.builder()
                .withNode(nodeId)
                .build();
    }

    /**
     * application.yml의 node-id가 반영된 Long형 TSID 생성
     */
    public static Long nextLong() {
        if (tsidFactory == null) {
            tsidFactory = TsidFactory.builder().withNode(0).build();
        }
        return tsidFactory.create().toLong();
    }

    /**
     * application.yml의 node-id가 반영된 Tsid 객체 생성
     */
    public static Tsid nextTsid() {
        if (tsidFactory == null) {
            tsidFactory = TsidFactory.builder().withNode(0).build();
        }
        return tsidFactory.create();
    }
}