package com.aozainkmc.core.ocr;

import com.aozainkmc.core.AozaiInkCore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import net.neoforged.fml.loading.FMLPaths;

final class OrtNativeCache {
    private static final String ROOT = "/META-INF/aozaink_core/onnxruntime-native/";
    private static final String INDEX = ROOT + "index.properties";
    private static final String NATIVE_PATH_PROPERTY = "onnxruntime.native.path";
    private static final String CACHE_PROPERTY = "aozaink_core.native_cache";

    private static boolean prepared;

    private OrtNativeCache() {
    }

    static synchronized void prepare() throws IOException {
        if (prepared) {
            return;
        }
        byte[] indexBytes = readResource(INDEX);
        if (indexBytes == null || System.getProperty(NATIVE_PATH_PROPERTY) != null) {
            prepared = true;
            return;
        }
        Properties index = new Properties();
        try (InputStream stream = new ByteArrayInputStream(indexBytes)) {
            index.load(stream);
        }
        String platform = platform();
        List<String> keys = index.stringPropertyNames().stream()
            .filter(key -> key.startsWith(platform + "/"))
            .sorted()
            .toList();
        if (keys.isEmpty()) {
            throw new IllegalStateException("No packed ONNX Runtime libraries for " + platform);
        }

        Path directory = cacheRoot().resolve(platform + "-" + HexFormat.of().formatHex(sha256(indexBytes), 0, 6));
        Files.createDirectories(directory);
        long started = System.nanoTime();
        int unpacked = 0;
        for (String key : keys) {
            String[] expected = index.getProperty(key).split(":", 2);
            long size = Long.parseLong(expected[0]);
            Path target = directory.resolve(key.substring(platform.length() + 1));
            if (Files.isRegularFile(target) && Files.size(target) == size) {
                continue;
            }
            unpack(ROOT + key + ".xz", target, size, expected[1]);
            unpacked++;
        }
        if (unpacked > 0) {
            AozaiInkCore.LOGGER.info("Unpacked {} ONNX Runtime libraries to {} in {} ms",
                unpacked, directory, (System.nanoTime() - started) / 1_000_000L);
        }
        System.setProperty(NATIVE_PATH_PROPERTY, directory.toAbsolutePath().toString());
        prepared = true;
    }

    private static void unpack(String resource, Path target, long size, String sha256) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
        try {
            MessageDigest digest = newDigest();
            try (InputStream packed = OrtNativeCache.class.getResourceAsStream(resource)) {
                if (packed == null) {
                    throw new IOException("Missing packed ONNX Runtime library: " + resource);
                }
                try (InputStream in = XzStreams.open(packed);
                     OutputStream out = new DigestOutputStream(Files.newOutputStream(temp), digest)) {
                    in.transferTo(out);
                }
            }
            if (Files.size(temp) != size || !HexFormat.of().formatHex(digest.digest()).equals(sha256)) {
                throw new IOException("Packed ONNX Runtime library is corrupt: " + resource);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Path cacheRoot() {
        String override = System.getProperty(CACHE_PROPERTY);
        if (override != null) {
            return Path.of(override);
        }
        return FMLPaths.GAMEDIR.get().resolve(".molu").resolve("cache").resolve("onnxruntime");
    }

    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osName;
        if (os.contains("mac") || os.contains("darwin")) {
            osName = "osx";
        } else if (os.contains("win")) {
            osName = "win";
        } else if (os.contains("nux")) {
            osName = "linux";
        } else {
            throw new IllegalStateException("Unsupported operating system for ONNX Runtime: " + os);
        }
        String archName;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            archName = "x64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            archName = "aarch64";
        } else {
            throw new IllegalStateException("Unsupported architecture for ONNX Runtime: " + arch);
        }
        return osName + "-" + archName;
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream stream = OrtNativeCache.class.getResourceAsStream(path)) {
            return stream == null ? null : stream.readAllBytes();
        }
    }

    private static byte[] sha256(byte[] bytes) {
        return newDigest().digest(bytes);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
