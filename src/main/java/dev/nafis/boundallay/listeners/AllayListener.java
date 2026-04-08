package dev.nafis.boundallay.listeners;

import dev.nafis.boundallay.AllayManager;
import dev.nafis.boundallay.BoundAllayPlugin;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
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

    public AllayListener(BoundAllayPlugin plugin, AllayManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    /** Bound allay died -> cancel drops, schedule respawn. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Allay allay)) return;
        if (!mgr.isBoundAllay(allay)) return;

        e.getDrops().clear();
        e.setDroppedExp(0);
        mgr.onBoundAllayDeath(allay);
    }

    /** Full invincibility mode -> cancel ALL damage to bound allays. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onAnyDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Allay allay)) return;
        if (!mgr.isBoundAllay(allay)) return;
        if (mgr.getConfig().invincible) {
            e.setCancelled(true);
        }
    }

    /** Block player-dealt damage to bound allays (PvP safety). */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Allay allay)) return;
        if (!mgr.isBoundAllay(allay)) return;
        if (mgr.getConfig().invincible) return;
        if (!mgr.getConfig().blockPvpDamage) return;
        if (e.getDamager() instanceof org.bukkit.entity.Player) {
            e.setCancelled(true);
        }
    }

    /** Owner teleports -> bring all active allays along. */
    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        UUID owner = e.getPlayer().getUniqueId();
        Map<String, UUID> activeMap = mgr.getAllActiveEntities(owner);
        if (activeMap.isEmpty()) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Map.Entry<String, UUID> entry : new ArrayList<>(activeMap.entrySet())) {
                Allay allay = mgr.getActiveEntity(owner, entry.getKey());
                if (allay != null && !allay.isDead()) {
                    allay.teleport(e.getPlayer().getLocation());
                }
            }
        });
    }

    /** Cross-dimension follow for all active allays. Auto-store if entering disabled world. */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        UUID owner = e.getPlayer().getUniqueId();
        Map<String, UUID> activeMap = mgr.getAllActiveEntities(owner);
        if (activeMap.isEmpty()) return;

        String newWorldName = e.getPlayer().getWorld().getName();
        boolean worldDisabled = mgr.getConfig().isWorldDisabled(newWorldName);

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (worldDisabled) {
                // Auto-store all allays when entering a disabled world
                mgr.storeAll(e.getPlayer());
                AllayManager.msg(e.getPlayer(), "Allays are disabled in this world. Your allays have been stored.",
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW);
            } else {
                // Teleport all active allays to the new world
                for (Map.Entry<String, UUID> entry : new ArrayList<>(activeMap.entrySet())) {
                    Allay allay = mgr.getActiveEntity(owner, entry.getKey());
                    if (allay != null && !allay.isDead()) {
                        allay.teleport(e.getPlayer().getLocation());
                    }
                }
            }
        });
    }

    /** Redirect mob targeting from allay to its owner */
    @EventHandler(ignoreCancelled = true)
    public void onMobTargetAllay(EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof org.bukkit.entity.Allay allay)) return;
        if (!mgr.isBoundAllay(allay)) return;
        java.util.UUID ownerId = mgr.readOwner(allay);
        if (ownerId == null) return;
        org.bukkit.entity.Player owner = org.bukkit.Bukkit.getPlayer(ownerId);
        if (owner != null && owner.isOnline()) {
            e.setTarget(owner);
        } else {
            e.setCancelled(true);
        }
    }


    /** Track when a player attacks the allay's owner (for PvP allay targeting). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onOwnerDamagedByPlayer(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.Player victim)) return;
        // Resolve actual damager (could be projectile)
        org.bukkit.entity.Entity rawDamager = e.getDamager();
        org.bukkit.entity.Player attacker = null;
        if (rawDamager instanceof org.bukkit.entity.Player p) {
            attacker = p;
        } else if (rawDamager instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof org.bukkit.entity.Player p) {
            attacker = p;
        }
        if (attacker == null || attacker.equals(victim)) return;

        // Record attacker so PvP allays can target them
        mgr.recordAttacker(victim.getUniqueId(), attacker.getUniqueId());
    }

    public void onQuit(PlayerQuitEvent e) {
        mgr.handleOwnerQuit(e.getPlayer());
    }

    /** Optionally summon all allays on join if configured. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!mgr.getConfig().summonAllOnJoin) return;
        if (mgr.getConfig().isWorldDisabled(e.getPlayer().getWorld().getName())) return;

        // Delay slightly to let the player fully load in
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (e.getPlayer().isOnline()) {
                mgr.summonAll(e.getPlayer());
            }
        }, 20L);
    }
}
