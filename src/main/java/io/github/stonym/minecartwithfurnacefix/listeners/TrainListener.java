package io.github.stonym.minecartwithfurnacefix.listeners;

import io.github.stonym.minecartwithfurnacefix.managers.PluginManager;
import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;

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
     *
     * A stoke is also a shove. Vanilla stores the push as "away from the
     * player", which means every right-click with coal turns the cart if the
     * player is standing on the other side of it — including while the fuel is
     * still burning. The mover cannot see that on its own: our engine is
     * pinned in place, so vanilla's own push never moves it, and the push
     * vector may not even be rewritten when the tank is already lit. Handing
     * the player's position over lets a stoke change direction every time,
     * exactly as it does in vanilla.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Minecart cart)) {
            return;
        }
        pluginManager.trainManager().tryForm(cart);

        if (cart instanceof PoweredMinecart engine && isFurnaceFuel(event, engine)) {
            pluginManager.trainManager().requestReignite(engine, event.getPlayer().getLocation());
        }
    }

    /** Coal or charcoal, the two items vanilla accepts in a furnace minecart. */
    private static boolean isFurnaceFuel(PlayerInteractEntityEvent event, PoweredMinecart engine) {
        ItemStack held = engine == null ? null
                : event.getPlayer().getInventory().getItem(event.getHand());
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
