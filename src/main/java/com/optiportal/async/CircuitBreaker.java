package com.optiportal.async;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Circuit breaker implementation for world thread protection.
 * 
 * This component prevents world thread overload by implementing a circuit
 * breaker pattern that opens when too many failures occur.
 */
public class CircuitBreaker {
    
    private static final Logger LOG = Logger.getLogger("OptiPortal");
    
    // Circuit breaker states
    public enum State {
        CLOSED,    // Normal operation
        OPEN,      // Circuit is open, blocking operations
        HALF_OPEN  // Testing if circuit should close
    }
    
    // Configuration
    private static final int FAILURE_THRESHOLD = 5;      // Open after 5 failures
    private static final long RECOVERY_TIMEOUT_MS = 30000; // 30 seconds recovery
    private static final int SUCCESS_THRESHOLD = 3;      // Close after 3 successes in half-open
    
    // State tracking
    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong lastStateChange = new AtomicLong(System.currentTimeMillis());
    
    /**
     * Record a successful operation.
     */
    public void recordSuccess() {
        if (state == State.HALF_OPEN) {
            int successes = successCount.incrementAndGet();
            if (successes >= SUCCESS_THRESHOLD) {
                closeCircuit();
            }
        } else if (state == State.CLOSED) {
            // Reset failure count on success in closed state
            failureCount.set(0);
        }
    }
    
    /**
     * Record a failed operation.
     */
    public void recordFailure() {
        int failures = failureCount.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        
        if (state == State.CLOSED && failures >= FAILURE_THRESHOLD) {
            openCircuit();
        } else if (state == State.HALF_OPEN) {
            // Any failure in half-open state opens the circuit again
            openCircuit();
        }
    }
    
    /**
     * Check if the circuit breaker is currently open.
     * 
     * @return true if circuit is open
     */
    public boolean isOpen() {
        if (state == State.OPEN) {
            // Check if recovery timeout has passed
            long timeSinceOpen = System.currentTimeMillis() - lastStateChange.get();
            if (timeSinceOpen >= RECOVERY_TIMEOUT_MS) {
                // Transition to half-open to test recovery
                state = State.HALF_OPEN;
                successCount.set(0);
                lastStateChange.set(System.currentTimeMillis());
                LOG.info("Circuit breaker transitioning to HALF_OPEN");
                return false;
            }
            return true;
        }
        return false;
    }
    
    /**
     * Check if we can attempt to reset the circuit.
     * 
     * @return true if reset can be attempted
     */
    public boolean canAttemptReset() {
        return state == State.OPEN && 
               (System.currentTimeMillis() - lastStateChange.get()) >= RECOVERY_TIMEOUT_MS;
    }
    
    /**
     * Attempt to reset the circuit breaker.
     */
    public void attemptReset() {
        if (canAttemptReset()) {
            state = State.HALF_OPEN;
            successCount.set(0);
            lastStateChange.set(System.currentTimeMillis());
            LOG.info("Circuit breaker reset attempt - transitioning to HALF_OPEN");
        }
    }
    
    /**
     * Force the circuit breaker open.
     */
    public void open() {
        openCircuit();
    }
    
    /**
     * Reset the circuit breaker to closed state.
     */
    public void reset() {
        closeCircuit();
    }
    
    /**
     * Get current circuit breaker state.
     * 
     * @return Current state
     */
    public State getState() {
        return state;
    }
    
    /**
     * Get circuit breaker statistics.
     * 
     * @return Statistics
     */
    public CircuitBreakerStats getStats() {
        return new CircuitBreakerStats(
            state,
            failureCount.get(),
            successCount.get(),
            lastFailureTime.get(),
            lastStateChange.get()
        );
    }
    
    /**
     * Open the circuit breaker.
     */
    private void openCircuit() {
        state = State.OPEN;
        lastStateChange.set(System.currentTimeMillis());
        LOG.warning("Circuit breaker OPENED due to " + failureCount.get() + " failures");
    }
    
    /**
     * Close the circuit breaker.
     */
    private void closeCircuit() {
        state = State.CLOSED;
        failureCount.set(0);
        successCount.set(0);
        lastStateChange.set(System.currentTimeMillis());
        LOG.info("Circuit breaker CLOSED - normal operation resumed");
    }
    
    /**
     * Circuit breaker statistics data class.
     */
    public static class CircuitBreakerStats {
        public final State state;
        public final int failureCount;
        public final int successCount;
        public final long lastFailureTime;
        public final long lastStateChange;
        
        public CircuitBreakerStats(State state, int failureCount, int successCount,
                                  long lastFailureTime, long lastStateChange) {
            this.state = state;
            this.failureCount = failureCount;
            this.successCount = successCount;
            this.lastFailureTime = lastFailureTime;
            this.lastStateChange = lastStateChange;
        }
        
        @Override
        public String toString() {
            return String.format(
                "CircuitBreakerStats{state=%s, failures=%d, successes=%d, lastFailure=%d, lastChange=%d}",
                state, failureCount, successCount, lastFailureTime, lastStateChange
            );
        }
    }
}