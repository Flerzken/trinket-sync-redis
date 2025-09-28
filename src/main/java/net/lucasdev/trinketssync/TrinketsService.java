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

public class TrinketsService {
    private final DatabaseManager db;
    private long lastAutosaveMs = 0L;
    private final ConcurrentHashMap<UUID, Long> lastAppliedMs = new ConcurrentHashMap<>();

    public TrinketsService(DatabaseManager db) { this.db = db; }

    public void loadFor(ServerPlayerEntity player) throws Exception {
        if (!Config.INSTANCE.loadOnJoin) return;
        Optional<String> encoded = db.load(player.getUuid());
        if (encoded.isEmpty()) { TrinketsSyncMod.LOGGER.info("[tsync] No DB row for {}", player.getGameProfile().getName()); return; }
        applyBase64IfNewer(player, encoded.get(), System.currentTimeMillis());
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
        comp.readFromNbt(nbt, lookup);
        player.getInventory().markDirty();
        player.currentScreenHandler.sendContentUpdates();
    }

    public void saveFor(ServerPlayerEntity player) throws Exception {
        if (!Config.INSTANCE.saveOnQuit) return;
        TrinketComponent comp = TrinketsApi.getTrinketComponent(player).orElse(null);
        if (comp == null) { TrinketsSyncMod.LOGGER.warn("[tsync] No TrinketComponent for {}", player.getGameProfile().getName()); return; }
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
