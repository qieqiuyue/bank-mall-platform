package com.bank.payment.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Payment business metrics exposed to Prometheus via Micrometer.
 * No extra dependency — micrometer-registry-prometheus already in pom.xml.
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Record a payment attempt with its final status. */
    public void recordPayment(String status) {
        counter("payment_requests_total", "status", status).increment();
    }

    /** Record payment processing duration in milliseconds. */
    public void recordDuration(long millis) {
        timer("payment_duration_seconds").record(millis, TimeUnit.MILLISECONDS);
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return counters.computeIfAbsent(name + ":" + tagValue,
                k -> Counter.builder(name)
                        .description("Payment requests by status")
                        .tag(tagKey, tagValue)
                        .register(registry));
    }

    private Timer timer(String name) {
        return timers.computeIfAbsent(name,
                k -> Timer.builder(name)
                        .description("Payment processing duration")
                        .register(registry));
    }
}
