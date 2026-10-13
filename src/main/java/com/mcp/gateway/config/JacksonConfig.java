package com.mcp.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
public class JacksonConfig { // 또는 기존 설정 클래스 내부에 추가

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        // 날짜/시간(JSR310) 직렬화를 위해 필요시 모듈 등록
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }
}