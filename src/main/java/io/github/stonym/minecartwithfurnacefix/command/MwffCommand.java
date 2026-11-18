package io.github.stonym.minecartwithfurnacefix.command;

import io.github.stonym.minecartwithfurnacefix.config.PluginConfig;
import io.github.stonym.minecartwithfurnacefix.managers.PluginManager;
import io.github.stonym.minecartwithfurnacefix.train.Train;
import io.github.stonym.minecartwithfurnacefix.train.TrainMover;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

public final class MwffCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "mwff.admin";
    private static final List<String> SUBCOMMANDS = List.of("stats", "here", "reload");

    private final PluginManager manager;

    public MwffCommand(PluginManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "stats" -> sendStats(sender);
            case "here" -> sendHere(sender);
            case "reload" -> {
                manager.reload();
                sender.sendMessage(Component.text("Configuration reloaded.", NamedTextColor.GREEN));
            }
            default -> sendHelp(sender, label);
        }
        return true;
    }

    private void sendStats(CommandSender sender) {
        PluginConfig config = manager.config();
        TrainMover mover = manager.trainManager().mover();

        sender.sendMessage(Component.text("MinecartWithFurnaceFix", NamedTextColor.GOLD));
        sender.sendMessage(line("trains", String.valueOf(manager.trainManager().trainCount())));
        sender.sendMessage(line("carts under control",
                String.valueOf(manager.trainManager().cartCount())));
        sender.sendMessage(line("assembled / dissolved",
                manager.trainManager().assemblies() + " / " + manager.trainManager().dissolves()));
        sender.sendMessage(line("teleports", String.valueOf(mover.teleports())));
        sender.sendMessage(line("releases", String.valueOf(mover.releases())));
        sender.sendMessage(line("path failures", String.valueOf(mover.pathFailures())));
        sender.sendMessage(line("spacing / max members",
                String.format("%.2f / %d", config.spacing, config.maxMembers)));
        sender.sendMessage(line("lookahead blocks", String.valueOf(config.lookaheadBlocks)));
        sender.sendMessage(Component.text(config.enabled
                ? "enabled" : "disabled", config.enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private void sendHere(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Run this in game.", NamedTextColor.RED));
            return;
        }
        Train nearest = null;
        double best = Double.MAX_VALUE;
        for (Train train : manager.trainManager().trains()) {
            if (!train.engine().getWorld().equals(player.getWorld())) {
                continue;
            }
            double distance = train.engine().getLocation().distanceSquared(player.getLocation());
            if (distance < best) {
                best = distance;
                nearest = train;
            }
        }
        if (nearest == null || best > 32.0 * 32.0) {
            sender.sendMessage(Component.text("No train within 32 blocks.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Train #" + nearest.id(), NamedTextColor.GOLD));
        sender.sendMessage(line("engine fuel", String.valueOf(nearest.engine().getFuel())));
        Vector heading = nearest.heading();
        sender.sendMessage(line("heading", heading == null ? "(unknown)"
                : String.format("(%.2f, %.2f)", heading.getX(), heading.getZ())));
        sender.sendMessage(line("speed", String.format("%.3f", nearest.speed())));
        sender.sendMessage(line("train length", String.format("%.2f / %.2f",
                -nearest.minOffset(), nearest.maxOffset())));
        sender.sendMessage(line("wagons", String.valueOf(nearest.wagons().size())));
        for (Train.Member member : nearest.wagons()) {
            sender.sendMessage(line("  slot", String.format("%+.2f", member.offset())));
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(Component.text("MinecartWithFurnaceFix", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/" + label + " stats", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/" + label + " here", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/" + label + " reload", NamedTextColor.GRAY));
    }

    private Component line(String key, String value) {
        return Component.text("  " + key + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    out.add(sub);
                }
            }
            return out;
        }
        return List.of();
    }
}
