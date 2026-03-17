package com.optiportal.async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Load balancer for async operations to prevent world thread overload.
 * 
 * This component manages the scheduling and execution of async operations,
 * implementing load balancing, adaptive batch sizing, and priority-based
 * scheduling to optimize world thread usage.
 */
public class AsyncLoadBalancer {
    
    private static final Logger LOG = Logger.getLogger("OptiPortal");
    
    // Load balancing configuration
    private static final int MAX_CONCURRENT_OPERATIONS = 10;
    private static final int DEFAULT_BATCH_SIZE = 4;
    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 16;
    private static final long LOAD_ADJUSTMENT_INTERVAL_MS = 5000;
    
    // State tracking
    private final AtomicInteger activeOperations = new AtomicInteger(0);
    private final AtomicLong totalOperations = new AtomicLong(0);
    private final AtomicLong totalExecutionTime = new AtomicLong(0);
    private final AtomicInteger currentBatchSize = new AtomicInteger(DEFAULT_BATCH_SIZE);
    
    // Priority queues
    private final ConcurrentHashMap<AsyncMetrics.AsyncTaskPriority, List<Supplier<CompletableFuture<Void>>>> pendingTasks = 
            new ConcurrentHashMap<>();
    
    private final ScheduledExecutorService executor;
    private final AsyncMetrics metrics;
    private final AsyncErrorHandler errorHandler;
    
    public AsyncLoadBalancer(ScheduledExecutorService executor,
                            AsyncMetrics metrics,
                            AsyncErrorHandler errorHandler) {
        this.executor = executor;
        this.metrics = metrics;
        this.errorHandler = errorHandler;
        
        // Initialize priority queues
        for (AsyncMetrics.AsyncTaskPriority priority : AsyncMetrics.AsyncTaskPriority.values()) {
            pendingTasks.put(priority, new ArrayList<>());
        }
        
        // Start load adjustment scheduler
        startLoadAdjustmentScheduler();
        
        // Start task processor
        startTaskProcessor();
    }
    
    /**
     * Schedule a load operation with priority.
     * 
     * @param operation The operation to schedule
     * @param priority Operation priority
     * @return CompletableFuture that completes when operation is done
     */
    public CompletableFuture<Void> scheduleLoad(Supplier<CompletableFuture<Void>> operation,
                                               AsyncMetrics.AsyncTaskPriority priority) {
        if (activeOperations.get() >= MAX_CONCURRENT_OPERATIONS) {
            // Queue the operation if we're at capacity
            return queueOperation(operation, priority);
        }
        
        // Execute immediately if we have capacity
        return executeOperation(operation, priority);
    }
    
    /**
     * Calculate optimal batch size based on current load.
     * 
     * @param totalChunks Total number of chunks to process
     * @return Optimal batch size
     */
    public int calculateOptimalBatchSize(int totalChunks) {
        int currentLoad = activeOperations.get();
        double avgExecutionTime = getAverageExecutionTime();
        
        // Adjust batch size based on current load and performance
        int adjustedSize = currentBatchSize.get();
        
        if (currentLoad > MAX_CONCURRENT_OPERATIONS * 0.8) {
            // High load - reduce batch size
            adjustedSize = Math.max(MIN_BATCH_SIZE, adjustedSize / 2);
        } else if (currentLoad < MAX_CONCURRENT_OPERATIONS * 0.3 && avgExecutionTime < 100) {
            // Low load and good performance - increase batch size
            adjustedSize = Math.min(MAX_BATCH_SIZE, adjustedSize + 1);
        }
        
        // Ensure we don't exceed total chunks
        return Math.min(adjustedSize, totalChunks);
    }
    
    /**
     * Get current load statistics.
     * 
     * @return Load statistics
     */
    public LoadStats getLoadStats() {
        return new LoadStats(
            activeOperations.get(),
            totalOperations.get(),
            getAverageExecutionTime(),
            currentBatchSize.get(),
            getQueuedTaskCount()
        );
    }
    
    /**
     * Queue an operation for later execution.
     * 
     * @param operation The operation to queue
     * @param priority Operation priority
     * @return CompletableFuture that completes when operation is done
     */
    private CompletableFuture<Void> queueOperation(Supplier<CompletableFuture<Void>> operation,
                                                  AsyncMetrics.AsyncTaskPriority priority) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        // Add to priority queue
        List<Supplier<CompletableFuture<Void>>> queue = pendingTasks.get(priority);
        synchronized (queue) {
            queue.add(() -> {
                return operation.get().whenComplete((result, ex) -> {
                    if (ex == null) {
                        future.complete(null);
                    } else {
                        future.completeExceptionally(ex);
                    }
                });
            });
        }
        
