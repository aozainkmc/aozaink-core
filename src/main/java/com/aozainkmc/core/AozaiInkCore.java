package com.aozainkmc.core;

import com.aozainkmc.core.command.AozaiInkCommand;
import com.aozainkmc.core.config.ModConfig;
import com.aozainkmc.core.ocr.OnnxOcrEngine;
import com.aozainkmc.core.ocr.OnnxTrajectoryOcrEngine;
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

    private static final String TRAJECTORY_MODEL_DIR = "/assets/aozaink_core/ocr/olsingle16";
    private static final int TRAJECTORY_MAX_POINTS = 256;
    private static final float TRAJECTORY_SIMPLIFY_EPS = 0.018f;
    private static final String TRAJECTORY_PROGRESS_MODE = "arc";

    public AozaiInkCore(IEventBus modBus, ModContainer modContainer) {
        AozaiInkCoreApi.installStore(new InMemoryInkMarkStore());
        AozaiInkCoreApi.installRecognizer();

        modContainer.registerConfig(Type.COMMON, ModConfig.SPEC);
        modBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (AozaiInkCoreApi.imageEngineNeeded()) {
                try {
                    AozaiInkCoreApi.installImageEngine(new OnnxOcrEngine());
                    LOGGER.info("Image OCR engine initialized");
                } catch (Exception e) {
                    LOGGER.error("Image OCR engine failed to initialize: {}", e.getMessage());
                }
            }
            if (AozaiInkCoreApi.trajectoryEngineNeeded()) {
                try {
                    AozaiInkCoreApi.installTrajectoryEngine(new OnnxTrajectoryOcrEngine(
                        TRAJECTORY_MODEL_DIR,
                        TRAJECTORY_MAX_POINTS,
                        TRAJECTORY_SIMPLIFY_EPS,
                        TRAJECTORY_PROGRESS_MODE
                    ));
                    LOGGER.info("Trajectory OCR engine initialized");
                } catch (Exception e) {
                    LOGGER.error("Trajectory OCR engine failed to initialize: {}", e.getMessage());
                }
            }
        });
    }

    private void registerCommands(RegisterCommandsEvent event) {
        AozaiInkCommand.register(event.getDispatcher());
    }
}

