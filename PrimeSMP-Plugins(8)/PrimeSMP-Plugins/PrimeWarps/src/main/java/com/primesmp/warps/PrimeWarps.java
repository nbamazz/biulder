package com.primesmp.warps;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class PrimeWarps extends JavaPlugin implements CommandExecutor, Listener {

    private File file;
    private FileConfiguration data;

    private final Map<UUID, Long> lastUse = new HashMap<>();
    private final Map<UUID, BukkitTask> warmupTasks = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "warps.yml");
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Could not create warps.yml", e);
            }
        }
        data = YamlConfiguration.loadConfiguration(file);

        getCommand("warp").setExecutor(this);
        getCommand("warps").setExecutor(this);
        getCommand("setwarp").setExecutor(this);
        getCommand("delwarp").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PrimeWarps enabled - " + getWarpNames().size() + " warps loaded.");
    }

    @Override
    public void onDisable() {
        save();
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not save warps.yml", e);
        }
    }

    private List<String> getWarpNames() {
        ConfigurationSection section = data.getConfigurationSection("warps");
        return section == null ? new ArrayList<>() : new ArrayList<>(section.getKeys(false));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        switch (label.toLowerCase()) {
            case "setwarp" -> handleSetWarp(player, args);
            case "delwarp" -> handleDelWarp(player, args);
            case "warps" -> handleListWarps(player);
            case "warp" -> handleWarp(player, args);
            default -> { return false; }
        }
        return true;
    }

    private void handleSetWarp(Player player, String[] args) {
        if (!player.hasPermission("primewarps.admin")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return;
        }
        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /setwarp <name>");
            return;
        }
        String name = args[0].toLowerCase();
        Location loc = player.getLocation();
        String base = "warps." + name;
        data.set(base + ".world", loc.getWorld().getName());
        data.set(base + ".x", loc.getX());
        data.set(base + ".y", loc.getY());
        data.set(base + ".z", loc.getZ());
        data.set(base + ".yaw", loc.getYaw());
        data.set(base + ".pitch", loc.getPitch());
        save();
        player.sendMessage(ChatColor.GREEN + "Warp '" + name + "' set.");
    }

    private void handleDelWarp(Player player, String[] args) {
        if (!player.hasPermission("primewarps.admin")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return;
        }
        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /delwarp <name>");
            return;
        }
        String name = args[0].toLowerCase();
        if (!data.contains("warps." + name)) {
            player.sendMessage(ChatColor.RED + "No warp named '" + name + "'.");
            return;
        }
        data.set("warps." + name, null);
        save();
        player.sendMessage(ChatColor.GREEN + "Warp '" + name + "' deleted.");
    }

    private void handleListWarps(Player player) {
        if (!player.hasPermission("primewarps.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return;
        }
        List<String> warps = getWarpNames();
        if (warps.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "No warps have been set yet.");
            return;
        }
        player.sendMessage(ChatColor.GOLD + "--- Warps (" + warps.size() + ") ---");
        player.sendMessage(ChatColor.WHITE + String.join(ChatColor.GRAY + ", " + ChatColor.WHITE, warps));
    }

    private void handleWarp(Player player, String[] args) {
        if (!player.hasPermission("primewarps.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return;
        }
        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /warp <name>. Use /warps to see the list.");
            return;
        }
        String name = args[0].toLowerCase();
        String base = "warps." + name;
        if (!data.contains(base)) {
            player.sendMessage(ChatColor.RED + "No warp named '" + name + "'. Use /warps to see the list.");
            return;
        }

        int cooldown = getConfig().getInt("cooldown-seconds", 5);
        Long last = lastUse.get(player.getUniqueId());
        if (last != null && !player.hasPermission("primewarps.bypasscooldown")) {
            long elapsed = (System.currentTimeMillis() - last) / 1000;
            if (elapsed < cooldown) {
                player.sendMessage(ChatColor.RED + "Please wait " + (cooldown - elapsed) + "s before warping again.");
                return;
            }
        }

        World world = Bukkit.getWorld(data.getString(base + ".world", ""));
        if (world == null) {
            player.sendMessage(ChatColor.RED + "That warp's world no longer exists.");
            return;
        }
        Location destination = new Location(world,
                data.getDouble(base + ".x"), data.getDouble(base + ".y"), data.getDouble(base + ".z"),
                (float) data.getDouble(base + ".yaw"), (float) data.getDouble(base + ".pitch"));

        lastUse.put(player.getUniqueId(), System.currentTimeMillis());

        int warmup = getConfig().getInt("warmup-seconds", 3);
        if (warmup <= 0) {
            player.teleport(destination);
            player.sendMessage(ChatColor.GREEN + "Warped to '" + name + "'.");
            return;
        }

        player.sendMessage(ChatColor.YELLOW + "Warping to '" + name + "' in " + warmup + " seconds - don't move!");
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            warmupTasks.remove(player.getUniqueId());
            if (player.isOnline()) {
                player.teleport(destination);
                player.sendMessage(ChatColor.GREEN + "Warped to '" + name + "'.");
            }
        }, warmup * 20L);
        warmupTasks.put(player.getUniqueId(), task);
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
