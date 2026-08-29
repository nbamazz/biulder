package com.primesmp.rtp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

public class PrimeRTP extends JavaPlugin implements CommandExecutor, Listener {

    private final Map<UUID, Long> lastUse = new HashMap<>();
    private final Map<UUID, BukkitTask> warmupTasks = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("rtp").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PrimeRTP enabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (!player.hasPermission("primertp.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return true;
        }

        int cooldown = getConfig().getInt("cooldown-seconds", 30);
        Long last = lastUse.get(player.getUniqueId());
        if (last != null && !player.hasPermission("primertp.bypasscooldown")) {
            long elapsed = (System.currentTimeMillis() - last) / 1000;
            if (elapsed < cooldown) {
                player.sendMessage(ChatColor.RED + "Please wait " + (cooldown - elapsed) + "s before using /rtp again.");
                return true;
            }
        }

        player.sendMessage(ChatColor.YELLOW + "Searching for a safe location...");
        Location destination = findSafeLocation(player.getWorld());
        if (destination == null) {
            player.sendMessage(ChatColor.RED + "Couldn't find a safe spot - try again in a moment.");
            return true;
        }

        lastUse.put(player.getUniqueId(), System.currentTimeMillis());

        int warmup = getConfig().getInt("warmup-seconds", 3);
        if (warmup <= 0) {
            player.teleport(destination);
            announceLanding(player, destination);
            return true;
        }

        player.sendMessage(ChatColor.YELLOW + "Teleporting in " + warmup + " seconds - don't move!");
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            warmupTasks.remove(player.getUniqueId());
            if (player.isOnline()) {
                player.teleport(destination);
                announceLanding(player, destination);
            }
        }, warmup * 20L);
        warmupTasks.put(player.getUniqueId(), task);
        return true;
    }

    private void announceLanding(Player player, Location loc) {
        player.sendMessage(ChatColor.GREEN + "Teleported to " + loc.getBlockX() + ", " + loc.getBlockY()
                + ", " + loc.getBlockZ() + ".");
    }

    private Location findSafeLocation(World world) {
        int minRadius = getConfig().getInt("min-radius", 500);
        int maxRadius = getConfig().getInt("max-radius", 5000);
        int attempts = getConfig().getInt("max-attempts", 20);

        Set<Material> unsafe = EnumSet.noneOf(Material.class);
        for (String name : getConfig().getStringList("unsafe-ground")) {
            try {
                unsafe.add(Material.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // skip invalid material names in config
            }
        }

        Location center = world.getSpawnLocation();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < attempts; i++) {
            double angle = random.nextDouble(0, Math.PI * 2);
            double distance = random.nextDouble(minRadius, maxRadius);
            int x = center.getBlockX() + (int) (Math.cos(angle) * distance);
            int z = center.getBlockZ() + (int) (Math.sin(angle) * distance);

            int y = world.getHighestBlockYAt(x, z);
            Block ground = world.getBlockAt(x, y, z);

            if (unsafe.contains(ground.getType()) || !ground.getType().isSolid()) {
                continue;
            }

            return new Location(world, x + 0.5, y + 1, z + 0.5);
        }
        return null;
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
