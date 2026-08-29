package com.primesmp.guard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
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
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

final class StaffTools implements Listener, CommandExecutor, TabCompleter {
    private static final String PREFIX = "§8[§bStaff§8] §f";
    private final PrimeGuard plugin;
    private final Random random = new Random();
    private final Set<UUID> frozen = new HashSet<>();

    StaffTools(PrimeGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be run by a player.");
            return true;
        }
        if (!player.hasPermission("primeguard.staff")) {
            player.sendMessage("§cYou do not have permission to use staff tools.");
            return true;
        }
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "staff" -> { openMainMenu(player); yield true; }
            case "freeze" -> { toggleFreezeFromCommand(player, args); yield true; }
            case "stafftp" -> { teleportToPlayer(player, args); yield true; }
            case "rtpstaff" -> { randomTeleport(player); yield true; }
            case "stashspawn" -> { spawnStash(player); yield true; }
            case "orespawn" -> { spawnOre(player, args.length == 0 ? Material.DIAMOND_ORE : oreFrom(args[0])); yield true; }
            default -> false;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && (command.getName().equalsIgnoreCase("freeze") || command.getName().equalsIgnoreCase("stafftp"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 1 && command.getName().equalsIgnoreCase("orespawn")) return List.of("diamond", "emerald", "gold", "iron", "coal");
        return List.of();
    }

