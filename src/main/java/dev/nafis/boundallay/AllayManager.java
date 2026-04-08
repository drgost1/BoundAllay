package dev.nafis.boundallay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

import org.bukkit.Particle;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Core logic for multi-allay system. Each player can have up to config.maxPerPlayer allays.
 *
 * Active tracking: ownerUUID -> (allayId -> entityUUID)
 */
public class AllayManager {

    private final Plugin plugin;
    private final AllayStorage storage;
    private final BoundAllayConfig config;

    /** ownerUUID -> (allayId -> live entity UUID) */
    private final Map<UUID, Map<String, UUID>> activeAllays = new ConcurrentHashMap<>();

    /** ownerUUID -> last action timestamp (spam protection) */
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    /** Healer cooldown: allayEntityUUID -> last heal timestamp (ms) */
    private final Map<UUID, Long> lastHealTime = new ConcurrentHashMap<>();
    private final Set<UUID> inCombat = ConcurrentHashMap.newKeySet();

    /** PvP: ownerUUID -> attackerPlayerUUID */
    private final Map<UUID, UUID> lastAttacker = new ConcurrentHashMap<>();
    /** PvP: ownerUUID -> timestamp of last attack */
    private final Map<UUID, Long> lastAttackTime = new ConcurrentHashMap<>();

    /** Tick counter for spreading combat processing across ticks */
    private int tickCounter = 0;

    /** Orbit angle offset - slowly rotates the formation circle */
    private double orbitAngle = 0.0;

    /** Track which mobs are already targeted by fighter allays (per tick) */
    private final Set<UUID> targetedMobs = new HashSet<>();

    public AllayManager(Plugin plugin, AllayStorage storage, BoundAllayConfig config) {
        this.plugin = plugin;
        this.storage = storage;
        this.config = config;
    }

    public BoundAllayConfig getConfig() {
        return config;
    }

    public AllayStorage getStorage() {
        return storage;
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public int getMaxAllays() {
        return config.maxPerPlayer;
    }

    // ==================== BIND ====================

    /**
     * Bind the nearest wild allay within config.bindRadius blocks to this player with a given name.
     */
    public boolean bind(Player player, String name) {
        if (!checkCooldown(player)) return false;

        if (config.isWorldDisabled(player.getWorld().getName())) {
            msg(player, "Allays are disabled in this world.", NamedTextColor.RED);
            return false;
        }

        int currentCount = storage.count(player.getUniqueId());
        if (currentCount >= config.maxPerPlayer) {
            msg(player, "You already have " + currentCount + "/" + config.maxPerPlayer + " allays. Release one first.", NamedTextColor.RED);
            return false;
        }

        // Sanitize name
        String sanitized = sanitizeName(name);
        if (sanitized == null || sanitized.isEmpty()) {
            msg(player, "Invalid allay name.", NamedTextColor.RED);
            return false;
        }

        if (storage.has(player.getUniqueId(), sanitized)) {
            msg(player, "You already have an allay named '" + sanitized + "'. Pick a different name.", NamedTextColor.RED);
            return false;
        }

        Allay nearest = findNearestWildAllay(player);
        if (nearest == null) {
            msg(player, "No wild allay within " + (int) config.bindRadius + " blocks.", NamedTextColor.RED);
            return false;
        }

        // Snapshot state from the wild allay
        ItemStack heldItem = nearest.getEquipment() != null
                ? nearest.getEquipment().getItemInMainHand()
                : null;
        ItemStack invItem = null;
        if (nearest instanceof InventoryHolder ih && ih.getInventory().getSize() > 0) {
            invItem = ih.getInventory().getItem(0);
        }

        String displayName = nearest.customName() != null ? plainName(nearest) : sanitized;

        AllayData data = new AllayData(sanitized, displayName, serializeItem(heldItem), serializeItem(invItem));
        storage.set(player.getUniqueId(), sanitized, data);
        nearest.remove();

        msg(player, "Allay '" + sanitized + "' bound to you! (" + (currentCount + 1) + "/" + config.maxPerPlayer + ")", NamedTextColor.GREEN);
        return true;
    }

    /** Legacy bind without name - auto generates a name. */
    public boolean bind(Player player) {
        String autoName = generateAutoName(player.getUniqueId());
        return bind(player, autoName);
    }

    private String generateAutoName(UUID owner) {
        Map<String, AllayData> existing = storage.getAll(owner);
        if (existing.isEmpty()) return "Allay";

        for (int i = 2; i <= 100; i++) {
            String candidate = "Allay" + i;
            if (!existing.containsKey(candidate)) return candidate;
        }
        return "Allay" + System.currentTimeMillis();
    }

    private Allay findNearestWildAllay(Player player) {
        Allay best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : player.getNearbyEntities(config.bindRadius, config.bindRadius, config.bindRadius)) {
            if (!(e instanceof Allay a)) continue;
            if (isBoundAllay(a)) continue;
            double d = a.getLocation().distanceSquared(player.getLocation());
            if (d < bestDist) {
                best = a;
                bestDist = d;
            }
        }
        return best;
    }

    // ==================== SUMMON ====================

