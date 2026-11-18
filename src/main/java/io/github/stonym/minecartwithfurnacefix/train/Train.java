package io.github.stonym.minecartwithfurnacefix.train;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import io.github.stonym.minecartwithfurnacefix.rail.RailPath;
import org.bukkit.Location;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.util.Vector;

/**
 * One locomotive plus the carts it drags.
 *
 * This is the TrainCarts model, reduced to the part that matters here: the
 * whole train has a single signed speed, and every member has a fixed
 * arc-length offset from the engine. Position is derived from those two
 * numbers, never from contact between carts.
 *
 * The engine is still a vanilla furnace minecart. Fuel, ignition and the push
 * vector keep running in vanilla; this class only reads them.
 */
public final class Train {

    /**
     * A wagon and its signed arc-length offset from the engine.
     *
     * The offset is mutable on purpose: it starts as wherever the cart was
     * standing when the train formed, then slides towards an even spacing once
     * the train rolls. Mutating it is how the gaps equalise without teleporting
     * anything.
     */
    public static final class Member {

        private final Minecart cart;
        private double offset;

        public Member(Minecart cart, double offset) {
            this.cart = cart;
            this.offset = offset;
        }

        public Minecart cart() {
            return cart;
        }

        public double offset() {
            return offset;
        }

        public void offset(double offset) {
            this.offset = offset;
        }
    }

    private final int id;
    private final PoweredMinecart engine;
    private final List<Member> wagons = new ArrayList<>();

    /** Last known direction of travel, in world space. Null until resolved. */
    private Vector heading;

    /**
     * Signed speed of the whole train, in blocks per tick. Positive means the
     * train travels along {@link #heading}; negative means it backs up.
     */
    private double speed;

    private String teamName;
    private boolean dissolved;

    /** Consecutive ticks the rail could not be resolved from the engine. */
    private int pathFailStreak;

    /**
     * Which way along the cached path the furnace is pushing: +1 with
     * increasing {@code s}, -1 against it, 0 until it has been read once.
     *
     * Vanilla latches this: while there is fuel left, nothing changes which way
     * a furnace minecart travels — not pushing it, not blocking it, not
     * derailing it. Only letting the fuel burn out and stoking it again picks a
     * new direction. Re-deriving it every tick is what made trains turn around
     * in the middle of a bend: the push is a fixed world vector written at
     * ignition, the rail turns 90 degrees under it, and the sign of the dot
     * product flips as soon as the angle passes a right angle.
     */
    private int pushSign;

    /**
     * Fuel reading at the end of the previous tick. A rise from zero is the
     * only event that is allowed to pick a new direction.
     */
    private long lastFuel = -1;

    /** Consecutive ticks the train has been standing still. */
    private int stallTicks;

    /**
     * Cached rail section, with its origin at the engine. {@code s} is how far
     * the engine has travelled from that origin. Both paths are only rebuilt
     * when the train reaches the end of the cached section.
     */
    private RailPath forward;
    private RailPath backward;
    private double s;

    public Train(int id, PoweredMinecart engine) {
        this.id = id;
        this.engine = engine;
    }

    public int id() {
        return id;
    }

    public PoweredMinecart engine() {
        return engine;
    }

    public List<Member> wagons() {
        return wagons;
    }

    public void add(Minecart cart, double offset) {
        wagons.add(new Member(cart, offset));
    }

    public boolean remove(Minecart cart) {
        return wagons.removeIf(m -> m.cart().getUniqueId().equals(cart.getUniqueId()));
    }

    public boolean contains(Minecart cart) {
        if (engine.getUniqueId().equals(cart.getUniqueId())) {
            return true;
        }
        for (Member member : wagons) {
            if (member.cart().getUniqueId().equals(cart.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    /** Engine plus wagons. */
    public int size() {
        return wagons.size() + 1;
    }

    public boolean hasNoWagons() {
        return wagons.isEmpty();
    }

    public Vector heading() {
        return heading;
    }

    public void heading(Vector heading) {
        this.heading = heading == null ? null : heading.clone();
    }

    public double speed() {
        return speed;
    }

    public void speed(double speed) {
        this.speed = speed;
    }

    public String teamName() {
        return teamName;
    }

    public void teamName(String teamName) {
        this.teamName = teamName;
    }

    public Location engineLocation() {
        return engine.getLocation();
    }

    public UUID engineId() {
        return engine.getUniqueId();
    }

    public boolean engineAlive() {
        return engine.isValid() && !engine.isDead();
    }

    /** Drops wagons that no longer exist in this world. */
    public void pruneInvalid() {
        wagons.removeIf(m -> !m.cart().isValid() || m.cart().isDead());
    }

    public void dissolveWagons() {
        wagons.clear();
    }

    public RailPath forward() {
        return forward;
    }

    public void forward(RailPath forward) {
        this.forward = forward;
    }

    public RailPath backward() {
        return backward;
    }

    public void backward(RailPath backward) {
        this.backward = backward;
    }

    public double s() {
        return s;
    }

    public void s(double s) {
        this.s = s;
    }

    public boolean dissolved() {
        return dissolved;
    }

    public int pathFailStreak() {
        return pathFailStreak;
    }

    public int bumpPathFailStreak() {
        return ++pathFailStreak;
    }

    public void resetPathFailStreak() {
        pathFailStreak = 0;
    }

    public int stallTicks() {
        return stallTicks;
    }

    public int bumpStallTicks() {
        return ++stallTicks;
    }

    public int resetStallTicks() {
        stallTicks = 0;
        return 0;
    }

    public int pushSign() {
        return pushSign;
    }

    public void pushSign(int pushSign) {
        this.pushSign = pushSign;
    }

    public long lastFuel() {
        return lastFuel;
    }

    public void lastFuel(long lastFuel) {
        this.lastFuel = lastFuel;
    }

    public void dissolved(boolean dissolved) {
        this.dissolved = dissolved;
    }

    /** Largest forward offset; how much track the train needs ahead of the engine. */
    public double maxOffset() {
        double max = 0.0;
        for (Member member : wagons) {
            max = Math.max(max, member.offset());
        }
        return max;
    }

    /** Most negative offset; how much track the train needs behind the engine. */
    public double minOffset() {
        double min = 0.0;
        for (Member member : wagons) {
            min = Math.min(min, member.offset());
        }
        return min;
    }
}
