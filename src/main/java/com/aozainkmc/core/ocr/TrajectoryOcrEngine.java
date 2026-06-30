package com.aozainkmc.core.ocr;

import com.aozainkmc.core.api.InkTrace;
import java.util.List;

public interface TrajectoryOcrEngine extends AutoCloseable {

    TrajectoryResult recognizeTrajectory(InkTrace trace, List<String> candidateWhitelist) throws Exception;
}
