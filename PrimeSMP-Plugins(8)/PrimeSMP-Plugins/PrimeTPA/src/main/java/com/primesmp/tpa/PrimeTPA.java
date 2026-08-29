package com.primesmp.tpa;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class PrimeTPA extends JavaPlugin implements CommandExecutor, Listener {

    /** Pending requests, keyed by the UUID of the player who must accept/deny. */
    private final Map<UUID, Request> pendingByTarget = new HashMap<>();
    private final Map<UUID, Long> lastRequestSent = new HashMap<>();
    private final Map<UUID, BukkitTask> warmupTasks = new HashMap<>();
    private final Map<UUID, Location> warmupStartLoc = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String cmd : new String[]{"tpa", "tpahere", "tpaccept", "tpdeny", "tpcancel"}) {
            getCommand(cmd).setExecutor(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PrimeTPA enabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (!player.hasPermission("primetpa.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return true;
        }

        switch (label.toLowerCase()) {
            case "tpa" -> handleRequest(player, args, false);
            case "tpahere" -> handleRequest(player, args, true);
            case "tpaccept" -> handleAccept(player);
            case "tpdeny" -> handleDeny(player);
            case "tpcancel" -> handleCancel(player);
            default -> { return false; }
        }
        return true;
    }

    private void handleRequest(Player requester, String[] args, boolean here) {
        if (args.length < 1) {
            requester.sendMessage(ChatColor.RED + "Usage: /" + (here ? "tpahere" : "tpa") + " <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            requester.sendMessage(ChatColor.RED + "That player isn't online.");
            return;
        }
        if (target.getUniqueId().equals(requester.getUniqueId())) {
            requester.sendMessage(ChatColor.RED + "You can't send a request to yourself.");
            return;
        }

        long cooldownMs = getConfig().getInt("cooldown-seconds", 5) * 1000L;
        Long last = lastRequestSent.get(requester.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < cooldownMs) {
            long remaining = (cooldownMs - (System.currentTimeMillis() - last)) / 1000 + 1;
            requester.sendMessage(ChatColor.RED + "Please wait " + remaining + "s before sending another request.");
            return;
        }

        Request request = new Request(requester.getUniqueId(), target.getUniqueId(), here);
        pendingByTarget.put(target.getUniqueId(), request);
        lastRequestSent.put(requester.getUniqueId(), System.currentTimeMillis());

        int timeoutSeconds = getConfig().getInt("request-timeout-seconds", 60);
        request.timeoutTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (pendingByTarget.get(target.getUniqueId()) == request) {
                pendingByTarget.remove(target.getUniqueId());
                if (requester.isOnline()) {
                    requester.sendMessage(ChatColor.YELLOW + "Your teleport request to " + target.getName() + " expired.");
                }
                if (target.isOnline()) {
                    target.sendMessage(ChatColor.YELLOW + "The teleport request from " + requester.getName() + " expired.");
                }
            }
        }, timeoutSeconds * 20L);

        if (here) {
            requester.sendMessage(ChatColor.GREEN + "Requested " + target.getName() + " to teleport to you.");
            target.sendMessage(ChatColor.GOLD + requester.getName() + ChatColor.YELLOW
                    + " wants you to teleport to them. Type " + ChatColor.GREEN + "/tpaccept"
                    + ChatColor.YELLOW + " or " + ChatColor.RED + "/tpdeny");
        } else {
            requester.sendMessage(ChatColor.GREEN + "Requested to teleport to " + target.getName() + ".");
            target.sendMessage(ChatColor.GOLD + requester.getName() + ChatColor.YELLOW
                    + " wants to teleport to you. Type " + ChatColor.GREEN + "/tpaccept"
                    + ChatColor.YELLOW + " or " + ChatColor.RED + "/tpdeny");
        }
    }

    private void handleAccept(Player acceptor) {
        Request request = pendingByTarget.remove(acceptor.getUniqueId());
        if (request == null) {
            acceptor.sendMessage(ChatColor.RED + "You have no pending teleport requests.");
            return;
        }
        if (request.timeoutTask != null) {
            request.timeoutTask.cancel();
        }
        Player requester = Bukkit.getPlayer(request.requester);
        if (requester == null || !requester.isOnline()) {
            acceptor.sendMessage(ChatColor.RED + "That player is no longer online.");
            return;
        }

        acceptor.sendMessage(ChatColor.GREEN + "Request accepted.");
        requester.sendMessage(ChatColor.GREEN + acceptor.getName() + " accepted your teleport request.");

        if (request.here) {
            // acceptor (the target) teleports to the requester
            beginTeleport(acceptor, requester, requester.getLocation());
        } else {
            // requester teleports to the acceptor
            beginTeleport(requester, acceptor, acceptor.getLocation());
        }
    }

    private void handleDeny(Player acceptor) {
        Request request = pendingByTarget.remove(acceptor.getUniqueId());
        if (request == null) {
            acceptor.sendMessage(ChatColor.RED + "You have no pending teleport requests.");
            return;
        }
        if (request.timeoutTask != null) {
            request.timeoutTask.cancel();
        }
        acceptor.sendMessage(ChatColor.YELLOW + "Request denied.");
        Player requester = Bukkit.getPlayer(request.requester);
        if (requester != null && requester.isOnline()) {
            requester.sendMessage(ChatColor.RED + acceptor.getName() + " denied your teleport request.");
        }
    }

    private void handleCancel(Player requester) {
        Iterator<Map.Entry<UUID, Request>> it = pendingByTarget.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Request> entry = it.next();
            if (entry.getValue().requester.equals(requester.getUniqueId())) {
                if (entry.getValue().timeoutTask != null) {
                    entry.getValue().timeoutTask.cancel();
                }
                Player target = Bukkit.getPlayer(entry.getKey());
                it.remove();
                requester.sendMessage(ChatColor.YELLOW + "Teleport request cancelled.");
                if (target != null && target.isOnline()) {
                    target.sendMessage(ChatColor.YELLOW + requester.getName() + " cancelled their teleport request.");
                }
                return;
            }
        }
        requester.sendMessage(ChatColor.RED + "You have no pending outgoing requests.");
    }

    /**
     * Teleports "mover" to "destination" after a warmup period (config,
     * default 3s) during which moving or taking damage cancels it.
     */
    private void beginTeleport(Player mover, Player other, Location destination) {
        int warmup = getConfig().getInt("warmup-seconds", 3);
        if (warmup <= 0) {
            mover.teleport(destination);
            mover.sendMessage(ChatColor.GREEN + "Teleported!");
            return;
        }

        mover.sendMessage(ChatColor.YELLOW + "Teleporting in " + warmup + " seconds - don't move!");
        warmupStartLoc.put(mover.getUniqueId(), mover.getLocation());

        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            warmupTasks.remove(mover.getUniqueId());
            warmupStartLoc.remove(mover.getUniqueId());
            if (!mover.isOnline()) {
                return;
            }
            mover.teleport(destination);
            mover.sendMessage(ChatColor.GREEN + "Teleported!");
            if (other != null && other.isOnline() && !other.equals(mover)) {
                other.sendMessage(ChatColor.GREEN + mover.getName() + " has teleported to you.");
            }
        }, warmup * 20L);

        warmupTasks.put(mover.getUniqueId(), task);
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
        // Ignore pure head-turns; only cancel on actual positional movement
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
        pendingByTarget.remove(uuid);
    }

    private static class Request {
        final UUID requester;
        final UUID target;
        final boolean here;
        BukkitTask timeoutTask;

        Request(UUID requester, UUID target, boolean here) {
            this.requester = requester;
            this.target = target;
            this.here = here;
        }
    }
}
