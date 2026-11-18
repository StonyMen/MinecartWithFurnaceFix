package io.github.stonym.minecartwithfurnacefix.train;

import io.github.stonym.minecartwithfurnacefix.config.PluginConfig;
import io.github.stonym.minecartwithfurnacefix.rail.RailPath;
import io.github.stonym.minecartwithfurnacefix.rail.RailWalker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.util.Vector;

/**
 * Moves a train along its cached rail path.
 *
 * Two state variables: {@code s}, how far the engine has travelled along the
 * path, and one signed {@code speed} shared by the whole train. Every cart sits
 * at a fixed arc-length offset from the engine, so a curve cannot change the
 * gaps between them — that is the whole fix for trains tearing apart on corners.
 *
 * Vanilla keeps ticking the carts; this class only overwrites position and
 * velocity every tick.
 */
public final class TrainMover {

    private static final Vector ZERO = new Vector();

    /** Keeps a slot from landing exactly on the end of a path. */
    private static final double EDGE_MARGIN = 0.05;

    /** Retry cadence for a train stopped at a wall or the end of the line. */
    private static final int STALL_RETRY_TICKS = 10;

    /** Minimum |dot| before a push is trusted to pick a direction. */
    private static final double PUSH_CLEAR = 0.25;

    /** Clear track a train must see ahead of its leading cart. */
    private static final double PROBE_AHEAD = 1.0;

    /** Half a block, so every rail block in a probe window is sampled twice. */
    private static final double PROBE_STEP = 0.5;

    /** Vertical offsets tried when looking for the next rail. */
    private static final int[] PROBE_DY = {0, 1, -1};

    /**
     * Ticks a new train gets before its fuel is checked. Vanilla writes the
     * coal only after the interact event that assembled the train.
     */
    private static final int FUEL_GRACE = 2;

    private final TrainManager manager;
    private PluginConfig config;

    private long teleports;
    private long releases;
    private long pathFailures;
    private long ticks;

