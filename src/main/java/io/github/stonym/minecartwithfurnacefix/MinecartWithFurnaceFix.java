package io.github.stonym.minecartwithfurnacefix;

import org.bukkit.plugin.java.JavaPlugin;
import io.github.stonym.minecartwithfurnacefix.managers.PluginManager;
import io.github.stonym.minecartwithfurnacefix.listeners.PlayerListener;

public class MinecartWithFurnaceFix extends JavaPlugin {
    
    @Override
    public void onEnable() {
        
        // Initialize managers
        PluginManager.getInstance().initialize();
        
        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(), this);
        
        getLogger().info(getDescription().getName() + " has been enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info(getDescription().getName() + " has been disabled!");
    }
    
}
