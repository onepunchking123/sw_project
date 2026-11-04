package com.example.fall_detection_backend.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.fall_detection_backend.domain.DomainEnums.EventKind;
import com.example.fall_detection_backend.domain.DomainEnums.EventRule;
import com.example.fall_detection_backend.domain.DomainEnums.PoseState;
import com.example.fall_detection_backend.domain.DomainEnums.ReviewLabel;

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
@Table(name = "detection_events")
public class DetectionEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "client_event_id", nullable = false, unique = true)
	private UUID clientEventId;

	@Column(name = "patient_id", nullable = false)
	private UUID patientId;

	@Column(name = "camera_device_id", nullable = false)
	private UUID cameraDeviceId;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "event_kind")
	private EventKind kind;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "event_rule")
	private EventRule rule;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "pose_state")
	private PoseState state;

	@Column(name = "prob_normal", nullable = false)
	private Float probNormal;

	@Column(name = "prob_fall", nullable = false)
	private Float probFall;

	@Column(name = "prob_fallen", nullable = false)
	private Float probFallen;

	@Column(name = "duration_sec")
	private Float durationSec;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String keypoints;

	@Column(name = "clip_id")
	private UUID clipId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "reviewed_as", columnDefinition = "review_label")
	private ReviewLabel reviewedAs;

	@Column(name = "reviewed_by_user_id")
	private UUID reviewedByUserId;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();
}
