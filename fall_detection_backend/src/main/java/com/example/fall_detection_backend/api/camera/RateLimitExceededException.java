package com.example.fall_detection_backend.api.camera;

public class RateLimitExceededException extends RuntimeException {
	private final long retryAfterSeconds;

	RateLimitExceededException(String message, long retryAfterSeconds) {
		super(message);
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}
}
