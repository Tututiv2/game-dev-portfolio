package com.smhmines;

import com.smhmines.spawn.SpawnCommand;
import com.smhmines.spawn.SpawnSafetyListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Composition root for SmhMines.
 *
 * <p>Design law inherited from the prison project: nothing that writes to the world may run from
 * {@link #onEnable}. Builders are constructed and registered here; an op has to ask for a build.
 */
public final class SmhMinesPlugin extends JavaPlugin {

    private SpawnCommand spawnCommand;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        spawnCommand = new SpawnCommand(this);
        bind("smhspawn", spawnCommand);

        getServer().getPluginManager().registerEvents(new SpawnSafetyListener(this), this);

        reportWorldHeight();
        getLogger().info("SmhMines enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("SmhMines disabled.");
    }

    /**
     * Prints the actual build range of each world at startup.
     *
     * <p>The 2048-block height comes from a datapack, and a datapack that fails to parse does not
     * fall back quietly — it aborts the whole server before the world ever loads. Worse, a
     * datapack that loads but is ignored would leave the spawn builder silently clipping a
     * 1,250-block structure against a 320-block ceiling. This line is how you know which of those
     * happened, without logging into the game to find out.
     */
    private void reportWorldHeight() {
        for (var world : getServer().getWorlds()) {
            int min = world.getMinHeight();
            int max = world.getMaxHeight() - 1;
            String note = (max - min + 1) >= 2048 ? "  <- 2048-block datapack ACTIVE"
                                                  : "  <- default height; height datapack NOT applied";
            getLogger().info("World '" + world.getName() + "' builds y" + min + " .. y" + max + note);
        }
    }

    private void bind(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command '" + name + "' missing from plugin.yml — not bound.");
            return;
        }
        cmd.setExecutor(executor);
    }
}