    /**
     * Summon a specific allay by name for the player.
     */
    public boolean summon(Player player, String allayId) {
        if (!checkCooldown(player)) return false;

        UUID owner = player.getUniqueId();

        if (config.isWorldDisabled(player.getWorld().getName())) {
            msg(player, "Allays are disabled in this world.", NamedTextColor.RED);
            return false;
        }

        AllayData data = storage.get(owner, allayId);
        if (data == null) {
            msg(player, "You have no allay named '" + allayId + "'.", NamedTextColor.RED);
            return false;
        }

        if (data.active && getActiveEntity(owner, allayId) != null) {
            msg(player, "'" + allayId + "' is already summoned.", NamedTextColor.YELLOW);
            return false;
        }

        Location safeLoc = findSafeLocation(player.getLocation());
        if (safeLoc == null) {
            msg(player, "No safe location to spawn the allay.", NamedTextColor.RED);
            return false;
        }

        Entity spawned = safeLoc.getWorld().spawnEntity(safeLoc, EntityType.ALLAY);
        if (!(spawned instanceof Allay allay)) {
            plugin.getLogger().warning("spawnEntity returned non-Allay or null for player " + player.getName());
            if (spawned != null) spawned.remove();
            msg(player, "Failed to spawn allay. Try again.", NamedTextColor.RED);
            return false;
        }

        // Tag the entity
        PersistentDataContainer pdc = allay.getPersistentDataContainer();
        pdc.set(AllayKeys.BOUND_ENTITY, PersistentDataType.BYTE, (byte) 1);
        pdc.set(AllayKeys.OWNER_UUID, PersistentDataType.STRING, owner.toString());
        pdc.set(AllayKeys.ALLAY_NAME, PersistentDataType.STRING, allayId);

        // Restore display name
        String displayName = data.displayName();
        allay.customName(Component.text(displayName).color(NamedTextColor.AQUA));
        allay.setCustomNameVisible(true);

        // Restore held item
        ItemStack held = deserializeItem(data.heldItemB64);
        if (held != null && allay.getEquipment() != null) {
            allay.getEquipment().setItemInMainHand(held);
        }

        // Restore inventory item
        ItemStack invItem = deserializeItem(data.invItemB64);
        if (invItem != null && allay instanceof InventoryHolder ih) {
            ih.getInventory().setItem(0, invItem);
        }

        allay.setPersistent(true);
        // Vanilla AI stays ENABLED - no setAware(false)
        allay.setCanPickupItems(false); // we handle pickup manually for COLLECTOR
        allay.setRemoveWhenFarAway(false);

        // Use pathfinder to initially move toward owner
        if (allay instanceof Mob mob) {
            mob.getPathfinder().moveTo(player.getLocation(), 1.0);
        }

        activeAllays.computeIfAbsent(owner, k -> new ConcurrentHashMap<>()).put(allayId, allay.getUniqueId());
        data.active = true;
        storage.set(owner, allayId, data);

        msg(player, "'" + displayName + "' summoned.", NamedTextColor.GREEN);
        return true;
    }

    /** Legacy summon - summons the first stored allay. */
    public boolean summon(Player player) {
        UUID owner = player.getUniqueId();
        Map<String, AllayData> allays = storage.getAll(owner);
        if (allays.isEmpty()) {
            msg(player, "You have no bound allays. Stand near a wild one and use /allay bind <name>.", NamedTextColor.RED);
            return false;
        }
        // Find first non-active allay
        for (Map.Entry<String, AllayData> entry : allays.entrySet()) {
            if (!entry.getValue().active) {
                return summon(player, entry.getKey());
            }
        }
        msg(player, "All your allays are already summoned.", NamedTextColor.YELLOW);
        return false;
    }

    // ==================== STORE ====================

    /**
     * Store a specific allay by name.
     */
    public boolean store(Player player, String allayId) {
        if (!checkCooldown(player)) return false;

        UUID owner = player.getUniqueId();
        Allay entity = getActiveEntity(owner, allayId);
        if (entity == null) {
            msg(player, "'" + allayId + "' is not currently summoned.", NamedTextColor.RED);
            return false;
        }
        storeEntity(owner, allayId, entity);
        msg(player, "'" + allayId + "' stored safely.", NamedTextColor.GREEN);
        return true;
    }

    /** Legacy store - stores first active allay. */
    public boolean store(Player player) {
        UUID owner = player.getUniqueId();
        Map<String, UUID> active = activeAllays.get(owner);
        if (active == null || active.isEmpty()) {
            msg(player, "You have no summoned allays to store.", NamedTextColor.RED);
            return false;
        }
        // Store first active one
        for (Map.Entry<String, UUID> entry : new HashMap<>(active).entrySet()) {
            Allay entity = getActiveEntity(owner, entry.getKey());
            if (entity != null) {
                return store(player, entry.getKey());
            }
        }
        msg(player, "No active allays found.", NamedTextColor.RED);
        return false;
    }

