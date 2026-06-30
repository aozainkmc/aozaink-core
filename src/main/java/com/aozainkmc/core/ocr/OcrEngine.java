package com.aozainkmc.core.ocr;

import com.aozainkmc.core.api.InkCandidate;
import java.util.List;

public interface OcrEngine extends AutoCloseable {

    List<InkCandidate> recognize(float[] input, int topK, List<String> candidateWhitelist) throws Exception;
}
