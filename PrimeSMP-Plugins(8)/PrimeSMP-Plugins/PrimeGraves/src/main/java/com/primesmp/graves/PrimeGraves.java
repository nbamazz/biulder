package com.primesmp.graves;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
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

/**
 * On death, moves a player's drops into a chest at the death location
 * instead of scattering them, with a temporary ownership window before
 * anyone else can loot it, and automatic expiry that drops remaining
 * items on the ground rather than deleting them.
 *
 * Item contents live in the actual chest block (the world save handles
 * that automatically) - this plugin only tracks the metadata needed for
 * ownership checks and expiry timing, in graves.yml.
 */
public class PrimeGraves extends JavaPlugin implements CommandExecutor, Listener {

    private final Map<String, GraveRecord> graves = new HashMap<>();
    private File file;
    private FileConfiguration data;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "graves.yml");
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Could not create graves.yml", e);
            }
        }
        data = YamlConfiguration.loadConfiguration(file);

        getCommand("graves").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        loadGraves();
        getLogger().info("PrimeGraves enabled - " + graves.size() + " active grave(s) restored.");
    }

    @Override
    public void onDisable() {
        saveGraves();
    }

    private String keyFor(Location loc) {
        return loc.getWorld().getName() + "_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
    }

    private void loadGraves() {
        ConfigurationSection section = data.getConfigurationSection("graves");
        if (section == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long expireMs = getConfig().getInt("expire-minutes", 60) * 60_000L;

        for (String key : section.getKeys(false)) {
            String path = "graves." + key + ".";
            String worldName = data.getString(path + "world", "");
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                continue; // world not loaded (yet) - leave the entry for a future restart
            }
            int x = data.getInt(path + "x");
            int y = data.getInt(path + "y");
            int z = data.getInt(path + "z");
            UUID owner = UUID.fromString(data.getString(path + "owner", ""));
            long placedAt = data.getLong(path + "placedAt");

            GraveRecord record = new GraveRecord(owner, new Location(world, x, y, z), placedAt);
            graves.put(key, record);

            long remaining = (placedAt + expireMs) - now;
            if (remaining <= 0) {
                expireGrave(key);
            } else {
                scheduleExpiry(key, remaining);
            }
        }
    }

    private void saveGraves() {
        try {
            data.save(file);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not save graves.yml", e);
        }
    }

    private void persistGrave(String key, GraveRecord record) {
        String path = "graves." + key + ".";
        data.set(path + "world", record.location.getWorld().getName());
        data.set(path + "x", record.location.getBlockX());
        data.set(path + "y", record.location.getBlockY());
        data.set(path + "z", record.location.getBlockZ());
        data.set(path + "owner", record.owner.toString());
        data.set(path + "placedAt", record.placedAt);
        saveGraves();
    }

    private void removeGraveRecord(String key) {
        GraveRecord record = graves.remove(key);
        if (record != null && record.expiryTask != null) {
            record.expiryTask.cancel();
        }
        data.set("graves." + key, null);
        saveGraves();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        if (drops.isEmpty()) {
            return;
        }

        Player player = event.getEntity();
        Location loc = player.getLocation();
        Block block = loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        block.setType(Material.CHEST);
        if (!(block.getState() instanceof Chest chestState)) {
            block.setType(Material.AIR);
            return; // couldn't place a chest here (shouldn't normally happen); let items drop normally
        }
        chestState.getInventory().setContents(drops.toArray(new ItemStack[0]));
        chestState.update(true, false);

        event.getDrops().clear();

        String key = keyFor(block.getLocation());
        GraveRecord record = new GraveRecord(player.getUniqueId(), block.getLocation(), System.currentTimeMillis());
        graves.put(key, record);
        persistGrave(key, record);
        scheduleExpiry(key, getConfig().getInt("expire-minutes", 60) * 60_000L);

        String msg = getConfig().getString("notify-message", "&eYour items were placed in a grave.");
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    private void scheduleExpiry(String key, long delayMs) {
        GraveRecord record = graves.get(key);
        if (record == null) {
            return;
        }
        record.expiryTask = Bukkit.getScheduler().runTaskLater(this, () -> expireGrave(key), delayMs / 50L);
    }

    private void expireGrave(String key) {
        GraveRecord record = graves.get(key);
        if (record == null) {
            return;
        }
        Block block = record.location.getBlock();
        if (block.getState() instanceof Chest chest) {
            for (ItemStack item : chest.getInventory().getContents()) {
                if (item != null) {
                    block.getWorld().dropItemNaturally(record.location.clone().add(0.5, 0.5, 0.5), item);
                }
            }
        }
        if (block.getType() == Material.CHEST) {
            block.setType(Material.AIR);
        }
        removeGraveRecord(key);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block.getType() != Material.CHEST) {
            return;
        }
        GraveRecord record = graves.get(keyFor(block.getLocation()));
        if (record == null) {
            return; // just a normal chest
        }

        Player player = event.getPlayer();
        if (player.getUniqueId().equals(record.owner) || player.hasPermission("primegraves.admin")) {
            return; // allowed
        }

        long protectMs = getConfig().getInt("protect-minutes", 5) * 60_000L;
        long elapsed = System.currentTimeMillis() - record.placedAt;
        if (elapsed < protectMs) {
            event.setCancelled(true);
            long remainingMin = (protectMs - elapsed) / 60_000L + 1;
            OfflineName owner = new OfflineName(record.owner);
            player.sendMessage(ChatColor.RED + "This grave belongs to " + owner.get()
                    + ChatColor.RED + " - try again in " + remainingMin + "m.");
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!getConfig().getBoolean("remove-when-emptied", true)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof Chest chest)) {
            return;
        }
        String key = keyFor(chest.getLocation());
        GraveRecord record = graves.get(key);
        if (record == null) {
            return;
        }
        boolean empty = true;
        for (ItemStack item : event.getInventory().getContents()) {
            if (item != null) {
                empty = false;
                break;
            }
        }
        if (empty) {
            chest.getBlock().setType(Material.AIR);
            removeGraveRecord(key);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        List<GraveRecord> mine = new ArrayList<>();
        for (GraveRecord record : graves.values()) {
            if (record.owner.equals(player.getUniqueId())) {
                mine.add(record);
            }
        }
        if (mine.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "You have no active graves.");
            return true;
        }
        player.sendMessage(ChatColor.GOLD + "--- Your Graves (" + mine.size() + ") ---");
        for (GraveRecord record : mine) {
            Location l = record.location;
            player.sendMessage(ChatColor.WHITE + l.getWorld().getName() + " " + l.getBlockX()
                    + ", " + l.getBlockY() + ", " + l.getBlockZ());
        }
        return true;
    }

    private static class GraveRecord {
        final UUID owner;
        final Location location;
        final long placedAt;
        BukkitTask expiryTask;

        GraveRecord(UUID owner, Location location, long placedAt) {
            this.owner = owner;
            this.location = location;
            this.placedAt = placedAt;
        }
    }

    /** Small helper so a missing/offline name never throws. */
    private static class OfflineName {
        private final UUID uuid;

        OfflineName(UUID uuid) {
            this.uuid = uuid;
        }

        String get() {
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            return name != null ? name : "someone";
        }
    }
}
