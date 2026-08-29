package com.primesmp.combatlog;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tags players who deal or take PvP damage as "in combat" for a configured
 * duration. If a tagged player disconnects before combat ends, they're
 * killed instantly instead of escaping a fight (and a rank-affecting death)
 * by logging out.
 */
public class PrimeCombatLog extends JavaPlugin implements Listener {

    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        // Ticks every second: updates boss bars and clears expired tags.
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);

        getLogger().info("PrimeCombatLog enabled.");
    }

    @Override
    public void onDisable() {
        for (BossBar bar : bossBars.values()) {
            bar.removeAll();
        }
        bossBars.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }

        tag(victim);
        tag(attacker);
    }

    /** Resolves the actual player responsible for damage, including via projectiles. */
    private Player resolveAttacker(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    private void tag(Player player) {
        if (player.hasPermission("primecombatlog.bypass")) {
            return;
        }
        int duration = getConfig().getInt("combat-duration-seconds", 15);
        combatUntil.put(player.getUniqueId(), System.currentTimeMillis() + duration * 1000L);

        if (getConfig().getBoolean("show-bossbar", true)) {
            BossBar bar = bossBars.computeIfAbsent(player.getUniqueId(), id -> {
                BossBar created = Bukkit.createBossBar("", BarColor.RED, BarStyle.SOLID);
                created.addPlayer(player);
                return created;
            });
            bar.setVisible(true);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = combatUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            long remainingMs = entry.getValue() - now;

            if (remainingMs <= 0) {
                it.remove();
                BossBar bar = bossBars.remove(entry.getKey());
                if (bar != null) {
                    bar.removeAll();
                }
                if (player != null) {
                    player.sendMessage(ChatColor.GREEN + "You are no longer in combat.");
                }
                continue;
            }

            if (player != null) {
                BossBar bar = bossBars.get(entry.getKey());
                if (bar != null) {
                    double total = getConfig().getInt("combat-duration-seconds", 15) * 1000.0;
                    bar.setProgress(Math.max(0.0, Math.min(1.0, remainingMs / total)));
                    bar.setTitle(ChatColor.RED + "" + ChatColor.BOLD + "COMBAT "
                            + ChatColor.WHITE + (remainingMs / 1000 + 1) + "s");
                }
            }
        }
    }

    public boolean isInCombat(UUID uuid) {
        Long until = combatUntil.get(uuid);
        return until != null && until > System.currentTimeMillis();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }

        if (!isInCombat(player.getUniqueId())) {
            return;
        }
        combatUntil.remove(player.getUniqueId());

        if (!getConfig().getBoolean("punish-logout", true)) {
            return;
        }

        // Player object is still valid for this tick - killing them here
        // produces a normal death (drops, death message, rank tracking, etc.)
        // instead of letting them dodge it by disconnecting.
        player.setHealth(0.0);

        if (getConfig().getBoolean("broadcast-on-punish", true)) {
            String msg = getConfig().getString("broadcast-message", "&c%player% combat logged!")
                    .replace("%player%", player.getName());
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
    }
}
