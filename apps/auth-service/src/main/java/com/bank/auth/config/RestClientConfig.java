package com.bank.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Spring Boot 4.0 / Spring Framework 7.0 RestClient 验证配置.
 * RestClient 从 Spring Framework 6.1 引入，在 7.0 中作为 HTTP 客户端首选方案.
 * 注意：Spring Boot 4.0 移除了 RestClientCustomizer，改用直接创建 RestClient Bean.
 */
@Configuration
public class RestClientConfig {

    @Bean
    RestClient restClient(RestClient.Builder builder) {
        return builder
                .defaultHeader("X-Service-Name", "auth-service")
                .build();
    }
}
