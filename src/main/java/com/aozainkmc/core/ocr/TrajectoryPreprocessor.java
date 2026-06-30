package com.aozainkmc.core.ocr;

import com.aozainkmc.core.api.InkPoint;
import com.aozainkmc.core.api.InkTrace;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TrajectoryPreprocessor {

    private static final float EPS_MIN = 1e-6f;
    private static final int MIN_POINTS_PER_STROKE = 2;

    private final int maxPoints;
    private final float simplifyEpsRatio;
    private final boolean useArcProgress;

    public TrajectoryPreprocessor(int maxPoints, float simplifyEpsRatio, String progressMode) {
        this.maxPoints = maxPoints;
        this.simplifyEpsRatio = simplifyEpsRatio;
        this.useArcProgress = "arc".equals(progressMode);
    }

    public TrajectoryFeatures preprocess(InkTrace trace) {
        List<List<Point>> simplified = simplifyStrokes(toRawStrokes(trace));
        if (simplified.isEmpty()) {
            return null;
        }

        int simplifiedStrokeCount = simplified.size();
        int simplifiedPointCount = 0;
        for (List<Point> stroke : simplified) {
            simplifiedPointCount += stroke.size();
        }

        List<NormPoint> norm = normalize(buildRawPoints(simplified));
        if (norm.isEmpty()) {
            return null;
        }

        norm = resample(norm, maxPoints);
        float[][] features = toFeatures(norm);
        return new TrajectoryFeatures(features, simplifiedStrokeCount, simplifiedPointCount);
    }

    public record TrajectoryFeatures(
        float[][] features,
        int simplifiedStrokeCount,
        int simplifiedPointCount
    ) {}

    private static List<List<Point>> toRawStrokes(InkTrace trace) {
        List<List<Point>> strokes = new ArrayList<>();
        for (List<InkPoint> stroke : trace.strokes()) {
            List<Point> pts = new ArrayList<>(stroke.size());
            for (InkPoint p : stroke) {
                pts.add(new Point(p.x(), p.y()));
            }
            if (!pts.isEmpty()) {
                strokes.add(pts);
            }
        }
        return strokes;
    }

    private List<List<Point>> simplifyStrokes(List<List<Point>> strokes) {
        List<Point> all = new ArrayList<>();
        for (List<Point> stroke : strokes) {
            all.addAll(stroke);
        }
        if (all.isEmpty()) {
            return Collections.emptyList();
        }

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Point p : all) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
        }
        float range = Math.max(Math.max(maxX - minX, maxY - minY), EPS_MIN);
        float eps = Math.max(range * simplifyEpsRatio, EPS_MIN);

        List<List<Point>> out = new ArrayList<>();
        for (List<Point> stroke : strokes) {
            if (stroke.size() <= MIN_POINTS_PER_STROKE) {
                out.add(new ArrayList<>(stroke));
                continue;
            }
            List<Point> simp = rdp(stroke, eps);
            if (simp.size() < MIN_POINTS_PER_STROKE) {
                simp = List.of(stroke.getFirst(), stroke.getLast());
            }
            out.add(simp);
        }
        return out;
    }

    private static List<Point> rdp(List<Point> points, float eps) {
        if (points.size() <= 2) {
            return new ArrayList<>(points);
        }
        Point a = points.getFirst();
        Point b = points.getLast();
        int bestI = 0;
        float bestD = -1.0f;
        for (int i = 1; i < points.size() - 1; i++) {
            float d = pointLineDistance(points.get(i), a, b);
            if (d > bestD) {
                bestI = i;
                bestD = d;
            }
        }
        if (bestD <= eps) {
            return new ArrayList<>(List.of(a, b));
        }
        List<Point> left = rdp(points.subList(0, bestI + 1), eps);
        List<Point> right = rdp(points.subList(bestI, points.size()), eps);
        List<Point> merged = new ArrayList<>(left);
        merged.addAll(right.subList(1, right.size()));
        return merged;
    }

    private static float pointLineDistance(Point p, Point a, Point b) {
        float vx = b.x - a.x;
        float vy = b.y - a.y;
        float wx = p.x - a.x;
        float wy = p.y - a.y;
        float denom = vx * vx + vy * vy;
        if (denom <= 1e-12f) {
            return (float) Math.sqrt((p.x - a.x) * (p.x - a.x) + (p.y - a.y) * (p.y - a.y));
        }
        float t = Math.max(0.0f, Math.min(1.0f, (wx * vx + wy * vy) / denom));
        float qx = a.x + t * vx;
        float qy = a.y + t * vy;
        return (float) Math.sqrt((p.x - qx) * (p.x - qx) + (p.y - qy) * (p.y - qy));
    }

    private static List<RawPoint> buildRawPoints(List<List<Point>> strokes) {
        List<RawPoint> pts = new ArrayList<>();
        for (int si = 0; si < strokes.size(); si++) {
            List<Point> stroke = strokes.get(si);
            if (stroke.isEmpty()) continue;
            for (int k = 0; k < stroke.size(); k++) {
                if (k == 0 && si > 0 && !pts.isEmpty()) {
                    RawPoint last = pts.getLast();
                    pts.add(new RawPoint(last.x, last.y, 0.0f));
                }
                Point p = stroke.get(k);
                pts.add(new RawPoint(p.x, p.y, 1.0f));
            }
            RawPoint last = pts.getLast();
            pts.add(new RawPoint(last.x, last.y, 0.0f));
        }
        return pts;
    }

    private static List<NormPoint> normalize(List<RawPoint> pts) {
        if (pts.isEmpty()) {
            return Collections.emptyList();
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (RawPoint p : pts) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
        }
        float range = Math.max(Math.max(maxX - minX, maxY - minY), EPS_MIN);
        float cx = (minX + maxX) / 2.0f;
        float cy = (minY + maxY) / 2.0f;

        List<NormPoint> norm = new ArrayList<>(pts.size());
        for (RawPoint p : pts) {
            norm.add(new NormPoint((p.x - cx) / range, (p.y - cy) / range, p.pen));
        }
        return norm;
    }

    private static List<NormPoint> resample(List<NormPoint> norm, int maxPoints) {
        if (norm.size() <= maxPoints) {
            return norm;
        }

        Set<Integer> mustKeep = new HashSet<>();
        mustKeep.add(0);
        mustKeep.add(norm.size() - 1);
        for (int i = 0; i < norm.size(); i++) {
            if (norm.get(i).pen <= 0.5f) {
                mustKeep.add(i);
                if (i + 1 < norm.size()) {
                    mustKeep.add(i + 1);
                }
            }
        }

        if (mustKeep.size() >= maxPoints) {
            float stride = norm.size() / (float) maxPoints;
            List<Integer> idxs = new ArrayList<>(maxPoints);
            for (int i = 0; i < maxPoints; i++) {
                idxs.add(Math.min((int) (i * stride), norm.size() - 1));
            }
            idxs.set(0, 0);
            idxs.set(idxs.size() - 1, norm.size() - 1);
            Set<Integer> unique = new HashSet<>(idxs);
            List<NormPoint> out = new ArrayList<>(unique.size());
            for (int i : unique.stream().sorted().toList()) {
                out.add(norm.get(i));
            }
            return out;
        }

        int remaining = maxPoints - mustKeep.size();
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < norm.size(); i++) {
            if (!mustKeep.contains(i)) {
                free.add(i);
            }
        }
        if (remaining > 0 && !free.isEmpty()) {
            if (remaining >= free.size()) {
                mustKeep.addAll(free);
            } else {
                float stride = free.size() / (float) remaining;
                for (int i = 0; i < remaining; i++) {
                    mustKeep.add(free.get(Math.min((int) (i * stride), free.size() - 1)));
                }
            }
        }
        List<Integer> sorted = mustKeep.stream().sorted().toList();
        List<NormPoint> out = new ArrayList<>(sorted.size());
        for (int i : sorted) {
            out.add(norm.get(i));
        }
        return out;
    }

    private float[][] toFeatures(List<NormPoint> norm) {
        int t = norm.size();
        float[] arc = new float[t];
        float total = 0.0f;
        arc[0] = 0.0f;
        for (int i = 1; i < t; i++) {
            NormPoint p0 = norm.get(i - 1);
            NormPoint p1 = norm.get(i);
            if (p0.pen > 0.5f && p1.pen > 0.5f) {
                total += (float) Math.sqrt((p1.x - p0.x) * (p1.x - p0.x) + (p1.y - p0.y) * (p1.y - p0.y));
            }
            arc[i] = total;
        }

        float[][] feats = new float[t][6];
        Float prevX = null;
        Float prevY = null;
        float prevPen = 1.0f;
        for (int i = 0; i < t; i++) {
            NormPoint p = norm.get(i);
            float dx, dy;
            if (prevX == null || prevPen <= 0.5f) {
                dx = 0.0f;
                dy = 0.0f;
            } else {
                dx = p.x - prevX;
                dy = p.y - prevY;
            }
            float progress;
            if (useArcProgress && total > 1e-8f) {
                progress = arc[i] / total;
            } else {
                progress = i / (float) Math.max(t - 1, 1);
            }
            feats[i][0] = dx;
            feats[i][1] = dy;
            feats[i][2] = p.pen;
            feats[i][3] = p.x;
            feats[i][4] = p.y;
            feats[i][5] = progress;
            prevX = p.x;
            prevY = p.y;
            prevPen = p.pen;
        }
        return feats;
    }

    private record Point(float x, float y) {}
    private record RawPoint(float x, float y, float pen) {}
    private record NormPoint(float x, float y, float pen) {}
}
