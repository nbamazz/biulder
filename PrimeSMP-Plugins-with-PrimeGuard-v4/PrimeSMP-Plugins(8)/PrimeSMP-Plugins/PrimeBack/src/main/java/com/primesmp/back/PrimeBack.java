package com.primesmp.back;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks each player's location before a death or a teleport (warp, home,
 * tpa, rtp, spawn - anything that fires PlayerTeleportEvent) so /back can
 * send them there.
 */
public class PrimeBack extends JavaPlugin implements CommandExecutor, Listener {

    private final Map<UUID, Location> lastLocation = new HashMap<>();
    private final Set<UUID> ignoreNextTeleport = new HashSet<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();
    private final Map<UUID, BukkitTask> warmupTasks = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("back").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PrimeBack enabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (!player.hasPermission("primeback.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return true;
        }

        Location destination = lastLocation.get(player.getUniqueId());
        if (destination == null) {
            player.sendMessage(ChatColor.RED + "You don't have anywhere to go back to yet.");
            return true;
        }

        int cooldown = getConfig().getInt("cooldown-seconds", 5);
        Long last = lastUse.get(player.getUniqueId());
        if (last != null && !player.hasPermission("primeback.bypasscooldown")) {
            long elapsed = (System.currentTimeMillis() - last) / 1000;
            if (elapsed < cooldown) {
                player.sendMessage(ChatColor.RED + "Please wait " + (cooldown - elapsed) + "s before using /back again.");
                return true;
            }
        }
        lastUse.put(player.getUniqueId(), System.currentTimeMillis());

        int warmup = getConfig().getInt("warmup-seconds", 3);
        if (warmup <= 0) {
            teleportBack(player, destination);
            return true;
        }

        player.sendMessage(ChatColor.YELLOW + "Teleporting back in " + warmup + " seconds - don't move!");
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            warmupTasks.remove(player.getUniqueId());
            if (player.isOnline()) {
                teleportBack(player, destination);
            }
        }, warmup * 20L);
        warmupTasks.put(player.getUniqueId(), task);
        return true;
    }

    private void teleportBack(Player player, Location destination) {
        ignoreNextTeleport.add(player.getUniqueId());
        player.teleport(destination);
        player.sendMessage(ChatColor.GREEN + "Teleported back.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!getConfig().getBoolean("save-on-death", true)) {
            return;
        }
        Player player = event.getEntity();
        if (player.getLocation().getWorld() != null) {
            lastLocation.put(player.getUniqueId(), player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        if (ignoreNextTeleport.remove(uuid)) {
            return; // this teleport was /back itself - don't overwrite with it
        }
        if (!getConfig().getBoolean("save-on-teleport", true)) {
            return;
        }
        Location from = event.getFrom();
        if (from != null && from.getWorld() != null) {
            lastLocation.put(uuid, from);
        }
    }

    private void cancelWarmup(Player player, String reason) {
        BukkitTask task = warmupTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            player.sendMessage(ChatColor.RED + "Teleport cancelled - " + reason + ".");
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!warmupTasks.containsKey(event.getPlayer().getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            cancelWarmup(event.getPlayer(), "you moved");
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && warmupTasks.containsKey(player.getUniqueId())) {
            cancelWarmup(player, "you took damage");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        BukkitTask task = warmupTasks.remove(event.getPlayer().getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }
}
