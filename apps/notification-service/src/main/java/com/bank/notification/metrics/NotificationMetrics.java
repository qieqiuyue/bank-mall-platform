package com.bank.notification.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class NotificationMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSent(String status) {
        counter("notifications_sent_total", "status", status).increment();
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return counters.computeIfAbsent(name + ":" + tagValue,
                k -> Counter.builder(name).description("Notifications sent by status")
                        .tag(tagKey, tagValue).register(registry));
    }
}
