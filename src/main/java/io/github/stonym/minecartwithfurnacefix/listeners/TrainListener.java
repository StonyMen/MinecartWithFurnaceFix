package io.github.stonym.minecartwithfurnacefix.listeners;

import io.github.stonym.minecartwithfurnacefix.managers.PluginManager;
import io.github.stonym.minecartwithfurnacefix.rail.RailWalker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Teardown hooks, plus the one event that builds a train: a player putting coal
 * in a furnace.
 *
 * These only decide <em>membership</em>. Positioning happens once per tick in
 * the mover, never from inside an event.
 */
public final class TrainListener implements Listener {

    private final PluginManager pluginManager;

    public TrainListener(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /**
     * Stoking the furnace.
     *
     * This is the only way a train is assembled, so it is also the only place
     * that has to know what "these carts belong together" means: the ones
     * standing on the same rail around the engine, read once, right now. There
     * is no background scan, no move event and no timer to get out of step
     * with what the player just did.
     *
     * A furnace minecart that is not on a rail is refused: it could never be
     * coupled to anything, and the train only exists while it is running on
     * track. The event is cancelled at HIGHEST so vanilla never gets to add
     * the fuel or the shove that comes with it.
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
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStoke(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof PoweredMinecart engine)) {
            return;
        }
        if (!isFurnaceFuel(event)) {
            return;
        }
        if (!RailWalker.standsOnRail(engine.getLocation())) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            player.sendActionBar(Component.text(
                    "动力矿车不在铁轨上，加不了煤", NamedTextColor.RED));
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
