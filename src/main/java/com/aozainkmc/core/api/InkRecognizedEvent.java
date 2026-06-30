package com.aozainkmc.core.api;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

public class InkRecognizedEvent extends Event implements ICancellableEvent {

    private final InkRecognitionResult result;
    private final InkSource source;
    private final InkMark mark;
    private final ServerPlayer player;
    private final ServerLevel level;

    public InkRecognizedEvent(
        InkRecognitionResult result,
        InkSource source,
        InkMark mark,
        ServerPlayer player,
        ServerLevel level
    ) {
        this.result = result;
        this.source = source;
        this.mark = mark;
        this.player = player;
        this.level = level;
    }

    public InkRecognitionResult result() { return result; }
    public InkSource source() { return source; }
    public InkMark mark() { return mark; }
    public ServerPlayer player() { return player; }
    public ServerLevel level() { return level; }
}
