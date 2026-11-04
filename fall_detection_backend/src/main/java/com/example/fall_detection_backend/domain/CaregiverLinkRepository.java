package com.example.fall_detection_backend.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CaregiverLinkRepository extends JpaRepository<CaregiverLink, UUID> {
    boolean existsByPatientIdAndUserId(UUID patientId, UUID userId);
}
