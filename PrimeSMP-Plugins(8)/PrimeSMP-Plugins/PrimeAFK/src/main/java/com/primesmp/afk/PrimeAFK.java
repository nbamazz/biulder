package com.primesmp.afk;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks player activity and auto-marks anyone idle past a threshold as
 * AFK (broadcast-only status - doesn't touch display names, to avoid
 * clashing with other name-prefix plugins like PrimeRanks). Optionally
 * kicks players who stay AFK too long.
 */
public class PrimeAFK extends JavaPlugin implements CommandExecutor, Listener {

    private final Map<UUID, Long> lastActivity = new HashMap<>();
    private final Set<UUID> afk = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("afk").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, this::checkAll, 20L * 10, 20L * 10);
        getLogger().info("PrimeAFK enabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (afk.contains(player.getUniqueId())) {
            setAfk(player, false);
        } else {
            setAfk(player, true);
        }
        // Manual toggle still counts as "activity" for the timer, so going
        // AFK manually doesn't get you instantly re-flagged as active.
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        return true;
    }

    private void checkAll() {
        long now = System.currentTimeMillis();
        int thresholdMs = getConfig().getInt("afk-threshold-seconds", 300) * 1000;
        int kickMinutes = getConfig().getInt("afk-kick-minutes", 30);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("primeafk.exempt")) {
                continue;
            }
            UUID uuid = player.getUniqueId();
            long last = lastActivity.getOrDefault(uuid, now);
            long idleMs = now - last;

            if (!afk.contains(uuid) && idleMs >= thresholdMs) {
                setAfk(player, true);
            }

            if (afk.contains(uuid) && kickMinutes > 0 && idleMs >= kickMinutes * 60_000L) {
                player.kickPlayer(ChatColor.translateAlternateColorCodes('&',
                        getConfig().getString("kick-message", "&cKicked for being AFK too long.")));
            }
        }
    }

    private void setAfk(Player player, boolean value) {
        if (value) {
            afk.add(player.getUniqueId());
            if (getConfig().getBoolean("afk-broadcast", true)) {
                broadcast(getConfig().getString("afk-message", "&7%player% is now AFK."), player);
            }
        } else {
            afk.remove(player.getUniqueId());
            if (getConfig().getBoolean("afk-broadcast", true)) {
                broadcast(getConfig().getString("back-message", "&7%player% is no longer AFK."), player);
            }
        }
    }

    private void broadcast(String template, Player player) {
        String msg = template.replace("%player%", player.getName());
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    private void markActive(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        if (afk.remove(player.getUniqueId())) {
            if (getConfig().getBoolean("afk-broadcast", true)) {
                broadcast(getConfig().getString("back-message", "&7%player% is no longer AFK."), player);
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        // Ignore pure head-turns; only count actual positional movement as activity
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            markActive(event.getPlayer());
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        markActive(event.getPlayer());
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        markActive(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastActivity.remove(uuid);
        afk.remove(uuid);
    }
}
