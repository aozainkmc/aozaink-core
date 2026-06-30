package com.aozainkmc.core;

import com.aozainkmc.core.api.InkMarkStore;
import com.aozainkmc.core.config.ModConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@EventBusSubscriber(modid = AozaiInkCore.MOD_ID)
public final class InkMarkCleanupHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_CLEANUP_INTERVAL_TICKS = 20 * 60;

    private static int tickCounter = 0;

    private InkMarkCleanupHandler() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server == null) return;

        tickCounter++;
        int interval = ModConfig.MARK_CLEANUP_INTERVAL != null
            ? ModConfig.MARK_CLEANUP_INTERVAL.get()
            : DEFAULT_CLEANUP_INTERVAL_TICKS;
        if (tickCounter >= interval) {
            tickCounter = 0;
            InkMarkStore store = AozaiInkCoreApi.markStore();
            if (store != null) {
                long gameTime = server.overworld().getGameTime();
                int before = store.allMarks().size();
                store.pruneExpired(gameTime);
                int after = store.allMarks().size();
                if (before != after) {
                    LOGGER.debug("Pruned {} expired ink marks", before - after);
                }
            }
        }
    }
}
