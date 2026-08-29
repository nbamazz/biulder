package com.primesmp.kits;

import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class PrimeKits extends JavaPlugin implements CommandExecutor {

    private final Map<String, Kit> kits = new HashMap<>();
    private File dataFile;
    private FileConfiguration data;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadKits();

        dataFile = new File(getDataFolder(), "kit-data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Could not create kit-data.yml", e);
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);

        getCommand("kit").setExecutor(this);
        getLogger().info("PrimeKits enabled - " + kits.size() + " kits loaded.");
    }

    private void loadKits() {
        kits.clear();
        reloadConfig();
        ConfigurationSection section = getConfig().getConfigurationSection("kits");
        if (section == null) {
            getLogger().warning("No kits defined in config.yml!");
            return;
        }
        for (String id : section.getKeys(false)) {
            String path = "kits." + id + ".";
            String display = getConfig().getString(path + "display", id);
            boolean oneTime = getConfig().getBoolean(path + "one-time", false);
            int cooldownHours = getConfig().getInt(path + "cooldown-hours", 0);
            String permission = getConfig().getString(path + "permission", null);
            List<String> rawItems = getConfig().getStringList(path + "items");

            List<ItemStack> items = new ArrayList<>();
            for (String raw : rawItems) {
                String[] parts = raw.split(":");
                try {
                    Material material = Material.valueOf(parts[0].toUpperCase());
                    int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                    items.add(new ItemStack(material, amount));
                } catch (IllegalArgumentException e) {
                    getLogger().warning("Invalid item '" + raw + "' in kit '" + id + "', skipping.");
                }
            }
            kits.put(id.toLowerCase(), new Kit(id.toLowerCase(), display, oneTime, cooldownHours, permission, items));
        }
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not save kit-data.yml", e);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("primekits.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }
            loadKits();
            sender.sendMessage(ChatColor.GREEN + "PrimeKits reloaded - " + kits.size() + " kits loaded.");
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("primekits.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }
            return handleGive(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (!player.hasPermission("primekits.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return true;
        }

        if (args.length == 0) {
            listKits(player);
            return true;
        }

        Kit kit = kits.get(args[0].toLowerCase());
        if (kit == null) {
            player.sendMessage(ChatColor.RED + "No kit named '" + args[0] + "'. Use /kit to see available kits.");
            return true;
        }
        claimKit(player, kit, false);
        return true;
    }

    private boolean handleGive(CommandSender sender, String[] args) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        Kit kit = kits.get(args[2].toLowerCase());
        if (kit == null) {
            sender.sendMessage(ChatColor.RED + "No kit named '" + args[2] + "'.");
            return true;
        }
        if (!target.isOnline() || target.getPlayer() == null) {
            sender.sendMessage(ChatColor.RED + "That player isn't online.");
            return true;
        }
        claimKit(target.getPlayer(), kit, true);
        sender.sendMessage(ChatColor.GREEN + "Gave " + kit.display + ChatColor.GREEN + " to " + target.getName() + ".");
        return true;
    }

    private void listKits(Player player) {
        player.sendMessage(ChatColor.GOLD + "--- Available Kits ---");
        for (Kit kit : kits.values()) {
            if (kit.permission != null && !player.hasPermission(kit.permission)) {
                continue;
            }
            String status = statusFor(player, kit);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', kit.display) + ChatColor.GRAY + " - " + status);
        }
        player.sendMessage(ChatColor.GRAY + "Use /kit <name> to claim one.");
    }

    private String statusFor(Player player, Kit kit) {
        long last = data.getLong("players." + player.getUniqueId() + "." + kit.id, -1);
        if (last < 0) {
            return ChatColor.GREEN + "ready";
        }
        if (kit.oneTime) {
            return ChatColor.RED + "already claimed";
        }
        long elapsedHours = (System.currentTimeMillis() - last) / 3_600_000L;
        if (elapsedHours >= kit.cooldownHours) {
            return ChatColor.GREEN + "ready";
        }
        return ChatColor.YELLOW + "available in " + (kit.cooldownHours - elapsedHours) + "h";
    }

    private void claimKit(Player player, Kit kit, boolean bypass) {
        if (!bypass) {
            if (kit.permission != null && !player.hasPermission(kit.permission)) {
                player.sendMessage(ChatColor.RED + "You don't have permission to claim that kit.");
                return;
            }
            long last = data.getLong("players." + player.getUniqueId() + "." + kit.id, -1);
            if (last >= 0) {
                if (kit.oneTime) {
                    player.sendMessage(ChatColor.RED + "You've already claimed " + kit.display + ChatColor.RED + ".");
                    return;
                }
                long elapsedHours = (System.currentTimeMillis() - last) / 3_600_000L;
                if (elapsedHours < kit.cooldownHours) {
                    player.sendMessage(ChatColor.RED + "You can claim " + kit.display + ChatColor.RED
                            + " again in " + (kit.cooldownHours - elapsedHours) + " hour(s).");
                    return;
                }
            }
        }

        for (ItemStack item : kit.items) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item.clone());
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItem(player.getLocation(), leftover);
            }
        }

        data.set("players." + player.getUniqueId() + "." + kit.id, System.currentTimeMillis());
        saveData();

        player.sendMessage(ChatColor.GREEN + "You claimed " + ChatColor.translateAlternateColorCodes('&', kit.display)
                + ChatColor.GREEN + "!");
    }

    private static class Kit {
        final String id;
        final String display;
        final boolean oneTime;
        final int cooldownHours;
        final String permission;
        final List<ItemStack> items;

        Kit(String id, String display, boolean oneTime, int cooldownHours, String permission, List<ItemStack> items) {
            this.id = id;
            this.display = display;
            this.oneTime = oneTime;
            this.cooldownHours = cooldownHours;
            this.permission = permission;
            this.items = items;
        }
    }
}
