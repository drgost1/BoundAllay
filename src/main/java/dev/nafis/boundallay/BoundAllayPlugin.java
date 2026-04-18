package dev.nafis.boundallay;

import dev.nafis.boundallay.commands.AllayCommand;
import dev.nafis.boundallay.listeners.AllayListener;
import dev.nafis.boundallay.listeners.VaultListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class BoundAllayPlugin extends JavaPlugin {

    private AllayManager manager;
    private AllayBehaviors behaviors;
    private BoundAllayConfig boundConfig;
    private AllayStorage storage;

    @Override
    public void onEnable() {
        AllayKeys.init(this);

        this.boundConfig = new BoundAllayConfig();
        this.boundConfig.load(this);

        this.storage = new AllayStorage(
                getDataFolder().toPath().resolve("allays.json"),
                getLogger(),
                this
        );
        storage.load();

        this.manager = new AllayManager(this, storage, boundConfig);
        this.behaviors = new AllayBehaviors(manager, boundConfig);

        // Deferred scan so all worlds are loaded
        getServer().getScheduler().runTaskLater(this, manager::scanAndRestoreActive, 40L);

        getServer().getPluginManager().registerEvents(new AllayListener(this, manager, behaviors), this);
        getServer().getPluginManager().registerEvents(new VaultListener(manager), this);

        AllayCommand cmd = new AllayCommand(this, manager);
        PluginCommand pc = Objects.requireNonNull(getCommand("allay"));
        pc.setExecutor(cmd);
        pc.setTabCompleter(cmd);

        getServer().getScheduler().runTaskTimer(this, behaviors::tickFollow,
                20L, boundConfig.followTickInterval);
        getServer().getScheduler().runTaskTimer(this, behaviors::tickCombat,
                25L, boundConfig.combatTickInterval);

        boolean floodgate = getServer().getPluginManager().getPlugin("floodgate") != null;
        getLogger().info("Floodgate: " + (floodgate ? "detected" : "absent"));
        getLogger().info("BoundAllay v" + getDescription().getVersion()
                + " enabled. Loaded " + storage.size() + " allays across " + storage.playerCount() + " players.");
    }

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
        if (storage != null) {
            storage.saveSync();
            getLogger().info("BoundAllay disabled. Final save complete.");
        }
    }

    public AllayManager getManager() { return manager; }
    public AllayBehaviors getBehaviors() { return behaviors; }
}
