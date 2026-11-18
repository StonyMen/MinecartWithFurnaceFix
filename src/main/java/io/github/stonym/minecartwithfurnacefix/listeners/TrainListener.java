package io.github.stonym.minecartwithfurnacefix.listeners;

import io.github.stonym.minecartwithfurnacefix.managers.PluginManager;
import io.github.stonym.minecartwithfurnacefix.rail.RailWalker;
import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Teardown hooks, plus the one event that builds a train: coal into a furnace.
 *
 * These only decide membership; positioning happens in the mover, never inside an
 * event.
 */
public final class TrainListener implements Listener {

    private final PluginManager pluginManager;

    public TrainListener(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /**
     * Stoking the furnace — the only way a train is assembled.
     *
     * A furnace minecart that is not on a rail is refused: it could never couple
     * to anything. Cancelled at HIGHEST so vanilla never adds the fuel or the
     * shove.
     *
     * A stoke is also a shove: vanilla points the push away from the player, so
     * right-clicking from the far side turns the cart, even while it is still
     * burning. Our engine never moves itself, so vanilla's push cannot be observed
     * afterwards — hence the player's position is handed over instead.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStoke(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof PoweredMinecart engine)) {
            return;
        }
        if (!isFurnaceFuel(event)) {
            return;
        }
        if (!RailWalker.standsOnRail(engine.getLocation())) {
            // Refused before vanilla sees it, so the furnace never gets the coal.
            // The client has already taken one off its own stack by then, which is
            // why the count blinks: resync immediately instead of waiting for the
            // next inventory update.
            event.setCancelled(true);
            event.getPlayer().updateInventory();
            return;
        }
        pluginManager.trainManager().stoke(engine, event.getPlayer().getLocation());
    }

    /** Coal or charcoal, the two items vanilla accepts in a furnace minecart. */
    private static boolean isFurnaceFuel(PlayerInteractEntityEvent event) {
        ItemStack held = event.getPlayer().getInventory().getItem(event.getHand());
        if (held == null) {
            return false;
        }
        Material type = held.getType();
        return type == Material.COAL || type == Material.CHARCOAL;
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
