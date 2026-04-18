package dev.nafis.boundallay;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

public class BoundAllayConfig {

    public int maxPerPlayer;
    public boolean summonAllOnJoin;

    public double followDistance;
    public long followTickInterval;
    public long combatTickInterval;
    public double teleportDistance;

    public double bindRadius;

    public long respawnDelayTicks;
    public boolean invincible;
    public boolean blockPvpDamage;

    public long actionCooldownMs;

    public boolean preferNativeForms;

    public double fighterDamage;
    public double fighterRange;
    public int fighterTargetPriorityPlayers;

    public long healerIntervalTicks;
    public int healerPotionDuration;
    public int healerPotionAmplifier;

    public boolean guardianDefendsOwnerFromPlayers;
    public double guardianRange;

    public AllayType defaultType;

    private final Set<String> disabledWorlds = new HashSet<>();

    public void load(Plugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        Logger log = plugin.getLogger();

        this.maxPerPlayer = (int) clampL(cfg.getLong("allay.max-per-player", 3L), 1L, 20L, "allay.max-per-player", log);
        this.summonAllOnJoin = cfg.getBoolean("allay.summon-all-on-join", false);
        this.defaultType = AllayType.parse(cfg.getString("allay.default-type", "FIGHTER"), AllayType.FIGHTER);

        this.followDistance = clampD(cfg.getDouble("follow.distance", 16.0), 4.0, 64.0, "follow.distance", log);
        this.followTickInterval = clampL(cfg.getLong("follow.tick-interval", 5L), 2L, 100L, "follow.tick-interval", log);
        this.combatTickInterval = clampL(cfg.getLong("combat.tick-interval", 5L), 2L, 40L, "combat.tick-interval", log);
        this.teleportDistance = clampD(cfg.getDouble("follow.teleport-distance", 32.0), 16.0, 128.0, "follow.teleport-distance", log);

        this.bindRadius = clampD(cfg.getDouble("bind.radius", 5.0), 1.0, 16.0, "bind.radius", log);

        this.respawnDelayTicks = clampL(cfg.getLong("death.respawn-delay-ticks", 60L), 0L, 600L, "death.respawn-delay-ticks", log);
        this.invincible = cfg.getBoolean("death.invincible", false);
        this.blockPvpDamage = cfg.getBoolean("death.block-pvp-damage", true);

        this.actionCooldownMs = clampL(cfg.getLong("cooldown.action-ms", 2000L), 0L, 60000L, "cooldown.action-ms", log);

        this.preferNativeForms = cfg.getBoolean("bedrock.prefer-native-forms", true);

        this.fighterDamage = clampD(cfg.getDouble("combat.fighter.damage", 3.0), 0.5, 20.0, "combat.fighter.damage", log);
        this.fighterRange = clampD(cfg.getDouble("combat.fighter.range", 12.0), 4.0, 32.0, "combat.fighter.range", log);
        this.fighterTargetPriorityPlayers = (int) clampL(cfg.getLong("combat.fighter.player-priority", 0L), 0L, 2L, "combat.fighter.player-priority", log);

        this.healerIntervalTicks = clampL(cfg.getLong("combat.healer.interval-ticks", 200L), 40L, 6000L, "combat.healer.interval-ticks", log);
        this.healerPotionDuration = (int) clampL(cfg.getLong("combat.healer.effect-duration-ticks", 120L), 20L, 1200L, "combat.healer.effect-duration-ticks", log);
        this.healerPotionAmplifier = (int) clampL(cfg.getLong("combat.healer.effect-amplifier", 1L), 0L, 4L, "combat.healer.effect-amplifier", log);

        this.guardianDefendsOwnerFromPlayers = cfg.getBoolean("combat.guardian.defend-from-players", true);
        this.guardianRange = clampD(cfg.getDouble("combat.guardian.range", 16.0), 4.0, 32.0, "combat.guardian.range", log);

        this.disabledWorlds.clear();
        List<String> worldList = cfg.getStringList("worlds.disabled");
        if (worldList != null) {
            for (String w : worldList) disabledWorlds.add(w.toLowerCase());
        }

        log.info("Config loaded: max=" + maxPerPlayer + " defaultType=" + defaultType
                + " follow=" + followDistance + "/" + teleportDistance
                + " fighter=" + fighterDamage + "dmg@" + fighterRange + "b"
                + " healer=" + healerIntervalTicks + "t"
                + " disabledWorlds=" + disabledWorlds.size());
    }

    public boolean isWorldDisabled(String worldName) {
        return disabledWorlds.contains(worldName.toLowerCase());
    }

    private static double clampD(double v, double min, double max, String key, Logger log) {
        if (v < min || v > max) {
            log.warning("Config '" + key + "' out of range (" + min + "-" + max + "), clamping: " + v);
            return Math.max(min, Math.min(max, v));
        }
        return v;
    }

    private static long clampL(long v, long min, long max, String key, Logger log) {
        if (v < min || v > max) {
            log.warning("Config '" + key + "' out of range (" + min + "-" + max + "), clamping: " + v);
            return Math.max(min, Math.min(max, v));
        }
        return v;
    }
}
