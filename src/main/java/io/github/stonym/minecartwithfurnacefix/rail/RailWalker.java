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
 * The walk starts on the block the cart currently occupies and continues in a
 * straight heading until the track ends, the next block is not a rail, or the
 * neighbour does not accept the edge we would arrive on. That last check is
 * what keeps two parallel tracks from being joined by accident.
 */
public final class RailWalker {

    private RailWalker() {
    }

    /**
     * @param start       block the cart is in
     * @param heading     horizontal unit vector of travel
     * @param maxBlocks   safety limit on how many blocks to follow
     */
    public static RailPath walk(World world, Block start, Vector heading, int maxBlocks) {
        Vector flat = heading.clone().setY(0.0);
        if (flat.lengthSquared() < 1.0E-6) {
            return RailPath.empty();
        }
        flat.normalize();

        List<Vector> points = new ArrayList<>();
        Block current = start;
        Edge entry = null;

        for (int step = 0; step < maxBlocks; step++) {
            Rail.Shape shape = shapeOf(current);
            if (shape == null) {
                break;
            }

            Edge[] edges = RailGeometry.edges(shape);
            Edge exit;
            if (entry == edges[0]) {
                exit = edges[1];
            } else if (entry == edges[1]) {
                exit = edges[0];
            } else {
                // First block: we do not know where the cart came from, so pick
                // the edge the heading points out of.
                double first = edges[0].direction().dot(flat);
                double second = edges[1].direction().dot(flat);
                if (Math.abs(first - second) < 0.1) {
                    // The rail runs across the direction of travel. Nothing
                    // sensible to follow.
                    break;
                }
                exit = first >= second ? edges[0] : edges[1];
                entry = exit == edges[0] ? edges[1] : edges[0];
            }

            points.addAll(RailGeometry.points(
                    current.getX(), current.getY(), current.getZ(), shape, entry, exit));

            int dy = 0;
            if (RailGeometry.isAscending(shape)) {
                Edge high = RailGeometry.highEdge(shape);
                dy = exit == high ? 1 : -1;
            }

            Block next = current.getRelative(exit.dx(), dy, exit.dz());
            Rail.Shape nextShape = shapeOf(next);
            if (nextShape == null || !RailGeometry.connects(nextShape, exit.opposite())) {
                break;
            }

            entry = exit.opposite();
            current = next;
        }

        return RailPath.of(points);
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
