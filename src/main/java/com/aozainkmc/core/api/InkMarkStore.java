package com.aozainkmc.core.api;

import java.util.List;

public interface InkMarkStore {

    void attach(InkMark mark);

    List<InkMark> marksOn(InkTarget target);

    List<InkMark> allMarks();

    void clear(InkTarget target);

    void clearAll();

    void pruneExpired(long gameTime);
}
