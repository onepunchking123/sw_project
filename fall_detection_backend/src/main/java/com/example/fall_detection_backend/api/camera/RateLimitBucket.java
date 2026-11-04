package com.example.fall_detection_backend.api.camera;

import java.time.Instant;

record RateLimitBucket(int count, Instant resetAt) {
}
