package com.bank.payment.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "transaction_no", length = 32)
    private String transactionNo;

    @Column(name = "service_name", length = 32)
    private String serviceName;

    @Column(length = 10)
    private String direction;

    @Column(length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public PaymentTransaction() {}

    public PaymentTransaction(Payment payment, String transactionNo, String serviceName,
                              String direction, String status) {
        this.payment = payment;
        this.transactionNo = transactionNo;
        this.serviceName = serviceName;
        this.direction = direction;
        this.status = status;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Payment getPayment() { return payment; }
    public String getTransactionNo() { return transactionNo; }
    public String getServiceName() { return serviceName; }
    public String getDirection() { return direction; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
