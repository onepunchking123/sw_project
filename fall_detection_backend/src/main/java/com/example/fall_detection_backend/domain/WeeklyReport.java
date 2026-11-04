package com.example.fall_detection_backend.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "weekly_reports", uniqueConstraints = @UniqueConstraint(columnNames = { "patient_id", "week_start" }))
public class WeeklyReport {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "patient_id", nullable = false)
	private UUID patientId;

	@Column(name = "week_start", nullable = false)
	private LocalDate weekStart;

	@Column(name = "abnormal_count", nullable = false)
	private Integer abnormalCount = 0;

	@Column(name = "fall_count", nullable = false)
	private Integer fallCount = 0;

	@Column(name = "uptime_percent", nullable = false)
	private Float uptimePercent = 0f;

	@Column(name = "avg_active_hours")
	private Float avgActiveHours;

	@Column(name = "avg_lying_night_hours")
	private Float avgLyingNightHours;

	@Column(name = "summary_text", columnDefinition = "text")
	private String summaryText;

	@Column(name = "pdf_key", length = 512)
	private String pdfKey;

	@Column(name = "generated_at", nullable = false)
	private Instant generatedAt = Instant.now();
}
