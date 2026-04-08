package dev.nafis.boundallay;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Mutable config holder. Loaded on enable and again on /allay admin reload.
 * All values are clamped to safe ranges so a misconfigured config.yml
 * can never crash the plugin or break allay behavior.
 */
public class BoundAllayConfig {

    // Allay limits
    public int maxPerPlayer;
    public boolean summonAllOnJoin;

    // Follow
    public double followDistance;
    public long followTickInterval;

    // Bind
    public double bindRadius;

    // Death / respawn
    public long respawnDelayTicks;
    public boolean invincible;
    public boolean blockPvpDamage;

    // Cooldown
    public long actionCooldownMs;

    // Cross-play
    public boolean preferNativeForms;

    // Combat
    public double fighterDamage;
    public int fighterRange;
    public int healerIntervalTicks;
    public int healerPotionDuration;
    public int guardianRange;
    public int collectorRange;

    // Worlds
    private final Set<String> disabledWorlds = new HashSet<>();

    public void load(Plugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        Logger log = plugin.getLogger();

        // Allay limits
        this.maxPerPlayer = (int) clampL(cfg.getLong("allay.max-per-player", 3L), 1L, 20L, "allay.max-per-player", log);
        this.summonAllOnJoin = cfg.getBoolean("allay.summon-all-on-join", false);

        // Follow
        this.followDistance = clampD(cfg.getDouble("follow.distance", 32.0), 4.0, 128.0, "follow.distance", log);
        this.followTickInterval = clampL(cfg.getLong("follow.tick-interval", 10L), 5L, 100L, "follow.tick-interval", log);

        // Bind
        this.bindRadius = clampD(cfg.getDouble("bind.radius", 5.0), 1.0, 64.0, "bind.radius", log);

        // Death
        this.respawnDelayTicks = clampL(cfg.getLong("death.respawn-delay-ticks", 60L), 0L, 6000L, "death.respawn-delay-ticks", log);
        this.invincible = cfg.getBoolean("death.invincible", false);
        this.blockPvpDamage = cfg.getBoolean("death.block-pvp-damage", true);

        // Cooldown
        this.actionCooldownMs = clampL(cfg.getLong("cooldown.action-ms", 2000L), 0L, 60000L, "cooldown.action-ms", log);

        // Bedrock
        this.preferNativeForms = cfg.getBoolean("bedrock.prefer-native-forms", true);

        // Combat
        this.fighterDamage = clampD(cfg.getDouble("combat.fighter-damage", 4.0), 0.5, 20.0, "combat.fighter-damage", log);
        this.fighterRange = (int) clampL(cfg.getLong("combat.fighter-range", 64L), 2L, 128L, "combat.fighter-range", log);
        this.healerIntervalTicks = (int) clampL(cfg.getLong("combat.healer-interval-ticks", 600L), 20L, 6000L, "combat.healer-interval-ticks", log);
        this.healerPotionDuration = (int) clampL(cfg.getLong("combat.healer-potion-duration", 200L), 20L, 1200L, "combat.healer-potion-duration", log);
        this.guardianRange = (int) clampL(cfg.getLong("combat.guardian-range", 10L), 2L, 128L, "combat.guardian-range", log);
        this.collectorRange = (int) clampL(cfg.getLong("combat.collector-range", 64L), 2L, 128L, "combat.collector-range", log);

        // Worlds
        this.disabledWorlds.clear();
        List<String> worldList = cfg.getStringList("worlds.disabled");
        if (worldList != null) {
            for (String w : worldList) {
                disabledWorlds.add(w.toLowerCase());
            }
        }

        log.info("Config loaded: maxAllays=" + maxPerPlayer
                + " follow=" + followDistance
                + " bind=" + bindRadius
                + " respawn=" + respawnDelayTicks + "t"
                + " cooldown=" + actionCooldownMs + "ms"
                + " invincible=" + invincible
                + " pvp-block=" + blockPvpDamage
                + " disabledWorlds=" + disabledWorlds.size()
                + " summonOnJoin=" + summonAllOnJoin
                + " fighterDmg=" + fighterDamage
                + " healerInterval=" + healerIntervalTicks);
    }

    /** Check if a world name is in the disabled list. */
    public boolean isWorldDisabled(String worldName) {
        return disabledWorlds.contains(worldName.toLowerCase());
    }

    public Set<String> getDisabledWorlds() {
        return new HashSet<>(disabledWorlds);
    }

    private static double clampD(double v, double min, double max, String key, Logger log) {
        if (v < min || v > max) {
            log.warning("Config value ' + key + ' out of range (" + min + "-" + max + "), clamping: " + v);
            return Math.max(min, Math.min(max, v));
        }
        return v;
    }

    private static long clampL(long v, long min, long max, String key, Logger log) {
        if (v < min || v > max) {
            log.warning("Config value ' + key + ' out of range (" + min + "-" + max + "), clamping: " + v);
            return Math.max(min, Math.min(max, v));
        }
        return v;
    }
}
