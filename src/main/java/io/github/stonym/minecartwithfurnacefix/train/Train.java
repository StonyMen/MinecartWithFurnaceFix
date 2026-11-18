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
 * One locomotive plus the carts it drags: a single signed speed, and a fixed
 * arc-length offset per cart. Position is derived from those two numbers, never
 * from contact between carts.
 *
 * The engine is still a vanilla furnace minecart; this class only reads its
 * fuel, ignition and push.
 */
public final class Train {

    /** A wagon and its signed arc-length offset from the engine. */
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

        /** Mutable: spacing converge slides it towards an even gap. */
        public void offset(double offset) {
            this.offset = offset;
        }
    }

    private final int id;
    private final PoweredMinecart engine;
    private final List<Member> wagons = new ArrayList<>();

    /** Last known direction of travel, in world space. Null until resolved. */
    private Vector heading;

    /** Signed speed of the whole train, in blocks per tick. */
    private double speed;

    private String teamName;
    private boolean dissolved;

    /**
     * Which way along the path the furnace pushes: +1 with increasing {@code s},
     * -1 against it, 0 until it has been read once.
     *
     * Latched because only a stoke may change it. Re-deriving it from the push
     * every tick is what turned trains around mid-bend.
     */
    private int pushSign;

    /**
     * The stoke the player just performed, as a unit vector pointing from the
     * player towards the engine. Null when nothing is pending.
     *
     * Read from the world rather than from {@code engine.getPushX()}: the interact
     * event fires before vanilla writes anything, and vanilla may keep the old
     * push while the tank is already burning.
     */
    private Vector reignite;

    /** Records a stoke; the mover consumes it on the next tick, so it applies once. */
    public void reignite(Vector playerToEngine) {
        if (playerToEngine == null) {
            return;
        }
        Vector flat = playerToEngine.clone().setY(0.0);
        if (flat.lengthSquared() < 1.0E-8) {
            return;
        }
        this.reignite = flat.normalize();
    }

    /** The pending stoke, cleared as it is handed out. */
    public Vector consumeReignite() {
        Vector pending = reignite;
        reignite = null;
        return pending;
    }

    /** Consecutive ticks the train has been standing still. */
    private int stallTicks;

    /** Ticks since the train was assembled. */
    private int age;

    public int bumpAge() {
        return ++age;
    }

    /** Cached rail section, origin at the engine; {@code s} is how far it has gone. */
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

    /** Ticks since the last look at the rail ahead of the leading cart. */
    private int probeTicks;

    public int bumpProbeTicks() {
        return ++probeTicks;
    }

    public int resetProbeTicks() {
        probeTicks = 0;
        return 0;
    }

    public int pushSign() {
        return pushSign;
    }

    public void pushSign(int pushSign) {
        this.pushSign = pushSign;
    }

    public void dissolved(boolean dissolved) {
        this.dissolved = dissolved;
    }

    /** Largest forward offset: how much track the train needs ahead of the engine. */
    public double maxOffset() {
        double max = 0.0;
        for (Member member : wagons) {
            max = Math.max(max, member.offset());
        }
        return max;
    }

    /** Most negative offset: how much track the train needs behind the engine. */
    public double minOffset() {
        double min = 0.0;
        for (Member member : wagons) {
            min = Math.min(min, member.offset());
        }
        return min;
    }
}
