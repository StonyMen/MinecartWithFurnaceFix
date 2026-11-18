package io.github.stonym.minecartwithfurnacefix;

import io.github.stonym.minecartwithfurnacefix.command.MwffCommand;
import io.github.stonym.minecartwithfurnacefix.listeners.TrainListener;
import io.github.stonym.minecartwithfurnacefix.managers.PluginManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MinecartWithFurnaceFix extends JavaPlugin {

    private PluginManager pluginManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        pluginManager = new PluginManager(this);
        pluginManager.initialize();

        getServer().getPluginManager().registerEvents(new TrainListener(pluginManager), this);

        PluginCommand command = getCommand("mwff");
        if (command != null) {
            MwffCommand executor = new MwffCommand(pluginManager);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getLogger().info("Enabled: rail-path train control, engine physics untouched.");
    }

    @Override
    public void onDisable() {
        if (pluginManager != null) {
            pluginManager.shutdown();
        }
    }

    public PluginManager pluginManager() {
        return pluginManager;
    }
}
