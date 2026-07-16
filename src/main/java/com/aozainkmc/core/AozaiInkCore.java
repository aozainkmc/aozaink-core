package com.aozainkmc.core;

import com.aozainkmc.core.command.AozaiInkCommand;
import com.aozainkmc.core.config.ModConfig;
import com.aozainkmc.core.ocr.OnnxUnifiedOcrEngine;
import com.aozainkmc.core.store.InMemoryInkMarkStore;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

@Mod(AozaiInkCore.MOD_ID)
public final class AozaiInkCore {
    public static final String MOD_ID = "aozaink_core";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AozaiInkCore(IEventBus modBus, ModContainer modContainer) {
        AozaiInkCoreApi.installStore(new InMemoryInkMarkStore());
        AozaiInkCoreApi.installRecognizer();

        modContainer.registerConfig(Type.COMMON, ModConfig.SPEC);
        modBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                AozaiInkCoreApi.installEngine(new OnnxUnifiedOcrEngine());
                LOGGER.info("Unified image/trajectory OCR engine initialized");
            } catch (Exception e) {
                LOGGER.error("Unified OCR engine failed to initialize", e);
            }
        });
    }

    private void registerCommands(RegisterCommandsEvent event) {
        AozaiInkCommand.register(event.getDispatcher());
    }
}

