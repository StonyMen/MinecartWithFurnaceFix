package io.github.stonym.minecartwithfurnacefix.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of config.yml.
 *
 * Reloading builds a new instance and swaps the reference; nothing mutates a
 * live config object while a tick is running.
 */
public final class PluginConfig {

    public final boolean enabled;

    public final double spacing;
    public final double spacingConverge;
    public final int maxMembers;
    public final double maxLength;
    public final double maxOffset;
    public final double breakDistance;

    public final int lookaheadBlocks;

    public final double cruiseSpeed;

    public final boolean debug;

    private PluginConfig(FileConfiguration c) {
        this.enabled = c.getBoolean("enabled", true);

        // A minecart is 0.98 wide, so anything below 1.0 makes the two carts
        // permanently overlap and fight each other.
        this.spacing = Math.max(1.0, c.getDouble("train.spacing", 1.5));

        // How far a cart may slide along the rail per tick while the gaps are
        // being equalised. 0 disables the whole thing.
        this.spacingConverge = Math.max(0.0,
                c.getDouble("train.spacing-converge", 0.03));
        this.maxMembers = Math.max(2, c.getInt("train.max-members", 5));
        this.maxLength = Math.max(spacing * (maxMembers - 1) + 0.5,
                c.getDouble("train.max-length", 8.0));
        this.maxOffset = Math.max(0.1, c.getDouble("train.max-offset", 0.75));
        // Both release checks are derived, not configured.
        //
        // Reach: every slot sits at most max-length from the engine, so a cart
        // further away than that cannot belong to this train — it was
        // teleported, carried through a portal, or the engine was destroyed
        // while our records were stale. Nothing inside a healthy consist can
        // cross it.
        //
        // Slot: a cart that is nowhere near where its own offset says it
        // should be has left the train even if it is still close to the
        // engine. One train length plus one cart gap is the widest a slot can
        // legitimately be, so anything beyond that is not drift, it is a move.
        this.breakDistance = this.maxLength + this.spacing;

        this.lookaheadBlocks = Math.max(maxMembers + 2,
                c.getInt("path.lookahead-blocks", 48));

        // What a lit furnace minecart actually does in vanilla: 4 blocks a
        // second, i.e. 0.2 blocks/tick, held constant — uphill, downhill and
        // through powered rails alike. It is also the only speed this plugin
        // has: a consist that runs out of fuel is dissolved, not left to
        // coast, so there is no second regime to configure.
        this.cruiseSpeed = Math.max(1.0E-4,
                c.getDouble("physics.cruise-speed", 0.2));

        this.debug = c.getBoolean("debug", false);
    }

    public static PluginConfig load(FileConfiguration c) {
        return new PluginConfig(c);
    }
}
