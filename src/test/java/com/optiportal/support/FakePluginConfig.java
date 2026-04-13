package com.optiportal.support;

import com.optiportal.config.PluginConfig;
import java.io.File;

/**
 * Minimal PluginConfig for use in unit tests.
 * Overrides only the fields tests need to control; all other values stay at PluginConfig defaults.
 */
public class FakePluginConfig extends PluginConfig {

    private final File dataFolder;
    private int writeBehindDelayMs;
    private double activationDistance = super.getActivationDistance();

    public FakePluginConfig(File dataFolder) {
        this(dataFolder, 0);
    }

    public FakePluginConfig(File dataFolder, int writeBehindDelayMs) {
        this.dataFolder = dataFolder;
        this.writeBehindDelayMs = writeBehindDelayMs;
    }

    @Override
    public File getDataFolder() {
        return dataFolder;
    }

    @Override
    public int getStorageWriteBehindDelayMs() {
        return writeBehindDelayMs;
    }

    @Override
    public double getActivationDistance() {
        return activationDistance;
    }

    public void setWriteBehindDelayMs(int ms) {
        this.writeBehindDelayMs = ms;
    }

    public void setActivationDistance(double activationDistance) {
        this.activationDistance = activationDistance;
    }
}