    /** Capture entity state back to AllayData and remove the entity. */
    public void storeEntity(UUID owner, String allayId, Allay entity) {
        AllayData data = storage.get(owner, allayId);
        if (data == null) {
            data = new AllayData();
            data.id = allayId;
        }

        if (entity.customName() != null) data.name = plainName(entity);
        if (entity.getEquipment() != null) {
            data.heldItemB64 = serializeItem(entity.getEquipment().getItemInMainHand());
        }
        if (entity instanceof InventoryHolder ih && ih.getInventory().getSize() > 0) {
            data.invItemB64 = serializeItem(ih.getInventory().getItem(0));
        }
        data.active = false;
        storage.set(owner, allayId, data);

        entity.remove();
        Map<String, UUID> ownerActive = activeAllays.get(owner);
        if (ownerActive != null) {
            ownerActive.remove(allayId);
            if (ownerActive.isEmpty()) {
                activeAllays.remove(owner);
            }
        }
    }

    // ==================== RELEASE ====================

    /**
     * Permanently release (unbind) an allay. Removes it from storage and despawns if active.
     */
    public boolean release(Player player, String allayId) {
        UUID owner = player.getUniqueId();

        if (!storage.has(owner, allayId)) {
            msg(player, "You have no allay named '" + allayId + "'.", NamedTextColor.RED);
            return false;
        }

        // Despawn if active
        Allay entity = getActiveEntity(owner, allayId);
        if (entity != null) {
            entity.remove();
            Map<String, UUID> ownerActive = activeAllays.get(owner);
            if (ownerActive != null) {
                ownerActive.remove(allayId);
                if (ownerActive.isEmpty()) activeAllays.remove(owner);
            }
        }

        storage.remove(owner, allayId);
        msg(player, "'" + allayId + "' has been released.", NamedTextColor.GOLD);
        return true;
    }

    // ==================== RENAME ====================

    /**
     * Rename an allay. Updates storage key and display name.
     */
    public boolean rename(Player player, String oldId, String newId) {
        UUID owner = player.getUniqueId();

        String sanitized = sanitizeName(newId);
        if (sanitized == null || sanitized.isEmpty()) {
            msg(player, "Invalid name.", NamedTextColor.RED);
            return false;
        }

        if (!storage.has(owner, oldId)) {
            msg(player, "You have no allay named '" + oldId + "'.", NamedTextColor.RED);
            return false;
        }

        if (storage.has(owner, sanitized)) {
            msg(player, "You already have an allay named '" + sanitized + "'.", NamedTextColor.RED);
            return false;
        }

        // Update active tracking if summoned
        Map<String, UUID> ownerActive = activeAllays.get(owner);
        UUID entityUuid = null;
        if (ownerActive != null) {
            entityUuid = ownerActive.remove(oldId);
            if (entityUuid != null) {
                ownerActive.put(sanitized, entityUuid);
                // Update PDC on the live entity
                Entity ent = Bukkit.getEntity(entityUuid);
                if (ent instanceof Allay allay) {
                    allay.getPersistentDataContainer().set(AllayKeys.ALLAY_NAME, PersistentDataType.STRING, sanitized);
                    allay.customName(Component.text(sanitized).color(NamedTextColor.AQUA));
                }
            }
        }

        storage.rename(owner, oldId, sanitized);
        msg(player, "Renamed '" + oldId + "' to '" + sanitized + "'.", NamedTextColor.GREEN);
        return true;
    }

    // ==================== LIST ====================

    /**
     * List all allays for a player.
     */
    public List<AllayData> listAllays(UUID owner) {
        Map<String, AllayData> allays = storage.getAll(owner);
        return new ArrayList<>(allays.values());
    }

    /** Get all allay IDs for a player. */
    public List<String> listAllayIds(UUID owner) {
        return new ArrayList<>(storage.getAll(owner).keySet());
    }

    // ==================== SUMMON ALL / STORE ALL ====================

    /**
     * Summon all stored (non-active) allays for a player.
     */
    public int summonAll(Player player) {
        UUID owner = player.getUniqueId();

        if (config.isWorldDisabled(player.getWorld().getName())) {
            msg(player, "Allays are disabled in this world.", NamedTextColor.RED);
            return 0;
        }

        Map<String, AllayData> allays = storage.getAll(owner);
        int summoned = 0;
        for (Map.Entry<String, AllayData> entry : allays.entrySet()) {
            if (!entry.getValue().active) {
                // Bypass cooldown for bulk summon
                cooldowns.remove(owner);
                if (summon(player, entry.getKey())) {
                    summoned++;
                }
            }
        }
        if (summoned > 0) {
            msg(player, "Summoned " + summoned + " allay(s).", NamedTextColor.GREEN);
        }
        return summoned;
    }

    /**
     * Store all active allays for a player.
     */
    public int storeAll(Player player) {
        UUID owner = player.getUniqueId();
        Map<String, UUID> active = activeAllays.get(owner);
        if (active == null || active.isEmpty()) {
            msg(player, "No active allays to store.", NamedTextColor.RED);
            return 0;
        }

        int stored = 0;
        for (String allayId : new ArrayList<>(active.keySet())) {
            Allay entity = getActiveEntity(owner, allayId);
            if (entity != null) {
                storeEntity(owner, allayId, entity);
                stored++;
            }
        }
        if (stored > 0) {
            msg(player, "Stored " + stored + " allay(s).", NamedTextColor.GREEN);
        }
        return stored;
    }

    // ==================== DEATH / RESPAWN ====================

