package com.optiportal.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tier-3 knob — the shared async executor thread count is configurable via
 * {@code async.executorThreadCount} (default 4 = legacy hardcoded behavior).
 */
class ExecutorThreadCountKnobTest {

    @Test
    void defaultIsFourWhenKeyAbsent(@TempDir File tempDir) {
        // load() takes the DATA FOLDER and reads <folder>/config.json from it
        PluginConfig config = PluginConfig.load(tempDir);
        assertEquals(4, config.getExecutorThreadCount(),
                "missing key must keep the legacy 4-thread executor");
    }

    @Test
    void configuredValueIsHonored(@TempDir File tempDir) throws Exception {
        File configFile = new File(tempDir, "config.json");
        Files.writeString(configFile.toPath(), """
            {
              "async": {
                "executorThreadCount": 7
              }
            }
            """);
        PluginConfig config = PluginConfig.load(tempDir);
        assertEquals(7, config.getExecutorThreadCount(),
                "async.executorThreadCount must flow through to the getter");
    }
}
