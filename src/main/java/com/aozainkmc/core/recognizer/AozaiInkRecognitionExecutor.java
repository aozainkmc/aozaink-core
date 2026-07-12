package com.aozainkmc.core.recognizer;

import com.aozainkmc.core.AozaiInkCoreApi;
import com.aozainkmc.core.AozaiInkCore;
import com.aozainkmc.core.api.InkPoint;
import com.aozainkmc.core.api.InkRecognitionRequest;
import com.aozainkmc.core.api.InkRecognitionResult;
import com.aozainkmc.core.api.InkTrace;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Executes handwriting recognition off the server main thread and dispatches the
 * result back to the server thread. Used by multiplayer-aware input modules so
 * that ONNX inference does not stall the tick loop.
 */
public final class AozaiInkRecognitionExecutor {

    private static final int MAX_QUEUE_SIZE = 128;
    private static final long PLAYER_COOLDOWN_MS = 500L;
    private static final int MAX_STROKES_PER_SLOT = 32;
    private static final int MAX_POINTS_PER_STROKE = 512;
    private static final int MAX_POINTS_TOTAL = 2048;

    private static AozaiInkRecognitionExecutor instance;

    private final ThreadPoolExecutor executor;
    private final AtomicInteger queued = new AtomicInteger(0);
    private final Map<UUID, Long> lastSubmitMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> latestRevisions = new ConcurrentHashMap<>();
    private final AtomicInteger threadCounter = new AtomicInteger(0);

    private AozaiInkRecognitionExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "aozai-ink-recognition-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(
            1, 1,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUE_SIZE),
            threadFactory
        );
    }

    public static synchronized AozaiInkRecognitionExecutor get() {
        if (instance == null) {
            instance = new AozaiInkRecognitionExecutor();
        }
        return instance;
    }

    /**
     * Validates structural limits on a trace. This is cheap and should be run on the
     * server thread before submitting work to the executor.
     */
    public static boolean validateTrace(InkTrace trace) {
        if (trace == null) return false;
        List<List<InkPoint>> strokes = trace.strokes();
        if (strokes.size() > MAX_STROKES_PER_SLOT) return false;
        int total = 0;
        for (List<InkPoint> stroke : strokes) {
            if (stroke == null) continue;
            if (stroke.size() > MAX_POINTS_PER_STROKE) return false;
            total += stroke.size();
        }
        return total <= MAX_POINTS_TOTAL;
    }

    /**
     * Submits a recognition request to be executed off-thread. Results are delivered
     * on the server main thread via {@code onSuccess} or {@code onFailure}.
     *
     * @return true if the task was accepted, false if it was rejected due to cooldown,
     *         queue overflow, or missing recognizer.
     */
    public boolean submit(
        ServerPlayer player,
        InkRecognitionRequest request,
        RecognitionCallback onSuccess,
        FailureCallback onFailure
    ) {
        return submitInternal(player, request, true, null, onSuccess, onFailure);
    }

    /**
     * Submits work without applying the per-player cooldown. Use this only for
     * sub-tasks that belong to a request which already acquired the cooldown,
     * such as the three slots in one talisman submission.
     */
    public boolean submitWithoutCooldown(
        ServerPlayer player,
        InkRecognitionRequest request,
        RecognitionCallback onSuccess,
        FailureCallback onFailure
    ) {
        return submitInternal(player, request, false, null, onSuccess, onFailure);
    }

    /** Queues a replaceable preview; older queued revisions are skipped before inference. */
    public boolean submitLatest(
        ServerPlayer player,
        InkRecognitionRequest request,
        long revision,
        RecognitionCallback onSuccess,
        FailureCallback onFailure
    ) {
        supersedeLatest(player, revision);
        return submitInternal(player, request, false, revision, onSuccess, onFailure);
    }

    /** Marks every lower preview revision as obsolete, including when a final request arrives. */
    public void supersedeLatest(ServerPlayer player, long revision) {
        latestRevisions.merge(player.getUUID(), revision, Math::max);
    }

    public boolean tryAcquireCooldown(ServerPlayer player) {
        return checkCooldown(player);
    }

    private boolean submitInternal(
        ServerPlayer player,
        InkRecognitionRequest request,
        boolean enforceCooldown,
        Long replaceableRevision,
        RecognitionCallback onSuccess,
        FailureCallback onFailure
    ) {
        if (AozaiInkCoreApi.recognizer() == null) {
            onFailure.onFailure("recognizer_unavailable");
            return false;
        }
        if (enforceCooldown && !checkCooldown(player)) {
            onFailure.onFailure("cooldown");
            return false;
        }
        if (queued.incrementAndGet() > MAX_QUEUE_SIZE) {
            queued.decrementAndGet();
            player.displayClientMessage(
                Component.literal("[AozaiInk] 识别繁忙，请稍后再试"), true);
            onFailure.onFailure("busy");
            return false;
        }

        try {
            executor.execute(() -> {
                if (replaceableRevision != null
                        && latestRevisions.getOrDefault(player.getUUID(), replaceableRevision) > replaceableRevision) {
                    scheduleOnServer(player, () -> {
                        queued.decrementAndGet();
                        onFailure.onFailure("superseded");
                    });
                    return;
                }
                try {
                    InkRecognitionResult result = AozaiInkCoreApi.recognizer().recognize(request);
                    scheduleOnServer(player, () -> {
                        queued.decrementAndGet();
                        onSuccess.onResult(result);
                    });
                } catch (Exception e) {
                    AozaiInkCore.LOGGER.warn("Async ink recognition failed", e);
                    scheduleOnServer(player, () -> {
                        queued.decrementAndGet();
                        onFailure.onFailure(e.getClass().getSimpleName());
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            queued.decrementAndGet();
            player.displayClientMessage(
                Component.literal("[AozaiInk] 识别繁忙，请稍后再试"), true);
            onFailure.onFailure("busy");
            return false;
        }
        return true;
    }

    private boolean checkCooldown(ServerPlayer player) {
        long now = System.currentTimeMillis();
        long last = lastSubmitMs.getOrDefault(player.getUUID(), 0L);
        if (now - last < PLAYER_COOLDOWN_MS) {
            return false;
        }
        lastSubmitMs.put(player.getUUID(), now);
        return true;
    }

    private void scheduleOnServer(ServerPlayer player, Runnable task) {
        if (player.getServer() == null || player.isRemoved() || player.connection == null) {
            // Player disconnected while recognition was running; drop the result and
            // keep the queue accounting consistent.
            queued.decrementAndGet();
            return;
        }
        player.getServer().execute(task);
    }

    @FunctionalInterface
    public interface RecognitionCallback {
        void onResult(InkRecognitionResult result);
    }

    @FunctionalInterface
    public interface FailureCallback {
        void onFailure(String reason);
    }
}
