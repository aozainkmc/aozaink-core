package com.aozainkmc.core.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ModConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<Integer> MARK_DEFAULT_TTL;
    public static final ModConfigSpec.ConfigValue<Integer> MARK_CLEANUP_INTERVAL;
    public static final ModConfigSpec.ConfigValue<Boolean> DEBUG_MODE;
    public static final ModConfigSpec.ConfigValue<Boolean> ALLOW_ALL_WORDS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Aozai Ink Core Configuration").push("core");

        MARK_DEFAULT_TTL = builder
            .comment("Default TTL for ink marks in ticks (20 ticks = 1 second)")
            .define("mark_default_ttl", 20 * 60 * 10); // 10 minutes

        MARK_CLEANUP_INTERVAL = builder
            .comment("Interval between mark cleanup checks in ticks")
            .define("mark_cleanup_interval", 20 * 60); // 1 minute

        DEBUG_MODE = builder
            .comment("Enable debug logging")
            .define("debug_mode", false);

        ALLOW_ALL_WORDS = builder
            .comment("Allow all recognized words without whitelist filtering")
            .define("allow_all_words", true);

        builder.pop();
        SPEC = builder.build();
    }

    private ModConfig() {}
}
