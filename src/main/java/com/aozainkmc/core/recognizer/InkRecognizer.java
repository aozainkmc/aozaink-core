package com.aozainkmc.core.recognizer;

import com.aozainkmc.core.api.InkRecognitionRequest;
import com.aozainkmc.core.api.InkRecognitionResult;
import com.aozainkmc.core.api.InkRecognizedEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public interface InkRecognizer {

    InkRecognitionResult recognize(InkRecognitionRequest request) throws Exception;

    InkRecognizedEvent recognizeAndBroadcast(
        InkRecognitionRequest request,
        MinecraftServer server,
        ServerPlayer player
    ) throws Exception;
}