    @EventHandler(ignoreCancelled = true)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !(event.getInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) return;
        if (holder.type == MenuType.MAIN) {
            switch (event.getRawSlot()) {
                case 10 -> openPlayerMenu(player, MenuType.TELEPORT);
                case 11 -> randomTeleport(player);
                case 12 -> spawnStash(player);
                case 13 -> spawnOre(player, Material.DIAMOND_ORE);
                case 14 -> openPlayerMenu(player, MenuType.FREEZE);
                case 15 -> openPlayerMenu(player, MenuType.INVSEE);
                case 22 -> player.closeInventory();
                default -> { }
            }
            return;
        }
        if (event.getRawSlot() >= event.getInventory().getSize()) return;
        String targetName = ChatColor.stripColor(event.getCurrentItem().getItemMeta().getDisplayName());
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            player.sendMessage("§cThat player is no longer online.");
            openPlayerMenu(player, holder.type);
            return;
        }
        if (holder.type == MenuType.TELEPORT) {
            player.teleport(target.getLocation());
            player.sendMessage(PREFIX + "Teleported to §b" + target.getName() + "§f.");
        } else if (holder.type == MenuType.FREEZE) {
            toggleFreeze(player, target);
        } else if (holder.type == MenuType.INVSEE) {
            player.openInventory(target.getInventory());
            player.sendMessage(PREFIX + "Inspecting §b" + target.getName() + "§f's inventory.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrozenMove(PlayerMoveEvent event) {
        if (!frozen.contains(event.getPlayer().getUniqueId()) || event.getTo() == null) return;
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        frozen.remove(event.getPlayer().getUniqueId());
    }

    private void openMainMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(new MenuHolder(MenuType.MAIN), 27, "§8PrimeGuard Staff Panel");
        inventory.setItem(10, item(Material.COMPASS, "§bStaff Teleport", "§7Teleport to an online player"));
        inventory.setItem(11, item(Material.ENDER_PEARL, "§aRandom Teleport", "§7Teleport to a safe random location"));
        inventory.setItem(12, item(Material.CHEST, "§6Spawn Staff Stash", "§7Create a configured loot chest"));
        inventory.setItem(13, item(Material.DIAMOND_ORE, "§9Spawn Ore Vein", "§7Generate diamond ore where you look"));
        inventory.setItem(14, item(Material.PACKED_ICE, "§bFreeze Player", "§7Stop a player from moving"));
        inventory.setItem(15, item(Material.BOOK, "§eInventory Inspect", "§7View a player's inventory"));
        inventory.setItem(22, item(Material.BARRIER, "§cClose", "§7Close this panel"));
        player.openInventory(inventory);
    }

    private void openPlayerMenu(Player player, MenuType type) {
        Inventory inventory = Bukkit.createInventory(new MenuHolder(type), 54, "§8Select a Player");
        int slot = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (slot >= inventory.getSize()) break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(online);
            meta.setDisplayName("§b" + online.getName());
            meta.setLore(List.of("§7Click to select this player"));
            head.setItemMeta(meta);
            inventory.setItem(slot++, head);
        }
        player.openInventory(inventory);
    }

    private void randomTeleport(Player player) {
        World world = player.getWorld();
        int radius = plugin.getConfig().getInt("staff-tools.random-teleport-radius", 3000);
        int x = player.getLocation().getBlockX() + random.nextInt(radius * 2 + 1) - radius;
        int z = player.getLocation().getBlockZ() + random.nextInt(radius * 2 + 1) - radius;
        int y = world.getHighestBlockYAt(x, z) + 1;
        Location destination = new Location(world, x + 0.5, y, z + 0.5, player.getLocation().getYaw(), player.getLocation().getPitch());
        player.teleport(destination);
        player.sendMessage(PREFIX + "Randomly teleported to §b" + x + "§f, §b" + y + "§f, §b" + z + "§f.");
    }

    private void spawnStash(Player player) {
        Block target = player.getTargetBlockExact(6);
        Block placement = (target == null ? player.getLocation().getBlock() : target).getRelative(0, 1, 0);
        if (!placement.getType().isAir()) {
            player.sendMessage("§cLook at a solid block with empty space above it.");
            return;
        }
        placement.setType(Material.CHEST);
        Chest chest = (Chest) placement.getState();
        chest.getBlockInventory().addItem(new ItemStack(Material.DIAMOND, plugin.getConfig().getInt("staff-tools.stash.diamonds", 8)));
        chest.getBlockInventory().addItem(new ItemStack(Material.GOLD_INGOT, plugin.getConfig().getInt("staff-tools.stash.gold-ingots", 16)));
        chest.getBlockInventory().addItem(new ItemStack(Material.ENDER_PEARL, plugin.getConfig().getInt("staff-tools.stash.ender-pearls", 4)));
        player.sendMessage(PREFIX + "Staff stash spawned.");
    }

    private void spawnOre(Player player, Material ore) {
        if (ore == null) {
            player.sendMessage("§cChoose diamond, emerald, gold, iron, or coal.");
            return;
        }
        Block center = player.getTargetBlockExact(8);
        if (center == null) {
            player.sendMessage("§cLook at a block within 8 blocks.");
            return;
        }
        int size = plugin.getConfig().getInt("staff-tools.ore-vein-size", 8);
        int placed = 0;
        for (int i = 0; i < size * 3 && placed < size; i++) {
            Block block = center.getRelative(random.nextInt(5) - 2, random.nextInt(3) - 1, random.nextInt(5) - 2);
            if (block.getType() == Material.STONE || block.getType() == Material.DEEPSLATE) {
                block.setType(ore);
                placed++;
            }
        }
        player.sendMessage(PREFIX + "Spawned §b" + placed + " §f" + ore.name().toLowerCase(Locale.ROOT) + " blocks.");
    }

    private void teleportToPlayer(Player player, String[] args) {
        Player target = args.length == 0 ? null : Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage("§cUsage: /stafftp <player>");
            return;
        }
        player.teleport(target.getLocation());
        player.sendMessage(PREFIX + "Teleported to §b" + target.getName() + "§f.");
    }

    private void toggleFreezeFromCommand(Player player, String[] args) {
        Player target = args.length == 0 ? null : Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage("§cUsage: /freeze <player>");
            return;
        }
        toggleFreeze(player, target);
    }

    private void toggleFreeze(Player staff, Player target) {
        if (frozen.remove(target.getUniqueId())) {
            target.sendMessage("§aYou have been unfrozen.");
            staff.sendMessage(PREFIX + "Unfroze §b" + target.getName() + "§f.");
        } else {
            frozen.add(target.getUniqueId());
            target.sendMessage("§cYou have been frozen by staff. Do not log out.");
            staff.sendMessage(PREFIX + "Froze §b" + target.getName() + "§f.");
        }
    }

    private Material oreFrom(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "diamond" -> Material.DIAMOND_ORE;
            case "emerald" -> Material.EMERALD_ORE;
            case "gold" -> Material.GOLD_ORE;
            case "iron" -> Material.IRON_ORE;
            case "coal" -> Material.COAL_ORE;
            default -> null;
        };
    }

    private ItemStack item(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(lore));
        item.setItemMeta(meta);
        return item;
    }

    private enum MenuType { MAIN, TELEPORT, FREEZE, INVSEE }

    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private MenuHolder(MenuType type) { this.type = type; }
        @Override public Inventory getInventory() { return null; }
    }
}
