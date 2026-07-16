package com.aozainkmc.core.ocr;

import com.aozainkmc.core.api.InkCandidate;
import com.aozainkmc.core.api.InkTrace;
import java.util.List;

public interface OcrEngine extends AutoCloseable {

    List<InkCandidate> recognizeImage(float[] input, List<String> candidateWhitelist) throws Exception;

    TrajectoryResult recognizeTrajectory(InkTrace trace, List<String> candidateWhitelist) throws Exception;
}
