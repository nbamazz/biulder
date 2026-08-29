package com.primesmp.guard;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

final class AntiCheatListener implements Listener {
    private final PrimeGuard plugin;
    private final Map<UUID, Long> lastAlert = new HashMap<>();
    private final Map<UUID, BreakWindow> breakWindows = new HashMap<>();

    AntiCheatListener(PrimeGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || shouldIgnore(player)) return;

        Vector delta = to.toVector().subtract(from.toVector());
        double horizontal = Math.hypot(delta.getX(), delta.getZ());
        double speedLimit = plugin.getConfig().getDouble("checks.speed.max-horizontal-distance", 0.85);
        if (plugin.getConfig().getBoolean("checks.speed.enabled", true) && horizontal > speedLimit) {
            flag(player, "Speed", String.format("%.2f blocks/tick", horizontal));
        }

        double upwardLimit = plugin.getConfig().getDouble("checks.fly.max-upward-distance", 1.15);
        if (plugin.getConfig().getBoolean("checks.fly.enabled", true)
                && delta.getY() > upwardLimit && !player.isGliding() && !player.isInsideVehicle()) {
            flag(player, "Fly", String.format("%.2f blocks upward", delta.getY()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (shouldIgnore(player) || !plugin.getConfig().getBoolean("checks.fast-break.enabled", true)) return;
        long now = System.currentTimeMillis();
        BreakWindow window = breakWindows.computeIfAbsent(player.getUniqueId(), ignored -> new BreakWindow(now));
        if (now - window.startedAt > 1000) {
            window.startedAt = now;
            window.breaks = 0;
        }
        window.breaks++;
        int maximum = plugin.getConfig().getInt("checks.fast-break.max-blocks-per-second", 14);
        if (window.breaks > maximum) flag(player, "FastBreak", window.breaks + " blocks/s");
    }

    private boolean shouldIgnore(Player player) {
        return player.hasPermission("primeguard.bypass") || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR || player.getAllowFlight()
                || player.isFlying() || player.isSwimming() || player.isGliding()
                || player.getFallDistance() == 0 && player.isInsideVehicle();
    }

    private void flag(Player player, String check, String detail) {
        long now = System.currentTimeMillis();
        Long previous = lastAlert.get(player.getUniqueId());
        if (previous != null && now - previous < 2000) return;
        lastAlert.put(player.getUniqueId(), now);
        plugin.alert(check, player, detail);
    }

    private static final class BreakWindow {
        private long startedAt;
        private int breaks;

        private BreakWindow(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
