package dev.nafis.boundallay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe JSON-backed storage with atomic writes and async debounced saving.
 *
 * v3 structure: Map<UUID (owner), Map<String (allay id), AllayData>>
 * Automatically migrates v2 single-allay format on load.
 */
public class AllayStorage {

    private final Path file;
    private final Logger log;
    private final Plugin plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** ownerUUID -> (allayId -> AllayData) */
    private final Map<UUID, Map<String, AllayData>> data = new ConcurrentHashMap<>();

    /** Debounce: marks that a save is pending. */
    private final AtomicBoolean savePending = new AtomicBoolean(false);

    /** Debounce delay in ticks (1 second = 20 ticks). */
    private static final long SAVE_DEBOUNCE_TICKS = 20L;

    public AllayStorage(Path file, Logger log, Plugin plugin) {
        this.file = file;
        this.log = log;
        this.plugin = plugin;
    }

    // ===================== LOAD =====================

    public synchronized void load() {
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = gson.fromJson(reader, JsonObject.class);
            if (root == null) return;

            data.clear();

            // Detect format: v2 has AllayData fields directly, v3 has nested objects
            boolean migrated = false;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                UUID ownerUuid;
                try {
                    ownerUuid = UUID.fromString(entry.getKey());
                } catch (IllegalArgumentException e) {
                    log.warning("Skipping invalid UUID key in allays.json: " + entry.getKey());
                    continue;
                }

                JsonElement value = entry.getValue();
                if (!value.isJsonObject()) continue;
                JsonObject obj = value.getAsJsonObject();

                if (isV2Format(obj)) {
                    // v2 migration: single AllayData directly under UUID
                    AllayData oldData = gson.fromJson(obj, AllayData.class);
                    if (oldData != null) {
                        if (oldData.id == null || oldData.id.isEmpty()) {
                            oldData.id = "default";
                        }
                        Map<String, AllayData> allays = new ConcurrentHashMap<>();
                        allays.put(oldData.id, oldData);
                        data.put(ownerUuid, allays);
                        migrated = true;
                    }
                } else {
                    // v3 format: nested map of allay id -> AllayData
                    Map<String, AllayData> allays = new ConcurrentHashMap<>();
                    for (Map.Entry<String, JsonElement> inner : obj.entrySet()) {
                        AllayData allayData = gson.fromJson(inner.getValue(), AllayData.class);
                        if (allayData != null) {
                            allayData.id = inner.getKey();
                            allays.put(inner.getKey(), allayData);
                        }
                    }
                    if (!allays.isEmpty()) {
                        data.put(ownerUuid, allays);
                    }
                }
            }

            // Reset all active flags on load (server restarted, nothing is in-world)
            for (Map<String, AllayData> allays : data.values()) {
                for (AllayData d : allays.values()) {
                    d.active = false;
                }
            }

