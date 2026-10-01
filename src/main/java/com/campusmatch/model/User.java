package com.campusmatch.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, unique = true, length = 254)
    public String email;                       // always stored lower-case
    @Column(nullable = false)
    public String passwordHash;                // BCrypt, never the password
    @Column(nullable = false, length = 100)
    public String fullName;
    @Column(name = "user_role", nullable = false, length = 20)
    public String userRole = "STUDENT";
    public int failedLogins;
    public Instant lockedUntil;
    public Instant createdAt = Instant.now();
    public Instant consentAt;
    public boolean emailVerified = false;
    public boolean phoneVerified = false;
    @Column(length = 128) public String emailOtpHash;
    public Instant emailOtpExpiresAt;
    @Column(length = 128) public String phoneOtpHash;
    public Instant phoneOtpExpiresAt;
    public int verificationAttempts;
}
