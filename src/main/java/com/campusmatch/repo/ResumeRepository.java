package com.campusmatch.repo;

import com.campusmatch.model.Resume;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumeRepository extends JpaRepository<Resume, Long> {
    List<Resume> findByUserIdOrderByUploadedAtDesc(Long userId);
    Optional<Resume> findByIdAndUserId(Long id, Long userId);
    Optional<Resume> findFirstByUserIdAndSha256(Long userId, String sha256);
    Optional<Resume> findFirstByUserIdAndActiveTrue(Long userId);
    long countByUserIdAndUploadedAtAfter(Long userId, Instant after);
    long countByUserId(Long userId);
}
