package com.optiportal.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.optiportal.model.PortalEntry;

class FakeStorageBackendTest {

    @Test
    void saveCopiesEntriesSoCallerMutationsDoNotLeakIntoStoredState() {
        FakeStorageBackend backend = new FakeStorageBackend();
        PortalEntry entry = new PortalEntry("portal-a", "world-a", 10.0, 64.0, 20.0, 0.0);
        entry.setWarmRadius(3);

        backend.save(entry);
        entry.setWorld("mutated-world");
        entry.setWarmRadius(99);

        PortalEntry loaded = backend.loadById("portal-a").orElseThrow();
        assertEquals("world-a", loaded.getWorld());
        assertEquals(3, loaded.getWarmRadius());
    }

    @Test
    void loadMethodsReturnCopiesSoReturnedMutationsDoNotChangeStoredState() {
        PortalEntry seed = new PortalEntry("portal-b", "world-b", 5.0, 64.0, 6.0, 0.0);
        seed.setNotes("original");
        FakeStorageBackend backend = new FakeStorageBackend(List.of(seed));

        PortalEntry byId = backend.loadById("portal-b").orElseThrow();
        PortalEntry fromAll = backend.loadAll().getFirst();

        assertNotSame(byId, fromAll);

        byId.setNotes("mutated");
        fromAll.setWorld("changed-world");

        PortalEntry reloaded = backend.loadById("portal-b").orElseThrow();
        assertEquals("original", reloaded.getNotes());
        assertEquals("world-b", reloaded.getWorld());
    }
}
