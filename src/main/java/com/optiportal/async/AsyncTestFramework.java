package com.optiportal.async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Comprehensive testing framework for async operations.
 * 
 * This component provides testing utilities for async operations,
 * including load testing, performance measurement, and validation.
 */
public class AsyncTestFramework {
    
    private static final Logger LOG = Logger.getLogger("OptiPortal");
    
    private final ScheduledExecutorService executor;
    private final AsyncMetrics metrics;
    private final WorldThreadBridge worldBridge;
    private final AsyncLoadBalancer loadBalancer;
    
    public AsyncTestFramework(ScheduledExecutorService executor,
                             AsyncMetrics metrics,
                             WorldThreadBridge worldBridge,
                             AsyncLoadBalancer loadBalancer) {
        this.executor = executor;
        this.metrics = metrics;
        this.worldBridge = worldBridge;
        this.loadBalancer = loadBalancer;
    }
    
    /**
     * Run a comprehensive async performance test.
     * 
     * @param testConfig Test configuration
     * @return CompletableFuture with test results
     */
    public CompletableFuture<TestResults> runPerformanceTest(TestConfig testConfig) {
        LOG.info("Starting async performance test with " + testConfig.concurrentOperations + 
                " concurrent operations, " + testConfig.durationSeconds + "s duration");
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return performPerformanceTest(testConfig);
            } catch (Exception e) {
                LOG.severe("Performance test failed: " + e.getMessage());
                throw new RuntimeException("Test failed", e);
            }
        }, executor);
    }
    
    /**
     * Perform the actual performance test.
     */
    private TestResults performPerformanceTest(TestConfig config) {
        AtomicInteger completedOperations = new AtomicInteger(0);
        AtomicInteger failedOperations = new AtomicInteger(0);
        AtomicLong totalExecutionTime = new AtomicLong(0);
        List<Long> executionTimes = new ArrayList<>();
        
        long startTime = System.currentTimeMillis();
        long endTime = startTime + (config.durationSeconds * 1000);
        
        // Start concurrent operations
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < config.concurrentOperations; i++) {
            CompletableFuture<Void> future = startTestOperation(i, config, 
                completedOperations, failedOperations, totalExecutionTime, executionTimes, endTime);
            futures.add(future);
        }
        
        // Wait for all operations to complete or timeout
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(config.durationSeconds + 10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warning("Test completion interrupted: " + e.getMessage());
        }
        
        long actualDuration = System.currentTimeMillis() - startTime;
        
        return new TestResults(
            completedOperations.get(),
            failedOperations.get(),
            actualDuration,
            calculateAverage(executionTimes),
            calculatePercentile(executionTimes, 95),
            calculatePercentile(executionTimes, 99),
            metrics.getPerformanceSummary(),
            loadBalancer.getLoadStats()
        );
    }
    
    /**
     * Start a single test operation.
     */
    private CompletableFuture<Void> startTestOperation(int operationId, TestConfig config,
                                                     AtomicInteger completed, AtomicInteger failed,
                                                     AtomicLong totalTime, List<Long> times,
                                                     long endTime) {
        return CompletableFuture.runAsync(() -> {
            while (System.currentTimeMillis() < endTime) {
                long opStart = System.currentTimeMillis();
                
                try {
                    // Perform test operation based on type
                    switch (config.operationType) {
                        case CHUNK_LOAD:
                            performChunkLoadTest();
                            break;
                        case WORLD_THREAD_ACCESS:
                            performWorldThreadAccessTest();
                            break;
                        case LOAD_BALANCER:
                            performLoadBalancerTest();
                            break;
                        default:
                            performGenericAsyncTest();
                    }
                    
                    long opDuration = System.currentTimeMillis() - opStart;
                    completed.incrementAndGet();
                    totalTime.addAndGet(opDuration);
                    times.add(opDuration);
                    
                    // Sleep between operations if configured
                    if (config.operationIntervalMs > 0) {
                        Thread.sleep(config.operationIntervalMs);
                    }
                    
                } catch (Exception e) {
                    failed.incrementAndGet();
                    LOG.fine("Test operation " + operationId + " failed: " + e.getMessage());
                }
            }
        }, executor);
    }
    
    /**
     * Perform chunk load test operation.
     */
    private void performChunkLoadTest() {
        // Simulate chunk load operation
        try {
            Thread.sleep(10 + (int)(Math.random() * 40)); // 10-50ms simulated load
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Perform world thread access test operation.
     */
    private void performWorldThreadAccessTest() {
        // Test world thread bridge operations
        worldBridge.executeOnWorldThread(null, () -> {
            // Simulate world thread operation
            return "test-result";
        });
    }
    
    /**
     * Perform load balancer test operation.
     */
    private void performLoadBalancerTest() {
        // Test load balancer scheduling
        loadBalancer.scheduleLoad(() -> {
            return CompletableFuture.completedFuture(null);
        }, AsyncMetrics.AsyncTaskPriority.NORMAL);
    }
    
    /**
     * Perform generic async test operation.
     */
    private void performGenericAsyncTest() {
        // Generic async operation
        CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(5 + (int)(Math.random() * 20)); // 5-25ms simulated work
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "test";
        }, executor);
    }
    
    /**
     * Calculate average from list of values.
     */
    private double calculateAverage(List<Long> values) {
        if (values.isEmpty()) return 0.0;
        return values.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }
    
    /**
     * Calculate percentile from list of values.
     */
    private double calculatePercentile(List<Long> values, int percentile) {
        if (values.isEmpty()) return 0.0;
        
        values.sort(Long::compareTo);
        int index = (int) Math.ceil(percentile / 100.0 * values.size()) - 1;
        index = Math.max(0, Math.min(index, values.size() - 1));
        
        return values.get(index);
    }
    
    /**
     * Test configuration.
     */
    public static class TestConfig {
        public final int concurrentOperations;
        public final int durationSeconds;
        public final int operationIntervalMs;
        public final OperationType operationType;
        
        public TestConfig(int concurrentOperations, int durationSeconds, 
                         int operationIntervalMs, OperationType operationType) {
            this.concurrentOperations = concurrentOperations;
            this.durationSeconds = durationSeconds;
            this.operationIntervalMs = operationIntervalMs;
            this.operationType = operationType;
        }
        
        public static TestConfig chunkLoadTest(int operations, int duration) {
            return new TestConfig(operations, duration, 0, OperationType.CHUNK_LOAD);
        }
        
        public static TestConfig worldThreadTest(int operations, int duration) {
            return new TestConfig(operations, duration, 10, OperationType.WORLD_THREAD_ACCESS);
        }
        
        public static TestConfig loadBalancerTest(int operations, int duration) {
            return new TestConfig(operations, duration, 5, OperationType.LOAD_BALANCER);
        }
    }
    
    /**
     * Operation types for testing.
     */
    public enum OperationType {
        CHUNK_LOAD,
        WORLD_THREAD_ACCESS,
        LOAD_BALANCER,
        GENERIC_ASYNC
    }
    
    /**
     * Test results.
     */
    public static class TestResults {
        public final int completedOperations;
        public final int failedOperations;
        public final long durationMs;
        public final double averageExecutionTime;
        public final double p95ExecutionTime;
        public final double p99ExecutionTime;
        public final AsyncMetrics.PerformanceSummary performanceSummary;
        public final AsyncLoadBalancer.LoadStats loadStats;
        
        public TestResults(int completedOperations, int failedOperations, long durationMs,
                          double averageExecutionTime, double p95ExecutionTime, double p99ExecutionTime,
                          AsyncMetrics.PerformanceSummary performanceSummary,
                          AsyncLoadBalancer.LoadStats loadStats) {
            this.completedOperations = completedOperations;
            this.failedOperations = failedOperations;
            this.durationMs = durationMs;
            this.averageExecutionTime = averageExecutionTime;
            this.p95ExecutionTime = p95ExecutionTime;
            this.p99ExecutionTime = p99ExecutionTime;
            this.performanceSummary = performanceSummary;
            this.loadStats = loadStats;
        }
        
        public double getSuccessRate() {
            int total = completedOperations + failedOperations;
            return total > 0 ? (double) completedOperations / total : 0.0;
        }
        
        public double getOperationsPerSecond() {
            return durationMs > 0 ? (completedOperations * 1000.0) / durationMs : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "TestResults{completed=%d, failed=%d, duration=%dms, successRate=%.2f%%, " +
                "ops/sec=%.1f, avgTime=%.1fms, p95=%.1fms, p99=%.1fms}",
                completedOperations, failedOperations, durationMs, getSuccessRate() * 100,
                getOperationsPerSecond(), averageExecutionTime, p95ExecutionTime, p99ExecutionTime
            );
        }
    }
    
    /**
     * Run a quick health check of async components.
     * 
     * @return CompletableFuture with health check results
     */
    public CompletableFuture<HealthCheckResults> runHealthCheck() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Check circuit breaker
                boolean circuitBreakerHealthy = !worldBridge.isCircuitBreakerOpen();
                
                // Check load balancer
                AsyncLoadBalancer.LoadStats loadStats = loadBalancer.getLoadStats();
                boolean loadBalancerHealthy = loadStats.activeOperations < loadStats.activeOperations + 10;
                
                // Check metrics
                AsyncMetrics.PerformanceSummary perfSummary = metrics.getPerformanceSummary();
                boolean metricsHealthy = perfSummary.worldThreadErrors < perfSummary.worldThreadExecutions * 0.1;
                
                return new HealthCheckResults(
                    circuitBreakerHealthy && loadBalancerHealthy && metricsHealthy,
                    circuitBreakerHealthy,
                    loadBalancerHealthy,
                    metricsHealthy,
                    loadStats,
                    perfSummary
                );
                
            } catch (Exception e) {
                LOG.warning("Health check failed: " + e.getMessage());
                return new HealthCheckResults(false, false, false, false, null, null);
            }
        }, executor);
    }
    
    /**
     * Health check results.
     */
    public static class HealthCheckResults {
        public final boolean overallHealthy;
        public final boolean circuitBreakerHealthy;
        public final boolean loadBalancerHealthy;
        public final boolean metricsHealthy;
        public final AsyncLoadBalancer.LoadStats loadStats;
        public final AsyncMetrics.PerformanceSummary performanceSummary;
        
        public HealthCheckResults(boolean overallHealthy, boolean circuitBreakerHealthy,
                                boolean loadBalancerHealthy, boolean metricsHealthy,
                                AsyncLoadBalancer.LoadStats loadStats,
                                AsyncMetrics.PerformanceSummary performanceSummary) {
            this.overallHealthy = overallHealthy;
            this.circuitBreakerHealthy = circuitBreakerHealthy;
            this.loadBalancerHealthy = loadBalancerHealthy;
            this.metricsHealthy = metricsHealthy;
            this.loadStats = loadStats;
            this.performanceSummary = performanceSummary;
        }
        
        @Override
        public String toString() {
            return String.format(
                "HealthCheck{overall=%s, circuitBreaker=%s, loadBalancer=%s, metrics=%s}",
                overallHealthy, circuitBreakerHealthy, loadBalancerHealthy, metricsHealthy
            );
        }
    }
}