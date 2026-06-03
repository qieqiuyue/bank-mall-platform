package com.bank.account.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class AccountMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public AccountMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordDebit() { counter("account_debit_total").increment(); }
    public void recordCredit() { counter("account_credit_total").increment(); }
    public void recordReverse() { counter("account_reverse_total").increment(); }

    private Counter counter(String name) {
        return counters.computeIfAbsent(name,
                k -> Counter.builder(name).description("Account operation count").register(registry));
    }
}
