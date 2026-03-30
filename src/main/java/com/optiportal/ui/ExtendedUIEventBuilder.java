package com.optiportal.ui;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBinding;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.logging.Logger;

/**
 * Extended UIEventBuilder that adds clearEventBindings() method to support
 * proper cleanup of event bindings when refreshing UI.
 */
public class ExtendedUIEventBuilder extends UIEventBuilder {

    private static final Logger LOG = Logger.getLogger("OptiPortal");
    private static final Field EVENTS_FIELD;
    static {
        try {
            EVENTS_FIELD = UIEventBuilder.class.getDeclaredField("events");
            EVENTS_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Failed to access events field in UIEventBuilder", e);
        }
    }
    
    /**
     * Clears all event bindings from this builder.
     * This is necessary to prevent event binding accumulation when refreshing UI.
     */
    public void clearEventBindings() {
        try {
            java.util.List<CustomUIEventBinding> events = (java.util.List<CustomUIEventBinding>) EVENTS_FIELD.get(this);
            if (events != null) {
                events.clear();
            }
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "[OptiPortal] Failed to clear event bindings via reflection", e);
        }
    }
    
    @Override
    @Nonnull
    public ExtendedUIEventBuilder addEventBinding(CustomUIEventBindingType type, String selector) {
        super.addEventBinding(type, selector);
        return this;
    }
    
    @Override
    @Nonnull
    public ExtendedUIEventBuilder addEventBinding(CustomUIEventBindingType type, String selector, boolean locksInterface) {
        super.addEventBinding(type, selector, locksInterface);
        return this;
    }
    
    @Override
    @Nonnull
    public ExtendedUIEventBuilder addEventBinding(CustomUIEventBindingType type, String selector, EventData data) {
        super.addEventBinding(type, selector, data);
        return this;
    }
    
    @Override
    @Nonnull
    public ExtendedUIEventBuilder addEventBinding(CustomUIEventBindingType type, String selector, @Nullable EventData data, boolean locksInterface) {
        super.addEventBinding(type, selector, data, locksInterface);
        return this;
    }
}
