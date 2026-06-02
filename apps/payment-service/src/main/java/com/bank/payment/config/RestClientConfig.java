package com.bank.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient 配置. Spring Boot 4.0 / Spring Framework 7.0 推荐 HTTP 客户端.
 * 连接超时 2s，读取超时 3s —— 支付链路不阻塞过久.
 */
@Configuration
public class RestClientConfig {

    @Bean
    RestClient restClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));

        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("X-Service-Name", "payment-service")
                .build();
    }
}
