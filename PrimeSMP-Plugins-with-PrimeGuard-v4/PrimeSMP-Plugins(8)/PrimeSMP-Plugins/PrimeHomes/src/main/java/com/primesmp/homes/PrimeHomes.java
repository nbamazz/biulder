package com.primesmp.homes;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PrimeHomes extends JavaPlugin implements CommandExecutor, Listener {

    private static final Pattern LIMIT_PATTERN = Pattern.compile("primehomes\\.limit\\.(\\d+)");
    private static final String DEFAULT_HOME = "home";

    private File file;
    private FileConfiguration data;

    private final Map<UUID, Long> lastHomeUse = new HashMap<>();
    private final Map<UUID, BukkitTask> warmupTasks = new HashMap<>();
    private final Map<UUID, Location> warmupStartLoc = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "homes.yml");
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Could not create homes.yml", e);
            }
        }
        data = YamlConfiguration.loadConfiguration(file);

        for (String cmd : new String[]{"sethome", "home", "delhome", "homes"}) {
            getCommand(cmd).setExecutor(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PrimeHomes enabled.");
    }

    @Override
    public void onDisable() {
        save();
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not save homes.yml", e);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (!player.hasPermission("primehomes.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return true;
        }

        switch (label.toLowerCase()) {
            case "sethome" -> handleSetHome(player, args);
            case "home" -> handleHome(player, args);
            case "delhome" -> handleDelHome(player, args);
            case "homes" -> handleListHomes(player);
            default -> { return false; }
        }
        return true;
    }

    private String path(UUID uuid, String home) {
        return "players." + uuid + ".homes." + home.toLowerCase();
    }

    private List<String> getHomeNames(UUID uuid) {
        ConfigurationSection section = data.getConfigurationSection("players." + uuid + ".homes");
        if (section == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(section.getKeys(false));
    }

    private int getHomeLimit(Player player) {
        if (player.hasPermission("primehomes.limit.unlimited")) {
            return Integer.MAX_VALUE;
        }
        int best = -1;
        for (org.bukkit.permissions.PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            Matcher matcher = LIMIT_PATTERN.matcher(info.getPermission());
            if (matcher.matches()) {
                best = Math.max(best, Integer.parseInt(matcher.group(1)));
            }
        }
        return best >= 0 ? best : getConfig().getInt("default-home-limit", 1);
    }

    private void handleSetHome(Player player, String[] args) {
        String name = args.length > 0 ? args[0] : DEFAULT_HOME;
        List<String> existing = getHomeNames(player.getUniqueId());

        boolean overwriting = existing.stream().anyMatch(h -> h.equalsIgnoreCase(name));
        if (!overwriting) {
            int limit = getHomeLimit(player);
            if (existing.size() >= limit) {
                player.sendMessage(ChatColor.RED + "You've reached your home limit (" + limit + "). "
                        + "Delete one with /delhome <name> first.");
                return;
            }
        }

        Location loc = player.getLocation();
        String base = path(player.getUniqueId(), name);
        data.set(base + ".world", loc.getWorld().getName());
        data.set(base + ".x", loc.getX());
        data.set(base + ".y", loc.getY());
        data.set(base + ".z", loc.getZ());
        data.set(base + ".yaw", loc.getYaw());
        data.set(base + ".pitch", loc.getPitch());
        save();

        player.sendMessage(ChatColor.GREEN + "Home '" + name + "' " + (overwriting ? "updated." : "set."));
    }

    private void handleHome(Player player, String[] args) {
        String name = args.length > 0 ? args[0] : DEFAULT_HOME;
        String base = path(player.getUniqueId(), name);
        if (!data.contains(base)) {
            player.sendMessage(ChatColor.RED + "You don't have a home named '" + name + "'. Use /homes to list them.");
            return;
        }

        int cooldown = getConfig().getInt("cooldown-seconds", 5);
        Long last = lastHomeUse.get(player.getUniqueId());
        if (last != null && !player.hasPermission("primehomes.bypasscooldown")) {
            long elapsed = (System.currentTimeMillis() - last) / 1000;
            if (elapsed < cooldown) {
                player.sendMessage(ChatColor.RED + "Please wait " + (cooldown - elapsed) + "s before teleporting again.");
                return;
            }
        }

        World world = Bukkit.getWorld(data.getString(base + ".world", ""));
        if (world == null) {
            player.sendMessage(ChatColor.RED + "That home's world no longer exists.");
            return;
        }
        Location destination = new Location(world,
                data.getDouble(base + ".x"), data.getDouble(base + ".y"), data.getDouble(base + ".z"),
                (float) data.getDouble(base + ".yaw"), (float) data.getDouble(base + ".pitch"));

        lastHomeUse.put(player.getUniqueId(), System.currentTimeMillis());

        int warmup = getConfig().getInt("warmup-seconds", 3);
        if (warmup <= 0) {
            player.teleport(destination);
            player.sendMessage(ChatColor.GREEN + "Teleported to '" + name + "'.");
            return;
        }

        player.sendMessage(ChatColor.YELLOW + "Teleporting to '" + name + "' in " + warmup + " seconds - don't move!");
        warmupStartLoc.put(player.getUniqueId(), player.getLocation());
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            warmupTasks.remove(player.getUniqueId());
            warmupStartLoc.remove(player.getUniqueId());
            if (player.isOnline()) {
                player.teleport(destination);
                player.sendMessage(ChatColor.GREEN + "Teleported to '" + name + "'.");
            }
        }, warmup * 20L);
        warmupTasks.put(player.getUniqueId(), task);
    }

    private void handleDelHome(Player player, String[] args) {
        String name = args.length > 0 ? args[0] : DEFAULT_HOME;
        String base = path(player.getUniqueId(), name);
        if (!data.contains(base)) {
            player.sendMessage(ChatColor.RED + "You don't have a home named '" + name + "'.");
            return;
        }
        data.set(base, null);
        save();
        player.sendMessage(ChatColor.GREEN + "Home '" + name + "' deleted.");
    }

    private void handleListHomes(Player player) {
        List<String> homes = getHomeNames(player.getUniqueId());
        if (homes.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "You have no homes set. Use /sethome [name] to set one.");
            return;
        }
        player.sendMessage(ChatColor.GOLD + "Your homes (" + homes.size() + "/" + limitDisplay(player) + "): "
                + ChatColor.WHITE + String.join(ChatColor.GRAY + ", " + ChatColor.WHITE, homes));
    }

    private String limitDisplay(Player player) {
        int limit = getHomeLimit(player);
        return limit == Integer.MAX_VALUE ? "\u221E" : String.valueOf(limit);
    }

    private void cancelWarmup(Player player, String reason) {
        BukkitTask task = warmupTasks.remove(player.getUniqueId());
        warmupStartLoc.remove(player.getUniqueId());
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
        UUID uuid = event.getPlayer().getUniqueId();
        BukkitTask task = warmupTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        warmupStartLoc.remove(uuid);
    }
}
