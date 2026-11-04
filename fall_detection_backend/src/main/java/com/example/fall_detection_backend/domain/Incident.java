package com.example.fall_detection_backend.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.fall_detection_backend.domain.DomainEnums.IncidentResolution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "incidents")
public class Incident {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "detection_event_id", nullable = false, unique = true)
	private UUID detectionEventId;

	@Column(name = "patient_id", nullable = false)
	private UUID patientId;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "countdown_ends_at", nullable = false)
	private Instant countdownEndsAt;

	@Column(name = "patient_responded", nullable = false)
	private Boolean patientResponded = false;

	@Column(name = "patient_responded_at")
	private Instant patientRespondedAt;

	@Column(name = "auto_notified_at")
	private Instant autoNotifiedAt;

	@Column(name = "resolved_at")
	private Instant resolvedAt;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(columnDefinition = "incident_resolution")
	private IncidentResolution resolution;

	@Column(name = "resolved_by_user_id")
	private UUID resolvedByUserId;

	@Column(name = "resolution_note", columnDefinition = "text")
	private String resolutionNote;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();
}
