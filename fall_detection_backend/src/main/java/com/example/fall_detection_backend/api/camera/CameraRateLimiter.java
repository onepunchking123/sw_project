package com.example.fall_detection_backend.api.camera;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
class CameraRateLimiter {
	private static final int MAX_BUCKETS = 2_000;

	private final Clock clock;
	private final Map<String, RateLimitBucket> buckets = new ConcurrentHashMap<>();

	CameraRateLimiter(Clock clock) {
		this.clock = clock;
	}

	synchronized void check(String key, int limit, Duration window) {
		var now = Instant.now(clock);
		prune(now);
		if (!buckets.containsKey(key) && buckets.size() >= MAX_BUCKETS) {
			throw tooManyRequests(nextRetryAfterSeconds(now));
		}
		var bucket = buckets.compute(key, (ignored, existing) -> {
			if (existing == null || !now.isBefore(existing.resetAt())) {
				return new RateLimitBucket(1, now.plus(window));
			}
			return new RateLimitBucket(existing.count() + 1, existing.resetAt());
		});
		if (bucket.count() > limit) {
			var retryAfter = retryAfterSeconds(now, bucket.resetAt());
			throw tooManyRequests(retryAfter);
		}
	}

	private void prune(Instant now) {
		if (buckets.size() < MAX_BUCKETS) {
			return;
		}
		buckets.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().resetAt()));
	}

	private RateLimitExceededException tooManyRequests(long retryAfterSeconds) {
		return new RateLimitExceededException("요청이 너무 많습니다. 잠시 후 다시 시도해주세요.", retryAfterSeconds);
	}

	private long nextRetryAfterSeconds(Instant now) {
		return buckets.values().stream()
			.map(RateLimitBucket::resetAt)
			.min(Instant::compareTo)
			.map(resetAt -> retryAfterSeconds(now, resetAt))
			.orElse(60L);
	}

	private long retryAfterSeconds(Instant now, Instant resetAt) {
		var millis = Math.max(1, Duration.between(now, resetAt).toMillis());
		return Math.max(1, (millis + 999) / 1000);
	}
}
