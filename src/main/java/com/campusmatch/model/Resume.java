package com.campusmatch.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** Resume metadata only. The file itself is AES-GCM encrypted on disk; extracted text is NOT stored. */
@Entity
@Table(name = "resumes", indexes = @Index(columnList = "user_id"))
public class Resume {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "user_id", nullable = false) public Long userId;
    public String originalName;
    public String storagePath;                 // relative path of the encrypted file
    @Column(length = 64) public String sha256;
    public long sizeBytes;
    public String rubricVersion;
    public int atsTotal;
    @Column(length = 30000) public String atsJson;   // full score breakdown, evidence and warnings
    public boolean active;
    public Instant uploadedAt = Instant.now();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "resume_skills", joinColumns = @JoinColumn(name = "resume_id"))
    @Column(name = "skill", length = 60)
    public Set<String> skills = new LinkedHashSet<>();
}
