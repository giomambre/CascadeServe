package com.giomambretti.cascadeserve.controlplane;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {
    @Test
    void opensAfterRepeatedFailuresAndAllowsAProbeAfterCooldown() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker(2, 25);

        breaker.recordFailure();
        assertTrue(breaker.allowsRequest());
        breaker.recordFailure();
        assertFalse(breaker.allowsRequest());

        Thread.sleep(40);
        assertTrue(breaker.allowsRequest());
        breaker.recordSuccess();
        assertTrue(breaker.allowsRequest());
    }
}
