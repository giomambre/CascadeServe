package com.giomambretti.cascadeserve.controlplane;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class CircuitBreaker {
    private final int failureThreshold;
    private final long cooldownNanos;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong openUntilNanos = new AtomicLong();

    CircuitBreaker(int failureThreshold, long cooldownMs) {
        if (failureThreshold <= 0 || cooldownMs <= 0) {
            throw new IllegalArgumentException("circuit breaker values must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.cooldownNanos = TimeUnit.MILLISECONDS.toNanos(cooldownMs);
    }

    boolean allowsRequest() {
        return System.nanoTime() >= openUntilNanos.get();
    }

    void recordSuccess() {
        failures.set(0);
        openUntilNanos.set(0);
    }

    void recordFailure() {
        if (failures.incrementAndGet() >= failureThreshold) {
            openUntilNanos.set(System.nanoTime() + cooldownNanos);
            failures.set(0);
        }
    }
}
