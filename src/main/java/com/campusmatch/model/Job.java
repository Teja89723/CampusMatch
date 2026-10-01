package com.campusmatch.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "jobs", uniqueConstraints = @UniqueConstraint(name = "uk_jobs_source_external", columnNames = {"source", "externalId"}))
public class Job {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false) public String title;
    @Column(nullable = false) public String company;
    public String companyDomain;
    public String location;
    @Column(length = 10) public String workMode = "ONSITE";      // REMOTE | HYBRID | ONSITE
    @Column(length = 12) public String jobType = "FULLTIME";     // INTERNSHIP | FULLTIME | PARTTIME | CONTRACT | TEMPORARY
    @Column(length = 10) public String seniority = "ENTRY";      // INTERN | ENTRY | MID | SENIOR
    @Column(length = 5000) public String description;
    @Column(nullable = false, length = 1000) public String applyUrl;
    @Column(length = 12) public String source = "EMPLOYER";      // EMPLOYER | ADMIN | SAMPLE | LEVER | ASHBY
    @Column(length = 180) public String externalId;
    public Instant lastSeenAt;
    public boolean active = true;
    public Instant postedAt = Instant.now();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "job_required_skills", joinColumns = @JoinColumn(name = "job_id"))
    @Column(name = "skill", length = 60)
    public Set<String> requiredSkills = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "job_preferred_skills", joinColumns = @JoinColumn(name = "job_id"))
    @Column(name = "skill", length = 60)
    public Set<String> preferredSkills = new LinkedHashSet<>();
}
