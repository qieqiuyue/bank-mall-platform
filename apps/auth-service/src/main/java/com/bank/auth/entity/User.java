package com.bank.auth.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(name = "user_id", unique = true, length = 32)
    private String userId;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @Column(length = 32)
    private String level;

    @Column(name = "risk_level", length = 16)
    private String riskLevel;

    @Column(length = 128)
    private String roles;

    public User() {}

    public User(String username, String password, String userId, String displayName, String level, String riskLevel, String roles) {
        this.username = username;
        this.password = password;
        this.userId = userId;
        this.displayName = displayName;
        this.level = level;
        this.riskLevel = riskLevel;
        this.roles = roles;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getUserId() { return userId; }
    public String getDisplayName() { return displayName; }
    public String getLevel() { return level; }
    public String getRiskLevel() { return riskLevel; }
    public String getRoles() { return roles; }

    public void setUserId(String userId) { this.userId = userId; }
}
