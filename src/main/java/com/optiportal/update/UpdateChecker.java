package com.optiportal.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.optiportal.OptiPortal;

/**
 * Checks for newer plugin versions on startup and notifies operators.
 * Runs async - no startup delay.
 */
public class UpdateChecker {

    private final OptiPortal plugin;
    private static final String GITHUB_API_URL = "https://api.github.com/repos/cpl/optiportal/releases/latest";
    private static final String PLUGIN_NAME = "OptiPortal";

    public UpdateChecker(OptiPortal plugin) {
        this.plugin = plugin;
    }

    public void checkAsync(ScheduledExecutorService executor) {
        executor.submit(() -> {
            try {
                checkForUpdates();
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).log("[OptiPortal] Failed to check for updates: " + e.getMessage());
            }
        });
    }

    private void checkForUpdates() throws IOException {
        // Get current version from manifest
        String currentVersion = getCurrentVersion();
        
        // Fetch latest version from GitHub API
        JsonObject latestRelease = fetchLatestRelease();
        if (latestRelease == null) return;
        
        String latestVersion = latestRelease.get("tag_name").getAsString().replace("v", "");
        
        // Compare versions
        if (isVersionNewer(currentVersion, latestVersion)) {
            plugin.getLogger().at(Level.INFO).log("[OptiPortal] A new version is available: " + latestVersion);
            plugin.getLogger().at(Level.INFO).log("[OptiPortal] Current version: " + currentVersion);
            plugin.getLogger().at(Level.INFO).log("[OptiPortal] Download at: https://github.com/cpl/optiportal/releases/latest");
        }
    }

    private String getCurrentVersion() {
        try {
            if (plugin.getManifest() != null && plugin.getManifest().getVersion() != null) {
                return plugin.getManifest().getVersion().toString();
            }
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log("[OptiPortal] Failed to read manifest version: " + e.getMessage());
        }
        return "unknown";
    }

    private JsonObject fetchLatestRelease() throws IOException {
        URL url = new URL(GITHUB_API_URL);
        URLConnection connection = url.openConnection();
        connection.setRequestProperty("User-Agent", "OptiPortal-UpdateChecker/1.0");
        connection.setConnectTimeout(5000); // 5 seconds
        connection.setReadTimeout(5000); // 5 seconds

        try (InputStream inputStream = connection.getInputStream();
             InputStreamReader reader = new InputStreamReader(inputStream)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private boolean isVersionNewer(String current, String latest) {
        try {
            // Simple version comparison
            String[] currentParts = current.split("\\.");
            String[] latestParts = latest.split("\\.");
            
            for (int i = 0; i < Math.min(currentParts.length, latestParts.length); i++) {
                int currentNum = Integer.parseInt(currentParts[i]);
                int latestNum = Integer.parseInt(latestParts[i]);
                
                if (latestNum > currentNum) return true;
                if (latestNum < currentNum) return false;
            }
            
            // If all parts are equal, the latest version is not newer
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
