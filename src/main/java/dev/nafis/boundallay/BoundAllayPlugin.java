package dev.nafis.boundallay;

import dev.nafis.boundallay.commands.AllayCommand;
import dev.nafis.boundallay.listeners.AllayListener;
import dev.nafis.boundallay.listeners.VaultListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class BoundAllayPlugin extends JavaPlugin {

    private AllayManager manager;
    private BoundAllayConfig boundConfig;
    private AllayStorage storage;

    @Override
    public void onEnable() {
        AllayKeys.init(this);

        // Load config
        this.boundConfig = new BoundAllayConfig();
        this.boundConfig.load(this);

        // Storage with plugin reference for async saves
        this.storage = new AllayStorage(
                getDataFolder().toPath().resolve("allays.json"),
                getLogger(),
                this
        );
        storage.load();

        this.manager = new AllayManager(this, storage, boundConfig);

        // Listeners
        getServer().getPluginManager().registerEvents(new AllayListener(this, manager), this);
        getServer().getPluginManager().registerEvents(new VaultListener(manager), this);

        // Command
        AllayCommand cmd = new AllayCommand(this, manager);
        PluginCommand pc = Objects.requireNonNull(getCommand("allay"));
        pc.setExecutor(cmd);
        pc.setTabCompleter(cmd);

        // Follow tick
        getServer().getScheduler().runTaskTimer(this, manager::tickFollow,
                20L, boundConfig.followTickInterval);

        // Combat tick (every 10 ticks = 0.5s, starting at 40 ticks)
        getServer().getScheduler().runTaskTimer(this, manager::tickCombat, 40L, 10L);

        boolean floodgate = getServer().getPluginManager().getPlugin("floodgate") != null;
        getLogger().info("Floodgate detected: " + floodgate
                + (floodgate ? " (Bedrock players will see native forms)"
                             : " (Bedrock players will see chest GUI via Geyser)"));

        getLogger().info("BoundAllay v" + getDescription().getVersion()
                + " enabled. Loaded " + storage.size() + " allays across " + storage.playerCount() + " players.");
    }

    /**
     * Reload config.yml at runtime. Some values (like follow-tick-interval)
     * only take effect after a full restart because schedulers are already running.
     */
    public void reloadBoundConfig() {
        boundConfig.load(this);
        getLogger().info("BoundAllay config reloaded.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            int stored = manager.storeAllActive();
            getLogger().info("Stored " + stored + " active allays on disable.");
        }
        // Final sync save to make sure nothing is lost
        if (storage != null) {
            storage.saveSync();
            getLogger().info("BoundAllay disabled. Final save complete.");
        }
    }

    public AllayManager getManager() {
        return manager;
    }
}
