package io.github.stonym.minecartwithfurnacefix.rail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.util.Vector;

/**
 * An ordered polyline addressed by one number: how far along the track.
 * {@code s = 0} is the start, {@code s = length()} the end.
 */
public final class RailPath {

    private final List<Vector> points;
    private final double[] cumulative;
    private final double length;

    private RailPath(List<Vector> points) {
        this.points = points;
        this.cumulative = new double[points.size()];
        double acc = 0.0;
        for (int i = 1; i < points.size(); i++) {
            acc += points.get(i).distance(points.get(i - 1));
            cumulative[i] = acc;
        }
        this.length = acc;
    }

    /** Builds a path, dropping duplicate consecutive points. */
    public static RailPath of(List<Vector> raw) {
        List<Vector> clean = new ArrayList<>(raw.size());
        for (Vector p : raw) {
            if (clean.isEmpty() || clean.get(clean.size() - 1).distanceSquared(p) > 1.0E-9) {
                clean.add(p.clone());
            }
        }
        return new RailPath(clean);
    }

    public static RailPath empty() {
        return new RailPath(new ArrayList<>());
    }

    public boolean isEmpty() {
        return points.size() < 2 || length < 1.0E-4;
    }

    public double length() {
        return length;
    }

    public RailPath reversed() {
        List<Vector> copy = new ArrayList<>(points);
        Collections.reverse(copy);
        return new RailPath(copy);
    }

    public Vector pointAt(double s) {
        if (points.isEmpty()) {
            return new Vector();
        }
        if (points.size() == 1) {
            return points.get(0).clone();
        }
        if (s <= 0.0) {
            return points.get(0).clone();
        }
        if (s >= length) {
            return points.get(points.size() - 1).clone();
        }
        int index = segmentAt(s);
        Vector a = points.get(index);
        Vector b = points.get(index + 1);
        double span = cumulative[index + 1] - cumulative[index];
        double t = span < 1.0E-9 ? 0.0 : (s - cumulative[index]) / span;
        return a.clone().add(b.clone().subtract(a).multiply(t));
    }

    /** Unit direction of travel at arc length {@code s}. */
    public Vector tangentAt(double s) {
        if (points.size() < 2) {
            return new Vector(0.0, 0.0, 1.0);
        }
        double clamped = Math.max(0.0, Math.min(length, s));
        int index;
        if (clamped <= 0.0) {
            index = 0;
        } else if (clamped >= length) {
            index = points.size() - 2;
        } else {
            index = segmentAt(clamped);
        }
        Vector delta = points.get(index + 1).clone().subtract(points.get(index));
        if (delta.lengthSquared() < 1.0E-9) {
            return new Vector(0.0, 0.0, 1.0);
        }
        return delta.normalize();
    }

    /**
     * The part of this path starting at arc length {@code s}.
     *
     * The walker always starts at the centre of a block, but the cart can sit
     * anywhere inside it; trimming is what puts the engine at {@code s = 0}.
     */
    public RailPath trimFrom(double s) {
        if (points.size() < 2 || s <= 1.0E-9) {
            return this;
        }
        if (s >= length - 1.0E-9) {
            return empty();
        }
        int index = segmentAt(s);
        List<Vector> result = new ArrayList<>();
        result.add(pointAt(s));
        for (int i = index + 1; i < points.size(); i++) {
            result.add(points.get(i));
        }
        return new RailPath(result);
    }

    /** Closest point on the path to {@code world}, with the arc length there. */
    public Hit nearest(Vector world) {
        if (points.isEmpty()) {
            return new Hit(0.0, Double.MAX_VALUE);
        }
        if (points.size() == 1) {
            return new Hit(0.0, points.get(0).distanceSquared(world));
        }
        double bestS = 0.0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < points.size() - 1; i++) {
            Vector a = points.get(i);
            Vector ab = points.get(i + 1).clone().subtract(a);
            double lengthSq = ab.lengthSquared();
            double t = 0.0;
            if (lengthSq > 1.0E-9) {
                t = world.clone().subtract(a).dot(ab) / lengthSq;
                t = Math.max(0.0, Math.min(1.0, t));
            }
            Vector projection = a.clone().add(ab.multiply(t));
            double dist = projection.distanceSquared(world);
            if (dist < bestDist) {
                bestDist = dist;
                bestS = cumulative[i] + Math.sqrt(lengthSq) * t;
            }
        }
        return new Hit(bestS, bestDist);
    }

    private int segmentAt(double s) {
        int low = 0;
        int high = cumulative.length - 1;
        while (low < high - 1) {
            int mid = (low + high) >>> 1;
            if (cumulative[mid] <= s) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return Math.min(low, points.size() - 2);
    }

    /** Result of projecting a world point onto the path. */
    public record Hit(double s, double distSq) {
    }
}
