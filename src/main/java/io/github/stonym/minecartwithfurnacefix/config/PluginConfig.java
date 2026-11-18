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
    public final int maxMembers;
    public final double maxLength;
    public final double maxOffset;
    public final double breakDistance;
    public final int scanInterval;

    public final int lookaheadBlocks;

    public final double maxSpeed;
    public final double acceleration;
    public final double friction;
    public final double gravity;
    public final double rollingResistance;
    public final double stopSpeed;

    public final boolean debug;

    private PluginConfig(FileConfiguration c) {
        this.enabled = c.getBoolean("enabled", true);

        // A minecart is 0.98 wide, so anything below 1.0 makes the two carts
        // permanently overlap and fight each other.
        this.spacing = Math.max(1.0, c.getDouble("train.spacing", 1.05));
        this.maxMembers = Math.max(2, c.getInt("train.max-members", 5));
        this.maxLength = Math.max(spacing * (maxMembers - 1) + 0.5,
                c.getDouble("train.max-length", 8.0));
        this.maxOffset = Math.max(0.1, c.getDouble("train.max-offset", 0.75));
        this.breakDistance = Math.max(1.0, c.getDouble("train.break-distance", 6.0));
        this.scanInterval = Math.max(1, c.getInt("train.scan-interval", 10));

        this.lookaheadBlocks = Math.max(maxMembers + 2,
                c.getInt("path.lookahead-blocks", 48));

        this.maxSpeed = Math.max(1.0E-4, c.getDouble("physics.max-speed", 0.4));
        this.acceleration = Math.max(1.0E-5, c.getDouble("physics.acceleration", 0.006));
        this.friction = Math.max(0.5, Math.min(1.0,
                c.getDouble("physics.friction", 0.997)));
        this.gravity = Math.max(0.0, c.getDouble("physics.gravity", 0.02));
        this.rollingResistance = Math.max(0.0,
                c.getDouble("physics.rolling-resistance", 0.001));
        this.stopSpeed = Math.max(0.0, c.getDouble("physics.stop-speed", 0.002));

        this.debug = c.getBoolean("debug", false);
    }

    public static PluginConfig load(FileConfiguration c) {
        return new PluginConfig(c);
    }
}
