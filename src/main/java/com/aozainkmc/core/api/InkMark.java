package com.aozainkmc.core.api;

import java.util.UUID;

public record InkMark(
    String word,
    float confidence,
    UUID owner,
    InkTarget target,
    String sourceId,
    long bornGameTime,
    long ttlTicks
) {
    public InkMark {
        if (word == null) word = "";
        confidence = Math.clamp(confidence, 0.0f, 1.0f);
        if (owner == null) owner = new UUID(0L, 0L);
        if (target == null) target = InkTarget.player("", new UUID(0L, 0L));
        if (sourceId == null) sourceId = "unknown";
        if (ttlTicks < 1L) ttlTicks = 12000L;
    }

    public boolean expired(long gameTime) {
        return gameTime - bornGameTime >= ttlTicks;
    }
}
