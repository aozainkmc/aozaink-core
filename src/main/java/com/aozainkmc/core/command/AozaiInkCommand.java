package com.aozainkmc.core.command;

import com.aozainkmc.core.AozaiInkCoreApi;
import com.aozainkmc.core.api.EngineType;
import com.aozainkmc.core.api.InkCandidate;
import com.aozainkmc.core.api.InkMark;
import com.aozainkmc.core.api.InkPoint;
import com.aozainkmc.core.api.InkRecognitionMode;
import com.aozainkmc.core.api.InkRecognitionRequest;
import com.aozainkmc.core.api.InkRecognitionResult;
import com.aozainkmc.core.api.InkSource;
import com.aozainkmc.core.api.InkTrace;
import com.aozainkmc.core.dev.AozaiInkDevMode;
import com.aozainkmc.core.ocr.OcrEngine;
import com.aozainkmc.core.ocr.OnnxOcrEngine;
import com.aozainkmc.core.ocr.OnnxTrajectoryOcrEngine;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class AozaiInkCommand {
    private static final int SELFTEST_IMAGE_SIZE = 64;
    private static final String SELFTEST_IMAGE_SOURCE = "__aozaink_selftest_image";
    private static final String SELFTEST_TRAJECTORY_SOURCE = "__aozaink_selftest_trajectory";
    private static final String TRAJECTORY_MODEL_DIR = "/assets/aozaink_core/ocr/olsingle24";
    private static final int TRAJECTORY_MAX_POINTS = 256;
    private static final float TRAJECTORY_SIMPLIFY_EPS = 0.018f;
    private static final String TRAJECTORY_PROGRESS_MODE = "arc";

    private AozaiInkCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("aozaink")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("dev")
                    .executes(AozaiInkCommand::toggleDev))
                .then(Commands.literal("status")
                    .executes(wrapDev(AozaiInkCommand::status)))
                .then(Commands.literal("list")
                    .executes(wrapDev(AozaiInkCommand::listMarks)))
                .then(Commands.literal("clear")
                    .executes(wrapDev(AozaiInkCommand::clearAll)))
                .then(Commands.literal("prune")
                    .executes(wrapDev(AozaiInkCommand::prune)))
                .then(Commands.literal("selftest")
                    .executes(wrapDev(AozaiInkCommand::selftest))
                    .then(Commands.literal("offline")
                        .executes(wrapDev(AozaiInkCommand::selftestOffline)))
                    .then(Commands.literal("online")
                        .executes(wrapDev(AozaiInkCommand::selftestOnline))))
                .then(Commands.literal("help")
                    .executes(wrapDev(AozaiInkCommand::help)))
        );
    }

    private static int toggleDev(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean enabled = AozaiInkDevMode.toggle(player);
        ctx.getSource().sendSuccess(() ->
            Component.literal("[AozaiInk] 开发模式: " + (enabled ? "开启" : "关闭")), false);
        return 1;
    }

    private static com.mojang.brigadier.Command<CommandSourceStack> wrapDev(
            com.mojang.brigadier.Command<CommandSourceStack> command) {
        return ctx -> {
            if (!requireDevMode(ctx)) return 0;
            return command.run(ctx);
        };
    }

    private static boolean requireDevMode(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (AozaiInkDevMode.isEnabled(player)) {
            return true;
        }
        ctx.getSource().sendFailure(Component.literal("[AozaiInk] 请先使用 /aozaink dev 开启开发模式"));
        return false;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        var store = AozaiInkCoreApi.markStore();
        int count = store.allMarks().size();
        ctx.getSource().sendSuccess(() ->
            Component.literal("[AozaiInk] 当前字灵标记数: " + count), false);
        return count;
    }

    private static int listMarks(CommandContext<CommandSourceStack> ctx) {
        var store = AozaiInkCoreApi.markStore();
        List<InkMark> marks = store.allMarks();
        if (marks.isEmpty()) {
            ctx.getSource().sendSuccess(() ->
                Component.literal("[AozaiInk] 无字灵标记"), false);
            return 0;
        }

        ctx.getSource().sendSuccess(() ->
            Component.literal("[AozaiInk] 字灵列表 (" + marks.size() + "):"), false);
        for (InkMark mark : marks) {
            ctx.getSource().sendSuccess(() ->
                Component.literal("  " + mark.word() + " | 置信度: " +
                    String.format(Locale.ROOT, "%.1f%%", mark.confidence() * 100) +
                    " | 来源: " + mark.sourceId() +
                    " | 剩余: " + (mark.ttlTicks() - (ctx.getSource().getServer().overworld().getGameTime() - mark.bornGameTime())) + " ticks"),
                false);
        }
        return marks.size();
    }

    private static int clearAll(CommandContext<CommandSourceStack> ctx) {
        var store = AozaiInkCoreApi.markStore();
        int before = store.allMarks().size();
        store.clearAll();
        ctx.getSource().sendSuccess(() ->
            Component.literal("[AozaiInk] 已清除 " + before + " 个字灵标记"), false);
        return before;
    }

    private static int prune(CommandContext<CommandSourceStack> ctx) {
        var store = AozaiInkCoreApi.markStore();
        long gameTime = ctx.getSource().getServer().overworld().getGameTime();
        int before = store.allMarks().size();
        store.pruneExpired(gameTime);
        int after = store.allMarks().size();
        ctx.getSource().sendSuccess(() ->
            Component.literal("[AozaiInk] 清理完成: " + (before - after) + " 个过期标记已移除"), false);
        return before - after;
    }

    private static int selftest(CommandContext<CommandSourceStack> ctx) {
        int offline = selftestOffline(ctx);
        int online = selftestOnline(ctx);
        return offline + online;
    }

    private static int selftestOffline(CommandContext<CommandSourceStack> ctx) {
        try {
            AozaiInkCoreApi.registerInput(SELFTEST_IMAGE_SOURCE, EngineType.OFFLINE_IMAGE);
            if (AozaiInkCoreApi.imageEngine() == null) {
                AozaiInkCoreApi.installImageEngine(new OnnxOcrEngine());
            }
            List<InkCandidate> candidates = AozaiInkCoreApi.imageEngine().recognize(builtinTestImage(), 5, Collections.emptyList());
            return printCandidates(ctx, "Selftest offline image", candidates);
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[AozaiInk] Selftest offline image 异常: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            return 0;
        }
    }

    private static int selftestOnline(CommandContext<CommandSourceStack> ctx) {
        try {
            AozaiInkCoreApi.registerInput(SELFTEST_TRAJECTORY_SOURCE, EngineType.ONLINE_TRAJECTORY);
            AozaiInkCoreApi.registerGlyphs(List.of("一", "二", "三", "火", "水", "木", "口", "人"));
            if (AozaiInkCoreApi.trajectoryEngine() == null) {
                AozaiInkCoreApi.installTrajectoryEngine(new OnnxTrajectoryOcrEngine(
                    TRAJECTORY_MODEL_DIR,
                    TRAJECTORY_MAX_POINTS,
                    TRAJECTORY_SIMPLIFY_EPS,
                    TRAJECTORY_PROGRESS_MODE
                ));
            }

            InkRecognitionRequest request = new InkRecognitionRequest(
                builtinTestTrace(),
                null,
                InkRecognitionMode.ONLINE,
                List.of("一", "二", "三", "火", "水", "木", "口", "人"),
                12000L,
                new InkSource(SELFTEST_TRAJECTORY_SOURCE, 1.0f, "selftest", 0.0f, Map.of())
            );
            InkRecognitionResult result = AozaiInkCoreApi.recognizer().recognize(request);
            return printCandidates(ctx, "Selftest online trajectory", result.candidates());
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[AozaiInk] Selftest online trajectory 异常: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            return 0;
        }
    }

    private static int printCandidates(CommandContext<CommandSourceStack> ctx, String label, List<InkCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[AozaiInk] " + label + ": 没有候选字"));
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal("[AozaiInk] " + label + " OK，Top-K:"), false);
        int count = Math.min(5, candidates.size());
        for (int i = 0; i < count; i++) {
            InkCandidate candidate = candidates.get(i);
            int rank = i + 1;
            ctx.getSource().sendSuccess(() -> Component.literal("  #" + rank + " " + candidate.word() + " "
                + String.format(Locale.ROOT, "%.2f%%", candidate.confidence() * 100)), false);
        }
        return count;
    }

    private static float[] builtinTestImage() {
        float[] image = new float[SELFTEST_IMAGE_SIZE * SELFTEST_IMAGE_SIZE];
        for (int i = 0; i < image.length; i++) {
            image[i] = -1.0f;
        }

        for (int y = 29; y <= 34; y++) {
            for (int x = 10; x <= 53; x++) {
                image[y * SELFTEST_IMAGE_SIZE + x] = 1.0f;
            }
        }
        for (int y = 27; y <= 36; y++) {
            image[y * SELFTEST_IMAGE_SIZE + 10] = 0.4f;
            image[y * SELFTEST_IMAGE_SIZE + 53] = 0.4f;
        }
        return image;
    }

    private static InkTrace builtinTestTrace() {
        return new InkTrace(List.of(List.of(
            new InkPoint(0.10f, 0.50f, 0L),
            new InkPoint(0.20f, 0.50f, 20L),
            new InkPoint(0.30f, 0.50f, 40L),
            new InkPoint(0.40f, 0.50f, 60L),
            new InkPoint(0.50f, 0.50f, 80L),
            new InkPoint(0.60f, 0.50f, 100L),
            new InkPoint(0.70f, 0.50f, 120L),
            new InkPoint(0.80f, 0.50f, 140L),
            new InkPoint(0.90f, 0.50f, 160L)
        )));
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() ->
            Component.literal("[AozaiInk] 命令:"), false);
        ctx.getSource().sendSuccess(() ->
            Component.literal("  /aozaink dev - 切换开发模式"), false);
        ctx.getSource().sendSuccess(() ->
            Component.literal("  /aozaink status - 查看字灵统计"), false);
        ctx.getSource().sendSuccess(() ->
            Component.literal("  /aozaink list - 列出所有字灵"), false);
        ctx.getSource().sendSuccess(() ->
            Component.literal("  /aozaink clear - 清除所有字灵"), false);
        ctx.getSource().sendSuccess(() ->
            Component.literal("  /aozaink prune - 清理过期字灵"), false);
        ctx.getSource().sendSuccess(() ->
            Component.literal("  /aozaink selftest - 验证离线图片和在线轨迹 OCR"), false);
        ctx.getSource().sendSuccess(() ->
            Component.literal("  /aozaink selftest offline - 只验证离线图片 OCR"), false);
        ctx.getSource().sendSuccess(() ->
            Component.literal("  /aozaink selftest online - 只验证在线轨迹 OCR"), false);
        return 0;
    }
}
