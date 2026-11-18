package io.github.stonym.minecartwithfurnacefix.managers;

import lombok.Getter;

public class PluginManager {
    @Getter private static final PluginManager instance = new PluginManager();
    
    private PluginManager() {}
    
    

    public void initialize() {
        // Initialize your managers here
    }
}
