package com.optiportal.ui;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBinding;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.lang.reflect.Field;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Debug version of UIEventBuilder with detailed logging for event binding issues
 */
public class DebugUIEventBuilder extends UIEventBuilder {
    private static final Logger LOG = Logger.getLogger("OptiPortal");
    private static final Field EVENTS_FIELD;
    private static final Field BINDING_COUNTER_FIELD;
    
    static {
        try {
            EVENTS_FIELD = UIEventBuilder.class.getDeclaredField("events");
            EVENTS_FIELD.setAccessible(true);
            
            BINDING_COUNTER_FIELD = UIEventBuilder.class.getDeclaredField("bindingCounter");
            BINDING_COUNTER_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Failed to access UIEventBuilder fields", e);
        }
    }
    
    /**
     * Clear all event bindings
     */
    public void clearEventBindings() {
        try {
            List<CustomUIEventBinding> events = (List<CustomUIEventBinding>) EVENTS_FIELD.get(this);
            if (events != null) {
                int beforeClear = events.size();
                events.clear();
                LOG.fine("[OptiPortal DEBUG] Cleared " + beforeClear + " event bindings");
            }
        } catch (IllegalAccessException e) {
            LOG.log(Level.WARNING, "[OptiPortal] Failed to clear event bindings via reflection", e);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[OptiPortal] Unexpected error clearing event bindings", e);
        }
    }
    
    /**
     * Get current event binding count
     */
    public int getEventBindingCount() {
        try {
            List<CustomUIEventBinding> events = (List<CustomUIEventBinding>) EVENTS_FIELD.get(this);
            return events != null ? events.size() : 0;
        } catch (IllegalAccessException e) {
            LOG.log(Level.WARNING, "[OptiPortal] Failed to get event binding count", e);
            return -1;
        }
    }
    
    /**
     * Get binding counter value
     */
    public int getBindingCounter() {
        try {
            return BINDING_COUNTER_FIELD.getInt(this);
        } catch (IllegalAccessException e) {
            LOG.log(Level.WARNING, "[OptiPortal] Failed to get binding counter", e);
            return -1;
        }
    }
    
    /**
     * Reset binding counter
     */
    public void resetBindingCounter() {
        try {
            BINDING_COUNTER_FIELD.setInt(this, 0);
            LOG.fine("[OptiPortal DEBUG] Reset binding counter to 0");
        } catch (IllegalAccessException e) {
            LOG.log(Level.WARNING, "[OptiPortal] Failed to reset binding counter", e);
        }
    }
}