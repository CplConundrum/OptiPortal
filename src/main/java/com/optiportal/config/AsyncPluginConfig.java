package com.optiportal.config;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Async-safe configuration manager for hot-reloading.
 * 
 * This component provides async-safe configuration reloading
 * with proper error handling and validation.
 */
public class AsyncPluginConfig {
    
    private static final Logger LOG = Logger.getLogger("OptiPortal");
    
    private final PluginConfig config;
    private final ScheduledExecutorService executor;
    private final File configFile;
    private volatile long lastModified;
    
    // Configuration change listeners
    private final java.util.List<ConfigurationListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    
    public AsyncPluginConfig(PluginConfig config, ScheduledExecutorService executor) {
        this.config = config;
        this.executor = executor;
        this.configFile = new File(config.getDataFolder(), "config.json");
        this.lastModified = configFile.lastModified();
        
        // Start configuration file watcher
        startConfigWatcher();
    }
    
    /**
     * Start watching for configuration file changes.
     */
    private void startConfigWatcher() {
        executor.scheduleAtFixedRate(() -> {
            try {
                checkForConfigChanges();
            } catch (Exception e) {
                LOG.warning("Error checking for config changes: " + e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }
    
    /**
     * Check for configuration file changes and reload if necessary.
     */
    private void checkForConfigChanges() {
        long currentModified = configFile.lastModified();
        if (currentModified > lastModified) {
            lastModified = currentModified;
            reloadConfigAsync();
        }
    }
    
    /**
     * Reload configuration asynchronously.
     * 
     * @return CompletableFuture that completes when reload is done
     */
    public CompletableFuture<String> reloadConfigAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("[OptiPortal] Starting async config reload...");
                
                // Reload configuration
                config.reload();
                
                // Notify listeners
                notifyConfigurationChanged();
                
                String summary = generateReloadSummary();
                LOG.info("[OptiPortal] Async config reload completed: " + summary);
                
                return summary;
                
            } catch (Exception e) {
                LOG.severe("[OptiPortal] Async config reload failed: " + e.getMessage());
                throw new RuntimeException("Config reload failed", e);
            }
        }, executor);
    }
    
    /**
     * Add a configuration change listener.
     * 
     * @param listener Listener to add
     */
    public void addConfigurationListener(ConfigurationListener listener) {
        listeners.add(listener);
    }
    
    /**
     * Remove a configuration change listener.
     * 
     * @param listener Listener to remove
     */
    public void removeConfigurationListener(ConfigurationListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Notify all listeners of configuration changes.
     */
    private void notifyConfigurationChanged() {
        for (ConfigurationListener listener : listeners) {
            try {
                listener.onConfigurationChanged(config);
            } catch (Exception e) {
                LOG.warning("Error notifying configuration listener: " + e.getMessage());
            }
        }
    }
    
    /**
     * Generate a summary of what was reloaded.
     * 
     * @return Summary string
     */
    private String generateReloadSummary() {
        return "Config reloaded. Live: decay, keepalive, snapshot interval, activation, TTLs, warps, UI flags. " +
               "Restart required for: backend, storage paths, MySQL credentials, startupLoadStrategy.";
    }
    
    /**
     * Get current configuration.
     * 
     * @return Current configuration
     */
    public PluginConfig getConfig() {
        return config;
    }
    
    /**
     * Check if configuration is currently being reloaded.
     * 
     * @return True if reload is in progress
     */
    public boolean isReloading() {
        // This would need to be implemented with proper state tracking
        return false;
    }
    
    /**
     * Configuration change listener interface.
     */
    public interface ConfigurationListener {
        /**
         * Called when configuration has changed.
         * 
         * @param newConfig New configuration
         */
        void onConfigurationChanged(PluginConfig newConfig);
    }
    
    /**
     * Configuration validation result.
     */
    public static class ValidationResult {
        public final boolean valid;
        public final java.util.List<String> errors;
        public final java.util.List<String> warnings;
        
        public ValidationResult(boolean valid, java.util.List<String> errors, java.util.List<String> warnings) {
            this.valid = valid;
            this.errors = errors;
            this.warnings = warnings;
        }
        
        public static ValidationResult valid() {
            return new ValidationResult(true, new ArrayList<>(), new ArrayList<>());
        }
        
        public static ValidationResult invalid(String error) {
            java.util.List<String> errors = new ArrayList<>();
            errors.add(error);
            return new ValidationResult(false, errors, new ArrayList<>());
        }
        
        public static ValidationResult withWarning(String warning) {
            java.util.List<String> warnings = new ArrayList<>();
            warnings.add(warning);
            return new ValidationResult(true, new ArrayList<>(), warnings);
        }
    }
    
    /**
     * Validate current configuration.
     * 
     * @return Validation result
     */
    public ValidationResult validateConfiguration() {
        java.util.List<String> errors = new ArrayList<>();
        java.util.List<String> warnings = new ArrayList<>();
        
        // Validate backend configuration
        String backend = config.getBackend();
        if (backend == null || backend.trim().isEmpty()) {
            errors.add("Backend cannot be null or empty");
        }
        
        // Validate MySQL configuration if using MySQL backend
        if ("MYSQL".equalsIgnoreCase(backend)) {
            if (config.getMysqlHost().trim().isEmpty()) {
                errors.add("MySQL host cannot be empty when using MySQL backend");
            }
            if (config.getMysqlDatabase().trim().isEmpty()) {
                errors.add("MySQL database cannot be empty when using MySQL backend");
            }
        }
        
        // Validate numeric values
        if (config.getPredictiveRadius() < 1) {
            errors.add("Predictive radius must be at least 1");
        }
        if (config.getDefaultWarmRadius() < 1) {
            errors.add("Default warm radius must be at least 1");
        }
        if (config.getWarmBatchSize() < 1) {
            errors.add("Warm batch size must be at least 1");
        }
        
        // Validate file paths
        if (config.getWarpsSourcePath() != null && !config.getWarpsSourcePath().trim().isEmpty()) {
            File warpsFile = new File(config.getWarpsSourcePath());
            if (!warpsFile.exists()) {
                warnings.add("Warps file does not exist: " + config.getWarpsSourcePath());
            }
        }
        
        boolean valid = errors.isEmpty();
        return new ValidationResult(valid, errors, warnings);
    }
}