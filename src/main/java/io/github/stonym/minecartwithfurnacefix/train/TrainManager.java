package io.github.stonym.minecartwithfurnacefix.train;

import io.github.stonym.minecartwithfurnacefix.config.PluginConfig;
import io.github.stonym.minecartwithfurnacefix.rail.RailPath;
import io.github.stonym.minecartwithfurnacefix.rail.RailWalker;
import java.util.ArrayList;
import java.util.Collection;
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
 * A train is assembled by looking along the rail from the furnace minecart,
 * never by watching two carts bump into each other. That is the whole point of
 * the rewrite: contact is no longer part of the model.
 */
public final class TrainManager {

    private record Candidate(Minecart cart, double offset) {
    }

    private final Logger log;
    private PluginConfig config;
    private final TrainMover mover;

    private final Map<UUID, Train> byMember = new HashMap<>();
    private final List<Train> trains = new ArrayList<>();
    private final Map<UUID, Long> lastScan = new HashMap<>();

    private int nextId = 1;
    private long tickCounter;
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

    public Train trainOf(Entity entity) {
        return byMember.get(entity.getUniqueId());
    }

    public void tickAll() {
        tickCounter++;
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

    /**
     * A player stoked the furnace, which in vanilla is also a shove: the push
     * is written as "away from the player", so stoking from the other side of
     * the cart sends it back the way it came. Recording it here and letting
     * the mover apply it on the next tick keeps event handling read-only and
     * avoids reading the engine's push before vanilla has written it.
     */
    public void requestReignite(PoweredMinecart engine, Location playerLocation) {
        Train train = byMember.get(engine.getUniqueId());
        if (train == null || playerLocation == null) {
            return;
        }
        Location cart = engine.getLocation();
        if (cart.getWorld() == null || !cart.getWorld().equals(playerLocation.getWorld())) {
            return;
        }
        Vector push = cart.toVector().subtract(playerLocation.toVector());
        train.reignite(push);
        if (config.debug) {
            log("train " + train.id() + ": stoked, push "
                    + String.format("(%.2f, %.2f)", push.getX(), push.getZ()));
        }
    }

    /**
     * Slow sweep: adopts carts that appeared next to an existing train but
     * never moved (a cart placed by a player does not fire a move event).
     *
     * The rail snapshot is only taken when there is actually a stray cart in
     * range. Walking the track is the expensive part of this sweep and most of
     * the time there is nothing to look at, so the cheap test comes first.
     */
    public void maintain() {
        if (!config.enabled || trains.isEmpty()) {
            return;
        }
        for (Train train : new ArrayList<>(trains)) {
            if (train.size() >= config.maxMembers) {
                continue;
            }
            Location centre = train.engineLocation();
            if (centre.getWorld() == null) {
                continue;
            }
            Collection<Entity> nearby = centre.getWorld().getNearbyEntities(
                    centre, config.maxLength, 3.0, config.maxLength);

            boolean stray = false;
            for (Entity entity : nearby) {
                if (entity instanceof Minecart cart && !(cart instanceof PoweredMinecart)
                        && !byMember.containsKey(cart.getUniqueId())) {
                    stray = true;
                    break;
                }
            }
            if (!stray) {
                continue;
            }

            Track track = Track.of(train.engine(), walkBlocks());
            if (track == null) {
                continue;
            }
            for (Entity entity : nearby) {
                if (train.size() >= config.maxMembers) {
                    break;
                }
                if (!(entity instanceof Minecart cart) || cart instanceof PoweredMinecart) {
                    continue;
                }
                if (byMember.containsKey(cart.getUniqueId())) {
                    continue;
                }
                Candidate candidate = track.locate(cart, config);
                if (candidate == null) {
                    continue;
                }
                boolean clashes = false;
                for (Train.Member member : train.wagons()) {
                    if (Math.abs(member.offset() - candidate.offset()) < config.spacing) {
                        clashes = true;
                        break;
                    }
                }
                if (clashes) {
                    continue;
                }
                train.add(cart, candidate.offset());
                byMember.put(cart.getUniqueId(), train);
                addToTeam(train, cart);
                if (config.debug) {
                    log("train " + train.id() + ": adopted a waiting cart");
                }
            }
        }
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
    // Assembly
    // ------------------------------------------------------------------

    /**
     * Called whenever a cart does something a player can see: moving, being
     * right-clicked, being placed. Cheap because of the per-cart cooldown.
     */
    public void tryForm(Minecart cart) {
        if (!config.enabled || !cart.isValid() || cart.isDead()) {
            return;
        }
        if (byMember.containsKey(cart.getUniqueId())) {
            return;
        }
        if (!scanAllowed(cart)) {
            return;
        }

        PoweredMinecart engine;
        if (cart instanceof PoweredMinecart powered && powered.getFuel() > 0) {
            engine = powered;
        } else {
            engine = nearestEngine(cart);
        }
        if (engine == null) {
            return;
        }

        Train existing = byMember.get(engine.getUniqueId());
        if (existing != null) {
            adopt(existing, cart);
        } else if (engine.getFuel() > 0) {
            assemble(engine);
        }
    }

    private boolean scanAllowed(Minecart cart) {
        Long last = lastScan.get(cart.getUniqueId());
        if (last != null && tickCounter - last < config.scanInterval) {
            return false;
        }
        lastScan.put(cart.getUniqueId(), tickCounter);
        if (lastScan.size() > 4096) {
            lastScan.clear();
        }
        return true;
    }

    private PoweredMinecart nearestEngine(Minecart cart) {
        double radius = config.maxLength;
        PoweredMinecart best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : cart.getWorld()
                .getNearbyEntities(cart.getLocation(), radius, 4.0, radius)) {
            if (!(entity instanceof PoweredMinecart powered) || powered.getFuel() <= 0) {
                continue;
            }
            if (!powered.isValid()) {
                continue;
            }
            double distance = powered.getLocation().distanceSquared(cart.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = powered;
            }
        }
        return best;
    }

    private void assemble(PoweredMinecart engine) {
        Track track = Track.of(engine, walkBlocks());
        if (track == null) {
            return;
        }

        Location centre = engine.getLocation();
        List<Candidate> found = new ArrayList<>();
        for (Entity entity : engine.getWorld().getNearbyEntities(
                centre, config.maxLength, 3.0, config.maxLength)) {
            if (!(entity instanceof Minecart cart)) {
                continue;
            }
            if (cart.getUniqueId().equals(engine.getUniqueId())) {
                continue;
            }
            if (cart instanceof PoweredMinecart) {
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
        if (found.isEmpty()) {
            return;
        }

        List<Candidate> ahead = new ArrayList<>();
        List<Candidate> behind = new ArrayList<>();
        for (Candidate candidate : found) {
            if (candidate.offset() > 0.0) {
                ahead.add(candidate);
            } else {
                behind.add(candidate);
            }
        }
        ahead.sort(Comparator.comparingDouble(Candidate::offset));
        behind.sort(Comparator.comparingDouble(candidate -> -candidate.offset()));

        Train train = new Train(nextId++, engine);
        double slot = config.spacing;
        for (Candidate candidate : ahead) {
            if (train.size() >= config.maxMembers) {
                break;
            }
            double offset = Math.max(candidate.offset(), slot);
            train.add(candidate.cart(), offset);
            slot = offset + config.spacing;
        }
        slot = -config.spacing;
        for (Candidate candidate : behind) {
            if (train.size() >= config.maxMembers) {
                break;
            }
            double offset = Math.min(candidate.offset(), slot);
            train.add(candidate.cart(), offset);
            slot = offset - config.spacing;
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

    /** Adds one more cart to a train that already exists. */
    private void adopt(Train train, Minecart cart) {
        if (train.size() >= config.maxMembers) {
            return;
        }
        Track track = Track.of(train.engine(), walkBlocks());
        if (track == null) {
            return;
        }
        Candidate candidate = track.locate(cart, config);
        if (candidate == null) {
            return;
        }
        for (Train.Member member : train.wagons()) {
            if (Math.abs(member.offset() - candidate.offset()) < config.spacing) {
                return;
            }
        }
        train.add(cart, candidate.offset());
        byMember.put(cart.getUniqueId(), train);
        addToTeam(train, cart);
        if (config.debug) {
            log("train " + train.id() + ": adopted a cart at offset "
                    + String.format("%.2f", candidate.offset()));
        }
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
     * Breaks a train up because the track in front of it is gone.
     *
     * Dissolving is the whole point: it unfreezes every cart, so the player can
     * push them again instead of finding a consist welded to a dead end.
     *
     * The fuel is cleared by default. A furnace minecart that still has fuel
     * pushes itself along, and from the end of a broken line that means
     * launching itself off the rails — possibly landing on the same track a
     * block further on, which is exactly where the wagons it just left are
     * standing, so the whole train would be assembled again and repeat. With an
     * empty tank it simply stands there until someone rebuilds the rail and
     * stokes it, and stoking is also what picks a fresh direction.
     */
    public void derail(Train train, String reason) {
        PoweredMinecart engine = train.engine();
        if (config.clearFuelOnDerail && engine.isValid() && !engine.isDead()) {
            try {
                engine.setFuel(0);
                engine.setPushX(0.0);
                engine.setPushZ(0.0);
            } catch (RuntimeException e) {
                log.log(Level.FINE, "could not empty the furnace", e);
            }
        }
        if (config.debug) {
            log("train " + train.id() + ": derailed (" + reason + ")"
                    + (config.clearFuelOnDerail ? ", furnace emptied" : ""));
        }
        dissolve(train, reason);
    }

    public void dissolve(Train train, String reason) {
        if (!trains.remove(train)) {
            return;
        }
        train.dissolved(true);

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
            dissolve(train, "plugin disabled");
        }
        trains.clear();
        byMember.clear();
        lastScan.clear();
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
        return String.format("(%.2f, %.2f)", vector.getX(), vector.getZ());
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

        static Track of(PoweredMinecart engine, int lookahead) {
            Location location = engine.getLocation();
            World world = location.getWorld();
            if (world == null) {
                return null;
            }
            Vector heading = TrainMover.resolveHeading(engine, location, null);
            Block block = location.getBlock();
            RailPath forward = RailWalker.walk(world, block, heading, lookahead);
            if (forward.isEmpty()) {
                return null;
            }
            RailPath backward = RailWalker
                    .walk(world, block, heading.clone().multiply(-1.0), lookahead)
                    .reversed();
            Vector position = location.toVector();
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
