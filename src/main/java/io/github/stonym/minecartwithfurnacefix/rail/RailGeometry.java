package io.github.stonym.minecartwithfurnacefix.rail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.block.data.Rail;
import org.bukkit.util.Vector;

/**
 * Geometry of a single rail block.
 *
 * Every shape is reduced to a short polyline that runs from its first edge to
 * its second edge. Straight rails need two points, curves are sampled along a
 * quarter circle of radius 0.5 whose centre sits on one of the block corners.
 * Sampling instead of solving the exact arc keeps everything else simple and
 * the error stays below 0.01 blocks.
 */
public final class RailGeometry {

    /** Height of a flat rail surface above the bottom of its block. */
    public static final double RAIL_HEIGHT = 0.0625;

    private static final int CURVE_STEPS = 6;

    private RailGeometry() {
    }

    /** The two edges this shape connects, first one is the canonical start. */
    public static Edge[] edges(Rail.Shape shape) {
        return switch (shape) {
            case NORTH_SOUTH -> new Edge[]{Edge.NORTH, Edge.SOUTH};
            case ASCENDING_SOUTH -> new Edge[]{Edge.NORTH, Edge.SOUTH};
            case ASCENDING_NORTH -> new Edge[]{Edge.SOUTH, Edge.NORTH};
            case EAST_WEST -> new Edge[]{Edge.WEST, Edge.EAST};
            case ASCENDING_EAST -> new Edge[]{Edge.WEST, Edge.EAST};
            case ASCENDING_WEST -> new Edge[]{Edge.EAST, Edge.WEST};
            case SOUTH_EAST -> new Edge[]{Edge.SOUTH, Edge.EAST};
            case SOUTH_WEST -> new Edge[]{Edge.SOUTH, Edge.WEST};
            case NORTH_WEST -> new Edge[]{Edge.NORTH, Edge.WEST};
            case NORTH_EAST -> new Edge[]{Edge.EAST, Edge.NORTH};
        };
    }

    public static boolean isAscending(Rail.Shape shape) {
        return switch (shape) {
            case ASCENDING_EAST, ASCENDING_WEST, ASCENDING_NORTH, ASCENDING_SOUTH -> true;
            default -> false;
        };
    }

    /** Which edge of an ascending rail is the raised one. */
    public static Edge highEdge(Rail.Shape shape) {
        return switch (shape) {
            case ASCENDING_EAST -> Edge.EAST;
            case ASCENDING_WEST -> Edge.WEST;
            case ASCENDING_NORTH -> Edge.NORTH;
            case ASCENDING_SOUTH -> Edge.SOUTH;
            default -> null;
        };
    }

    public static boolean isCurve(Rail.Shape shape) {
        return switch (shape) {
            case SOUTH_EAST, SOUTH_WEST, NORTH_WEST, NORTH_EAST -> true;
            default -> false;
        };
    }

    public static boolean connects(Rail.Shape shape, Edge edge) {
        Edge[] e = edges(shape);
        return e[0] == edge || e[1] == edge;
    }

    public static Edge other(Rail.Shape shape, Edge edge) {
        Edge[] e = edges(shape);
        if (e[0] == edge) {
            return e[1];
        }
        if (e[1] == edge) {
            return e[0];
        }
        return null;
    }

    /**
     * World position where the rail crosses a block edge.
     * For an ascending rail the raised end sits one block higher.
     */
    public static Vector edgePoint(int x, int y, int z, Rail.Shape shape, Edge edge) {
        double base = y + RAIL_HEIGHT;
        if (isAscending(shape) && highEdge(shape) == edge) {
            base += 1.0;
        }
        return switch (edge) {
            case NORTH -> new Vector(x + 0.5, base, z);
            case SOUTH -> new Vector(x + 0.5, base, z + 1.0);
            case EAST -> new Vector(x + 1.0, base, z + 0.5);
            case WEST -> new Vector(x, base, z + 0.5);
        };
    }

    /**
     * Polyline for one rail block, ordered so that it starts on {@code from}
     * and ends on {@code to}. Callers pass the two edges they actually want;
     * the canonical ordering is reversed when needed.
     */
    public static List<Vector> points(int x, int y, int z, Rail.Shape shape, Edge from, Edge to) {
        List<Vector> pts;
        if (isCurve(shape)) {
            pts = curvePoints(x, y, z, shape);
        } else {
            Edge[] e = edges(shape);
            pts = new ArrayList<>(2);
            pts.add(edgePoint(x, y, z, shape, e[0]));
            pts.add(edgePoint(x, y, z, shape, e[1]));
        }

        Edge[] e = edges(shape);
        if (from == e[1] && to == e[0]) {
            Collections.reverse(pts);
        }
        return pts;
    }

    private static List<Vector> curvePoints(int x, int y, int z, Rail.Shape shape) {
        double cx;
        double cz;
        double startDeg;
        double endDeg;
        switch (shape) {
            case SOUTH_EAST -> {
                cx = x + 1.0;
                cz = z + 1.0;
                startDeg = 180.0;
                endDeg = 270.0;
            }
            case SOUTH_WEST -> {
                cx = x;
                cz = z + 1.0;
                startDeg = 0.0;
                endDeg = -90.0;
            }
            case NORTH_WEST -> {
                cx = x;
                cz = z;
                startDeg = 0.0;
                endDeg = 90.0;
            }
            case NORTH_EAST -> {
                cx = x + 1.0;
                cz = z;
                startDeg = 90.0;
                endDeg = 180.0;
            }
            default -> throw new IllegalArgumentException("not a curve: " + shape);
        }

        double base = y + RAIL_HEIGHT;
        List<Vector> pts = new ArrayList<>(CURVE_STEPS + 1);
        for (int i = 0; i <= CURVE_STEPS; i++) {
            double t = (double) i / CURVE_STEPS;
            double deg = startDeg + (endDeg - startDeg) * t;
            double rad = Math.toRadians(deg);
            pts.add(new Vector(
                    cx + 0.5 * Math.cos(rad),
                    base,
                    cz + 0.5 * Math.sin(rad)));
        }
        return pts;
    }
}
