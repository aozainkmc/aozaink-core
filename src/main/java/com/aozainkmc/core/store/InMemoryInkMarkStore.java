package com.aozainkmc.core.store;

import com.aozainkmc.core.api.InkMark;
import com.aozainkmc.core.api.InkMarkStore;
import com.aozainkmc.core.api.InkTarget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryInkMarkStore implements InkMarkStore {

    private final ConcurrentMap<InkTarget, List<InkMark>> store = new ConcurrentHashMap<>();

    @Override
    public void attach(InkMark mark) {
        List<InkMark> list = store.computeIfAbsent(mark.target(), key -> new CopyOnWriteArrayList<>());
        list.add(mark);
    }

    @Override
    public List<InkMark> marksOn(InkTarget target) {
        return Collections.unmodifiableList(store.getOrDefault(target, Collections.emptyList()));
    }

    @Override
    public List<InkMark> allMarks() {
        List<InkMark> all = new ArrayList<>();
        for (List<InkMark> list : store.values()) {
            all.addAll(list);
        }
        return all;
    }

    @Override
    public void clear(InkTarget target) {
        store.remove(target);
    }

    @Override
    public void clearAll() {
        store.clear();
    }

    @Override
    public void pruneExpired(long gameTime) {
        for (InkTarget target : new ArrayList<>(store.keySet())) {
            List<InkMark> marks = store.get(target);
            if (marks == null) continue;
            List<InkMark> alive = new ArrayList<>();
            for (InkMark mark : marks) {
                if (!mark.expired(gameTime)) {
                    alive.add(mark);
                }
            }
            if (alive.isEmpty()) {
                store.remove(target);
            } else if (alive.size() < marks.size()) {
                store.put(target, new CopyOnWriteArrayList<>(alive));
            }
        }
    }
}
