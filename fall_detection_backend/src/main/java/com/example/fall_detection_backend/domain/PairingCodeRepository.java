package com.example.fall_detection_backend.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PairingCodeRepository extends JpaRepository<PairingCode, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PairingCode p where p.code = :code")
    Optional<PairingCode> findLockedByCode(String code);

    List<PairingCode> findByCameraDeviceIdAndUsedAtIsNull(UUID cameraDeviceId);
}
