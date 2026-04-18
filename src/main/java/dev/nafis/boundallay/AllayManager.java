package dev.nafis.boundallay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AllayManager {

    private final Plugin plugin;
    private final AllayStorage storage;
    private final BoundAllayConfig config;

    private final Map<UUID, Map<String, UUID>> activeAllays = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHealerTick = new ConcurrentHashMap<>();

    public AllayManager(Plugin plugin, AllayStorage storage, BoundAllayConfig config) {
        this.plugin = plugin;
        this.storage = storage;
        this.config = config;
    }

    public BoundAllayConfig getConfig() { return config; }
    public AllayStorage getStorage() { return storage; }
    public Plugin getPlugin() { return plugin; }
    public int getMaxAllays() { return config.maxPerPlayer; }

    public Map<UUID, Long> getLastHealerTick() { return lastHealerTick; }

    // ==================== STARTUP SCAN (fixes duplication) ====================

    /**
     * Scan all loaded worlds for bound allays and re-register them to activeAllays.
     * Fixes orphan/duplicate bugs after restart or chunk reload.
     */
    public void scanAndRestoreActive() {
        activeAllays.clear();
        int restored = 0;
        int orphanKilled = 0;

        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (!(e instanceof Allay allay)) continue;
                if (!isBoundAllay(allay)) continue;

                UUID owner = readOwner(allay);
                String allayId = readAllayName(allay);

                if (owner == null || allayId == null) {
                    allay.remove();
                    orphanKilled++;
                    continue;
                }

                AllayData data = storage.get(owner, allayId);
                if (data == null) {
                    allay.remove();
                    orphanKilled++;
                    continue;
                }

                Map<String, UUID> ownerActive = activeAllays.computeIfAbsent(owner, k -> new ConcurrentHashMap<>());
                UUID existing = ownerActive.get(allayId);
                if (existing != null && !existing.equals(allay.getUniqueId())) {
                    Entity prev = Bukkit.getEntity(existing);
                    if (prev != null && !prev.isDead()) prev.remove();
                }
                ownerActive.put(allayId, allay.getUniqueId());
                data.active = true;
                storage.set(owner, allayId, data);
                restored++;
            }
        }

        for (Map.Entry<UUID, Map<String, AllayData>> ownerEntry : storage.all().entrySet()) {
            UUID owner = ownerEntry.getKey();
            Map<String, UUID> registered = activeAllays.getOrDefault(owner, Collections.emptyMap());
            for (AllayData d : ownerEntry.getValue().values()) {
                if (d.active && !registered.containsKey(d.id)) {
                    d.active = false;
                    storage.set(owner, d.id, d);
                }
            }
        }

        plugin.getLogger().info("Entity scan: restored " + restored + " active allays, removed " + orphanKilled + " orphans.");
    }

    // ==================== BIND ====================

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

        ItemStack heldItem = nearest.getEquipment() != null ? nearest.getEquipment().getItemInMainHand() : null;
        ItemStack invItem = null;
        if (nearest instanceof InventoryHolder ih && ih.getInventory().getSize() > 0) {
            invItem = ih.getInventory().getItem(0);
        }

        String displayName = nearest.customName() != null ? plainName(nearest) : sanitized;

        AllayData data = new AllayData(sanitized, displayName, serializeItem(heldItem), serializeItem(invItem), config.defaultType);
        storage.set(player.getUniqueId(), sanitized, data);
        nearest.remove();

        msg(player, "Allay '" + sanitized + "' bound as " + config.defaultType.displayName()
                + " (" + (currentCount + 1) + "/" + config.maxPerPlayer + ")", NamedTextColor.GREEN);
        return true;
    }

    public boolean bind(Player player) {
        return bind(player, generateAutoName(player.getUniqueId()));
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
            if (d < bestDist) { best = a; bestDist = d; }
        }
        return best;
    }

    // ==================== SUMMON ====================

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

        if (data.active) {
            Allay existing = findBoundEntityInWorlds(owner, allayId);
            if (existing != null) {
                activeAllays.computeIfAbsent(owner, k -> new ConcurrentHashMap<>()).put(allayId, existing.getUniqueId());
                msg(player, "'" + data.displayName() + "' is already in the world.", NamedTextColor.YELLOW);
                return false;
            }
            data.active = false;
            storage.set(owner, allayId, data);
        }

        Location safeLoc = findSafeLocation(player.getLocation());
        if (safeLoc == null) {
            msg(player, "No safe location to spawn the allay.", NamedTextColor.RED);
            return false;
        }

        Entity spawned = safeLoc.getWorld().spawnEntity(safeLoc, EntityType.ALLAY);
        if (!(spawned instanceof Allay allay)) {
            if (spawned != null) spawned.remove();
            msg(player, "Failed to spawn allay. Try again.", NamedTextColor.RED);
            return false;
        }

        PersistentDataContainer pdc = allay.getPersistentDataContainer();
        pdc.set(AllayKeys.BOUND_ENTITY, PersistentDataType.BYTE, (byte) 1);
        pdc.set(AllayKeys.OWNER_UUID, PersistentDataType.STRING, owner.toString());
        pdc.set(AllayKeys.ALLAY_NAME, PersistentDataType.STRING, allayId);
        pdc.set(AllayKeys.ALLAY_TYPE, PersistentDataType.STRING, data.getType().name());

        AllayType type = data.getType();
        String displayName = data.displayName() + " " + typeTag(type);
        allay.customName(Component.text(data.displayName()).color(typeColor(type)));
        allay.setCustomNameVisible(true);

        ItemStack held = deserializeItem(data.heldItemB64);
        if (held != null && allay.getEquipment() != null) {
            allay.getEquipment().setItemInMainHand(held);
        }

        ItemStack invItem = deserializeItem(data.invItemB64);
        if (invItem != null && allay instanceof InventoryHolder ih) {
            ih.getInventory().setItem(0, invItem);
        }

        allay.setPersistent(true);
        allay.setRemoveWhenFarAway(false);

        activeAllays.computeIfAbsent(owner, k -> new ConcurrentHashMap<>()).put(allayId, allay.getUniqueId());
        data.active = true;
        storage.set(owner, allayId, data);

        msg(player, "'" + data.displayName() + "' summoned as " + type.displayName() + ".", NamedTextColor.GREEN);
        return true;
    }

    public boolean summon(Player player) {
        UUID owner = player.getUniqueId();
        Map<String, AllayData> allays = storage.getAll(owner);
        if (allays.isEmpty()) {
            msg(player, "You have no bound allays. Stand near a wild one and use /allay bind <name>.", NamedTextColor.RED);
            return false;
        }
        for (Map.Entry<String, AllayData> entry : allays.entrySet()) {
            if (!entry.getValue().active) return summon(player, entry.getKey());
        }
        msg(player, "All your allays are already summoned.", NamedTextColor.YELLOW);
        return false;
    }

    // ==================== STORE ====================

    public boolean store(Player player, String allayId) {
        if (!checkCooldown(player)) return false;

        UUID owner = player.getUniqueId();
        Allay entity = getActiveEntity(owner, allayId);
        if (entity == null) {
            AllayData data = storage.get(owner, allayId);
            if (data != null && data.active) {
                Allay found = findBoundEntityInWorlds(owner, allayId);
                if (found != null) {
                    storeEntity(owner, allayId, found);
                    msg(player, "'" + data.displayName() + "' stored.", NamedTextColor.GREEN);
                    return true;
                }
                data.active = false;
                storage.set(owner, allayId, data);
            }
            msg(player, "'" + allayId + "' is not currently summoned.", NamedTextColor.RED);
            return false;
        }
        storeEntity(owner, allayId, entity);
        msg(player, "'" + allayId + "' stored safely.", NamedTextColor.GREEN);
        return true;
    }

    public boolean store(Player player) {
        UUID owner = player.getUniqueId();
        Map<String, UUID> active = activeAllays.get(owner);
        if (active == null || active.isEmpty()) {
            msg(player, "You have no summoned allays to store.", NamedTextColor.RED);
            return false;
        }
        for (Map.Entry<String, UUID> entry : new HashMap<>(active).entrySet()) {
            return store(player, entry.getKey());
        }
        return false;
    }

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
        removeFromActive(owner, allayId);
        lastHealerTick.remove(entity.getUniqueId());
    }

    // ==================== RELEASE ====================

    public boolean release(Player player, String allayId) {
        UUID owner = player.getUniqueId();

        if (!storage.has(owner, allayId)) {
            msg(player, "You have no allay named '" + allayId + "'.", NamedTextColor.RED);
            return false;
        }

        Allay entity = getActiveEntity(owner, allayId);
        if (entity == null) entity = findBoundEntityInWorlds(owner, allayId);
        if (entity != null) {
            lastHealerTick.remove(entity.getUniqueId());
            entity.remove();
            removeFromActive(owner, allayId);
        }

        storage.remove(owner, allayId);
        msg(player, "'" + allayId + "' has been released.", NamedTextColor.GOLD);
        return true;
    }

    // ==================== RENAME ====================

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

        Map<String, UUID> ownerActive = activeAllays.get(owner);
        if (ownerActive != null) {
            UUID entityUuid = ownerActive.remove(oldId);
            if (entityUuid != null) {
                ownerActive.put(sanitized, entityUuid);
                Entity ent = Bukkit.getEntity(entityUuid);
                if (ent instanceof Allay allay) {
                    allay.getPersistentDataContainer().set(AllayKeys.ALLAY_NAME, PersistentDataType.STRING, sanitized);
                    AllayData d = storage.get(owner, oldId);
                    AllayType t = d != null ? d.getType() : AllayType.FIGHTER;
                    allay.customName(Component.text(sanitized).color(typeColor(t)));
                }
            }
        }

        storage.rename(owner, oldId, sanitized);
        msg(player, "Renamed '" + oldId + "' to '" + sanitized + "'.", NamedTextColor.GREEN);
        return true;
    }

    // ==================== TYPE ====================

    public boolean setType(Player player, String allayId, AllayType newType) {
        UUID owner = player.getUniqueId();
        AllayData data = storage.get(owner, allayId);
        if (data == null) {
            msg(player, "You have no allay named '" + allayId + "'.", NamedTextColor.RED);
            return false;
        }
        data.setType(newType);
        storage.set(owner, allayId, data);

        Allay entity = getActiveEntity(owner, allayId);
        if (entity != null) {
            entity.getPersistentDataContainer().set(AllayKeys.ALLAY_TYPE, PersistentDataType.STRING, newType.name());
            entity.customName(Component.text(data.displayName()).color(typeColor(newType)));
        }
        msg(player, "'" + data.displayName() + "' is now a " + newType.displayName() + ".", NamedTextColor.GREEN);
        return true;
    }

    // ==================== LIST / BULK ====================

    public List<AllayData> listAllays(UUID owner) {
        return new ArrayList<>(storage.getAll(owner).values());
    }

    public List<String> listAllayIds(UUID owner) {
        return new ArrayList<>(storage.getAll(owner).keySet());
    }

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
                cooldowns.remove(owner);
                if (summon(player, entry.getKey())) summoned++;
            }
        }
        if (summoned > 0) msg(player, "Summoned " + summoned + " allay(s).", NamedTextColor.GREEN);
        return summoned;
    }

    public int storeAll(Player player) {
        UUID owner = player.getUniqueId();
        int stored = 0;
        for (Map.Entry<String, AllayData> entry : new HashMap<>(storage.getAll(owner)).entrySet()) {
            if (!entry.getValue().active) continue;
            Allay entity = getActiveEntity(owner, entry.getKey());
            if (entity == null) entity = findBoundEntityInWorlds(owner, entry.getKey());
            if (entity != null) {
                storeEntity(owner, entry.getKey(), entity);
                stored++;
            } else {
                entry.getValue().active = false;
                storage.set(owner, entry.getKey(), entry.getValue());
            }
        }
        if (stored > 0) msg(player, "Stored " + stored + " allay(s).", NamedTextColor.GREEN);
        return stored;
    }

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

        removeFromActive(owner, allayId);
        lastHealerTick.remove(deadAllay.getUniqueId());

        final String respawnId = allayId;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player ownerPlayer = Bukkit.getPlayer(owner);
            if (ownerPlayer != null && ownerPlayer.isOnline()) {
                cooldowns.remove(owner);
                if (summon(ownerPlayer, respawnId)) {
                    msg(ownerPlayer, "'" + respawnId + "' has returned.", NamedTextColor.LIGHT_PURPLE);
                }
            }
        }, config.respawnDelayTicks);
    }

    // ==================== LIFECYCLE ====================

    public void handleOwnerQuit(Player player) {
        UUID owner = player.getUniqueId();
        Map<String, UUID> active = activeAllays.get(owner);
        if (active != null) {
            for (Map.Entry<String, UUID> entry : new HashMap<>(active).entrySet()) {
                Allay entity = getActiveEntity(owner, entry.getKey());
                if (entity != null) storeEntity(owner, entry.getKey(), entity);
            }
        }
        cooldowns.remove(owner);
    }

    // ==================== GUI ====================

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
        msg(player, "Name: " + data.displayName(), NamedTextColor.WHITE);
        msg(player, "Type: " + data.getType().displayName(), NamedTextColor.WHITE);
        msg(player, "Status: " + (data.active ? "Summoned" : "Stored"), NamedTextColor.WHITE);
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
            msg(player, "  " + entry.getKey() + " [" + data.getType().displayName() + "] " + status,
                    data.active ? NamedTextColor.GREEN : NamedTextColor.GRAY);
        }
    }

    // ==================== QUERIES ====================

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

    public Map<String, UUID> getAllActiveEntities(UUID owner) {
        return activeAllays.getOrDefault(owner, Collections.emptyMap());
    }

    public Map<UUID, Map<String, UUID>> getActiveAllaysRaw() {
        return activeAllays;
    }

    public boolean isBoundAllay(Entity entity) {
        return entity.getPersistentDataContainer().has(AllayKeys.BOUND_ENTITY, PersistentDataType.BYTE);
    }

    public UUID readOwner(Entity entity) {
        String s = entity.getPersistentDataContainer().get(AllayKeys.OWNER_UUID, PersistentDataType.STRING);
        if (s == null) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }

    public String readAllayName(Entity entity) {
        return entity.getPersistentDataContainer().get(AllayKeys.ALLAY_NAME, PersistentDataType.STRING);
    }

    public AllayType readAllayType(Entity entity) {
        String t = entity.getPersistentDataContainer().get(AllayKeys.ALLAY_TYPE, PersistentDataType.STRING);
        return AllayType.parse(t, AllayType.FIGHTER);
    }

    /** Search all loaded worlds for a bound allay matching owner + id. */
    public Allay findBoundEntityInWorlds(UUID owner, String allayId) {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (!(e instanceof Allay allay) || allay.isDead()) continue;
                if (!isBoundAllay(allay)) continue;
                UUID ownerOnEntity = readOwner(allay);
                String nameOnEntity = readAllayName(allay);
                if (owner.equals(ownerOnEntity) && allayId.equals(nameOnEntity)) {
                    return allay;
                }
            }
        }
        return null;
    }

    // ==================== HELPERS ====================

    private void removeFromActive(UUID owner, String allayId) {
        Map<String, UUID> ownerActive = activeAllays.get(owner);
        if (ownerActive != null) {
            ownerActive.remove(allayId);
            if (ownerActive.isEmpty()) activeAllays.remove(owner);
        }
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

    private Location findSafeLocation(Location target) {
        if (target == null || target.getWorld() == null) return null;
        Location above = target.clone().add(0, 1.5, 0);
        if (isSafe(above)) return above;
        if (isSafe(target)) return target;
        int[][] offsets = {{1,1,0},{-1,1,0},{0,1,1},{0,1,-1},{0,2,0}};
        for (int[] off : offsets) {
            Location check = target.clone().add(off[0], off[1], off[2]);
            if (isSafe(check)) return check;
        }
        return target.clone().add(0, 2, 0);
    }

    private boolean isSafe(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        Block block = loc.getBlock();
        Block above = block.getRelative(BlockFace.UP);
        return !block.getType().isSolid() && !above.getType().isSolid();
    }

    private String sanitizeName(String name) {
        if (name == null) return null;
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
        try { return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64)); }
        catch (Exception e) { return null; }
    }

    static String plainName(Entity e) {
        Component c = e.customName();
        if (c == null) return null;
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    public static NamedTextColor typeColor(AllayType type) {
        return switch (type) {
            case FIGHTER -> NamedTextColor.RED;
            case HEALER -> NamedTextColor.LIGHT_PURPLE;
            case GUARDIAN -> NamedTextColor.GOLD;
        };
    }

    private static String typeTag(AllayType type) {
        return "[" + type.displayName() + "]";
    }

    public static void msg(Player p, String text, NamedTextColor color) {
        p.sendMessage(Component.text("[Allay] ").color(NamedTextColor.DARK_AQUA)
                .append(Component.text(text).color(color)));
    }
}
