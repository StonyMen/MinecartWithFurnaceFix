package io.github.stonym.minecartwithfurnacefix.managers;

import io.github.stonym.minecartwithfurnacefix.MinecartWithFurnaceFix;
import io.github.stonym.minecartwithfurnacefix.config.PluginConfig;
import io.github.stonym.minecartwithfurnacefix.train.TrainManager;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns the single per-tick task and the slow maintenance sweep.
 *
 * There is exactly one task for the whole plugin: it walks the small list of
 * known trains. Nothing is driven from per-entity events, which is what keeps
 * the cost flat on a server with thousands of parked carts.
 */
public final class PluginManager {

    private static final int MAINTAIN_INTERVAL = 20;

    private final MinecartWithFurnaceFix plugin;

    private PluginConfig config;
    private TrainManager trainManager;
    private BukkitTask tickTask;
    private long startedAt;
    private int tickCounter;

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
        tickCounter++;
        if (!config.enabled) {
            return;
        }
        // A repeating task that throws is cancelled by the scheduler, which
        // would take the whole plugin down silently. Never let that happen.
        try {
            trainManager.tickAll();
            if (tickCounter % MAINTAIN_INTERVAL == 0) {
                trainManager.maintain();
            }
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
