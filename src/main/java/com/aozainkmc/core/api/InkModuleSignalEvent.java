package com.aozainkmc.core.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

public final class InkModuleSignalEvent extends Event {
    private final ServerPlayer player;
    private final ResourceLocation signalId;
    private final CompoundTag payload;

    public InkModuleSignalEvent(ServerPlayer player, ResourceLocation signalId, CompoundTag payload) {
        this.player = player;
        this.signalId = signalId;
        this.payload = payload == null ? new CompoundTag() : payload.copy();
    }

    public ServerPlayer player() {
        return player;
    }

    public ResourceLocation signalId() {
        return signalId;
    }

    public CompoundTag payload() {
        return payload.copy();
    }
}
