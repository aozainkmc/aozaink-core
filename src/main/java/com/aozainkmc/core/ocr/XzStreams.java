package com.aozainkmc.core.ocr;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.tukaani.xz.XZInputStream;

final class XzStreams {
    private XzStreams() {
    }

    static InputStream open(InputStream packed) throws IOException {
        return new XZInputStream(new BufferedInputStream(packed, 1 << 16));
    }
}
