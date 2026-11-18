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
 * Moves a train the way TrainCarts does, minus everything that is not needed
 * for a furnace minecart pulling a few carts.
 *
 * The model has exactly two state variables:
 *
 * <ul>
 *   <li>{@code s} — how far the engine has travelled along the cached rail
 *       path, in blocks.</li>
 *   <li>{@code speed} — one signed scalar shared by the whole train.</li>
 * </ul>
 *
 * Every cart sits at a fixed arc-length offset from the engine, so its
 * position is {@code path.pointAt(s + offset)}. A curve cannot change that
 * distance, which is the entire fix for trains tearing apart on corners.
 *
 * The vanilla minecart is pinned in place: max speed zero, no physics, zero
 * velocity. It still ticks, so fuel burns and right-click ignition still work,
 * but it can no longer move itself, shove its neighbours, or be shoved.
 */
public final class TrainMover {

    private static final Vector ZERO = new Vector();

    /** Keep this much track in hand so a slot never lands exactly on the end. */
    private static final double EDGE_MARGIN = 0.05;

    /** How often a train stopped at a wall or the end of the line looks again. */
    private static final int STALL_RETRY_TICKS = 10;

    /**
     * How well the push has to line up with the rail before it is trusted to
     * say which way the train goes. Below this the last reading is kept.
     */
    private static final double PUSH_CLEAR = 0.25;

    /**
     * How much clear track the train must see ahead of its leading cart before
     * a gap counts as "still far enough away". One block, the same as before.
     */
    private static final double PROBE_AHEAD = 1.0;

    /**
     * Spacing of the sample points inside one probe window.
     *
     * Half a block means every rail block the window crosses is looked at from
     * two path points, so a sample that happens to land exactly on a block
     * boundary cannot step into the neighbouring column and miss it.
     */
    private static final double PROBE_STEP = 0.5;

    /** Vertical steps the walker tries when it looks for the next rail. */
    private static final int[] PROBE_DY = {0, 1, -1};

    /** What a vanilla minecart uses. Restored whenever a cart leaves a train. */
    private static final double VANILLA_MAX_SPEED = 0.4;

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
        train.pruneInvalid();
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

        // Distance is the one part of membership that needs no bookkeeping: a
        // cart that is nowhere near the engine cannot belong to it. This is
        // what frees wagons when the engine is destroyed, teleported away, or
        // adopted by something else while our records are still stale.
        double detachSq = config.detachDistance * config.detachDistance;
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

        Vector enginePos = engineLocation.toVector();
        Vector heading = resolveHeading(engine, engineLocation, train.heading());
        train.heading(heading);

        // Make sure there is a path long enough to hold the whole train this
        // tick. The walk only happens when the train reaches the end of the
        // cached section, so corners never trigger a re-walk.
        double speed = train.speed();

        // Offsets were whatever the carts were standing on when the train
        // formed. Equalising them here, before the path is checked, keeps the
        // length requirement in step with the slots we are about to place.
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

        // The cache is a memory of where the track was, not a fact about where
        // it is. A rail torn out of the line stays in it, and the train drives
        // over the gap as if the block were still there. Looking is not free,
        // though, so it rides the same half-second cadence as the rest of the
        // stalled-train work — and the window it inspects covers everything
        // the train will travel before it looks again, so a gap cannot slip
        // between two probes.
        int probeTick = speed == 0.0 ? train.resetProbeTicks() : train.bumpProbeTicks();
        boolean gapAhead = speed != 0.0 && probeTick % STALL_RETRY_TICKS == 1
                && railGoneAhead(world, forward, backward, s, speed, maxOffset, minOffset);

