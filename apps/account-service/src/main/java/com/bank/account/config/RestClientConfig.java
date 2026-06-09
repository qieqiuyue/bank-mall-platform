package com.bank.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * RestClient 配置. Spring Boot 4.0 / Spring Framework 7.0 推荐 HTTP 客户端.
 */
@Configuration
public class RestClientConfig {

    @Bean
    RestClient restClient() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(2));
        factory.setReadTimeout(java.time.Duration.ofSeconds(5));

        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("X-Service-Name", "account-service")
                .build();
    }
}