    public TrainMover(TrainManager manager, PluginConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    public void tick(Train train) {
        ticks++;
        if (!train.engineAlive()) {
            manager.dissolve(train, "engine removed");
            return;
        }

        // No fuel, no train: the carts are handed back where they stand. Speed is
        // left alone — once dissolved they belong to the game again.
        if (train.bumpAge() > FUEL_GRACE && train.engine().getFuel() <= 0) {
            manager.dissolve(train, "out of fuel");
            return;
        }
        manager.dropGone(train);
        if (train.hasNoWagons()) {
            manager.dissolve(train, "no wagons left");
            return;
        }

        PoweredMinecart engine = train.engine();
        Location engineLocation = engine.getLocation();
        World world = engineLocation.getWorld();
        if (world == null) {
            manager.dissolve(train, "engine left the world");
            return;
        }

        freeze(engine);
        for (Train.Member member : train.wagons()) {
            freeze(member.cart());
        }

        // A cart a whole train length from the engine cannot still belong to it:
        // it was teleported, portalled, or dragged away while our records slept.
        double detachSq = config.maxLength * config.maxLength;
        for (Train.Member member : new ArrayList<>(train.wagons())) {
            Minecart cart = member.cart();
            if (!cart.getWorld().equals(world)) {
                manager.release(train, cart, "different world");
                continue;
            }
            if (cart.getLocation().distanceSquared(engineLocation) > detachSq) {
                manager.release(train, cart, "too far from the engine");
            }
        }
        if (train.dissolved()) {
            return;
        }

        Vector heading = resolveHeading(engine, engineLocation, train.heading());
        train.heading(heading);

        double speed = train.speed();

        // Offsets are real arc lengths; evening them out first keeps the path
        // length requirement in step with the slots about to be placed.
        evenOutSpacing(train, speed);

        double maxOffset = train.maxOffset();
        double minOffset = train.minOffset();
        RailPath forward = train.forward();
        RailPath backward = train.backward();
        double s = train.s();

        boolean noAhead = forward == null || forward.isEmpty();
        boolean canExtend = !noAhead
                && forward.length() >= maxOffset + EDGE_MARGIN + 1.0;
        double backwardLength = backward == null ? 0.0 : backward.length();

        // The cache remembers where the track was, not where it is. Sample it on
        // the retry cadence, with a window covering everything the train will
        // travel before the next probe, so no gap can slip between two checks.
        int probeTick = speed == 0.0 ? train.resetProbeTicks() : train.bumpProbeTicks();
        boolean gapAhead = speed != 0.0 && probeTick % STALL_RETRY_TICKS == 1
                && railGoneAhead(world, forward, backward, s, speed, maxOffset, minOffset);

        // One cadence answers every question about a stop: is the rail still
        // there, did the player rebuild it, is this the end of the line.
        boolean stalled = speed == 0.0;
        int stallTicks = stalled ? train.bumpStallTicks() : train.resetStallTicks();
        boolean frontLimit = noAhead
                || forward.length() - maxOffset - EDGE_MARGIN - s < 0.25;

        boolean stale = noAhead
                || gapAhead
                || (canExtend && s + speed + maxOffset > forward.length() - EDGE_MARGIN)
                || s + speed + minOffset < -backwardLength + EDGE_MARGIN
                || (stalled && frontLimit);

        if (stale && stalled && frontLimit && forward != null
                && stallTicks % STALL_RETRY_TICKS != 0) {
            train.speed(0.0);
            // Nothing is placed on this tick, so clear the velocity by hand or
            // the rolling sound keeps playing while the train stands still.
            stopAll(train);
            return;
        }

        if (stale) {
            RailPath before = noAhead ? null : forward;
            double beforeS = s;
            Rebuild rebuilt = rebuild(world, engine, engineLocation.toVector(),
                    engineLocation.getBlock(), heading);
            if (rebuilt == null) {
                // No heading finds rail under the engine, so this consist can
                // never be placed again. Hand it back with an empty tank.
                pathFailures++;
                manager.derail(train, "no rail under the engine");
                return;
            }
            rebuilt = keepDirection(train, before, beforeS, rebuilt);
            forward = rebuilt.forward();
            backward = rebuilt.backward();
            s = 0.0;
            train.forward(forward);
            train.backward(backward);
            train.s(0.0);
            train.heading(rebuilt.heading());

            // The track behind the engine is gone: carts back there can never be
            // placed correctly again, so let them go instead of re-walking forever.
            if (backward.isEmpty() && minOffset < -EDGE_MARGIN) {
                for (Train.Member member : new ArrayList<>(train.wagons())) {
                    if (member.offset() < 0.0) {
                        manager.release(train, member.cart(), "no rail behind the engine");
                    }
                }
                maxOffset = train.maxOffset();
                minOffset = train.minOffset();
                if (train.dissolved()) {
                    return;
                }
            }
        }

        // Which way it travels has to be known before the dead-end test below.
        latchDirection(train, forward, s);
        int direction = train.pushSign();

        // Judge the end the train is heading for. A consist stoked from the front
        // runs backwards, and testing `forward` alone let it burn out against the
        // torn end it had just reversed into.
        double frontRoom = forward.isEmpty()
                ? 0.0
                : forward.length() - maxOffset - EDGE_MARGIN - s;
        double backRoom = (backward == null ? 0.0 : backward.length())
                + minOffset - EDGE_MARGIN + s;
        boolean deadEnd = direction != 0
                && (direction < 0 ? backRoom : frontRoom) < 0.25;
        if (stalled && deadEnd) {
            manager.derail(train, "the track ends here");
            return;
        }

        speed = speedFor(direction, RailWalker.poweredRailUnder(engineLocation));

        // Clamp to the track that is actually available: the whole group stops.
        double d = speed;
        if (d >= 0.0) {
            double available = forward.length() - maxOffset - EDGE_MARGIN - s;
            if (d > available) {
                d = Math.max(0.0, available);
                speed = 0.0;
            }
        } else {
            double available = (backward == null ? 0.0 : backward.length())
                    + minOffset - EDGE_MARGIN + s;
            if (-d > available) {
                d = -Math.max(0.0, available);
                speed = 0.0;
            }
        }

        s += d;
        train.s(s);
        train.speed(speed);

        if (backward == null) {
            backward = RailPath.empty();
        }

        final double breakDistanceSq = config.breakDistance * config.breakDistance;

        // An empty forward path means the engine stands on the last rail block of
        // the line; there is no position to ask for.
        if (!forward.isEmpty()) {
            Vector engineTarget = s >= 0.0 || backward.isEmpty()
                    ? forward.pointAt(Math.max(0.0, s))
                    : backward.pointAt(Math.min(backward.length(), -s));
            Vector engineTangent = s >= 0.0 || backward.isEmpty()
                    ? forward.tangentAt(Math.max(0.0, s))
                    : backward.tangentAt(Math.min(backward.length(), -s)).multiply(-1.0);
            place(engine, engineTarget, engineTangent, speed);
        } else {
            // Nothing was placed, so clear the old velocity or the cart keeps
            // reporting motion it no longer has.
            stop(engine);
        }

        for (Train.Member member : new ArrayList<>(train.wagons())) {
            Minecart cart = member.cart();
            if (!cart.isValid() || cart.isDead()) {
                manager.release(train, cart, "cart gone");
                continue;
            }
            if (!cart.getWorld().equals(world)) {
                manager.release(train, cart, "different world");
                continue;
            }

            double along = s + member.offset();
            Vector target;
            Vector tangent;
            if (along >= 0.0) {
                if (forward.isEmpty() || along > forward.length() + EDGE_MARGIN) {
                    // Not enough track left for this cart. Leave it here; clamping
                    // every cart onto the end point piles them into one block.
                    continue;
                }
                target = forward.pointAt(along);
                tangent = forward.tangentAt(along);
            } else if (!backward.isEmpty()) {
                double distance = -along;
                if (distance > backward.length() + EDGE_MARGIN) {
                    continue;
                }
                target = backward.pointAt(distance);
                tangent = backward.tangentAt(distance).multiply(-1.0);
            } else {
                continue;
            }

            // Something moved this cart far from its slot — a player, a portal,
            // another plugin. Let it go instead of dragging it through blocks.
            if (cart.getLocation().toVector().distanceSquared(target)
                    > breakDistanceSq) {
                manager.release(train, cart, "left its slot");
                continue;
            }

            place(cart, target, tangent, speed);
        }

        if (!forward.isEmpty() && s >= 0.0) {
            train.heading(forward.tangentAt(Math.min(forward.length(), s)));
        }
    }

    /**
     * Takes a cart out of collision handling.
     *
     * Max speed is deliberately left alone: vanilla clamps velocity to it every
     * tick, so pinning it to zero would also wipe the velocity the client reads
     * for the rolling sound. Position is owned by {@link #place}.
     */
    private void freeze(Minecart cart) {
        if (!cart.hasNoPhysics()) {
            cart.setNoPhysics(true);
        }
    }

    /** Hands a cart back to vanilla; {@link #freeze} runs again every tick. */
    public void unfreeze(Minecart cart) {
        if (cart == null || !cart.isValid() || cart.isDead()) {
            return;
        }
        try {
            if (cart.hasNoPhysics()) {
                cart.setNoPhysics(false);
            }
        } catch (RuntimeException e) {
            if (config.debug) {
                manager.log("could not hand a cart back: " + e.getMessage());
            }
        }
    }

    /**
     * Speed for this tick: {@code powered-speed} while the engine stands on a
     * switched-on powered rail, {@code cruise-speed} otherwise.
     *
     * Stateless on purpose, so a booster cannot leak its speed past its last
     * powered rail.
     */
    private double speedFor(int direction, boolean powered) {
        return direction * (powered ? config.poweredSpeed : config.cruiseSpeed);
    }

    /**
     * Latches which way along the path the furnace is pushing. Only a stoke may
     * change it.
     *
     * Re-deriving the sign every tick is what used to turn trains around mid-bend:
     * the push is a fixed world vector, the rail turns under it, and the dot
     * product flips once the angle passes a right angle.
     */
    private void latchDirection(Train train, RailPath forward, double s) {
        Vector stoke = train.consumeReignite();
        if (stoke == null && train.pushSign() != 0) {
            return;
        }
        Vector push = stoke != null ? stoke
                : new Vector(train.engine().getPushX(), 0.0, train.engine().getPushZ());
        Vector tangent = forward == null || forward.isEmpty()
                ? flatten(train.heading())
                : forward.tangentAt(Math.max(0.0, s));
        int latched = signOf(push, tangent, train.pushSign());
        if (latched != 0 && latched != train.pushSign()) {
            if (config.debug) {
                manager.log("train " + train.id() + ": direction " + latched
                        + (stoke != null ? " (stoked)" : " (ignited)"));
            }
            train.pushSign(latched);
        }
    }

    /**
     * Which way along the rail a push points.
     *
     * @param push     horizontal direction the furnace is pushed in
     * @param tangent  rail tangent at the engine, in the forward path's frame
     * @param fallback kept when the push says nothing — a push at right angles to
     *                 the rail does not move the cart along it
     * @return +1 with increasing {@code s}, -1 against it, or {@code fallback}
     */
    public static int signOf(Vector push, Vector tangent, int fallback) {
        Vector flatPush = horizontal(push);
        Vector flatTangent = horizontal(tangent);
        if (flatPush == null || flatTangent == null) {
            return fallback;
        }
        double dot = flatPush.getX() * flatTangent.getX()
                + flatPush.getZ() * flatTangent.getZ();
        if (dot >= PUSH_CLEAR) {
            return 1;
        }
        if (dot <= -PUSH_CLEAR) {
            return -1;
        }
        return fallback;
    }

    /** Unit horizontal component, or null when there is none. */
    private static Vector horizontal(Vector raw) {
        if (raw == null) {
            return null;
        }
        Vector flat = raw.clone().setY(0.0);
        return flat.lengthSquared() < 1.0E-8 ? null : flat.normalize();
    }

    /**
     * True when a rail inside the next probe window is gone.
     *
     * A block-break listener would only catch players — not pistons, explosions,
     * {@code /setblock} or another plugin — so the path is sampled instead. The
     * window is one retry interval of travel plus {@link #PROBE_AHEAD}, which
     * makes consecutive windows overlap and leaves at least a block of warning.
     *
     * Each sample looks at a block column, y through y±1, because rail points sit
     * on block boundaries and can round into the neighbouring column.
     */
    private boolean railGoneAhead(World world, RailPath forward, RailPath backward,
            double s, double speed, double maxOffset, double minOffset) {
        if (speed == 0.0) {
            return false;
        }
        boolean goingForward = speed > 0.0;
        double front = goingForward ? Math.max(s, s + maxOffset) : Math.min(s, s + minOffset);
        double reach = PROBE_AHEAD + Math.abs(speed) * STALL_RETRY_TICKS;

        for (double step = PROBE_STEP; step <= reach + 1.0E-9; step += PROBE_STEP) {
            double along = front + (goingForward ? step : -step);

            Vector point;
            if (along >= 0.0) {
                if (forward == null || forward.isEmpty() || along > forward.length()) {
                    return false;
                }
                point = forward.pointAt(along);
            } else {
                if (backward == null || backward.isEmpty() || -along > backward.length()) {
                    return false;
                }
                point = backward.pointAt(-along);
            }

            int x = point.getBlockX();
            int y = point.getBlockY();
            int z = point.getBlockZ();
            boolean railHere = false;
            for (int dy : PROBE_DY) {
                if (RailWalker.isRail(world.getBlockAt(x, y + dy, z))) {
                    railHere = true;
                    break;
                }
            }
            if (!railHere) {
                return true;
            }
        }
        return false;
    }

    /** Horizontal unit vector of {@code raw}, or null when it has no direction. */
    private static Vector flatten(Vector raw) {
        if (raw == null) {
            return null;
        }
        Vector flat = raw.clone().setY(0.0);
        return flat.lengthSquared() < 1.0E-6 ? null : flat.normalize();
    }

    /** Nearest first, so slot 1 is the closest cart. */
    private static final Comparator<Train.Member> BY_OFFSET =
            Comparator.comparingDouble(Train.Member::offset);
    private static final Comparator<Train.Member> BY_OFFSET_DESC = BY_OFFSET.reversed();

    /** Reused between ticks; the mover runs on the server thread. */
    private final List<Train.Member> ahead = new ArrayList<>();
    private final List<Train.Member> behind = new ArrayList<>();

    /**
     * Slides each cart towards its ordinal slot by at most
     * {@code spacing-converge} per tick, so inherited gaps become even without a
     * visible jump.
     *
     * Skipped while parked: moving carts then would fight the player.
     */
    private void evenOutSpacing(Train train, double speed) {
        if (config.spacingConverge <= 0.0 || train.wagons().isEmpty()) {
            return;
        }
        if (speed == 0.0) {
            return;
        }

        ahead.clear();
        behind.clear();
        for (Train.Member member : train.wagons()) {
            if (member.offset() > 0.0) {
                ahead.add(member);
            } else {
                behind.add(member);
            }
        }
        ahead.sort(BY_OFFSET);
        behind.sort(BY_OFFSET_DESC);

        pull(ahead, 1.0);
        pull(behind, -1.0);
    }

    private void pull(List<Train.Member> ordered, double sign) {
        for (int index = 0; index < ordered.size(); index++) {
            Train.Member member = ordered.get(index);
            double target = sign * (index + 1) * config.spacing;
            double delta = target - member.offset();
            if (Math.abs(delta) <= 1.0E-4) {
                continue;
            }
            double step = Math.min(Math.abs(delta), config.spacingConverge);
            member.offset(member.offset() + Math.signum(delta) * step);
        }
    }

    /**
     * Puts a cart on its arc length and reports its speed.
     *
     * The teleport is skipped when neither position nor yaw changed; the velocity
     * is not, because that is what the client reads to decide whether to play the
     * rolling loop ({@code entity.minecart.riding}, volume and pitch taken from
     * the speed).
     */
    private void place(Minecart cart, Vector target, Vector tangent, double speed) {
        Vector velocity = tangent.clone().multiply(speed);
        float yaw = yawOf(tangent);
        Location current = cart.getLocation();
        if (current.getWorld() == null) {
            return;
        }
        Location destination = new Location(current.getWorld(),
                target.getX(), target.getY(), target.getZ(), yaw, 0.0f);
        if (current.distanceSquared(destination) > 1.0E-6
                || Math.abs(wrapDegrees(current.getYaw() - yaw)) > 0.5) {
            try {
                cart.teleport(destination);
                teleports++;
            } catch (RuntimeException e) {
                if (config.debug) {
                    manager.log("teleport refused: " + e.getMessage());
                }
            }
        }
        if (cart.getVelocity().distanceSquared(velocity) > 1.0E-8) {
            cart.setVelocity(velocity);
        }
    }

    /** Brings a cart to a stop, for the paths that place nothing. */
    private static void stop(Minecart cart) {
        if (cart.getVelocity().lengthSquared() > 1.0E-8) {
            cart.setVelocity(ZERO);
        }
    }

    /** Same, for a whole consist. */
    private static void stopAll(Train train) {
        stop(train.engine());
        for (Train.Member member : train.wagons()) {
            stop(member.cart());
        }
    }

    private static final Vector[] AXES = {
            new Vector(1.0, 0.0, 0.0),
            new Vector(-1.0, 0.0, 0.0),
            new Vector(0.0, 0.0, 1.0),
            new Vector(0.0, 0.0, -1.0)
    };

    /**
     * Restores which half of a rebuilt path is "ahead".
     *
     * If the walk came back pointing the way the train arrived, exchanging the
     * halves is the whole fix: an offset, the push sign and the speed all mean
     * "along the track ahead of the engine", which after the swap is
     * {@code forward} again. Flipping those too would turn the train around a
     * second time.
     */
    private Rebuild keepDirection(Train train, RailPath before, double beforeS,
            Rebuild rebuilt) {
        if (rebuilt.forward().isEmpty()) {
            return rebuilt;
        }
        Vector was;
        if (before != null && !before.isEmpty()) {
            was = before.tangentAt(Math.max(0.0, Math.min(before.length(), beforeS)));
        } else {
            // First rebuild after the assembly: no cached path to compare against,
            // but every offset was measured against the heading the train was
            // built along, so that is the frame to keep.
            was = flatten(train.heading());
        }
        if (was == null) {
            return rebuilt;
        }
        Vector now = rebuilt.forward().tangentAt(0.0);
        if (was.getX() * now.getX() + was.getZ() * now.getZ() >= -0.5) {
            return rebuilt;
        }
        if (config.debug) {
            manager.log("train " + train.id()
                    + ": path rebuilt backwards, swapped the halves");
        }
        return new Rebuild(rebuilt.backward(), rebuilt.forward(),
                rebuilt.heading().clone().multiply(-1.0));
    }

    private Rebuild rebuild(World world, PoweredMinecart engine, Vector enginePos,
            Block block, Vector heading) {
        Vector used = heading;
        RailPath forward = RailWalker.walk(world, block, enginePos, used, config.lookaheadBlocks);

        // A cart in the middle of a curve has a 45 degree tangent, which the
        // walker resolves from its position inside the block. If it could not,
        // the furnace push is the next best signal.
        if (forward.isEmpty()) {
            Vector push = new Vector(engine.getPushX(), 0.0, engine.getPushZ());
            if (push.lengthSquared() > 1.0E-6) {
                used = snap(push);
                forward = RailWalker.walk(world, block, enginePos, used, config.lookaheadBlocks);
            }
        }

        // Still nothing: the remembered heading points across the rail, so only
        // the rail's own axes can walk.
        if (forward.isEmpty()) {
            for (Vector axis : AXES) {
                RailPath attempt = RailWalker
                        .walk(world, block, enginePos, axis, config.lookaheadBlocks);
                if (!attempt.isEmpty()) {
                    used = axis;
                    forward = attempt;
                    break;
                }
            }
        }

        if (forward.isEmpty()) {
            return null;
        }
        // Trimming can leave nothing when the engine stands on the very last point
        // of the line. That is not a broken walk, so keep the empty path and let
        // the caller stand still.
        forward = forward.trimFrom(forward.nearest(enginePos).s());

        RailPath backward = RailWalker.walk(world, block, enginePos,
                used.clone().multiply(-1.0), config.lookaheadBlocks);
        if (!backward.isEmpty()) {
            backward = backward.trimFrom(backward.nearest(enginePos).s());
        }
        return new Rebuild(forward, backward, used);
    }

    /**
     * Direction of travel: the remembered heading first, then the furnace push,
     * then where the entity faces. Only the latter two are snapped — a 45 degree
     * curve tangent must stay diagonal or the walker cannot follow it.
     */
    public static Vector resolveHeading(PoweredMinecart engine, Location engineLocation, Vector fallback) {
        if (fallback != null && fallback.lengthSquared() > 1.0E-6) {
            Vector flat = fallback.clone().setY(0.0);
            return flat.lengthSquared() > 1.0E-6 ? flat.normalize() : new Vector(0.0, 0.0, 1.0);
        }

        Vector push = new Vector(engine.getPushX(), 0.0, engine.getPushZ());
        if (push.lengthSquared() > 1.0E-6) {
            return snap(push);
        }

        double yaw = Math.toRadians(engineLocation.getYaw());
        Vector facing = new Vector(-Math.sin(yaw), 0.0, Math.cos(yaw));
        return snap(facing);
    }

    /**
     * Axis-aligns a freshly read push or facing vector.
     *
     * Shared with the assembly, which walks the track along the stoke; the mover
     * has to walk it the same way or every offset ends up meaning the other side.
     */
    public static Vector snap(Vector raw) {
        Vector flat = raw.clone().setY(0.0);
        if (flat.lengthSquared() < 1.0E-6) {
            return new Vector(0.0, 0.0, 1.0);
        }
        flat.normalize();
        double ax = Math.abs(flat.getX());
        double az = Math.abs(flat.getZ());
        if (Math.abs(ax - az) < 0.08) {
            if (ax >= az) {
                flat.setZ(0.0);
            } else {
                flat.setX(0.0);
            }
        }
        return flat.normalize();
    }

    private static float yawOf(Vector tangent) {
        return (float) Math.toDegrees(Math.atan2(-tangent.getX(), tangent.getZ()));
    }

    private static double wrapDegrees(double degrees) {
        double value = degrees % 360.0;
        if (value >= 180.0) {
            value -= 360.0;
        }
        if (value < -180.0) {
            value += 360.0;
        }
        return value;
    }

    private record Rebuild(RailPath forward, RailPath backward, Vector heading) {
    }

    public long teleports() {
        return teleports;
    }

    public long releases() {
        return releases;
    }

    public long pathFailures() {
        return pathFailures;
    }

    public long ticks() {
        return ticks;
    }

    void countRelease() {
        releases++;
    }

    public List<String> debugLines() {
        List<String> lines = new ArrayList<>();
        lines.add("ticks: " + ticks);
        lines.add("teleports: " + teleports);
        lines.add("releases: " + releases);
        lines.add("path failures: " + pathFailures);
        return lines;
    }
}
