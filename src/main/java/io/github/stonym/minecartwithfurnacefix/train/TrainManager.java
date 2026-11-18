package io.github.stonym.minecartwithfurnacefix.train;

import io.github.stonym.minecartwithfurnacefix.config.PluginConfig;
import io.github.stonym.minecartwithfurnacefix.rail.RailPath;
import io.github.stonym.minecartwithfurnacefix.rail.RailWalker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

/**
 * Owns every train: who belongs to which one, and the teams that stop the
 * members from colliding with each other.
 *
 * A train is created by stoking the furnace and is assembled by looking along
 * the rail from that engine, never by watching two carts bump into each other
 * or by scanning the world on a timer. That is the whole point of the rewrite:
 * contact is no longer part of the model, and neither is a background sweep
 * that the player cannot see or predict.
 */
public final class TrainManager {

    private record Candidate(Minecart cart, double offset) {
    }

    private final Logger log;
    private PluginConfig config;
    private final TrainMover mover;

    private final Map<UUID, Train> byMember = new HashMap<>();
    private final List<Train> trains = new ArrayList<>();

    private int nextId = 1;
    private int assemblies;
    private int dissolves;

    public TrainManager(Logger log, PluginConfig config) {
        this.log = log;
        this.config = config;
        this.mover = new TrainMover(this, config);
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
        this.mover.setConfig(config);
    }

    public TrainMover mover() {
        return mover;
    }

    public List<Train> trains() {
        return trains;
    }

