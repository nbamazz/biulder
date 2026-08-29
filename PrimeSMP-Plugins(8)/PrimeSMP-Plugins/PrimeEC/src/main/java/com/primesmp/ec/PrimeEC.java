package com.primesmp.ec;

import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PrimeEC - opens a player's real ender chest inventory from anywhere.
 * Because it opens the actual EnderChest object (not a copy), items placed
 * in it sync perfectly with the vanilla ender chest block.
 */
public class PrimeEC extends JavaPlugin implements CommandExecutor {

    private final Map<UUID, Long> lastUsed = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("enderchest").setExecutor(this);
        getLogger().info("PrimeEC enabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        if (!player.hasPermission("primeec.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use /ec.");
            return true;
        }

        // Viewing someone else's ender chest
        if (args.length > 0) {
            if (!player.hasPermission("primeec.others")) {
                player.sendMessage(ChatColor.RED + "You don't have permission to view other players' ender chests.");
                return true;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                player.sendMessage(ChatColor.RED + "That player has never joined this server.");
                return true;
            }
            player.openInventory(target.getEnderChest());
            player.sendMessage(ChatColor.GRAY + "Viewing " + (target.getName() != null ? target.getName() : args[0])
                    + "'s ender chest.");
            return true;
        }

        // Cooldown check (own ender chest only)
        int cooldown = getConfig().getInt("cooldown-seconds", 0);
        if (cooldown > 0 && !player.hasPermission("primeec.bypasscooldown")) {
            long now = System.currentTimeMillis();
            Long last = lastUsed.get(player.getUniqueId());
            if (last != null) {
                long elapsed = (now - last) / 1000;
                if (elapsed < cooldown) {
                    long remaining = cooldown - elapsed;
                    String msg = getConfig().getString("cooldown-message", "&cWait %seconds%s.")
                            .replace("%seconds%", String.valueOf(remaining));
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                    return true;
                }
            }
            lastUsed.put(player.getUniqueId(), now);
        }

        player.openInventory(player.getEnderChest());
        return true;
    }
}
