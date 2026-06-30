package com.aozainkmc.core.dev;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public final class AozaiInkDevMode {
    private static final Map<UUID, Boolean> STATES = new ConcurrentHashMap<>();

    private AozaiInkDevMode() {}

    public static boolean isEnabled(Player player) {
        return STATES.getOrDefault(player.getUUID(), false);
    }

    public static void setEnabled(ServerPlayer player, boolean enabled) {
        if (enabled) {
            STATES.put(player.getUUID(), true);
        } else {
            STATES.remove(player.getUUID());
        }
    }

    public static boolean toggle(ServerPlayer player) {
        boolean next = !isEnabled(player);
        setEnabled(player, next);
        return next;
    }

    public static void clear() {
        STATES.clear();
    }
}