    public void tickAll() {
        if (trains.isEmpty()) {
            return;
        }
        for (Train train : new ArrayList<>(trains)) {
            try {
                mover.tick(train);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "train " + train.id() + " failed to tick", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Assembly
    // ------------------------------------------------------------------

    /**
     * A player put coal in the furnace. This is the only way a train is born.
     *
     * Stoking is the moment the player is looking at these carts and expecting
     * them to move together, so it is also the moment to look around the
     * engine: a brand new consist is assembled, an existing one picks up the
     * carts that were standing beside it. Nothing else forms a train — a cart
     * that starts rolling on its own, or one that was placed while the train
     * was elsewhere, is simply not part of it until the player stokes again.
     *
     * The stoke is passed on as a direction, exactly as vanilla does it: the
     * push is "away from the player", so stoking from the other side of the
     * cart sends the train back the way it came.
     */
    public void stoke(PoweredMinecart engine, Location playerLocation) {
        if (!config.enabled || !engine.isValid() || engine.isDead()) {
            return;
        }
        // Which way the push goes is known here, before vanilla writes it into
        // the cart, and it has to be known here: it decides which half of the
        // rail is "ahead". Walking the track off the old push and then letting
        // the first tick walk it off the new one makes the two disagree, and
        // every cart's offset then means the opposite side of the furnace.
        Vector push = pushFrom(engine, playerLocation);
        Train train = byMember.get(engine.getUniqueId());
        if (train == null || train.dissolved()) {
            assemble(engine, push);
            train = byMember.get(engine.getUniqueId());
        } else {
            regroup(train);
        }
        if (train != null) {
            train.reignite(push);
            if (config.debug) {
                log("train " + train.id() + ": stoked, push "
                        + (push == null ? "(none)"
                        : String.format("(%.2f, %.2f)", push.getX(), push.getZ())));
            }
        }
    }

    /** Horizontal "away from the player", or null when it cannot be told. */
    private static Vector pushFrom(PoweredMinecart engine, Location playerLocation) {
        if (playerLocation == null) {
            return null;
        }
        Location cart = engine.getLocation();
        if (cart.getWorld() == null || !cart.getWorld().equals(playerLocation.getWorld())) {
            return null;
        }
        Vector push = cart.toVector().subtract(playerLocation.toVector());
        push.setY(0.0);
        return push.lengthSquared() < 1.0E-8 ? null : push;
    }

    private void assemble(PoweredMinecart engine, Vector push) {
        Vector heading = push == null ? null : TrainMover.snap(push);
        Track track = Track.of(engine, walkBlocks(), heading);
        if (track == null) {
            return;
        }

        List<Candidate> found = candidates(track, engine);
        if (found.isEmpty()) {
            return;
        }

        // Nearest first, on both sides of the engine, and at the offsets the
        // carts are actually standing on.
        //
        // Nothing is snapped onto a slot here. Pushing a cart to "the next
        // multiple of spacing" is a move the player sees the instant they put
        // coal in the furnace: a consist that was standing still suddenly
        // re-orders and spreads out. Even gaps are the rolling train's job
        // (spacing converge), which slides them apart a hundredth of a block
        // at a time instead of a jump.
        found.sort(Comparator.comparingDouble(candidate -> Math.abs(candidate.offset())));

        Train train = new Train(nextId++, engine);
        // The direction is part of the assembly, not something the first tick
        // works out again from the furnace push: the mover prefers a known
        // heading over that push, so recording it here is what keeps the two
        // walks pointing the same way.
        train.heading(heading);
        for (Candidate candidate : found) {
            if (train.size() >= config.maxMembers) {
                break;
            }
            if (crowded(train, candidate.offset())) {
                continue;
            }
            train.add(candidate.cart(), candidate.offset());
        }
        if (train.hasNoWagons()) {
            return;
        }

        trains.add(train);
        reindex(train);
        attachTeam(train);
        assemblies++;
        if (config.debug) {
            log("train " + train.id() + " assembled: " + train.wagons().size()
                    + " wagons, heading " + format(train.heading()));
        }
    }

    /**
     * Adds the carts that were left standing beside a train that already
     * exists.
     *
     * Stoking again is how a player says "this one too". Carts are taken
     * nearest-first, so a train that is nearly full fills its closest slots
     * instead of whatever the entity list happened to return first.
     */
    private void regroup(Train train) {
        if (train.size() >= config.maxMembers) {
            return;
        }
        // The train's own heading, so a cart added now gets its offset in the
        // same frame the mover is placing carts in. Walking off the furnace
        // push here would flip every offset the moment the player stoked from
        // the other side.
        Track track = Track.of(train.engine(), walkBlocks(), train.heading());
        if (track == null) {
            return;
        }
        List<Candidate> found = candidates(track, train.engine());
        found.sort(Comparator.comparingDouble(candidate -> Math.abs(candidate.offset())));

        for (Candidate candidate : found) {
            if (train.size() >= config.maxMembers) {
                break;
            }
            if (crowded(train, candidate.offset())) {
                continue;
            }
            train.add(candidate.cart(), candidate.offset());
            byMember.put(candidate.cart().getUniqueId(), train);
            addToTeam(train, candidate.cart());
            if (config.debug) {
                log("train " + train.id() + ": picked up a cart at offset "
                        + String.format("%.2f", candidate.offset()));
            }
        }
    }

    /**
     * Whether a cart at this offset would land on top of one that is already
     * coupled.
     *
     * This is deliberately <em>not</em> the spacing rule. Carts parked bumper
     * to bumper are how a train is left standing, and rejecting those is what
     * made stoking a furnace ignore the cart the player had just pushed
     * against it: a minecart is 0.98 wide, so two of them touch at roughly one
     * block, while {@code spacing} is 1.5. Only a real overlap — two carts in
     * the same place — is refused; the spacing converge pulls the rest apart
     * once the train rolls.
     */
    private static boolean crowded(Train train, double offset) {
        for (Train.Member member : train.wagons()) {
            if (Math.abs(member.offset() - offset) < 0.25) {
                return true;
            }
        }
        return false;
    }

    /** Unclaimed wagons standing near the engine and on the same rail. */
    private List<Candidate> candidates(Track track, PoweredMinecart engine) {
        Location centre = engine.getLocation();
        World world = centre.getWorld();
        if (world == null) {
            return List.of();
        }
        List<Candidate> found = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(
                centre, config.maxLength, 3.0, config.maxLength)) {
            if (!(entity instanceof Minecart cart) || cart instanceof PoweredMinecart) {
                continue;
            }
            if (cart.getUniqueId().equals(engine.getUniqueId())) {
                continue;
            }
            if (byMember.containsKey(cart.getUniqueId())) {
                continue;
            }
            Candidate candidate = track.locate(cart, config);
            if (candidate != null) {
                found.add(candidate);
            }
        }
        return found;
    }

    /**
     * How much track an assembly needs to see.
     *
     * Locating a cart only ever needs {@code max-length} blocks of rail — a
     * candidate further along the track than that is rejected anyway — so
     * there is no reason to walk the full {@code lookahead-blocks} for it.
     */
    private int walkBlocks() {
        return (int) Math.ceil(config.maxLength) + 2;
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    public void release(Train train, Minecart cart, String reason) {
        if (!train.remove(cart)) {
            return;
        }
        byMember.remove(cart.getUniqueId());
        removeFromTeam(train, cart);
        mover.unfreeze(cart);
        mover.countRelease();
        if (config.debug) {
            log("train " + train.id() + ": released a cart (" + reason + ")");
        }
        if (train.hasNoWagons()) {
            dissolve(train, "no wagons left");
        }
    }

    /**
     * Drops wagons that no longer exist.
     *
     * Removing them through {@link #release} instead of deleting them from the
     * member list keeps the id index in step; a dead cart left behind in
     * {@code byMember} would hold a UUID for as long as the mapping lives.
     */
    void dropGone(Train train) {
        for (int index = train.wagons().size() - 1; index >= 0; index--) {
            Minecart cart = train.wagons().get(index).cart();
            if (!cart.isValid() || cart.isDead()) {
                release(train, cart, "cart gone");
                if (train.dissolved()) {
                    return;
                }
            }
        }
    }

    /**
     * Breaks a train up because the line under or in front of it is gone.
     *
     * Dissolving is the whole point: it unfreezes every cart, so the player can
     * push them again instead of finding a consist welded to a dead end.
     */
    public void derail(Train train, String reason) {
        if (config.debug) {
            log("train " + train.id() + " derailed: " + reason);
        }
        dissolve(train, reason);
    }

    public void dissolve(Train train, String reason) {
        dissolve(train, reason, true);
    }

    /**
     * Drops a train and hands every cart back to vanilla.
     *
     * The furnace is emptied on the way out. This is the plugin's one special
     * case, and it is not about speed — it is about what the engine would do
     * next. A minecart that still has fuel drives itself along, and for a
     * consist that just came apart that means the engine wandering off on its
     * own, burning, while the wagons it was pulling stay behind. With an empty
     * tank it stands where the train died until someone rebuilds the line and
     * stokes it, and a stoke is also what picks a fresh direction.
     */
    private void dissolve(Train train, String reason, boolean emptyTank) {
        if (!trains.remove(train)) {
            return;
        }
        train.dissolved(true);
        if (emptyTank) {
            emptyFurnace(train);
        }
        // Every cart must be handed back to vanilla before the records go, or
        // it keeps the frozen state the mover gave it and stands there forever.
        for (Train.Member member : train.wagons()) {
            mover.unfreeze(member.cart());
        }
        mover.unfreeze(train.engine());

        byMember.remove(train.engineId());
        for (Train.Member member : train.wagons()) {
            byMember.remove(member.cart().getUniqueId());
        }
        train.wagons().clear();
        unregisterTeam(train);
        dissolves++;
        if (config.debug) {
            log("train " + train.id() + " dissolved (" + reason + ")");
        }
    }

    /**
     * Takes the coal out of the engine and stops it pushing.
     *
     * The push is cleared with the fuel for the same reason: both are what
     * make a furnace minecart drive itself, and a train that has just been
     * taken apart must not leave one behind under its own power.
     */
    private void emptyFurnace(Train train) {
        PoweredMinecart engine = train.engine();
        if (!engine.isValid() || engine.isDead()) {
            return;
        }
        try {
            engine.setFuel(0);
            engine.setPushX(0.0);
            engine.setPushZ(0.0);
        } catch (RuntimeException e) {
            log.log(Level.FINE, "could not empty the furnace", e);
        }
    }

    public void onCartRemoved(Minecart cart) {
        Train train = byMember.get(cart.getUniqueId());
        if (train == null) {
            return;
        }
        if (train.engineId().equals(cart.getUniqueId())) {
            dissolve(train, "engine destroyed");
        } else {
            release(train, cart, "destroyed");
        }
    }

    public void shutdown() {
        for (Train train : new ArrayList<>(trains)) {
            // A shutdown is not a derailment: the world is being saved and the
            // engine keeps the coal it has for the next start.
            dissolve(train, "plugin disabled", false);
        }
        trains.clear();
        byMember.clear();
    }

    private void reindex(Train train) {
        byMember.put(train.engineId(), train);
        for (Train.Member member : train.wagons()) {
            byMember.put(member.cart().getUniqueId(), train);
        }
    }

    // ------------------------------------------------------------------
    // Collision teams
    //
    // Carts inside one train must not shove each other. Vanilla resolves
    // contact by averaging velocities, which is exactly what used to tear a
    // train apart on a curve. A team with COLLISION_RULE = NEVER removes the
    // contact entirely instead of trying to out-compute it.
    // ------------------------------------------------------------------

    private Scoreboard scoreboard() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        return manager == null ? null : manager.getMainScoreboard();
    }

    private void attachTeam(Train train) {
        Scoreboard scoreboard = scoreboard();
        if (scoreboard == null) {
            return;
        }
        try {
            String name = "mwff" + train.id();
            Team team = scoreboard.getTeam(name);
            if (team == null) {
                team = scoreboard.registerNewTeam(name);
            }
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            team.addEntry(train.engine().getUniqueId().toString());
            for (Train.Member member : train.wagons()) {
                team.addEntry(member.cart().getUniqueId().toString());
            }
            train.teamName(name);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not create a collision team", e);
        }
    }

    private void addToTeam(Train train, Minecart cart) {
        Team team = teamOf(train);
        if (team != null) {
            team.addEntry(cart.getUniqueId().toString());
        }
    }

    private void removeFromTeam(Train train, Minecart cart) {
        Team team = teamOf(train);
        if (team != null) {
            team.removeEntry(cart.getUniqueId().toString());
        }
    }

    private void unregisterTeam(Train train) {
        Scoreboard scoreboard = scoreboard();
        String name = train.teamName();
        if (scoreboard == null || name == null) {
            return;
        }
        try {
            Team team = scoreboard.getTeam(name);
            if (team != null) {
                team.unregister();
            }
        } catch (RuntimeException e) {
            log.log(Level.FINE, "could not unregister team " + name, e);
        }
    }

    private Team teamOf(Train train) {
        Scoreboard scoreboard = scoreboard();
        String name = train.teamName();
        if (scoreboard == null || name == null) {
            return null;
        }
        return scoreboard.getTeam(name);
    }

    // ------------------------------------------------------------------

    void log(String message) {
        log.info("[train] " + message);
    }

    private static String format(Vector vector) {
        return vector == null ? "(unknown)"
                : String.format("(%.2f, %.2f)", vector.getX(), vector.getZ());
    }

    public int trainCount() {
        return trains.size();
    }

    public int cartCount() {
        int total = 0;
        for (Train train : trains) {
            total += train.size();
        }
        return total;
    }

    public int assemblies() {
        return assemblies;
    }

    public int dissolves() {
        return dissolves;
    }

    /**
     * A snapshot of the rail around one engine: the track ahead, the track
     * behind, and where the engine sits on both.
     */
    private record Track(RailPath forward, double sForward, RailPath backward, double sBackward) {

        static Track of(PoweredMinecart engine, int lookahead, Vector hint) {
            Location location = engine.getLocation();
            World world = location.getWorld();
            if (world == null) {
                return null;
            }
            Vector heading = hint != null && hint.lengthSquared() > 1.0E-6
                    ? hint.clone().setY(0.0).normalize()
                    : TrainMover.resolveHeading(engine, location, null);
            Block block = location.getBlock();
            Vector position = location.toVector();
            // The cart's own position decides which end of the first rail block
            // it is heading for. Without it the walk falls back to comparing
            // edge directions, which ties in the middle of a curve and gives up
            // — that is how a cart standing on the same rail as the engine used
            // to be invisible to the assembly.
            RailPath forward = RailWalker.walk(world, block, position, heading, lookahead);
            RailPath backward = RailWalker
                    .walk(world, block, position, heading.clone().multiply(-1.0), lookahead)
                    .reversed();
            // Either half alone is enough to couple carts against. A train
            // parked on the last block of a line has no track in front of it,
            // but that says nothing about the wagons standing behind it.
            if (forward.isEmpty() && backward.isEmpty()) {
                return null;
            }
            double sForward = forward.nearest(position).s();
            double sBackward = backward.isEmpty() ? 0.0 : backward.nearest(position).s();
            return new Track(forward, sForward, backward, sBackward);
        }

        Candidate locate(Minecart cart, PluginConfig config) {
            Vector point = cart.getLocation().toVector();
            RailPath.Hit forwardHit = forward.nearest(point);
            RailPath.Hit backwardHit = backward.isEmpty() ? null : backward.nearest(point);
            boolean useForward = backwardHit == null || forwardHit.distSq() <= backwardHit.distSq();
            double distSq = useForward ? forwardHit.distSq() : backwardHit.distSq();
            if (distSq > config.maxOffset * config.maxOffset) {
                return null;
            }
            double offset = useForward
                    ? forwardHit.s() - sForward
                    : backwardHit.s() - sBackward;
            if (Math.abs(offset) < 0.2 || Math.abs(offset) > config.maxLength) {
                return null;
            }
            return new Candidate(cart, offset);
        }
    }
}
