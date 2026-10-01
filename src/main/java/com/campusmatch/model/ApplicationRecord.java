package com.campusmatch.model;

import jakarta.persistence.*;
import java.time.Instant;

/** The student's own tracker. The platform never submits applications; it only records clicks and self-reported status. */
@Entity
@Table(name = "applications", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "job_id"}))
public class ApplicationRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "user_id", nullable = false) public Long userId;
    @Column(name = "job_id", nullable = false) public Long jobId;
    @Column(length = 12) public String status = "CLICKED";   // CLICKED | APPLIED | INTERVIEW | OFFER | REJECTED
    public Instant clickedAt = Instant.now();
    public Instant updatedAt = Instant.now();
}
