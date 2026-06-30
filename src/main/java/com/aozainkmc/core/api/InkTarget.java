package com.aozainkmc.core.api;

import java.util.UUID;

public record InkTarget(
    InkTargetType type,
    String dimension,
    UUID entityUuid,
    long packedBlockPos,
    int chunkX,
    int chunkZ,
    String slot
) {
    public static InkTarget player(String dimension, UUID playerId) {
        return new InkTarget(InkTargetType.PLAYER, dimension, playerId, 0L, 0, 0, null);
    }

    public static InkTarget entity(String dimension, UUID entityId) {
        return new InkTarget(InkTargetType.ENTITY, dimension, entityId, 0L, 0, 0, null);
    }

    public static InkTarget block(String dimension, long packedBlockPos) {
        return new InkTarget(InkTargetType.BLOCK, dimension, null, packedBlockPos, 0, 0, null);
    }

    public static InkTarget chunk(String dimension, int chunkX, int chunkZ) {
        return new InkTarget(InkTargetType.CHUNK, dimension, null, 0L, chunkX, chunkZ, null);
    }

    public static InkTarget marker(String dimension, long packedBlockPos, int chunkX, int chunkZ) {
        return new InkTarget(InkTargetType.MARKER, dimension, null, packedBlockPos, chunkX, chunkZ, null);
    }
}