            if (migrated) {
                log.info("Migrated v2 allay data to v3 multi-allay format.");
                saveSync();
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to load allays.json", e);
        }
    }

    /**
     * Detect v2 format by checking if the object has AllayData fields directly
     * (like "active", "lastSeen", "heldItemB64") rather than nested allay objects.
     */
    private boolean isV2Format(JsonObject obj) {
        return obj.has("active") || obj.has("lastSeen") || obj.has("heldItemB64");
    }

    // ===================== SAVE =====================

    /** Synchronous save - used for migration and shutdown. */
    public synchronized void saveSync() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                // Convert UUID keys to strings for JSON
                Map<String, Map<String, AllayData>> serializable = new LinkedHashMap<>();
                for (Map.Entry<UUID, Map<String, AllayData>> entry : data.entrySet()) {
                    serializable.put(entry.getKey().toString(), entry.getValue());
                }
                gson.toJson(serializable, w);
            }
            try {
                Files.move(tmp, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ame) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "Failed to save allays.json", e);
        }
    }

    /** Async debounced save. Schedules a save on the main thread after a short delay. */
    public void scheduleSave() {
        if (savePending.compareAndSet(false, true)) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                savePending.set(false);
                saveSync();
            }, SAVE_DEBOUNCE_TICKS);
        }
    }

    // ===================== GETTERS =====================

    /** Get all allays for a player. Returns empty map if none. */
    public Map<String, AllayData> getAll(UUID owner) {
        return data.getOrDefault(owner, Collections.emptyMap());
    }

    /** Get a specific allay by owner + id. */
    public AllayData get(UUID owner, String allayId) {
        Map<String, AllayData> allays = data.get(owner);
        if (allays == null) return null;
        return allays.get(allayId);
    }

    /** Check if a player has any allays. */
    public boolean hasAny(UUID owner) {
        Map<String, AllayData> allays = data.get(owner);
        return allays != null && !allays.isEmpty();
    }

    /** Check if a player has a specific allay. */
    public boolean has(UUID owner, String allayId) {
        Map<String, AllayData> allays = data.get(owner);
        return allays != null && allays.containsKey(allayId);
    }

    /** Get the count of allays a player has. */
    public int count(UUID owner) {
        Map<String, AllayData> allays = data.get(owner);
        return allays == null ? 0 : allays.size();
    }

    // ===================== SETTERS =====================

    /** Set/update a specific allay for a player. */
    public void set(UUID owner, String allayId, AllayData allayData) {
        allayData.lastSeen = System.currentTimeMillis();
        allayData.id = allayId;
        data.computeIfAbsent(owner, k -> new ConcurrentHashMap<>()).put(allayId, allayData);
        scheduleSave();
    }

    /** Remove a specific allay from a player. */
    public void remove(UUID owner, String allayId) {
        Map<String, AllayData> allays = data.get(owner);
        if (allays != null) {
            allays.remove(allayId);
            if (allays.isEmpty()) {
                data.remove(owner);
            }
        }
        scheduleSave();
    }

    /** Remove all allays for a player. */
    public void removeAll(UUID owner) {
        data.remove(owner);
        scheduleSave();
    }

    /** Rename an allay's key. Returns true on success. */
    public boolean rename(UUID owner, String oldId, String newId) {
        Map<String, AllayData> allays = data.get(owner);
        if (allays == null) return false;
        AllayData allayData = allays.remove(oldId);
        if (allayData == null) return false;
        allayData.id = newId;
        allayData.name = newId;
        allays.put(newId, allayData);
        scheduleSave();
        return true;
    }

    // ===================== MAINTENANCE =====================

    /** Prune allays not seen in the given duration. Returns count pruned. */
    public int pruneOlderThan(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        int pruned = 0;
        Iterator<Map.Entry<UUID, Map<String, AllayData>>> outerIt = data.entrySet().iterator();
        while (outerIt.hasNext()) {
            Map.Entry<UUID, Map<String, AllayData>> outerEntry = outerIt.next();
            Map<String, AllayData> allays = outerEntry.getValue();
            Iterator<Map.Entry<String, AllayData>> innerIt = allays.entrySet().iterator();
            while (innerIt.hasNext()) {
                Map.Entry<String, AllayData> innerEntry = innerIt.next();
                if (innerEntry.getValue().lastSeen < cutoff) {
                    innerIt.remove();
                    pruned++;
                }
            }
            if (allays.isEmpty()) {
                outerIt.remove();
            }
        }
        if (pruned > 0) scheduleSave();
        return pruned;
    }

    /** Total number of allay records across all players. */
    public int size() {
        int total = 0;
        for (Map<String, AllayData> allays : data.values()) {
            total += allays.size();
        }
        return total;
    }

    /** Total number of players with at least one allay. */
    public int playerCount() {
        return data.size();
    }

    /** All data for iteration (read-only usage recommended). */
    public Map<UUID, Map<String, AllayData>> all() {
        return data;
    }
}
