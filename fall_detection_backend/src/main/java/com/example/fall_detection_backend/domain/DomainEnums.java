package com.example.fall_detection_backend.domain;

public final class DomainEnums {

	private DomainEnums() {
	}

	public enum CaregiverRole {
		OWNER,
		MEMBER
	}

	public enum CaregiverRelation {
		CHILD,
		SPOUSE,
		SIBLING,
		RELATIVE,
		ACQUAINTANCE,
		OTHER
	}

	public enum PrivacyMode {
		VIDEO_SKELETON,
		SKELETON_ONLY,
		NO_OVERLAY
	}

	public enum DeviceStatus {
		ONLINE,
		OFFLINE,
		UNPAIRED
	}

	public enum EventKind {
		FALL,
		FALLEN_IMMOBILE,
		STILL_HORIZONTAL
	}

	public enum EventRule {
		MODEL,
		R1,
		R2
	}

	public enum PoseState {
		NORMAL,
		FALL,
		FALLEN,
		NO_PERSON
	}

	public enum ReviewLabel {
		CONFIRMED,
		FALSE_POSITIVE
	}

	public enum Posture {
		UPRIGHT,
		LYING,
		ABSENT
	}

	public enum ClipStatus {
		UPLOADING,
		READY,
		DELETED
	}

	public enum IncidentResolution {
		PATIENT_OK,
		CAREGIVER_OK,
		EMERGENCY_CALL,
		FALSE_POSITIVE
	}

	public enum ResponseStatus {
		NOTIFIED,
		ACKNOWLEDGED,
		RESPONDING
	}

	public enum RecordCategory {
		SYMPTOM,
		MEDICATION,
		DOSE,
		ENVIRONMENT,
		VISIT,
		OTHER
	}

	public enum ConditionLevel {
		NORMAL,
		SLIGHTLY_UNSTABLE,
		DIZZY,
		GAIT,
		APPETITE,
		SLEEP,
		MEMORY,
		FALL
	}

	public enum NoteKind {
		CALL,
		VISIT,
		HOSPITAL,
		OBSERVE,
		ETC
	}

	public enum PatientState {
		FINE,
		PAIN,
		SLEEPY,
		UNSURE
	}
}
