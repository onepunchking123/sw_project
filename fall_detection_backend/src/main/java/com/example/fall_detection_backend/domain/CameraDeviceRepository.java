package com.example.fall_detection_backend.domain;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CameraDeviceRepository extends JpaRepository<CameraDevice, UUID> {
    Optional<CameraDevice> findByDeviceToken(String deviceToken);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CameraDevice c where c.id = :id")
    Optional<CameraDevice> findLockedById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CameraDevice c where c.deviceToken = :deviceToken")
    Optional<CameraDevice> findLockedByDeviceToken(String deviceToken);
}
