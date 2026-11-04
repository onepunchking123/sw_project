package com.example.fall_detection_backend.domain;

import java.time.Instant;
import java.util.UUID;

import com.example.fall_detection_backend.domain.DomainEnums.NoteKind;
import com.example.fall_detection_backend.domain.DomainEnums.PatientState;

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
@Table(name = "event_notes")
public class EventNote {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "detection_event_id")
	private UUID detectionEventId;

	@Column(name = "care_record_id")
	private UUID careRecordId;

	@Column(name = "author_user_id", nullable = false)
	private UUID authorUserId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private NoteKind kind;

	@Enumerated(EnumType.STRING)
	@Column(name = "patient_state", length = 16)
	private PatientState patientState;

	@Column(nullable = false, columnDefinition = "text")
	private String memo;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();
}
