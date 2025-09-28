package net.lucasdev.trinketssync;

import dev.emi.trinkets.api.TrinketComponent;
import dev.emi.trinkets.api.TrinketsApi;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrinketsService {
    private final DatabaseManager db;
    private long lastAutosaveMs = 0L;
    private final ConcurrentHashMap<UUID, Long> lastAppliedMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastLoadedMs = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    public TrinketsService(DatabaseManager db) { this.db = db; }

    public void shutdown() { io.shutdownNow(); }

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
        TrinketsSyncMod.LOGGER.info("[tsync] Applying NBT for {} (keys={})", player.getGameProfile().getName(), nbt.getSize());
        
// Clear all Trinkets slots before applying to ensure removals are reflected
try {
    var invs = comp.getInventory().entrySet(); // Map<Pair<Group,Slot>, TrinketInventory>
    for (var entry : invs) {
        var tinv = entry.getValue();
        for (int i = 0; i < tinv.size(); i++) {
            tinv.setStack(i, net.minecraft.item.ItemStack.EMPTY);
        }
    }
} catch (Throwable t) {
    TrinketsSyncMod.LOGGER.warn("[tsync] Failed to pre-clear trinket slots for {}", player.getGameProfile().getName(), t);
}
            comp.readFromNbt(nbt, lookup);
        player.getInventory().markDirty();
        player.currentScreenHandler.sendContentUpdates();
    }

    public void saveFor(ServerPlayerEntity player) {
        if (!Config.INSTANCE.saveOnQuit) return;
        // Skip save if very close to a recent load (avoid overwriting fresh state)
        Long t = lastLoadedMs.get(player.getUuid());
        if (t != null && System.currentTimeMillis() - t < 5000) {
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
                String encoded = Base64.getEncoder().encodeToString(out.toByteArray());
                TrinketsSyncMod.LOGGER.info("[tsync] Saving NBT for {} (keys={})", player.getGameProfile().getName(), nbt.getSize());
                db.save(player.getUuid(), encoded);

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
