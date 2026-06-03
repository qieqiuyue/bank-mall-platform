package com.bank.auth.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public AuthMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordLogin(String status) {
        counter("login_attempts_total", "status", status).increment();
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return counters.computeIfAbsent(name + ":" + tagValue,
                k -> Counter.builder(name).description("Login attempts by result")
                        .tag(tagKey, tagValue).register(registry));
    }
}
