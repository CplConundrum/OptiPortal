package com.optiportal.integrations;

import com.optiportal.model.PortalEntry;
import com.optiportal.support.FakeStorageBackend;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins PortalSyncSupport.deleteMissing (deslopify qd-002): the
 * watcher-owned deletion loop. isPluginLoaded is engine-coupled and is
 * pinned by byte-parity with the migrated call sites instead.
 */
class PortalSyncSupportTest {

    private PortalEntry entry(String id) {
        PortalEntry e = new PortalEntry();
        e.setId(id);
        e.setWorld("world");
        return e;
    }

    @Test
    void deleteMissing_removesOnlyEntriesNotInIncomingSet() {
        Map<String, PortalEntry> existing = new LinkedHashMap<>();
        existing.put("a", entry("a"));
        existing.put("b", entry("b"));
        existing.put("c", entry("c"));
        Set<String> incoming = new HashSet<>();
        incoming.add("a");
        FakeStorageBackend storage = new FakeStorageBackend(List.of(entry("a"), entry("b"), entry("c")));
        List<String> callbacks = new ArrayList<>();

        int removed = PortalSyncSupport.deleteMissing(existing, incoming, callbacks::add, storage, "test");

        assertEquals(2, removed);
        assertEquals(List.of("b", "c"), callbacks);
        assertEquals(1, storage.loadAll().size());
        assertEquals("a", storage.loadAll().get(0).getId());
    }

    @Test
    void deleteMissing_allStillPresentNothingRemoved() {
        Map<String, PortalEntry> existing = new LinkedHashMap<>();
        existing.put("a", entry("a"));
        existing.put("b", entry("b"));
        Set<String> incoming = new HashSet<>(List.of("a", "b"));
        FakeStorageBackend storage = new FakeStorageBackend(List.of(entry("a"), entry("b")));
        List<String> callbacks = new ArrayList<>();

        int removed = PortalSyncSupport.deleteMissing(existing, incoming, callbacks::add, storage, "test");

        assertEquals(0, removed);
        assertEquals(0, callbacks.size());
        assertEquals(2, storage.loadAll().size());
    }

    @Test
    void deleteMissing_nullEntryValueIsSkipped() {
        Map<String, PortalEntry> existing = new LinkedHashMap<>();
        existing.put("x", null);
        existing.put("y", entry("y"));
        Set<String> incoming = new HashSet<>();
        FakeStorageBackend storage = new FakeStorageBackend(List.of(entry("y")));
        List<String> callbacks = new ArrayList<>();

        int removed = PortalSyncSupport.deleteMissing(existing, incoming, callbacks::add, storage, "test");

        assertEquals(1, removed);
        assertEquals(List.of("y"), callbacks);
        assertEquals(0, storage.loadAll().size());
    }
}