        // While the train stands there, re-walking every tick would only find
        // the same dead end, so look again every half second instead. The same
        // cadence answers every question about the stop — is the rail still
        // there, did the player rebuild it, is this the end of the line — so
        // there is one schedule for all of it and no first-tick special case.
        boolean stalled = Math.abs(speed) < config.stopSpeed;
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
            return;
        }

        if (stale) {
            RailPath before = noAhead ? null : forward;
            double beforeS = s;
            Rebuild rebuilt = rebuild(world, engine, enginePos, engineLocation.getBlock(), heading);
            if (rebuilt == null) {
                pathFailures++;
                // Do not let speed pile up while the track is unresolvable.
                train.speed(speed * 0.5);
                if (config.debug) {
                    manager.log("train " + train.id() + ": no rail at "
                            + engineLocation.getBlockX() + "," + engineLocation.getBlockY()
                            + "," + engineLocation.getBlockZ());
                }
                // A train whose engine sits off the rails can never be placed
                // again. Holding it would leave every wagon frozen forever, so
                // let go after a grace period instead.
                if (train.bumpPathFailStreak() >= config.pathFailLimit) {
                    manager.dissolve(train, "no rail under the engine");
                }
                return;
            }
            train.resetPathFailStreak();
            rebuilt = keepDirection(train, before, beforeS, rebuilt);
            forward = rebuilt.forward();
            backward = rebuilt.backward();
            s = 0.0;
            train.forward(forward);
            train.backward(backward);
            train.s(0.0);
            train.heading(rebuilt.heading());

            // Track behind the engine disappeared (a player removed it, or the
            // walker could not follow a junction backwards). Carts behind the
            // engine can never be placed correctly again, so let them go
            // instead of rebuilding this path on every tick forever.
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

        // The cache now matches the ground, so this is the real answer to
        // "is there still track in front of the train?". If there is not, the
        // train cannot get itself out of the situation: every cart in it is
        // pinned (max speed zero, no physics), so the player cannot push it,
        // cannot tow it, and the wagons are welded to the furnace. A line that
        // ends here is either a rail someone tore out or a track that was
        // never finished — both mean this consist is going nowhere until it is
        // rebuilt. Hand the carts back to vanilla instead of leaving them
        // stuck; because it is only reached on a retry tick, a dead end is
        // confirmed within one STALL_RETRY_TICKS window.
        boolean deadEnd = forward.isEmpty()
                || forward.length() - maxOffset - EDGE_MARGIN - s < 0.25;
        if (stalled && deadEnd) {
            manager.derail(train, "the track ends here");
            return;
        }

        speed = updateSpeed(train, engine, forward, backward, s);

        // A wall in front of the train shortens the usable path. Clamping here
        // stops the engine from pushing the rest of the train into the wall:
        // the whole group simply stops.
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

        // Engine. A path with nothing ahead means the engine is standing on the
        // last rail block of the line; leave it exactly where it is instead of
        // asking an empty path for a position.
        if (!forward.isEmpty()) {
            Vector engineTarget = s >= 0.0 || backward.isEmpty()
                    ? forward.pointAt(Math.max(0.0, s))
                    : backward.pointAt(Math.min(backward.length(), -s));
            Vector engineTangent = s >= 0.0 || backward.isEmpty()
                    ? forward.tangentAt(Math.max(0.0, s))
                    : backward.tangentAt(Math.min(backward.length(), -s)).multiply(-1.0);
            place(engine, engineTarget, engineTangent);
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
                    // There is not enough track left in front of this cart.
                    // Leave it where it is; clamping all of them onto the end
                    // point is exactly what used to pile carts into one block.
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

            // Safety net: if something teleported the cart far away (a player
            // breaking it, a portal, another plugin), let it go instead of
            // yanking it back through blocks.
            if (cart.getLocation().toVector().distanceSquared(target)
                    > Math.pow(config.breakDistance, 2.0)) {
                manager.release(train, cart, "left its slot");
                continue;
            }

            place(cart, target, tangent);
        }

        // Keep heading aligned with the rail, not with the frozen velocity.
        if (!forward.isEmpty() && s >= 0.0) {
            train.heading(forward.tangentAt(Math.min(forward.length(), s)));
        }
    }

    /**
     * Vanilla is still ticking the entity; these three lines are what keep it
     * from moving. Speed zero stops momentum, max speed zero stops
     * {@code moveAlongTrack} from advancing, no physics stops block and entity
     * collisions from shoving it.
     */
    private void freeze(Minecart cart) {
        Vector velocity = cart.getVelocity();
        if (velocity.lengthSquared() > 1.0E-8) {
            cart.setVelocity(ZERO);
        }
        if (cart.getMaxSpeed() != 0.0) {
            cart.setMaxSpeed(0.0);
        }
        if (!cart.hasNoPhysics()) {
            cart.setNoPhysics(true);
        }
    }

    /**
     * Hands a cart back to vanilla.
     *
     * {@link #freeze} runs every tick on every member, so a cart that leaves a
     * train keeps "cannot move" on itself until something undoes it. That was
     * the bug: destroying the engine dissolved the records but left every wagon
     * pinned, and they stood there as if still coupled.
     */
    public void unfreeze(Minecart cart) {
        if (cart == null || !cart.isValid() || cart.isDead()) {
            return;
        }
        try {
            if (cart.getMaxSpeed() != VANILLA_MAX_SPEED) {
                cart.setMaxSpeed(VANILLA_MAX_SPEED);
            }
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
     * Speed for this tick.
     *
     * A lit furnace drives the train at a constant {@code cruise-speed}:
     * vanilla moves a furnace minecart at 4 blocks a second — 0.2 blocks per
     * tick, a little slower than walking — and neither a downhill nor a
     * powered rail changes that. Nothing here accelerates, coasts on a grade or
     * brakes while there is fuel left, which is also why no incline can hold
     * the train back.
     *
     * Without fuel the train only coasts: friction, rolling resistance and the
     * grade, in whichever direction it happens to be rolling, up to the normal
     * minecart limit.
     */
    private double updateSpeed(Train train, PoweredMinecart engine,
            RailPath forward, RailPath backward, double s) {
        long fuel = engine.getFuel();
        Vector stoke = train.consumeReignite();

        if (fuel > 0) {
            // Two events may pick a direction: a stoke, and the fuel going
            // from nothing to something. Everything else keeps the latched
            // one, which is what stops a bend from turning the train around.
            if (stoke != null || train.lastFuel() <= 0 || train.pushSign() == 0) {
                Vector push = stoke != null ? stoke
                        : new Vector(engine.getPushX(), 0.0, engine.getPushZ());
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
            train.lastFuel(fuel);
            return train.pushSign() * config.cruiseSpeed;
        }

        train.lastFuel(0);
        return SpeedModel.coast(train.speed(), config.friction,
                config.rollingResistance, config.gravity,
                slopeAt(forward, backward, s), config.maxSpeed, config.stopSpeed);
    }

    /**
     * Which way along the rail a push points.
     *
     * @param push     horizontal direction the furnace is being pushed in
     * @param tangent  rail tangent at the engine, in the forward path's frame
     * @param fallback what to keep when the push says nothing — a push at right
     *                 angles to the rail moves the cart sideways, not along
     *                 the track, so there is no honest answer to give
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
     * True when a rail between the leading cart and the far end of the next
     * probe window is gone.
     *
     * Re-walking the whole line every tick would defeat the path cache, and a
     * block-break listener only catches players — not pistons, explosions,
     * {@code /setblock} or another plugin. So the path is sampled instead, on
     * the same half-second cadence the rest of the stalled-train work uses.
     *
     * Looking every ten ticks means the train covers up to
     * {@code 10 * |speed|} blocks between two checks, which is why the window
     * is that long plus {@link #PROBE_AHEAD}: sampled every
     * {@link #PROBE_STEP}, the windows of consecutive checks overlap, so the
     * train always knows about a gap at least one block before it reaches it
     * and no gap can slip through the seam between two probes.
     *
     * The point is looked up as a block column, not as a block: rail geometry
     * puts its sample points on block boundaries, so the coordinate under a
     * point can flip to the neighbour. The walker itself only ever steps to a
     * rail that shares an edge height with the rails {@code dy} in
     * {0, +1, -1} away, so asking the same three blocks whether any of them is
     * still rail is both safe and faithful.
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
                    // Beyond the cached section; the rebuild conditions handle it.
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

    /**
     * dY/ds of the rail under the engine, in the path's own frame.
     *
     * Behind the origin {@code s} is negative and the engine is standing on the
     * backward path, whose tangent points the other way; its Y has to be
     * negated or a train rolling back down a hill would feel no grade at all
     * and just coast to a stop halfway down.
     */
    private static double slopeAt(RailPath forward, RailPath backward, double s) {
        if (s >= 0.0) {
            return forward == null ? 0.0 : forward.tangentAt(s).getY();
        }
        return backward == null ? 0.0 : -backward.tangentAt(-s).getY();
    }

    /** Horizontal unit vector of {@code raw}, or null when it has no direction. */
    private static Vector flatten(Vector raw) {
        if (raw == null) {
            return null;
        }
        Vector flat = raw.clone().setY(0.0);
        return flat.lengthSquared() < 1.0E-6 ? null : flat.normalize();
    }

    /**
     * Slides every cart towards an even spacing.
     *
     * A train inherits whatever gaps it was assembled with: one pair may be
     * 1.05 apart and the next 3.2. Once the train is rolling, each cart creeps
     * along the rail towards its ordinal slot by at most
     * {@code spacing-converge} per tick, so the gaps end up identical without
     * any visible jump.
     *
     * Skipped while parked, because a train standing still is either being
     * loaded by hand or serving as decoration, and moving the carts then would
     * fight the player.
     */
    private void evenOutSpacing(Train train, double speed) {
        if (config.spacingConverge <= 0.0 || train.wagons().isEmpty()) {
            return;
        }
        if (Math.abs(speed) < config.stopSpeed) {
            return;
        }

        List<Train.Member> ahead = new ArrayList<>();
        List<Train.Member> behind = new ArrayList<>();
        for (Train.Member member : train.wagons()) {
            if (member.offset() > 0.0) {
                ahead.add(member);
            } else {
                behind.add(member);
            }
        }
        // Nearest first on both sides, so slot 1 is always the closest cart.
        Comparator<Train.Member> byOffset =
                Comparator.comparingDouble((Train.Member member) -> member.offset());
        ahead.sort(byOffset);
        behind.sort(byOffset.reversed());

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

    private void place(Minecart cart, Vector target, Vector tangent) {
        float yaw = yawOf(tangent);
        Location current = cart.getLocation();
        if (current.getWorld() == null) {
            return;
        }
        Location destination = new Location(current.getWorld(),
                target.getX(), target.getY(), target.getZ(), yaw, 0.0f);
        if (current.distanceSquared(destination) <= 1.0E-6
                && Math.abs(wrapDegrees(current.getYaw() - yaw)) <= 0.5) {
            return;
        }
        try {
            cart.teleport(destination);
            teleports++;
        } catch (RuntimeException e) {
            if (config.debug) {
                manager.log("teleport refused: " + e.getMessage());
            }
        }
    }

    private static final Vector[] AXES = {
            new Vector(1.0, 0.0, 0.0),
            new Vector(-1.0, 0.0, 0.0),
            new Vector(0.0, 0.0, 1.0),
            new Vector(0.0, 0.0, -1.0)
    };

    /**
     * A rebuilt cache is meant to continue the track the train is already on.
     * If the walk came out pointing back the way the train arrived, the engine
     * would step a whole block backwards and then keep travelling that way —
     * the train drives off in the opposite direction.
     *
     * Swapping the halves is the whole fix. The two walks are started with
     * opposite headings and {@code firstExit} picks opposite edges for them, so
     * one of the two halves is always the track ahead and the other the track
     * behind; if the walker labelled them the wrong way round, exchanging them
     * restores the labels. Nothing else has to move: an offset, the push sign
     * and the speed all mean "along the track ahead of the engine", and after
     * the swap that is exactly what {@code forward} is again. Flipping those
     * too would turn the train around a second time — the mistake that made
     * this method look like it did nothing.
     *
     * {@code s} is reset to zero by the caller, and both halves start at the
     * engine, so the engine itself does not move.
     */
    private Rebuild keepDirection(Train train, RailPath before, double beforeS,
            Rebuild rebuilt) {
        if (before == null || before.isEmpty() || rebuilt.forward().isEmpty()) {
            return rebuilt;
        }
        Vector was = before.tangentAt(Math.max(0.0, Math.min(before.length(), beforeS)));
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

        // A cart standing in the middle of a curve has a 45 degree tangent.
        // The walker resolves that from the cart's position inside the block;
        // if it still could not, the furnace push is the next best signal.
        if (forward.isEmpty()) {
            Vector push = new Vector(engine.getPushX(), 0.0, engine.getPushZ());
            if (push.lengthSquared() > 1.0E-6) {
                used = snap(push);
                forward = RailWalker.walk(world, block, enginePos, used, config.lookaheadBlocks);
            }
        }

        // Still nothing: the remembered heading points across the rail (a cart
        // that was just placed, or a track rebuilt under a parked train). The
        // only axes that can walk at all are the rail's own axes.
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
        // Trimming can legitimately leave nothing: the engine may be standing
        // on the very last point of the line. That is not a broken walk, it
        // only means there is no track ahead, so keep the empty path and let
        // the caller stand still instead of dissolving the train.
        forward = forward.trimFrom(forward.nearest(enginePos).s());

        RailPath backward = RailWalker.walk(world, block, enginePos,
                used.clone().multiply(-1.0), config.lookaheadBlocks);
        if (!backward.isEmpty()) {
            backward = backward.trimFrom(backward.nearest(enginePos).s());
        }
        return new Rebuild(forward, backward, used);
    }

    /**
     * Direction of travel: the rail tangent remembered from last tick first,
     * then the furnace push, then where the entity is facing. Snapping is only
     * applied to the two latter sources — a 45 degree curve tangent must stay
     * diagonal or the walker cannot follow it.
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

    /** Axis-aligns a freshly read push or facing vector. */
    private static Vector snap(Vector raw) {
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
