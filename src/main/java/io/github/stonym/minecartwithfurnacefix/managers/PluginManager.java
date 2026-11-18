package io.github.stonym.minecartwithfurnacefix.managers;

import io.github.stonym.minecartwithfurnacefix.MinecartWithFurnaceFix;
import io.github.stonym.minecartwithfurnacefix.config.PluginConfig;
import io.github.stonym.minecartwithfurnacefix.train.TrainManager;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns the single per-tick task: it walks the short list of known trains.
 *
 * Nothing is driven by per-entity events and nothing sweeps the world on a timer,
 * which keeps the cost flat on a server with thousands of parked carts.
 */
public final class PluginManager {

    private final MinecartWithFurnaceFix plugin;

    private PluginConfig config;
    private TrainManager trainManager;
    private BukkitTask tickTask;
    private long startedAt;

    public PluginManager(MinecartWithFurnaceFix plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        this.config = PluginConfig.load(plugin.getConfig());
        this.trainManager = new TrainManager(plugin.getLogger(), config);
        this.startedAt = System.currentTimeMillis();
        restartTask();
    }

    private void restartTask() {
        stopTask();
        tickTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        if (!config.enabled) {
            return;
        }
        // A throwing repeating task is cancelled by the scheduler, silently taking
        // the whole plugin down.
        try {
            trainManager.tickAll();
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "train tick failed", e);
        }
    }

    public void reload() {
        plugin.reloadConfig();
        PluginConfig fresh = PluginConfig.load(plugin.getConfig());
        this.config = fresh;
        trainManager.setConfig(fresh);
    }

    public void shutdown() {
        stopTask();
        if (trainManager != null) {
            trainManager.shutdown();
        }
    }

    private void stopTask() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    public PluginConfig config() {
        return config;
    }

    public TrainManager trainManager() {
        return trainManager;
    }

    public long uptimeMillis() {
        return System.currentTimeMillis() - startedAt;
    }
}
