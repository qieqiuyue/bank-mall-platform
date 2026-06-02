package com.bank.payment.config;

import org.springframework.context.annotation.Configuration;

/**
 * No seed data needed for payment-service.
 * Payments are created dynamically via API requests.
 */
@Configuration
public class DataInitializer {
    // Intentionally empty — payment records created by incoming requests
}
