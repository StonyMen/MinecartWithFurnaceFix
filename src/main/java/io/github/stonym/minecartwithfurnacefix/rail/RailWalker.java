package io.github.stonym.minecartwithfurnacefix.rail;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.util.Vector;

/**
 * Walks the rail network block by block and turns it into a {@link RailPath}.
 *
 * The walk starts on the block the cart currently occupies and follows the
 * rails until the track ends, the next block is not a rail, or the neighbour
 * does not meet this block at the same height.
 *
 * Two things decide whether a neighbour belongs to the same line of track:
 *
 * <ul>
 *   <li>it must connect on the edge we are leaving through, which is what keeps
 *       two parallel tracks apart;</li>
 *   <li>the surface height on that shared edge must be identical, which is what
 *       tells a level neighbour apart from one that climbs or drops a block.</li>
 * </ul>
 *
 * The height rule matters most downhill. A rail that descends lives in the
 * <em>lower</em> block, so stepping onto it means y-1 while leaving it means
 * y unchanged. Guessing the step from the shape alone gets one of those two
 * wrong and loses the whole track from that point on, which looks exactly like
 * a train refusing to move.
 */
public final class RailWalker {

    /** Limit on the vertical step between two connected rail blocks. */
    private static final int[] STEP_DY = {0, 1, -1};

    /** Slack when comparing two edge heights; rails are placed on 1/16 units. */
    private static final double HEIGHT_EPSILON = 1.0E-4;

    /**
     * Smallest score that still counts as "this end lies ahead of the cart".
     *
     * A cart in the middle of a block, heading along its rail, scores about
     * 0.35-0.5 for the end it is heading towards. A rail running across the
     * heading scores ~0 for both ends, which is the only case where the walk
     * has nothing to go on.
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
     * @param startX    block the cart is in
     * @param startPos  where the cart actually is inside that block, or null
     * @param heading   horizontal unit vector of travel
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
                // First block: the cart's own spot inside the block says which
                // end of the rail is ahead of it. Picking by heading alone
                // cannot work here — halfway through a curve the heading is
                // 45 degrees away from both edges, and the old code then gave
                // up and guessed from the furnace push, which on a curve can
                // point straight back through the edge the cart came in by.
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

            // Where the rail surface sits on the edge we are leaving through.
            // The neighbour has to present the same height on the opposite
            // edge, otherwise the two pieces do not actually join up.
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
     * The rail's two ends are projected onto the heading, measured from the
     * cart's own position, and the one that scores highest is ahead. On a curve
     * that is the only signal that works: the heading halfway through a 90
     * degree bend is 45 degrees from both ends, so comparing edge directions
     * gives a tie, and the fallback used to be the furnace push — which on a
     * curve can easily be the cardinal the cart came in by, sending the whole
     * path straight back the way it arrived.
     *
     * @return null when the rail runs across the heading and neither end is
     *         meaningfully ahead, i.e. the cart is not on this piece of track
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

        // No position to go on (or the cart sits exactly between the ends):
        // fall back to the edge the heading points out of.
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
}
