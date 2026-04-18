package dev.nafis.boundallay;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AllayBehaviors {

    private final AllayManager mgr;
    private final BoundAllayConfig cfg;

    private final Map<UUID, UUID> currentTarget = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> targetTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextPathRetry = new ConcurrentHashMap<>();

    private static final double ATTACK_REACH_SQ = 2.25;
    private static final double ORBIT_RADIUS = 2.4;
    private static final double FOLLOW_SPEED = 1.3;
    private static final double COMBAT_SPEED = 1.5;

    public AllayBehaviors(AllayManager mgr, BoundAllayConfig cfg) {
        this.mgr = mgr;
        this.cfg = cfg;
    }

    // ==================== FOLLOW TICK ====================

    public void tickFollow() {
        long now = System.currentTimeMillis();
        Map<UUID, Map<String, UUID>> active = mgr.getActiveAllaysRaw();

        for (Map.Entry<UUID, Map<String, UUID>> ownerEntry : new HashMap<>(active).entrySet()) {
            UUID ownerId = ownerEntry.getKey();
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner == null || !owner.isOnline()) continue;

            int index = 0;
            int total = ownerEntry.getValue().size();

            for (Map.Entry<String, UUID> allayEntry : new HashMap<>(ownerEntry.getValue()).entrySet()) {
                UUID entityId = allayEntry.getValue();
                Entity ent = Bukkit.getEntity(entityId);

                if (!(ent instanceof Allay allay) || allay.isDead()) {
                    cleanup(entityId);
                    continue;
                }

                if (!allay.getWorld().equals(owner.getWorld())
                        || allay.getLocation().distanceSquared(owner.getLocation())
                           > cfg.teleportDistance * cfg.teleportDistance) {
                    Location tp = owner.getLocation().clone().add(0, 1.2, 0);
                    allay.teleport(tp);
                    continue;
                }

                if (currentTarget.containsKey(entityId)) continue;

                Location goal = orbitPoint(owner.getLocation(), index, total);
                moveIfReady(allay, goal, FOLLOW_SPEED, now);
                index++;
            }
        }
    }

    // ==================== COMBAT TICK ====================

    public void tickCombat() {
        long now = System.currentTimeMillis();
        Map<UUID, Map<String, UUID>> active = mgr.getActiveAllaysRaw();

        for (Map.Entry<UUID, Map<String, UUID>> ownerEntry : new HashMap<>(active).entrySet()) {
            UUID ownerId = ownerEntry.getKey();
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner == null || !owner.isOnline()) continue;

            for (Map.Entry<String, UUID> allayEntry : new HashMap<>(ownerEntry.getValue()).entrySet()) {
                UUID entityId = allayEntry.getValue();
                Entity ent = Bukkit.getEntity(entityId);
                if (!(ent instanceof Allay allay) || allay.isDead()) continue;

                AllayType type = mgr.readAllayType(allay);
                switch (type) {
                    case FIGHTER -> tickFighter(allay, owner, now);
                    case HEALER -> tickHealer(allay, owner, now);
                    case GUARDIAN -> tickGuardian(allay, owner, now);
                }
            }
        }
    }

    // ==================== FIGHTER ====================

    private void tickFighter(Allay allay, Player owner, long now) {
        LivingEntity target = validateTarget(allay, owner, cfg.fighterRange, false);
        if (target == null) {
            target = findHostileNear(owner, cfg.fighterRange, false);
            if (target != null) assignTarget(allay, target);
        }
        if (target == null) {
            releaseTarget(allay);
            return;
        }
        engageTarget(allay, target, now);
    }

    // ==================== GUARDIAN ====================

    private void tickGuardian(Allay allay, Player owner, long now) {
        LivingEntity target = validateTarget(allay, owner, cfg.guardianRange, true);
        if (target == null) {
            target = findHostileNear(owner, cfg.guardianRange, cfg.guardianDefendsOwnerFromPlayers);
            if (target != null) assignTarget(allay, target);
        }
        if (target == null) {
            releaseTarget(allay);
            return;
        }
        engageTarget(allay, target, now);
    }

    // ==================== HEALER ====================

    private void tickHealer(Allay allay, Player owner, long now) {
        Map<UUID, Long> heals = mgr.getLastHealerTick();
        long lastTick = heals.getOrDefault(allay.getUniqueId(), 0L);
        long intervalMs = cfg.healerIntervalTicks * 50L;

        if (now - lastTick < intervalMs) return;

        double healRangeSq = 6 * 6;
        if (allay.getLocation().distanceSquared(owner.getLocation()) > healRangeSq) return;

        if (owner.getHealth() >= owner.getMaxHealth() - 0.5) return;

        owner.addPotionEffect(new PotionEffect(
                PotionEffectType.REGENERATION,
                cfg.healerPotionDuration,
                cfg.healerPotionAmplifier,
                true, true, true
        ));
        allay.swingMainHand();
        allay.getWorld().spawnParticle(org.bukkit.Particle.HEART, owner.getLocation().add(0, 2, 0), 5, 0.4, 0.4, 0.4);
        heals.put(allay.getUniqueId(), now);
    }

    // ==================== COMBAT CORE ====================

    private void engageTarget(Allay allay, LivingEntity target, long now) {
        double dSq = allay.getLocation().distanceSquared(target.getLocation());

        if (allay instanceof Mob mob) {
            mob.setTarget(target);
        }

        if (dSq <= ATTACK_REACH_SQ) {
            allay.swingMainHand();
            target.damage(cfg.fighterDamage, allay);
            allay.getWorld().spawnParticle(org.bukkit.Particle.CRIT, target.getLocation().add(0, 1, 0), 4, 0.3, 0.3, 0.3);
        } else {
            moveIfReady(allay, target.getLocation(), COMBAT_SPEED, now);
        }

        int ticks = targetTicks.getOrDefault(allay.getUniqueId(), 0) + 1;
        targetTicks.put(allay.getUniqueId(), ticks);

        if (ticks > 200) {
            releaseTarget(allay);
        }
    }

    private LivingEntity validateTarget(Allay allay, Player owner, double range, boolean includePlayers) {
        UUID targetId = currentTarget.get(allay.getUniqueId());
        if (targetId == null) return null;
        Entity ent = Bukkit.getEntity(targetId);
        if (!(ent instanceof LivingEntity le) || le.isDead()) {
            releaseTarget(allay);
            return null;
        }
        if (!isValidHostile(le, owner, includePlayers)) {
            releaseTarget(allay);
            return null;
        }
        if (le.getLocation().distanceSquared(owner.getLocation()) > range * range) {
            releaseTarget(allay);
            return null;
        }
        return le;
    }

    private LivingEntity findHostileNear(Player owner, double range, boolean includePlayers) {
        Collection<Entity> nearby = owner.getWorld().getNearbyEntities(owner.getLocation(), range, range, range);
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : nearby) {
            if (!(e instanceof LivingEntity le)) continue;
            if (!isValidHostile(le, owner, includePlayers)) continue;
            double sq = le.getLocation().distanceSquared(owner.getLocation());
            if (sq < bestSq) {
                best = le;
                bestSq = sq;
            }
        }
        return best;
    }

    private boolean isValidHostile(LivingEntity le, Player owner, boolean includePlayers) {
        if (le.equals(owner)) return false;
        if (le.isDead()) return false;
        if (le instanceof Allay) return false;
        if (le instanceof Tameable t && owner.getUniqueId().equals(tameOwner(t))) return false;
        if (le instanceof Monster) return true;
        if (includePlayers && le instanceof Player other && !other.getUniqueId().equals(owner.getUniqueId())) {
            return true;
        }
        return false;
    }

    private UUID tameOwner(Tameable t) {
        if (t.getOwner() == null) return null;
        return t.getOwner().getUniqueId();
    }

    // ==================== MOVEMENT ====================

    /**
     * Move entity toward goal using Pathfinder if available, teleport as last resort.
     * Rate-limited so we don't spam pathfinder updates.
     */
    private void moveIfReady(Allay allay, Location goal, double speed, long now) {
        UUID id = allay.getUniqueId();
        long nextAllowed = nextPathRetry.getOrDefault(id, 0L);
        if (now < nextAllowed) return;

        try {
            allay.getPathfinder().moveTo(goal, speed);
            nextPathRetry.put(id, now + 400L);
        } catch (Throwable t) {
            Location lookAt = goal.clone();
            lookAt.setDirection(goal.toVector().subtract(allay.getLocation().toVector()));
            allay.teleport(lookAt);
            nextPathRetry.put(id, now + 800L);
        }
    }

    private Location orbitPoint(Location center, int index, int total) {
        if (total <= 0) total = 1;
        double angle = (Math.PI * 2.0 / total) * index + (System.currentTimeMillis() / 2500.0);
        double x = center.getX() + Math.cos(angle) * ORBIT_RADIUS;
        double z = center.getZ() + Math.sin(angle) * ORBIT_RADIUS;
        double y = center.getY() + 1.6;
        return new Location(center.getWorld(), x, y, z);
    }

    // ==================== TARGET MGMT ====================

    public void assignTarget(Allay allay, LivingEntity target) {
        currentTarget.put(allay.getUniqueId(), target.getUniqueId());
        targetTicks.put(allay.getUniqueId(), 0);
        if (allay instanceof Mob mob) mob.setTarget(target);
    }

    public void releaseTarget(Allay allay) {
        currentTarget.remove(allay.getUniqueId());
        targetTicks.remove(allay.getUniqueId());
        if (allay instanceof Mob mob) mob.setTarget(null);
    }

    public void cleanup(UUID entityId) {
        currentTarget.remove(entityId);
        targetTicks.remove(entityId);
        nextPathRetry.remove(entityId);
        mgr.getLastHealerTick().remove(entityId);
    }

    // ==================== PVP DEFENSE TRIGGER ====================

    /**
     * Called when the owner is attacked by some entity. Make FIGHTER/GUARDIAN allays
     * retaliate against the attacker.
     */
    public void onOwnerAttacked(Player owner, LivingEntity attacker) {
        if (attacker == null || attacker.isDead() || attacker.equals(owner)) return;
        boolean attackerIsPlayer = attacker instanceof Player;

        Map<String, UUID> active = mgr.getActiveAllaysRaw().get(owner.getUniqueId());
        if (active == null) return;

        for (UUID entityId : new ArrayList<>(active.values())) {
            Entity ent = Bukkit.getEntity(entityId);
            if (!(ent instanceof Allay allay) || allay.isDead()) continue;
            AllayType type = mgr.readAllayType(allay);

            if (type == AllayType.HEALER) continue;
            if (type == AllayType.FIGHTER && attackerIsPlayer) continue;

            assignTarget(allay, attacker);
        }
    }

    /**
     * Called when a bound allay is attacked. Redirect attacker to ALL of that owner's
     * fighting allays so they gang up.
     */
    public void onAllayAttacked(Allay victim, LivingEntity attacker) {
        UUID owner = mgr.readOwner(victim);
        if (owner == null) return;
        Player ownerPlayer = Bukkit.getPlayer(owner);
        if (ownerPlayer == null) return;
        onOwnerAttacked(ownerPlayer, attacker);
    }
}
