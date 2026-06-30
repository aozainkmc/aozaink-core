package com.aozainkmc.core.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InkTrace {

    private final List<List<InkPoint>> strokes;
    private final float minX;
    private final float minY;
    private final float maxX;
    private final float maxY;

    public InkTrace(List<List<InkPoint>> strokes) {
        this.strokes = List.copyOf(strokes.stream()
            .map(Collections::unmodifiableList)
            .toList());
        float mnX = Float.MAX_VALUE, mnY = Float.MAX_VALUE;
        float mxX = Float.MIN_VALUE, mxY = Float.MIN_VALUE;
        for (List<InkPoint> stroke : this.strokes) {
            for (InkPoint p : stroke) {
                mnX = Math.min(mnX, p.x());
                mnY = Math.min(mnY, p.y());
                mxX = Math.max(mxX, p.x());
                mxY = Math.max(mxY, p.y());
            }
        }
        if (this.strokes.isEmpty()) {
            mnX = 0;
            mnY = 0;
            mxX = 1;
            mxY = 1;
        }
        this.minX = mnX;
        this.minY = mnY;
        this.maxX = mxX;
        this.maxY = mxY;
    }

    public List<List<InkPoint>> strokes() {
        return strokes;
    }

    public boolean isEmpty() {
        for (List<InkPoint> stroke : strokes) {
            if (!stroke.isEmpty()) return false;
        }
        return true;
    }

    public float minX() { return minX; }
    public float minY() { return minY; }
    public float maxX() { return maxX; }
    public float maxY() { return maxY; }

    public float width() { return Math.max(0.001f, maxX - minX); }
    public float height() { return Math.max(0.001f, maxY - minY); }

    public float padLeft(float padding) { return minX - width() * padding; }
    public float padRight(float padding) { return maxX + width() * padding; }
    public float padBottom(float padding) { return minY - height() * padding; }
    public float padTop(float padding) { return maxY + height() * padding; }
}
