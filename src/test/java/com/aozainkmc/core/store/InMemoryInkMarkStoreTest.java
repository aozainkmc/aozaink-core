package com.aozainkmc.core.store;

import com.aozainkmc.core.api.InkMark;
import com.aozainkmc.core.api.InkTarget;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryInkMarkStoreTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final InkTarget TARGET = InkTarget.player("minecraft:overworld", OWNER);

    @Test
    void attachAndRetrieveMarksOnTarget() {
        InMemoryInkMarkStore store = new InMemoryInkMarkStore();
        InkMark mark = new InkMark("火", 0.9f, OWNER, TARGET, "test", 100L, 200L);

        store.attach(mark);

        List<InkMark> marks = store.marksOn(TARGET);
        assertEquals(1, marks.size());
        assertEquals("火", marks.getFirst().word());
    }

    @Test
    void allMarksAggregatesAcrossTargets() {
        InMemoryInkMarkStore store = new InMemoryInkMarkStore();
        InkTarget otherTarget = InkTarget.player("minecraft:overworld", UUID.randomUUID());

        store.attach(new InkMark("火", 0.9f, OWNER, TARGET, "test", 100L, 200L));
        store.attach(new InkMark("水", 0.8f, OWNER, otherTarget, "test", 100L, 200L));

        assertEquals(2, store.allMarks().size());
    }

    @Test
    void pruneExpiredRemovesOldMarks() {
        InMemoryInkMarkStore store = new InMemoryInkMarkStore();

        store.attach(new InkMark("火", 0.9f, OWNER, TARGET, "test", 100L, 10L));
        store.attach(new InkMark("水", 0.8f, OWNER, TARGET, "test", 100L, 100L));

        store.pruneExpired(120L);

        List<InkMark> remaining = store.marksOn(TARGET);
        assertEquals(1, remaining.size());
        assertEquals("水", remaining.getFirst().word());
    }

    @Test
    void clearTargetRemovesOnlyThatTarget() {
        InMemoryInkMarkStore store = new InMemoryInkMarkStore();
        InkTarget otherTarget = InkTarget.player("minecraft:overworld", UUID.randomUUID());

        store.attach(new InkMark("火", 0.9f, OWNER, TARGET, "test", 100L, 200L));
        store.attach(new InkMark("水", 0.8f, OWNER, otherTarget, "test", 100L, 200L));

        store.clear(TARGET);

        assertTrue(store.marksOn(TARGET).isEmpty());
        assertEquals(1, store.marksOn(otherTarget).size());
    }

    @Test
    void clearAllRemovesEverything() {
        InMemoryInkMarkStore store = new InMemoryInkMarkStore();
        InkTarget otherTarget = InkTarget.player("minecraft:overworld", UUID.randomUUID());

        store.attach(new InkMark("火", 0.9f, OWNER, TARGET, "test", 100L, 200L));
        store.attach(new InkMark("水", 0.8f, OWNER, otherTarget, "test", 100L, 200L));

        store.clearAll();

        assertTrue(store.allMarks().isEmpty());
    }
}
