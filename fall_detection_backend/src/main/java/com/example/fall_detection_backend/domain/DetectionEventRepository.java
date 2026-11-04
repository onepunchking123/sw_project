package com.example.fall_detection_backend.domain;

import java.util.UUID;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DetectionEventRepository extends JpaRepository<DetectionEvent, UUID> {
    Optional<DetectionEvent> findByClientEventId(UUID clientEventId);
}
