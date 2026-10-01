package com.campusmatch.repo;

import com.campusmatch.model.ApplicationRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<ApplicationRecord, Long> {
    Optional<ApplicationRecord> findByUserIdAndJobId(Long userId, Long jobId);
    List<ApplicationRecord> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
