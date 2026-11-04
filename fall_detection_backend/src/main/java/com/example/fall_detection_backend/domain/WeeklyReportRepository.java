package com.example.fall_detection_backend.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, UUID> {
}