        LOG.fine("Queued operation with priority: " + priority);
        return future;
    }
    
    /**
     * Execute an operation immediately.
     * 
     * @param operation The operation to execute
     * @param priority Operation priority
     * @return CompletableFuture that completes when operation is done
     */
    private CompletableFuture<Void> executeOperation(Supplier<CompletableFuture<Void>> operation,
                                                    AsyncMetrics.AsyncTaskPriority priority) {
        activeOperations.incrementAndGet();
        totalOperations.incrementAndGet();
        
        long startTime = System.currentTimeMillis();
        
        if (metrics != null) {
            metrics.recordAsyncTaskStart(priority);
        }
        
        return operation.get().whenComplete((result, ex) -> {
            long duration = System.currentTimeMillis() - startTime;
            activeOperations.decrementAndGet();
            totalExecutionTime.addAndGet(duration);
             
            if (metrics != null) {
                metrics.recordAsyncTaskComplete(priority, duration);
            }
             
            if (ex != null) {
                errorHandler.handleWorldThreadError(ex instanceof Exception ? (Exception) ex : new Exception(ex), "loadBalancer");
            }
        });
    }
    
    /**
     * Start the load adjustment scheduler.
     */
    private void startLoadAdjustmentScheduler() {
        executor.scheduleAtFixedRate(() -> {
            try {
                adjustBatchSize();
            } catch (Exception e) {
                LOG.warning("Error in load adjustment: " + e.getMessage());
            }
        }, LOAD_ADJUSTMENT_INTERVAL_MS, LOAD_ADJUSTMENT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Start the task processor to handle queued tasks.
     */
    private void startTaskProcessor() {
        executor.scheduleAtFixedRate(() -> {
            try {
                processQueuedTasks();
            } catch (Exception e) {
                LOG.warning("Error processing queued tasks: " + e.getMessage());
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Adjust batch size based on current performance.
     */
    private void adjustBatchSize() {
        double avgExecutionTime = getAverageExecutionTime();
        int currentLoad = activeOperations.get();
        
        int newSize = currentBatchSize.get();
        
        if (avgExecutionTime > 200 || currentLoad > MAX_CONCURRENT_OPERATIONS * 0.7) {
            // Performance is poor - reduce batch size
            newSize = Math.max(MIN_BATCH_SIZE, newSize - 1);
        } else if (avgExecutionTime < 50 && currentLoad < MAX_CONCURRENT_OPERATIONS * 0.4) {
            // Performance is good - increase batch size
            newSize = Math.min(MAX_BATCH_SIZE, newSize + 1);
        }
        
        if (newSize != currentBatchSize.get()) {
            currentBatchSize.set(newSize);
            LOG.fine("Adjusted batch size to " + newSize + " (avg time: " + avgExecutionTime + "ms, load: " + currentLoad + ")");
        }
    }
    
    /**
     * Process queued tasks by priority.
     */
    private void processQueuedTasks() {
        if (activeOperations.get() >= MAX_CONCURRENT_OPERATIONS) {
            return; // Still at capacity
        }
        
        // Process tasks by priority (CRITICAL first)
        for (AsyncMetrics.AsyncTaskPriority priority : AsyncMetrics.AsyncTaskPriority.values()) {
            List<Supplier<CompletableFuture<Void>>> queue = pendingTasks.get(priority);
            
            Supplier<CompletableFuture<Void>> operation;
            synchronized (queue) {
                if (!queue.isEmpty()) {
                    operation = queue.remove(0);
                } else {
                    continue;
                }
            }
            
            // Execute the operation
            executeOperation(operation, priority);
            
            // Check if we're at capacity now
            if (activeOperations.get() >= MAX_CONCURRENT_OPERATIONS) {
                break;
            }
        }
    }
    
    /**
     * Get average execution time.
     * 
     * @return Average execution time in milliseconds
     */
    private double getAverageExecutionTime() {
        long ops = totalOperations.get();
        return ops > 0 ? (double) totalExecutionTime.get() / ops : 0.0;
    }
    
    /**
     * Get total queued task count.
     * 
     * @return Total number of queued tasks
     */
    private int getQueuedTaskCount() {
        int total = 0;
        for (List<Supplier<CompletableFuture<Void>>> queue : pendingTasks.values()) {
            synchronized (queue) {
                total += queue.size();
            }
        }
        return total;
    }
    
    /**
     * Load statistics data class.
     */
    public static class LoadStats {
        public final int activeOperations;
        public final long totalOperations;
        public final double averageExecutionTime;
        public final int currentBatchSize;
        public final int queuedTasks;
        
        public LoadStats(int activeOperations, long totalOperations, double averageExecutionTime,
                        int currentBatchSize, int queuedTasks) {
            this.activeOperations = activeOperations;
            this.totalOperations = totalOperations;
            this.averageExecutionTime = averageExecutionTime;
            this.currentBatchSize = currentBatchSize;
            this.queuedTasks = queuedTasks;
        }
        
        @Override
        public String toString() {
            return String.format(
                "LoadStats{active=%d, total=%d, avgTime=%.1fms, batchSize=%d, queued=%d}",
                activeOperations, totalOperations, averageExecutionTime, currentBatchSize, queuedTasks
            );
        }
    }
}