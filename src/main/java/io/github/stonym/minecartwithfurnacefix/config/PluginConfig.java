package io.github.stonym.minecartwithfurnacefix.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of config.yml. Reloading builds a new instance and swaps the
 * reference, so nothing mutates a config while a tick is running.
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
    public final double poweredSpeed;

    public final boolean debug;

    private PluginConfig(FileConfiguration c) {
        this.enabled = c.getBoolean("enabled", true);

        // A minecart is 0.98 wide; below 1.0 the two carts overlap permanently.
        this.spacing = Math.max(1.0, c.getDouble("train.spacing", 1.5));

        // How far a cart may slide per tick while the gaps equalise. 0 disables it.
        this.spacingConverge = Math.max(0.0,
                c.getDouble("train.spacing-converge", 0.03));
        this.maxMembers = Math.max(2, c.getInt("train.max-members", 5));
        this.maxLength = Math.max(spacing * (maxMembers - 1) + 0.5,
                c.getDouble("train.max-length", 8.0));
        this.maxOffset = Math.max(0.1, c.getDouble("train.max-offset", 0.75));
        // Derived, not configured: one train length plus one cart gap is the
        // widest a slot can legitimately be, so anything beyond it is a move.
        this.breakDistance = this.maxLength + this.spacing;

        this.lookaheadBlocks = Math.max(maxMembers + 2,
                c.getInt("path.lookahead-blocks", 48));

        // What a lit furnace minecart does in vanilla: 0.2 blocks/tick, constant.
        this.cruiseSpeed = Math.max(1.0E-4,
                c.getDouble("physics.cruise-speed", 0.2));

        // Vanilla lets any minecart reach 8 blocks/s on a switched-on powered rail
        // — 0.4 blocks/tick. Held above cruise, or a booster would be a trap.
        this.poweredSpeed = Math.max(this.cruiseSpeed,
                c.getDouble("physics.powered-speed", 0.4));

        this.debug = c.getBoolean("debug", false);
    }

    public static PluginConfig load(FileConfiguration c) {
        return new PluginConfig(c);
    }
}
