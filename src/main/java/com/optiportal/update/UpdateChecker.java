package com.optiportal.update;

import com.optiportal.OptiPortal;

import java.util.concurrent.ScheduledExecutorService;

/**
 * Checks for newer plugin versions on startup and notifies operators.
 * Runs async - no startup delay.
 */
public class UpdateChecker {

    private final OptiPortal plugin;

    public UpdateChecker(OptiPortal plugin) {
        this.plugin = plugin;
    }

    public void checkAsync(ScheduledExecutorService executor) {
        executor.submit(() -> {
            // TODO: Check against GitHub releases API or Modrinth/CurseForge
            // Compare against current version from manifest.json
            // Log notification if newer version available
        });
    }
}
