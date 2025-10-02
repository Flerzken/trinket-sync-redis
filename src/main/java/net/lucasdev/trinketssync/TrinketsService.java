package net.lucasdev.trinketssync;

import dev.emi.trinkets.api.SlotReference;
import dev.emi.trinkets.api.TrinketComponent;
import dev.emi.trinkets.api.TrinketsApi;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Formatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrinketsService {
    private final DatabaseManager db;
    private long lastAutosaveMs = 0L;
    private final ConcurrentHashMap<UUID, Long> lastLoadedMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastLoadedHash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastSavedHash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastAppliedMs = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    public TrinketsService(DatabaseManager db) { this.db = db; }

    public void shutdown() { io.shutdownNow(); }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String currentHash(ServerPlayerEntity player, TrinketComponent comp) throws Exception {
        NbtCompound nbt = new NbtCompound();
        RegistryWrapper.WrapperLookup lookup = (RegistryWrapper.WrapperLookup) player.getRegistryManager();
        comp.writeToNbt(nbt, lookup);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(nbt, out);
        return sha256(out.toByteArray());
    }

    public void loadFor(ServerPlayerEntity player) {
        if (!Config.INSTANCE.loadOnJoin) return;
        io.execute(() -> {
            try {
                Optional<DatabaseManager.Row> row = db.load(player.getUuid());
                if (row.isEmpty()) return;
                player.getServer().execute(() -> {
                    try {
                        applyBase64IfNewer(player, row.get().base64(), row.get().updatedAtMs());
                        lastLoadedMs.put(player.getUuid(), System.currentTimeMillis());
                        byte[] bytes = java.util.Base64.getDecoder().decode(row.get().base64());
                        String hash = sha256(bytes);
                        lastLoadedHash.put(player.getUuid(), hash);
                        lastSavedHash.put(player.getUuid(), hash);
                    } catch (Exception e) {
                        TrinketsSyncMod.LOGGER.error("[tsync] loadFor apply", e);
                    }
                });
            } catch (Exception e) {
                TrinketsSyncMod.LOGGER.error("[tsync] loadFor", e);
            }
        });
    }

    public void applyBase64IfNewer(ServerPlayerEntity player, String base64, long updatedAt) throws Exception {
        TrinketComponent comp = TrinketsApi.getTrinketComponent(player).orElse(null);
        if (comp == null) { TrinketsSyncMod.LOGGER.warn("[tsync] No TrinketComponent for {}", player.getGameProfile().getName()); return; }

        Long last = lastAppliedMs.get(player.getUuid());
        if (last != null && updatedAt <= last) return;
        lastAppliedMs.put(player.getUuid(), updatedAt);

        byte[] bytes = Base64.getDecoder().decode(base64);
        NbtCompound nbt;
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            nbt = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
        }
        RegistryWrapper.WrapperLookup lookup = (RegistryWrapper.WrapperLookup) player.getRegistryManager();

        // Hard clear pre-apply
        try {
            List<?> equipped = comp.getAllEquipped();
            for (Object pair : equipped) {
                Object left = null;
                try { left = pair.getClass().getMethod("getLeft").invoke(pair); }
                catch (NoSuchMethodException e) {
                    try { left = pair.getClass().getMethod("getFirst").invoke(pair); }
                    catch (NoSuchMethodException e2) { /* ignore */ }
                }
                if (left instanceof SlotReference ref) {
                    ref.inventory().setStack(ref.index(), ItemStack.EMPTY);
                }
            }
        } catch (Throwable t) {
            TrinketsSyncMod.LOGGER.warn("[tsync] Failed to pre-clear trinket slots for {}", player.getGameProfile().getName(), t);
        }

        TrinketsSyncMod.LOGGER.info("[tsync] Applying NBT for {} (bytes={})", player.getGameProfile().getName(), bytes.length);
        comp.readFromNbt(nbt, lookup);
        player.getInventory().markDirty();
        player.currentScreenHandler.sendContentUpdates();
    }

    public void saveFor(ServerPlayerEntity player) {
        if (!Config.INSTANCE.saveOnQuit) return;
        io.execute(() -> {
            try {
                TrinketComponent comp = TrinketsApi.getTrinketComponent(player).orElse(null);
                if (comp == null) return;
                String curHash = currentHash(player, comp);
                String lastLoadHash = lastLoadedHash.get(player.getUuid());
                String lastSaveHash = lastSavedHash.get(player.getUuid());

                long now = System.currentTimeMillis();
                Long loadedAt = lastLoadedMs.get(player.getUuid());
                boolean withinGrace = loadedAt != null && (now - loadedAt) < Config.INSTANCE.skipSaveMsAfterLoad;

                if (withinGrace && curHash != null && curHash.equals(lastLoadHash)) {
                    TrinketsSyncMod.LOGGER.info("[tsync] Skipping save (unchanged within grace) for {}", player.getGameProfile().getName());
                    return;
                }
                if (curHash != null && curHash.equals(lastSaveHash)) {
                    TrinketsSyncMod.LOGGER.info("[tsync] Skipping save (duplicate) for {}", player.getGameProfile().getName());
                    return;
                }

                NbtCompound nbt = new NbtCompound();
                RegistryWrapper.WrapperLookup lookup = (RegistryWrapper.WrapperLookup) player.getRegistryManager();
                comp.writeToNbt(nbt, lookup);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                NbtIo.writeCompressed(nbt, out);
                String encoded = Base64.getEncoder().encodeToString(out.toByteArray());

                TrinketsSyncMod.LOGGER.info("[tsync] Saving NBT for {} (changed, bytes={})", player.getGameProfile().getName(), out.size());
                db.save(player.getUuid(), encoded);
                lastSavedHash.put(player.getUuid(), curHash);

                if (Config.INSTANCE.redisEnabled && TrinketsSyncMod.REDIS != null) {
                    TrinketsSyncMod.REDIS.publish(player.getUuid(), encoded, System.currentTimeMillis());
                }
            } catch (Exception e) {
                TrinketsSyncMod.LOGGER.error("[tsync] saveFor", e);
            }
        });
    }

    public void maybeAutosave(net.minecraft.server.MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastAutosaveMs < Config.INSTANCE.autosaveSeconds * 1000L) return;
        lastAutosaveMs = now;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            try { saveFor(p); } catch (Exception e) {
                TrinketsSyncMod.LOGGER.error("[TrinketsSync] Autosave failed for {}", p.getGameProfile().getName(), e);
            }
        }
    }
}
