package net.lucasdev.trinketssync;

import dev.emi.trinkets.api.SlotReference;
import dev.emi.trinkets.api.TrinketComponent;
import dev.emi.trinkets.api.TrinketsApi;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrinketsService {
    private final DatabaseManager db;
    private long lastAutosaveMs = 0L;
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> lastAppliedMs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> lastLoadedMs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<UUID, String> lastAppliedHash = new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    public TrinketsService(DatabaseManager db) { this.db = db; }

    public void shutdown() { io.shutdownNow(); }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    public void loadFor(ServerPlayerEntity player) {
        if (!Config.INSTANCE.loadOnJoin) return;
        io.execute(() -> {
            try {
                Optional<DatabaseManager.Row> row = db.load(player.getUuid());
                if (row.isEmpty()) return;
                byte[] bytes = Base64.getDecoder().decode(row.get().base64());
                String hash = sha256Hex(bytes);
                player.getServer().execute(() -> {
                    try {
                        applyIfNewerAndDifferent(player, row.get().base64(), bytes, row.get().updatedAtMs(), hash);
                        lastLoadedMs.put(player.getUuid(), System.currentTimeMillis());
                    } catch (Exception e) {
                        TrinketsSyncMod.LOGGER.error("[tsync] loadFor apply", e);
                    }
                });
            } catch (Exception e) {
                TrinketsSyncMod.LOGGER.error("[tsync] loadFor", e);
            }
        });
    }

    private void applyIfNewerAndDifferent(ServerPlayerEntity player, String base64, byte[] bytes, long updatedAt, String hash) throws Exception {
        TrinketComponent comp = TrinketsApi.getTrinketComponent(player).orElse(null);
        if (comp == null) { TrinketsSyncMod.LOGGER.warn("[tsync] No TrinketComponent for {}", player.getGameProfile().getName()); return; }

        Long last = lastAppliedMs.get(player.getUuid());
        if (last != null && updatedAt <= last) return;

        String prevHash = lastAppliedHash.get(player.getUuid());
        if (prevHash != null && prevHash.equals(hash)) return;

        lastAppliedMs.put(player.getUuid(), updatedAt);
        lastAppliedHash.put(player.getUuid(), hash);

        NbtCompound nbt;
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            nbt = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
        }
        RegistryWrapper.WrapperLookup lookup = (RegistryWrapper.WrapperLookup) player.getRegistryManager();

        // Hard clear via getAllEquipped(), but avoid compile issues with Pair type by using reflection
        try {
            List<?> equipped = comp.getAllEquipped();
            for (Object pair : equipped) {
                Object left = null;
                try { left = pair.getClass().getMethod("getLeft").invoke(pair); }
                catch (NoSuchMethodException e) {
                    try { left = pair.getClass().getMethod("getFirst").invoke(pair); }
                    catch (NoSuchMethodException e2) {
                        try { left = pair.getClass().getMethod("getA").invoke(pair); } catch (NoSuchMethodException e3) { /* give up */ }
                    }
                }
                if (left instanceof SlotReference ref) {
                    ref.inventory().setStack(ref.index(), ItemStack.EMPTY);
                }
            }
        } catch (Throwable t) {
            TrinketsSyncMod.LOGGER.warn("[tsync] Failed to pre-clear trinket slots for {}", player.getGameProfile().getName(), t);
        }

        TrinketsSyncMod.LOGGER.info("[tsync] Applying NBT for {} (keys={})", player.getGameProfile().getName(), nbt.getSize());
        comp.readFromNbt(nbt, lookup);
        player.getInventory().markDirty();
        player.currentScreenHandler.sendContentUpdates();
    }

    public void applyBase64IfNewer(ServerPlayerEntity player, String base64, long updatedAt) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(base64);
        String hash = sha256Hex(bytes);
        applyIfNewerAndDifferent(player, base64, bytes, updatedAt, hash);
    }

    public void saveFor(ServerPlayerEntity player) {
        if (!Config.INSTANCE.saveOnQuit) return;
        Long t = lastLoadedMs.get(player.getUuid());
        if (t != null && System.currentTimeMillis() - t < Config.INSTANCE.skipSaveMsAfterLoad) {
            TrinketsSyncMod.LOGGER.info("[tsync] Skipping save (recent load) for {}", player.getGameProfile().getName());
            return;
        }
        io.execute(() -> {
            try {
                TrinketComponent comp = TrinketsApi.getTrinketComponent(player).orElse(null);
                if (comp == null) return;
                NbtCompound nbt = new NbtCompound();
                RegistryWrapper.WrapperLookup lookup = (RegistryWrapper.WrapperLookup) player.getRegistryManager();
                comp.writeToNbt(nbt, lookup);

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                NbtIo.writeCompressed(nbt, out);
                byte[] bytes = out.toByteArray();
                String encoded = Base64.getEncoder().encodeToString(bytes);
                String hash = sha256Hex(bytes);

                TrinketsSyncMod.LOGGER.info("[tsync] Saving NBT for {} (keys={})", player.getGameProfile().getName(), nbt.getSize());
                db.save(player.getUuid(), encoded);
                lastAppliedHash.put(player.getUuid(), hash);

                if (Config.INSTANCE.redisEnabled && TrinketsSyncMod.REDIS != null) {
                    TrinketsSyncMod.REDIS.publish(player.getUuid(), encoded, System.currentTimeMillis());
                }
            } catch (Exception e) {
                TrinketsSyncMod.LOGGER.error("[tsync] saveFor", e);
            }
        });
    }

    public void flushAll(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            try { saveFor(p); } catch (Exception ignored) {}
        }
    }

    public void maybeAutosave(MinecraftServer server) {
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
