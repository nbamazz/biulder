package com.primesmp.guard;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class PrimeGuard extends JavaPlugin {
    private StaffTools staffTools;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        staffTools = new StaffTools(this);
        getServer().getPluginManager().registerEvents(new AntiCheatListener(this), this);
        getServer().getPluginManager().registerEvents(staffTools, this);
        register("staff", staffTools);
        register("freeze", staffTools);
        register("stafftp", staffTools);
        register("rtpstaff", staffTools);
        register("stashspawn", staffTools);
        register("orespawn", staffTools);
        getLogger().info("PrimeGuard enabled.");
    }

    private void register(String command, StaffTools executor) {
        PluginCommand pluginCommand = getCommand(command);
        if (pluginCommand == null) {
            throw new IllegalStateException("Missing command in plugin.yml: " + command);
        }
        pluginCommand.setExecutor(executor);
        pluginCommand.setTabCompleter(executor);
    }

    public void alert(String check, org.bukkit.entity.Player player, String detail) {
        String message = "§8[§cPrimeGuard§8] §f" + player.getName() + " §7failed §e" + check + " §8(" + detail + ")";
        getServer().getOnlinePlayers().stream()
                .filter(staff -> staff.hasPermission("primeguard.alerts"))
                .forEach(staff -> staff.sendMessage(message));
        getLogger().info(player.getName() + " failed " + check + " (" + detail + ")");
    }
}
