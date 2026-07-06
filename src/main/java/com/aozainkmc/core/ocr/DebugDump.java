package com.aozainkmc.core.ocr;

import com.aozainkmc.core.AozaiInkCore;
import com.aozainkmc.core.api.InkCandidate;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class DebugDump {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final int OFFLINE_SIZE = 64;
    private static final int TRAJECTORY_SIZE = 256;
    private static final int TRAJECTORY_PADDING = 18;
    private static final ThreadLocal<Boolean> ENABLED = ThreadLocal.withInitial(() -> true);

    private DebugDump() {
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static void setEnabled(boolean enabled) {
        ENABLED.set(enabled);
    }

    public static void offlineImageInput(float[] input) {
        if (!isEnabled()) {
            return;
        }
        if (input == null || input.length != OFFLINE_SIZE * OFFLINE_SIZE) {
            return;
        }
        byte[] rgb = new byte[OFFLINE_SIZE * OFFLINE_SIZE * 3];
        for (int y = 0; y < OFFLINE_SIZE; y++) {
            for (int x = 0; x < OFFLINE_SIZE; x++) {
                float value = input[y * OFFLINE_SIZE + x];
                int gray = clamp(Math.round((value + 1.0f) * 127.5f), 0, 255);
                setRgb(rgb, OFFLINE_SIZE, x, y, gray, gray, gray);
            }
        }
        writeBmp("offline-image", OFFLINE_SIZE, OFFLINE_SIZE, rgb);
    }

    public static void onlineTrajectoryInput(float[][] features) {
        if (!isEnabled()) {
            return;
        }
        if (features == null || features.length == 0) {
            return;
        }
        byte[] rgb = new byte[TRAJECTORY_SIZE * TRAJECTORY_SIZE * 3];
        fill(rgb, 255, 255, 255);

        Integer prevX = null;
        Integer prevY = null;
        float prevPen = 0.0f;
        for (float[] frame : features) {
            if (frame.length < 5) {
                continue;
            }
            int x = toPixelX(frame[3]);
            int y = toPixelY(frame[4]);
            float pen = frame[2];
            if (prevX != null && prevPen > 0.5f && pen > 0.5f) {
                drawLine(rgb, TRAJECTORY_SIZE, prevX, prevY, x, y, 0, 0, 0);
            }
            prevX = x;
            prevY = y;
            prevPen = pen;
        }

        int startIndex = firstPenDownIndex(features);
        int endIndex = lastPenDownIndex(features);
        for (int i = 0; i < features.length; i++) {
            float[] frame = features[i];
            if (frame.length < 5 || frame[2] <= 0.5f) {
                continue;
            }
            int x = toPixelX(frame[3]);
            int y = toPixelY(frame[4]);
            if (i == startIndex) {
                drawPoint(rgb, TRAJECTORY_SIZE, x, y, 24, 138, 66, 9);
            } else if (i == endIndex) {
                drawPoint(rgb, TRAJECTORY_SIZE, x, y, 197, 40, 40, 9);
            } else {
                drawPoint(rgb, TRAJECTORY_SIZE, x, y, 29, 95, 184, 5);
            }
        }
        writeBmp("online-trajectory", TRAJECTORY_SIZE, TRAJECTORY_SIZE, rgb);
        writeTrajectoryCsv(features);
    }

    public static void onlineCandidates(List<InkCandidate> candidates, String label) {
        if (!isEnabled()) {
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        StringBuilder line = new StringBuilder("AozaiInk online candidates [").append(label).append("]:");
        int count = Math.min(8, candidates.size());
        for (int i = 0; i < count; i++) {
            InkCandidate c = candidates.get(i);
            line.append(' ').append(i + 1).append('=').append(c.word())
                .append('@').append(String.format(java.util.Locale.ROOT, "%.4f", c.confidence()));
        }
        AozaiInkCore.LOGGER.info(line.toString());
    }

    private static void writeTrajectoryCsv(float[][] features) {
        try {
            Path dir = debugDir();
            Files.createDirectories(dir);
            Path path = dir.resolve("online-trajectory-" + LocalDateTime.now().format(STAMP) + ".csv");
            StringBuilder out = new StringBuilder("i,dx,dy,pen,x,y,progress\n");
            for (int i = 0; i < features.length; i++) {
                float[] f = features[i];
                out.append(i);
                for (int j = 0; j < 6; j++) {
                    out.append(',').append(String.format(java.util.Locale.ROOT, "%.9f", f[j]));
                }
                out.append('\n');
            }
            Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
            AozaiInkCore.LOGGER.info("AozaiInk debug dump written: {}", path.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            AozaiInkCore.LOGGER.warn("Failed to write AozaiInk trajectory CSV", e);
        }
    }

    private static int firstPenDownIndex(float[][] features) {
        for (int i = 0; i < features.length; i++) {
            if (features[i].length >= 5 && features[i][2] > 0.5f) {
                return i;
            }
        }
        return -1;
    }

    private static int lastPenDownIndex(float[][] features) {
        for (int i = features.length - 1; i >= 0; i--) {
            if (features[i].length >= 5 && features[i][2] > 0.5f) {
                return i;
            }
        }
        return -1;
    }

    private static int toPixelX(float x) {
        float normalized = x + 0.5f;
        return clamp(Math.round(TRAJECTORY_PADDING + normalized * (TRAJECTORY_SIZE - TRAJECTORY_PADDING * 2)), 0, TRAJECTORY_SIZE - 1);
    }

    private static int toPixelY(float y) {
        float normalized = y + 0.5f;
        return clamp(Math.round(TRAJECTORY_PADDING + normalized * (TRAJECTORY_SIZE - TRAJECTORY_PADDING * 2)), 0, TRAJECTORY_SIZE - 1);
    }

    private static void fill(byte[] rgb, int r, int g, int b) {
        for (int i = 0; i < rgb.length; i += 3) {
            rgb[i] = (byte) r;
            rgb[i + 1] = (byte) g;
            rgb[i + 2] = (byte) b;
        }
    }

    private static void drawLine(byte[] rgb, int width, int x0, int y0, int x1, int y1, int r, int g, int b) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0;
        int y = y0;
        while (true) {
            drawPoint(rgb, width, x, y, r, g, b, 3);
            if (x == x1 && y == y1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private static void drawPoint(byte[] rgb, int width, int cx, int cy, int r, int g, int b, int size) {
        int radius = size / 2;
        for (int y = cy - radius; y <= cy + radius; y++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                int dx = x - cx;
                int dy = y - cy;
                if (dx * dx + dy * dy <= radius * radius) {
                    setRgb(rgb, width, x, y, r, g, b);
                }
            }
        }
    }

    private static void setRgb(byte[] rgb, int width, int x, int y, int r, int g, int b) {
        int height = rgb.length / (width * 3);
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return;
        }
        int offset = (y * width + x) * 3;
        rgb[offset] = (byte) r;
        rgb[offset + 1] = (byte) g;
        rgb[offset + 2] = (byte) b;
    }

    private static void writeBmp(String prefix, int width, int height, byte[] rgb) {
        try {
            Path dir = debugDir();
            Files.createDirectories(dir);
            Path path = dir.resolve(prefix + "-" + LocalDateTime.now().format(STAMP) + ".bmp");
            Files.write(path, encodeBmp(width, height, rgb));
            AozaiInkCore.LOGGER.info("AozaiInk debug dump written: {}", path.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            AozaiInkCore.LOGGER.warn("Failed to write AozaiInk debug dump", e);
        }
    }

    private static Path debugDir() {
        return Path.of(System.getProperty("user.dir"), "aozaink-debug");
    }

    private static byte[] encodeBmp(int width, int height, byte[] rgb) throws IOException {
        int rowBytes = width * 3;
        int paddedRowBytes = (rowBytes + 3) & ~3;
        int pixelBytes = paddedRowBytes * height;
        int fileSize = 54 + pixelBytes;
        ByteArrayOutputStream out = new ByteArrayOutputStream(fileSize);
        out.write('B');
        out.write('M');
        writeInt(out, fileSize);
        writeShort(out, 0);
        writeShort(out, 0);
        writeInt(out, 54);
        writeInt(out, 40);
        writeInt(out, width);
        writeInt(out, height);
        writeShort(out, 1);
        writeShort(out, 24);
        writeInt(out, 0);
        writeInt(out, pixelBytes);
        writeInt(out, 2835);
        writeInt(out, 2835);
        writeInt(out, 0);
        writeInt(out, 0);
        byte[] padding = new byte[paddedRowBytes - rowBytes];
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 3;
                out.write(rgb[offset + 2] & 0xff);
                out.write(rgb[offset + 1] & 0xff);
                out.write(rgb[offset] & 0xff);
            }
            out.write(padding);
        }
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}