    public void onBoundAllayDeath(Allay deadAllay) {
        UUID owner = readOwner(deadAllay);
        String allayId = readAllayName(deadAllay);
        if (owner == null || allayId == null) return;

        AllayData data = storage.get(owner, allayId);
        if (data == null) {
            data = new AllayData();
            data.id = allayId;
        }

        if (deadAllay.customName() != null) data.name = plainName(deadAllay);
        if (deadAllay.getEquipment() != null) {
            data.heldItemB64 = serializeItem(deadAllay.getEquipment().getItemInMainHand());
        }
        if (deadAllay instanceof InventoryHolder ih && ih.getInventory().getSize() > 0) {
            data.invItemB64 = serializeItem(ih.getInventory().getItem(0));
        }
        data.active = false;
        storage.set(owner, allayId, data);

        Map<String, UUID> ownerActive = activeAllays.get(owner);
        if (ownerActive != null) {
            ownerActive.remove(allayId);
            if (ownerActive.isEmpty()) activeAllays.remove(owner);
        }

        final String respawnId = allayId;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player ownerPlayer = Bukkit.getPlayer(owner);
            if (ownerPlayer != null && ownerPlayer.isOnline()) {
                cooldowns.remove(owner);
                summon(ownerPlayer, respawnId);
                msg(ownerPlayer, "'" + respawnId + "' has returned.", NamedTextColor.LIGHT_PURPLE);
            }
        }, config.respawnDelayTicks);
    }

    // ==================== FOLLOW TICK ====================

    /**
     * Pathfinder-based follow with circle formation.
     * Allays orbit the player in a slowly rotating circle instead of stacking on center.
     */
    public void tickFollow() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        // Slowly rotate the orbit - about 1 degree per tick call
        orbitAngle += Math.toRadians(1.0);
        if (orbitAngle > Math.PI * 2) orbitAngle -= Math.PI * 2;

        for (Map.Entry<UUID, Map<String, UUID>> ownerEntry : new HashMap<>(activeAllays).entrySet()) {
            UUID ownerId = ownerEntry.getKey();
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner == null || !owner.isOnline()) continue;

            Map<String, UUID> allayMap = ownerEntry.getValue();
            List<String> allayIds = new ArrayList<>(allayMap.keySet());
            int totalAllays = allayIds.size();

            for (int index = 0; index < totalAllays; index++) {
                String allayId = allayIds.get(index);
                UUID entityId = allayMap.get(allayId);
                if (entityId == null) continue;
                Entity ent = Bukkit.getEntity(entityId);

                if (!(ent instanceof Allay allay) || allay.isDead()) {
                    Map<String, UUID> ownerActive = activeAllays.get(ownerId);
                    if (ownerActive != null) {
                        ownerActive.remove(allayId);
                        if (ownerActive.isEmpty()) activeAllays.remove(ownerId);
                    }
                    continue;
                }

                // Skip guardian allays in guard mode (they stay at guard point)
                AllayData followData = storage.get(ownerId, allayId);
                if (followData != null && followData.guardMode
                        && AllayType.fromString(followData.type) == AllayType.GUARDIAN) {
                    continue;
                }

                // Skip if allay is in combat (fighting or collecting)
                if (inCombat.contains(allay.getUniqueId())) continue;

                // Calculate unique offset position in circle formation
                double angle = orbitAngle + (2.0 * Math.PI / totalAllays) * index;
                double offsetX = Math.cos(angle) * 3.0;
                double offsetZ = Math.sin(angle) * 3.0;
                Location targetLoc = owner.getLocation().clone().add(offsetX, 1.0, offsetZ);

                double distSquared = 0;
                boolean sameWorld = allay.getWorld().equals(owner.getWorld());

                if (sameWorld) {
                    distSquared = allay.getLocation().distanceSquared(owner.getLocation());
                }

                double followDistSq = config.followDistance * config.followDistance;

                if (!sameWorld || distSquared > followDistSq) {
                    // Emergency teleport - different world or way too far
                    allay.teleport(targetLoc);
                } else if (distSquared > 25.0) {
                    // 5+ blocks away - use pathfinder to navigate to formation position
                    if (allay instanceof Mob mob) {
                        mob.getPathfinder().moveTo(targetLoc, 1.2);
                    }
                }
                // Within 5 blocks - let vanilla AI handle it naturally
            }
        }
    }

    // ==================== LIFECYCLE ====================

    public void handleOwnerQuit(Player player) {
        UUID owner = player.getUniqueId();
        Map<String, UUID> active = activeAllays.get(owner);
        if (active != null) {
            for (Map.Entry<String, UUID> entry : new HashMap<>(active).entrySet()) {
                Allay entity = getActiveEntity(owner, entry.getKey());
                if (entity != null) {
                    storeEntity(owner, entry.getKey(), entity);
                }
            }
        }
        cooldowns.remove(owner);
    }

    /** Store all active allays across all players (shutdown). */
    public int storeAllActive() {
        int count = 0;
        for (UUID ownerId : new HashSet<>(activeAllays.keySet())) {
            Map<String, UUID> active = activeAllays.get(ownerId);
            if (active == null) continue;
            for (String allayId : new ArrayList<>(active.keySet())) {
                Allay entity = getActiveEntity(ownerId, allayId);
                if (entity != null) {
                    storeEntity(ownerId, allayId, entity);
                    count++;
                }
            }
        }
        return count;
    }

    // ==================== GUI ENTRY POINT ====================

    /**
     * Open the vault menu. Routes to Floodgate form if applicable, otherwise chest GUI.
     */
    public void openVault(Player player) {
        if (config.preferNativeForms && BedrockMenu.shouldUseForm(player.getUniqueId())) {
            BedrockMenu.sendListForm(player, this);
        } else {
            dev.nafis.boundallay.gui.AllayVaultGUI.openList(player, this);
        }
    }

    public void showInfo(Player player, String allayId) {
        AllayData data = storage.get(player.getUniqueId(), allayId);
        if (data == null) {
            msg(player, "No allay named '" + allayId + "'.", NamedTextColor.GRAY);
            return;
        }
        AllayType type = AllayType.fromString(data.type);
        msg(player, "Name: " + data.displayName(), NamedTextColor.WHITE);
        msg(player, "Type: " + type.getDisplayName() + " - " + type.getDescription(), NamedTextColor.WHITE);
        msg(player, "Status: " + (data.active ? "Summoned" : "Stored"), NamedTextColor.WHITE);
        msg(player, "Guard Mode: " + (data.guardMode ? "ON" : "OFF"), NamedTextColor.WHITE);
        msg(player, "Has held item: " + (data.heldItemB64 != null), NamedTextColor.WHITE);
    }

    public void showInfo(Player player) {
        Map<String, AllayData> allays = storage.getAll(player.getUniqueId());
        if (allays.isEmpty()) {
            msg(player, "No bound allays. Stand near a wild one and use /allay bind <name>.", NamedTextColor.GRAY);
            return;
        }
        msg(player, "--- Your Allays (" + allays.size() + "/" + config.maxPerPlayer + ") ---", NamedTextColor.AQUA);
        for (Map.Entry<String, AllayData> entry : allays.entrySet()) {
            AllayData data = entry.getValue();
            String status = data.active ? "[Summoned]" : "[Stored]";
            msg(player, "  " + entry.getKey() + " " + status, data.active ? NamedTextColor.GREEN : NamedTextColor.GRAY);
        }
    }


    // ==================== COMBAT TICK ====================

    /**
     * Called every 10 ticks (0.5s). Runs type-specific behavior for all active allays.
     * Uses tick spreading: each allay only processes every 3rd tick call.
     */
    public void tickCombat() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        tickCounter++;

        // Clear per-tick target tracking
        targetedMobs.clear();

        int allayIndex = 0;
        for (Map.Entry<UUID, Map<String, UUID>> ownerEntry : new HashMap<>(activeAllays).entrySet()) {
            UUID ownerId = ownerEntry.getKey();
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner == null || !owner.isOnline()) continue;

            // Collect hostile mobs ONCE per owner for target distribution
            List<Monster> hostileMobs = null;

            for (Map.Entry<String, UUID> allayEntry : new HashMap<>(ownerEntry.getValue()).entrySet()) {
                String allayId = allayEntry.getKey();
                UUID entityId = allayEntry.getValue();
                Entity ent = Bukkit.getEntity(entityId);

                if (!(ent instanceof Allay allay) || allay.isDead()) continue;

                // Tick spreading: only process every 3rd tick
                if ((allayIndex + tickCounter) % 3 != 0) {
                    allayIndex++;
                    continue;
                }
                allayIndex++;

                AllayData data = storage.get(ownerId, allayId);
                if (data == null) continue;

                AllayType type = AllayType.fromString(data.type);

                switch (type) {
                    case FIGHTER -> {
                        // Lazy-init hostile mob list per owner
                        if (hostileMobs == null) {
                            hostileMobs = collectHostileMobs(owner, config.fighterRange);
                        }
                        tickFighter(allay, owner, hostileMobs);
                    }
                    case HEALER -> tickHealer(allay, owner);
                    case GUARDIAN -> tickGuardian(allay, owner, data);
                    case COLLECTOR -> tickCollector(allay, owner);
                }
            }
        }
    }

    /**
     * Collect all hostile mobs near the owner ONCE, sorted by distance.
     */
    private List<Monster> collectHostileMobs(Player owner, int range) {
        List<Monster> mobs = new ArrayList<>();
        for (Entity e : owner.getNearbyEntities(range, range, range)) {
            if (e instanceof Monster mob && !mob.isDead()) {
                mobs.add(mob);
            }
        }
        // Sort by distance to owner (closest first)
        Location ownerLoc = owner.getLocation();
        mobs.sort(Comparator.comparingDouble(m -> m.getLocation().distanceSquared(ownerLoc)));
        return mobs;
    }

    private void tickFighter(Allay allay, Player owner, List<Monster> hostileMobs) {
        UUID ownerId = owner.getUniqueId();
        AllayData fData = storage.get(ownerId, readAllayName(allay));

        // 1. PvP player targeting: if pvpMode is on and owner was recently attacked
        if (fData != null && fData.pvpMode) {
            UUID attackerUUID = lastAttacker.get(ownerId);
            Long attackTime = lastAttackTime.get(ownerId);
            if (attackerUUID != null && attackTime != null
                    && (System.currentTimeMillis() - attackTime) < 10_000L) {
                Player attacker = Bukkit.getPlayer(attackerUUID);
                if (attacker != null && attacker.isOnline() && !attacker.isDead()
                        && attacker.getWorld().equals(owner.getWorld())
                        && attacker.getLocation().distanceSquared(owner.getLocation())
                           < config.fighterRange * config.fighterRange * 4) {
                    inCombat.add(allay.getUniqueId());
                    Location pLoc = attacker.getLocation();
                    double distToTarget = allay.getLocation().distance(pLoc);

                    // Use pathfinder instead of setVelocity
                    if (distToTarget > 3.0 && allay instanceof Mob mob) {
                        mob.getPathfinder().moveTo(pLoc, 1.5);
                    }

                    // Only damage when naturally close
                    if (distToTarget < 3.0) {
                        attacker.damage(config.fighterDamage, allay);
                        World world = pLoc.getWorld();
                        if (world != null) {
                            world.spawnParticle(Particle.CRIT, pLoc.clone().add(0, 1, 0), 5, 0.3, 0.3, 0.3, 0.1);
                        }
                    }

                    // 2. Allay vs Allay: attack enemy allays nearby
                    for (Map.Entry<UUID, Map<String, UUID>> enemyOwnerEntry : activeAllays.entrySet()) {
                        if (enemyOwnerEntry.getKey().equals(ownerId)) continue;
                        if (!enemyOwnerEntry.getKey().equals(attackerUUID)) continue;
                        for (UUID enemyEntityId : enemyOwnerEntry.getValue().values()) {
                            Entity enemyEnt = Bukkit.getEntity(enemyEntityId);
                            if (enemyEnt instanceof Allay enemyAllay && !enemyAllay.isDead()
                                    && enemyAllay.getWorld().equals(allay.getWorld())
                                    && enemyAllay.getLocation().distanceSquared(allay.getLocation()) < 9.0) {
                                String enemyAllayId = readAllayName(enemyAllay);
                                UUID enemyOwnerId = readOwner(enemyAllay);
                                if (enemyOwnerId != null && enemyAllayId != null) {
                                    AllayData enemyData = storage.get(enemyOwnerId, enemyAllayId);
                                    if (enemyData != null && enemyData.pvpMode) {
                                        enemyAllay.damage(config.fighterDamage, allay);
                                        World ew = enemyAllay.getLocation().getWorld();
                                        if (ew != null) {
                                            ew.spawnParticle(Particle.CRIT, enemyAllay.getLocation().add(0, 0.5, 0), 3, 0.2, 0.2, 0.2, 0.05);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return; // handled PvP target this tick
                }
            }
            // Clear stale attacker
            if (attackTime != null && (System.currentTimeMillis() - attackTime) >= 10_000L) {
                lastAttacker.remove(ownerId);
                lastAttackTime.remove(ownerId);
            }
        }

        // 3. Normal mob targeting - pick a UNIQUE target not already claimed
        Monster target = null;
        for (Monster mob : hostileMobs) {
            if (!targetedMobs.contains(mob.getUniqueId())) {
                target = mob;
                targetedMobs.add(mob.getUniqueId());
                break;
            }
        }
        // If all mobs are taken, share the closest one
        if (target == null && !hostileMobs.isEmpty()) {
            target = hostileMobs.get(0);
        }

        if (target == null) {
            inCombat.remove(allay.getUniqueId());
            return;
        }
        inCombat.add(allay.getUniqueId());

        Location mobLoc = target.getLocation();
        double distToMob = allay.getLocation().distance(mobLoc);

        // Use pathfinder to move toward mob naturally
        if (distToMob > 3.0 && allay instanceof Mob mob) {
            mob.getPathfinder().moveTo(mobLoc, 1.5);
        }

        // Only deal damage when allay is within 3 blocks naturally
        if (distToMob <= 3.0) {
            target.damage(config.fighterDamage, allay);
            World world = mobLoc.getWorld();
            if (world != null) {
                world.spawnParticle(Particle.CRIT, mobLoc.clone().add(0, 1, 0), 5, 0.3, 0.3, 0.3, 0.1);
            }
        }
    }

    private void tickHealer(Allay allay, Player owner) {
        UUID allayUuid = allay.getUniqueId();
        long now = System.currentTimeMillis();
        long intervalMs = config.healerIntervalTicks * 50L;

        Long lastHeal = lastHealTime.get(allayUuid);
        if (lastHeal != null && (now - lastHeal) < intervalMs) return;

        owner.addPotionEffect(new PotionEffect(
                PotionEffectType.REGENERATION,
                config.healerPotionDuration,
                0,
                true,
                true,
                true
        ));

        World world = owner.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.HEART, owner.getLocation().clone().add(0, 2, 0), 5, 0.5, 0.3, 0.5, 0.0);
        }

        lastHealTime.put(allayUuid, now);
    }

    private void tickGuardian(Allay allay, Player owner, AllayData data) {
        if (!data.guardMode || data.guardWorld == null) {
            // Not in guard mode - behave like a fighter
            List<Monster> mobs = collectHostileMobs(owner, config.fighterRange);
            tickFighter(allay, owner, mobs);
            return;
        }

        World guardWorld = Bukkit.getWorld(data.guardWorld);
        if (guardWorld == null) return;

        Location guardLoc = new Location(guardWorld, data.guardX, data.guardY, data.guardZ);

        // Use pathfinder to return to guard point if drifted
        if (!allay.getWorld().equals(guardWorld)) {
            allay.teleport(guardLoc);
        } else {
            double distToGuard = allay.getLocation().distanceSquared(guardLoc);
            if (distToGuard > 9.0 && allay instanceof Mob mob) {
                // More than 3 blocks away - pathfind back
                mob.getPathfinder().moveTo(guardLoc, 1.0);
            }
        }

        // Attack mobs near guard point using pathfinder
        int range = config.guardianRange;
        Monster nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity e : guardWorld.getNearbyEntities(guardLoc, range, range, range)) {
            if (!(e instanceof Monster mob)) continue;
            double dist = mob.getLocation().distanceSquared(guardLoc);
            if (dist < nearestDist) {
                nearest = mob;
                nearestDist = dist;
            }
        }

        if (nearest != null) {
            Location mobLoc = nearest.getLocation();
            double distToMob = allay.getLocation().distance(mobLoc);

            // Use pathfinder to approach the mob
            if (distToMob > 3.0 && allay instanceof Mob mob) {
                mob.getPathfinder().moveTo(mobLoc, 1.5);
            }

            // Only damage when within 3 blocks naturally
            if (distToMob <= 3.0) {
                nearest.damage(config.fighterDamage, allay);
                World world = nearest.getLocation().getWorld();
                if (world != null) {
                    world.spawnParticle(Particle.CRIT, nearest.getLocation().clone().add(0, 1, 0), 5, 0.3, 0.3, 0.3, 0.1);
                }
            }
        }
    }

    private void tickCollector(Allay allay, Player owner) {
        int range = config.collectorRange;

        // Find nearest item
        Item nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity e : allay.getNearbyEntities(range, range, range)) {
            if (!(e instanceof Item item)) continue;
            if (item.isDead() || item.getItemStack().getType().isAir()) continue;
            if (item.getTicksLived() < 40) continue;
            double dist = item.getLocation().distanceSquared(allay.getLocation());
            if (dist < nearestDist) {
                nearest = item;
                nearestDist = dist;
            }
        }
        if (nearest == null) {
            inCombat.remove(allay.getUniqueId());
            return;
        }

        Location itemLoc = nearest.getLocation();
        double distToItem = allay.getLocation().distance(itemLoc);

        if (distToItem > 2.0) {
            // Use pathfinder to move toward item naturally
            if (allay instanceof Mob mob) {
                mob.getPathfinder().moveTo(itemLoc, 1.0);
            }
            inCombat.add(allay.getUniqueId()); // prevent follow teleport while collecting
            return;
        }

        // Allay is close enough - pick up the item
        inCombat.remove(allay.getUniqueId());
        HashMap<Integer, ItemStack> leftover = owner.getInventory().addItem(nearest.getItemStack());
        if (leftover.isEmpty()) {
            nearest.remove();
            World world = itemLoc.getWorld();
            if (world != null) {
                world.spawnParticle(Particle.HAPPY_VILLAGER, itemLoc, 8, 0.3, 0.5, 0.3, 0.0);
            }
        }
    }

    // ==================== TYPE / GUARD MANAGEMENT ====================

    public void setType(Player player, String allayName, AllayType type) {
        UUID owner = player.getUniqueId();
        AllayData data = storage.get(owner, allayName);
        if (data == null) {
            msg(player, "No allay named '" + allayName + "'.", NamedTextColor.RED);
            return;
        }
        data.type = type.name();
        storage.set(owner, allayName, data);
        msg(player, "'" + allayName + "' is now a " + type.getDisplayName() + ".", NamedTextColor.GREEN);
    }

    public void cycleType(Player player, String allayName) {
        UUID owner = player.getUniqueId();
        AllayData data = storage.get(owner, allayName);
        if (data == null) {
            msg(player, "No allay named '" + allayName + "'.", NamedTextColor.RED);
            return;
        }
        AllayType current = AllayType.fromString(data.type);
        AllayType next = current.next();
        data.type = next.name();
        if (next != AllayType.GUARDIAN) {
            data.guardMode = false;
        }
        storage.set(owner, allayName, data);
        msg(player, "'" + allayName + "' is now a " + next.getDisplayName() + ": " + next.getDescription(), NamedTextColor.GREEN);
    }

    public void toggleGuard(Player player, String allayName) {
        UUID owner = player.getUniqueId();
        AllayData data = storage.get(owner, allayName);
        if (data == null) {
            msg(player, "No allay named '" + allayName + "'.", NamedTextColor.RED);
            return;
        }

        AllayType type = AllayType.fromString(data.type);
        if (type != AllayType.GUARDIAN) {
            msg(player, "'" + allayName + "' must be a Guardian to use guard mode. Current type: " + type.getDisplayName(), NamedTextColor.RED);
            return;
        }

        data.guardMode = !data.guardMode;
        if (data.guardMode) {
            Location loc = player.getLocation();
            data.guardX = loc.getX();
            data.guardY = loc.getY();
            data.guardZ = loc.getZ();
            data.guardWorld = loc.getWorld().getName();
            msg(player, "'" + allayName + "' is now guarding this location.", NamedTextColor.GREEN);
        } else {
            msg(player, "'" + allayName + "' stopped guarding. It will follow you now.", NamedTextColor.GOLD);
        }
        storage.set(owner, allayName, data);
    }

    // ==================== PVP ====================

    /**
     * Toggle PvP mode for a specific allay.
     */
    public void togglePvp(Player player, String allayName) {
        UUID owner = player.getUniqueId();
        AllayData data = storage.get(owner, allayName);
        if (data == null) {
            msg(player, "No allay named '" + allayName + "'.", NamedTextColor.RED);
            return;
        }
        data.pvpMode = !data.pvpMode;
        storage.set(owner, allayName, data);
        msg(player, "'" + allayName + "' PvP mode: " + (data.pvpMode ? "ON" : "OFF"),
                data.pvpMode ? NamedTextColor.GREEN : NamedTextColor.GOLD);
    }

    /**
     * Record that a player attacked the owner. Called from AllayListener.
     */
    public void recordAttacker(UUID ownerUUID, UUID attackerUUID) {
        lastAttacker.put(ownerUUID, attackerUUID);
        lastAttackTime.put(ownerUUID, System.currentTimeMillis());
    }

    /** Get lastAttacker map (for listener access). */
    public Map<UUID, UUID> getLastAttackerMap() { return lastAttacker; }
    /** Get lastAttackTime map (for listener access). */
    public Map<UUID, Long> getLastAttackTimeMap() { return lastAttackTime; }

    /** Get the active allays map (for allay-vs-allay lookup). */
    public Map<UUID, Map<String, UUID>> getActiveAllaysMap() { return activeAllays; }

    // ==================== HELPERS ====================

    /** Get a specific active allay entity. */
    public Allay getActiveEntity(UUID owner, String allayId) {
        Map<String, UUID> ownerActive = activeAllays.get(owner);
        if (ownerActive == null) return null;
        UUID entityId = ownerActive.get(allayId);
        if (entityId == null) return null;
        Entity ent = Bukkit.getEntity(entityId);
        if (ent instanceof Allay a && !a.isDead()) return a;
        ownerActive.remove(allayId);
        if (ownerActive.isEmpty()) activeAllays.remove(owner);
        return null;
    }

    /** Get all active entity UUIDs for a player. */
    public Map<String, UUID> getAllActiveEntities(UUID owner) {
        return activeAllays.getOrDefault(owner, Collections.emptyMap());
    }

    public boolean isBoundAllay(Entity entity) {
        return entity.getPersistentDataContainer()
                .has(AllayKeys.BOUND_ENTITY, PersistentDataType.BYTE);
    }

    public UUID readOwner(Entity entity) {
        String s = entity.getPersistentDataContainer()
                .get(AllayKeys.OWNER_UUID, PersistentDataType.STRING);
        if (s == null) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }

    /** Read the allay name/id from an entity's PDC. */
    public String readAllayName(Entity entity) {
        return entity.getPersistentDataContainer()
                .get(AllayKeys.ALLAY_NAME, PersistentDataType.STRING);
    }

    private boolean checkCooldown(Player p) {
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < config.actionCooldownMs) {
            long wait = (config.actionCooldownMs - (now - last)) / 1000 + 1;
            msg(p, "Wait " + wait + "s before doing that again.", NamedTextColor.YELLOW);
            return false;
        }
        cooldowns.put(p.getUniqueId(), now);
        return true;
    }

    /**
     * Find a safe location near the target. Checks the block at feet and above.
     * Returns null if no safe spot found.
     */
    private Location findSafeLocation(Location target) {
        if (target == null || target.getWorld() == null) return null;
        // First try exact location
        if (isSafe(target)) return target;
        // Try nearby offsets
        int[][] offsets = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0},{1,0,1},{-1,0,-1}};
        for (int[] off : offsets) {
            Location check = target.clone().add(off[0], off[1], off[2]);
            if (isSafe(check)) return check;
        }
        // Allays fly, so above is fine
        Location above = target.clone().add(0, 2, 0);
        if (isSafe(above)) return above;
        // Last resort: just use the target
        return target;
    }

    private boolean isSafe(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        Block block = loc.getBlock();
        Block above = block.getRelative(BlockFace.UP);
        return !block.getType().isSolid() || !above.getType().isSolid();
    }

    private String sanitizeName(String name) {
        if (name == null) return null;
        // Strip control chars and limit length
        String cleaned = name.strip().replaceAll("[^a-zA-Z0-9_\\- ]", "");
        if (cleaned.length() > 24) cleaned = cleaned.substring(0, 24);
        return cleaned;
    }

    static String serializeItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    static ItemStack deserializeItem(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
        } catch (Exception e) {
            return null;
        }
    }

    static String plainName(Entity e) {
        Component c = e.customName();
        if (c == null) return null;
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    public static void msg(Player p, String text, NamedTextColor color) {
        p.sendMessage(Component.text("[Allay] ").color(NamedTextColor.DARK_AQUA)
                .append(Component.text(text).color(color)));
    }
}
