package dev.nafis.boundallay.listeners;

import dev.nafis.boundallay.AllayBehaviors;
import dev.nafis.boundallay.AllayManager;
import dev.nafis.boundallay.BoundAllayPlugin;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

public class AllayListener implements Listener {

    private final BoundAllayPlugin plugin;
    private final AllayManager mgr;
    private final AllayBehaviors behaviors;

    public AllayListener(BoundAllayPlugin plugin, AllayManager mgr, AllayBehaviors behaviors) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.behaviors = behaviors;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Allay allay)) return;
        if (!mgr.isBoundAllay(allay)) return;

        e.getDrops().clear();
        e.setDroppedExp(0);
        behaviors.cleanup(allay.getUniqueId());
        mgr.onBoundAllayDeath(allay);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onAnyDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Allay allay)) return;
        if (!mgr.isBoundAllay(allay)) return;
        if (mgr.getConfig().invincible) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Allay allay && mgr.isBoundAllay(allay)) {
            LivingEntity attacker = resolveAttacker(e.getDamager());
            if (mgr.getConfig().invincible) return;
            if (mgr.getConfig().blockPvpDamage && e.getDamager() instanceof Player) {
                e.setCancelled(true);
                return;
            }
            if (attacker != null) behaviors.onAllayAttacked(allay, attacker);
            return;
        }

        if (e.getEntity() instanceof Player victim && !e.isCancelled()) {
            LivingEntity attacker = resolveAttacker(e.getDamager());
            if (attacker == null) return;
            if (attacker.equals(victim)) return;
            behaviors.onOwnerAttacked(victim, attacker);
        }
    }

    private LivingEntity resolveAttacker(Entity damager) {
        if (damager instanceof LivingEntity le) return le;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof LivingEntity shooter) return shooter;
        }
        return null;
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        UUID owner = e.getPlayer().getUniqueId();
        Map<String, UUID> activeMap = mgr.getAllActiveEntities(owner);
        if (activeMap.isEmpty()) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Map.Entry<String, UUID> entry : new ArrayList<>(activeMap.entrySet())) {
                Allay allay = mgr.getActiveEntity(owner, entry.getKey());
                if (allay != null && !allay.isDead()) {
                    allay.teleport(e.getPlayer().getLocation().clone().add(0, 1.2, 0));
                }
            }
        });
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        UUID owner = e.getPlayer().getUniqueId();
        Map<String, UUID> activeMap = mgr.getAllActiveEntities(owner);
        if (activeMap.isEmpty()) return;

        String newWorldName = e.getPlayer().getWorld().getName();
        boolean worldDisabled = mgr.getConfig().isWorldDisabled(newWorldName);

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (worldDisabled) {
                mgr.storeAll(e.getPlayer());
                AllayManager.msg(e.getPlayer(), "Allays stored (disabled world).",
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW);
            } else {
                for (Map.Entry<String, UUID> entry : new ArrayList<>(activeMap.entrySet())) {
                    Allay allay = mgr.getActiveEntity(owner, entry.getKey());
                    if (allay != null && !allay.isDead()) {
                        allay.teleport(e.getPlayer().getLocation().clone().add(0, 1.2, 0));
                    }
                }
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        mgr.handleOwnerQuit(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!mgr.getConfig().summonAllOnJoin) return;
        if (mgr.getConfig().isWorldDisabled(e.getPlayer().getWorld().getName())) return;

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (e.getPlayer().isOnline()) mgr.summonAll(e.getPlayer());
        }, 20L);
    }
}
