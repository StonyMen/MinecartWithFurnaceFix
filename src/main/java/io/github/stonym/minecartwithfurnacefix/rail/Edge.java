package io.github.stonym.minecartwithfurnacefix.rail;

import org.bukkit.util.Vector;

/**
 * The four horizontal sides of a rail block.
 *
 * A rail shape connects exactly two of them, so every piece of track can be
 * described as "enter through this edge, leave through that one".
 */
public enum Edge {

    NORTH(0, -1),
    SOUTH(0, 1),
    EAST(1, 0),
    WEST(-1, 0);

    private final int dx;
    private final int dz;

    Edge(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public int dx() {
        return dx;
    }

    public int dz() {
        return dz;
    }

    public Edge opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
        };
    }

    /** Unit vector pointing out of the block through this edge. */
    public Vector direction() {
        return new Vector(dx, 0.0, dz);
    }
}
