package com.aozainkmc.core.recognizer;

import com.aozainkmc.core.api.InkRecognitionRequest;
import com.aozainkmc.core.api.InkRecognitionResult;
import com.aozainkmc.core.api.InkRecognizedEvent;
import com.aozainkmc.core.api.InkSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public interface InkRecognizer {

    InkRecognitionResult recognize(InkRecognitionRequest request) throws Exception;

    InkRecognizedEvent recognizeAndBroadcast(
        InkRecognitionRequest request,
        MinecraftServer server,
        ServerPlayer player
    ) throws Exception;

    /**
     * Directly attaches an already-computed recognition result to the mark store and posts
     * {@link InkRecognizedEvent} on the server thread. This is intended for multiplayer flows
     * where the client only sends the raw {@link com.aozainkmc.core.api.InkTrace} and the server
     * performs the actual ONNX inference asynchronously.
     */
    InkRecognizedEvent broadcast(
        InkRecognitionResult result,
        InkSource source,
        ServerPlayer player
    ) throws Exception;
}
