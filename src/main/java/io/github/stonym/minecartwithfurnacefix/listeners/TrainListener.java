package io.github.stonym.minecartwithfurnacefix.listeners;

import io.github.stonym.minecartwithfurnacefix.managers.PluginManager;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

/**
 * Discovery and teardown hooks.
 *
 * These only decide <em>membership</em>. Positioning happens once per tick in
 * the mover, never from inside an event.
 */
public final class TrainListener implements Listener {

    private final PluginManager pluginManager;

    public TrainListener(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (event.getVehicle() instanceof Minecart cart) {
            pluginManager.trainManager().tryForm(cart);
        }
    }

    /**
     * Stoking the furnace is the moment a player expects the train to exist,
     * so group immediately instead of waiting for the cart to start rolling.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Minecart cart) {
            pluginManager.trainManager().tryForm(cart);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (event.getVehicle() instanceof Minecart cart) {
            pluginManager.trainManager().onCartRemoved(cart);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(EntityPortalEvent event) {
        if (event.getEntity() instanceof Minecart cart) {
            pluginManager.trainManager().onCartRemoved(cart);
        }
    }
}
