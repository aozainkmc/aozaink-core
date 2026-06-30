package com.aozainkmc.core.ocr;

import com.aozainkmc.core.api.InkPoint;
import com.aozainkmc.core.api.InkTrace;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

public final class StrokeRasterizer {

    private static final int SIZE = 64;
    private static final int BACKGROUND = 255;
    private static final int INK = 60;
    private static final float PADDING = 0.18f;
    private static final float STROKE_WIDTH = 4f;

    public float[] rasterize(InkTrace trace) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = createGraphics(image);
        Bounds bounds = Bounds.from(trace);

        for (List<InkPoint> stroke : trace.strokes()) {
            if (stroke.size() == 1) {
                InkPoint p = stroke.getFirst();
                int x = bounds.x(p.x());
                int y = bounds.y(p.y());
                g.fillOval(x - 2, y - 2, 4, 4);
                continue;
            }
            for (int i = 1; i < stroke.size(); i++) {
                InkPoint a = stroke.get(i - 1);
                InkPoint b = stroke.get(i);
                g.drawLine(bounds.x(a.x()), bounds.y(a.y()), bounds.x(b.x()), bounds.y(b.y()));
            }
        }

        g.dispose();

        float[] input = new float[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int gray = image.getRaster().getSample(x, y, 0);
                input[y * SIZE + x] = (gray / 255.0f - 0.5f) / 0.5f;
            }
        }
        return input;
    }

    private static Graphics2D createGraphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(BACKGROUND, BACKGROUND, BACKGROUND));
        g.fillRect(0, 0, SIZE, SIZE);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(INK, INK, INK));
        g.setStroke(new BasicStroke(STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        return g;
    }

    private record Bounds(float minX, float minY, float scale, float offsetX, float offsetY) {
        static Bounds from(InkTrace trace) {
            if (trace.isEmpty()) {
                return new Bounds(-1.0f, -1.0f, (SIZE - 4.0f) * 0.5f, 2.0f, 2.0f);
            }

            float minX = trace.minX();
            float minY = trace.minY();
            float maxX = trace.maxX();
            float maxY = trace.maxY();
            float width = Math.max(maxX - minX, 0.001f);
            float height = Math.max(maxY - minY, 0.001f);
            float pad = Math.max(width, height) * PADDING;
            minX -= pad;
            maxX += pad;
            minY -= pad;
            maxY += pad;
            width = maxX - minX;
            height = maxY - minY;

            float scale = (SIZE - 4.0f) / Math.max(width, height);
            float drawW = width * scale;
            float drawH = height * scale;
            float offsetX = (SIZE - drawW) * 0.5f;
            float offsetY = (SIZE - drawH) * 0.5f;
            return new Bounds(minX, minY, scale, offsetX, offsetY);
        }

        int x(float x) {
            return Math.round(offsetX + (x - minX) * scale);
        }

        int y(float y) {
            return Math.round(SIZE - 1.0f - (offsetY + (y - minY) * scale));
        }
    }
}
