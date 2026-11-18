package io.github.stonym.minecartwithfurnacefix.rail;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.type.RedstoneRail;
import org.bukkit.util.Vector;

/**
 * Walks the rail network block by block and turns it into a {@link RailPath}.
 *
 * A neighbour joins the line only if it connects on the edge we are leaving
 * through (which keeps parallel tracks apart) and its surface height on that
 * shared edge is identical (which tells a level neighbour from one that climbs or
 * drops). The height rule matters downhill: a descending rail lives in the lower
 * block, so entering it means y-1 while leaving it means y unchanged.
 */
public final class RailWalker {

    /** Vertical steps tried between two connected rail blocks. */
    private static final int[] STEP_DY = {0, 1, -1};

    /** Slack when comparing edge heights; rails are placed on 1/16 units. */
    private static final double HEIGHT_EPSILON = 1.0E-4;

    /**
     * Smallest score that still counts as "this end lies ahead of the cart". A
     * rail running across the heading scores ~0 for both ends, which is the only
     * case where the walk has nothing to go on.
     */
    private static final double FIRST_EXIT_MIN_SCORE = 0.05;

    private RailWalker() {
    }

    /** Block lookup, kept separate so the walk can be exercised without a server. */
    public interface BlockSource {
        Rail.Shape shapeAt(int x, int y, int z);
    }

    public static RailPath walk(World world, Block start, Vector heading, int maxBlocks) {
        return walk((x, y, z) -> shapeOf(world.getBlockAt(x, y, z)),
                start.getX(), start.getY(), start.getZ(), null, heading, maxBlocks);
    }

    public static RailPath walk(World world, Block start, Vector startPos,
            Vector heading, int maxBlocks) {
        return walk((x, y, z) -> shapeOf(world.getBlockAt(x, y, z)),
                start.getX(), start.getY(), start.getZ(), startPos, heading, maxBlocks);
    }

    /**
     * @param startPos where the cart sits inside its block, or null
     * @param maxBlocks safety limit on how many blocks to follow
     */
    public static RailPath walk(BlockSource source, int startX, int startY, int startZ,
            Vector heading, int maxBlocks) {
        return walk(source, startX, startY, startZ, null, heading, maxBlocks);
    }

    public static RailPath walk(BlockSource source, int startX, int startY, int startZ,
            Vector startPos, Vector heading, int maxBlocks) {
        Vector flat = heading.clone().setY(0.0);
        if (flat.lengthSquared() < 1.0E-6) {
            return RailPath.empty();
        }
        flat.normalize();

        List<Vector> points = new ArrayList<>();
        int x = startX;
        int y = startY;
        int z = startZ;
        Edge entry = null;

        for (int step = 0; step < maxBlocks; step++) {
            Rail.Shape shape = source.shapeAt(x, y, z);
            if (shape == null) {
                break;
            }

            Edge exit = entry == null ? null : RailGeometry.other(shape, entry);
            if (exit == null) {
                // First block: the cart's spot inside it says which end is ahead.
                exit = firstExit(x, y, z, shape, startPos, flat);
                if (exit == null) {
                    break;
                }
            }

            Edge from = RailGeometry.other(shape, exit);
            if (from == null) {
                break;
            }
            points.addAll(RailGeometry.points(x, y, z, shape, from, exit));

            // The neighbour must present the same height on the opposite edge, or
            // the two pieces do not actually join up.
            double exitHeight = RailGeometry.edgePoint(x, y, z, shape, exit).getY();
            Edge arriving = exit.opposite();

            boolean joined = false;
            for (int dy : STEP_DY) {
                int nx = x + exit.dx();
                int ny = y + dy;
                int nz = z + exit.dz();

                Rail.Shape nextShape = source.shapeAt(nx, ny, nz);
                if (nextShape == null || !RailGeometry.connects(nextShape, arriving)) {
                    continue;
                }
                double entryHeight = RailGeometry.edgePoint(nx, ny, nz, nextShape, arriving).getY();
                if (Math.abs(entryHeight - exitHeight) > HEIGHT_EPSILON) {
                    continue;
                }
                x = nx;
                y = ny;
                z = nz;
                entry = arriving;
                joined = true;
                break;
            }
            if (!joined) {
                break;
            }
        }

        return RailPath.of(points);
    }

    /**
     * Which edge of the first block the cart leaves through.
     *
     * The rail's two ends are projected onto the heading from the cart's own
     * position. That is the only signal that works on a curve: halfway through a
     * 90 degree bend the heading is 45 degrees from both ends, so comparing edge
     * directions ties.
     *
     * @return null when the rail runs across the heading, i.e. the cart is not on
     *         this piece of track
     */
    private static Edge firstExit(int x, int y, int z, Rail.Shape shape,
            Vector startPos, Vector flat) {
        Edge[] edges = RailGeometry.edges(shape);

        if (startPos != null) {
            Edge best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (Edge edge : edges) {
                Vector to = RailGeometry.edgePoint(x, y, z, shape, edge)
                        .subtract(startPos)
                        .setY(0.0);
                double score = to.getX() * flat.getX() + to.getZ() * flat.getZ();
                if (score > bestScore) {
                    bestScore = score;
                    best = edge;
                }
            }
            if (best != null && bestScore > FIRST_EXIT_MIN_SCORE) {
                return best;
            }
        }

        // No position to go on, or the cart sits exactly between the ends.
        double first = edges[0].direction().dot(flat);
        double second = edges[1].direction().dot(flat);
        if (Math.abs(first - second) < 0.1) {
            return null;
        }
        return first >= second ? edges[0] : edges[1];
    }

    public static Rail.Shape shapeOf(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Rail rail) {
            return rail.getShape();
        }
        return null;
    }
    public static boolean isRail(Block block) {
        return block.getBlockData() instanceof Rail;
    }

    /**
     * Whether a minecart standing here is on a rail.
     *
     * A cart rides with its feet on the rail surface, so the block it occupies is
     * the rail itself. The block below counts too, for an ascending rail whose
     * surface carries the cart into the block above. Nothing above: a cart
     * hovering over a track cannot couple to anything.
     */
    public static boolean standsOnRail(Location location) {
        if (location == null) {
            return false;
        }
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return isRail(world.getBlockAt(x, y, z)) || isRail(world.getBlockAt(x, y - 1, z));
    }

    /**
     * Whether the rail a cart rides here is a powered rail that is switched on.
     *
     * Same coordinates as {@link #standsOnRail}, but the order matters: the block
     * the cart occupies decides first, so a powered rail on a line underneath does
     * not boost this one.
     */
    public static boolean poweredRailUnder(Location location) {
        if (location == null) {
            return false;
        }
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        Block at = world.getBlockAt(x, y, z);
        if (isRail(at)) {
            return isPoweredRail(at);
        }
        return isPoweredRail(world.getBlockAt(x, y - 1, z));
    }

    private static boolean isPoweredRail(Block block) {
        return block.getType() == Material.POWERED_RAIL
                && block.getBlockData() instanceof RedstoneRail rail
                && rail.isPowered();
    }
}
