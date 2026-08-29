package com.primesmp.vanish;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Simple staff vanish: hides the vanished player from anyone without
 * primevanish.see, suppresses their quit message, and stops mobs from
 * targeting them. Vanish state is per-session (resets on rejoin).
 */
public class PrimeVanish extends JavaPlugin implements CommandExecutor, Listener {

    private final Set<UUID> vanished = new HashSet<>();

    @Override
    public void onEnable() {
        getCommand("vanish").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PrimeVanish enabled.");
    }

    public boolean isVanished(UUID uuid) {
        return vanished.contains(uuid);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (!player.hasPermission("primevanish.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return true;
        }

        if (vanished.contains(player.getUniqueId())) {
            setVanished(player, false);
        } else {
            setVanished(player, true);
        }
        return true;
    }

    private void setVanished(Player player, boolean vanish) {
        if (vanish) {
            vanished.add(player.getUniqueId());
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(player) && !other.hasPermission("primevanish.see")) {
                    other.hidePlayer(this, player);
                }
            }
            player.sendMessage(ChatColor.GRAY + "You are now " + ChatColor.ITALIC + "vanished" + ChatColor.RESET
                    + ChatColor.GRAY + ".");
        } else {
            vanished.remove(player.getUniqueId());
            for (Player other : Bukkit.getOnlinePlayers()) {
                other.showPlayer(this, player);
            }
            player.sendMessage(ChatColor.GRAY + "You are " + ChatColor.ITALIC + "visible" + ChatColor.RESET
                    + ChatColor.GRAY + " again.");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joiner = event.getPlayer();
        if (joiner.hasPermission("primevanish.see") || vanished.isEmpty()) {
            return;
        }
        for (UUID uuid : vanished) {
            Player vanishedPlayer = Bukkit.getPlayer(uuid);
            if (vanishedPlayer != null && !vanishedPlayer.equals(joiner)) {
                joiner.hidePlayer(this, vanishedPlayer);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (vanished.remove(player.getUniqueId())) {
            event.setQuitMessage(null);
        }
    }

    @EventHandler
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player target && vanished.contains(target.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
