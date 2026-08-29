package com.primesmp.spawn;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PrimeSpawn extends JavaPlugin implements CommandExecutor, Listener {

    private final Map<UUID, Long> lastUse = new HashMap<>();
    private final Map<UUID, BukkitTask> warmupTasks = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("spawn").setExecutor(this);
        getCommand("setspawn").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PrimeSpawn enabled" + (getSpawnLocation() == null ? " - no spawn set yet, use /setspawn" : "."));
    }

    private Location getSpawnLocation() {
        if (!getConfig().contains("spawn-location.world")) {
            return null;
        }
        World world = Bukkit.getWorld(getConfig().getString("spawn-location.world", ""));
        if (world == null) {
            return null;
        }
        return new Location(world,
                getConfig().getDouble("spawn-location.x"),
                getConfig().getDouble("spawn-location.y"),
                getConfig().getDouble("spawn-location.z"),
                (float) getConfig().getDouble("spawn-location.yaw"),
                (float) getConfig().getDouble("spawn-location.pitch"));
    }

    private void setSpawnLocation(Location loc) {
        getConfig().set("spawn-location.world", loc.getWorld().getName());
        getConfig().set("spawn-location.x", loc.getX());
        getConfig().set("spawn-location.y", loc.getY());
        getConfig().set("spawn-location.z", loc.getZ());
        getConfig().set("spawn-location.yaw", loc.getYaw());
        getConfig().set("spawn-location.pitch", loc.getPitch());
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        if (label.equalsIgnoreCase("setspawn")) {
            if (!player.hasPermission("primespawn.admin")) {
                player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }
            setSpawnLocation(player.getLocation());
            player.sendMessage(ChatColor.GREEN + "Spawn point set to your current location.");
            return true;
        }

        // /spawn
        if (!player.hasPermission("primespawn.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return true;
        }
        Location spawn = getSpawnLocation();
        if (spawn == null) {
            player.sendMessage(ChatColor.RED + "Spawn hasn't been set yet. Ask an admin to run /setspawn.");
            return true;
        }

        int cooldown = getConfig().getInt("cooldown-seconds", 5);
        Long last = lastUse.get(player.getUniqueId());
        if (last != null && !player.hasPermission("primespawn.bypasscooldown")) {
            long elapsed = (System.currentTimeMillis() - last) / 1000;
            if (elapsed < cooldown) {
                player.sendMessage(ChatColor.RED + "Please wait " + (cooldown - elapsed) + "s before using /spawn again.");
                return true;
            }
        }
        lastUse.put(player.getUniqueId(), System.currentTimeMillis());

        int warmup = getConfig().getInt("warmup-seconds", 3);
        if (warmup <= 0) {
            player.teleport(spawn);
            player.sendMessage(ChatColor.GREEN + "Teleported to spawn.");
            return true;
        }

        player.sendMessage(ChatColor.YELLOW + "Teleporting to spawn in " + warmup + " seconds - don't move!");
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            warmupTasks.remove(player.getUniqueId());
            if (player.isOnline()) {
                player.teleport(spawn);
                player.sendMessage(ChatColor.GREEN + "Teleported to spawn.");
            }
        }, warmup * 20L);
        warmupTasks.put(player.getUniqueId(), task);
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("teleport-new-players-to-spawn", true)) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPlayedBefore()) {
            Location spawn = getSpawnLocation();
            if (spawn != null) {
                player.teleport(spawn);
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!getConfig().getBoolean("teleport-to-spawn-on-death", false)) {
            return;
        }
        Location spawn = getSpawnLocation();
        if (spawn != null) {
            event.setRespawnLocation(spawn);
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
