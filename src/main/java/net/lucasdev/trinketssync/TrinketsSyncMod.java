package net.lucasdev.trinketssync;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TrinketsSyncMod implements ModInitializer {
    public static String SERVER_INSTANCE_ID = java.util.UUID.randomUUID().toString();
    public static final String MOD_ID = "trinketssync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    static DatabaseManager DB;
    static TrinketsService SERVICE;
    static RedisBus REDIS;

    @Override
    public void onInitialize() {
        LOGGER.info("[TrinketsSync] Init (Redis async)");
        Config.load();
        DB = new DatabaseManager(Config.INSTANCE);
        SERVICE = new TrinketsService(DB);

        ServerLifecycleEvents.SERVER_STARTED.register((MinecraftServer server) -> {
            DB.init();
            LOGGER.info("[TrinketsSync] DB initialised");
            if (Config.INSTANCE.redisEnabled) {
                REDIS = new RedisBus(Config.INSTANCE);
                REDIS.startSubscribe(message -> server.execute(() -> {
                    try {
                        ServerPlayerEntity p = server.getPlayerManager().getPlayer(message.uuid());
                        if (p != null && (message.originId() == null || !message.originId().equals(SERVER_INSTANCE_ID))) {
                        SERVICE.applyBase64IfNewer(p, message.base64(), message.updatedAt());
                    }
                    } catch (Exception e) { LOGGER.error("[tsync] Redis apply error", e); }
                }));
                LOGGER.info("[TrinketsSync] Redis subscriber started on {}", Config.INSTANCE.redisChannel);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register((MinecraftServer server) -> {
            SERVICE.shutdown();
            if (REDIS != null) REDIS.close();
            DB.close();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.player;
            try { SERVICE.loadFor(p); } catch (Exception e) { LOGGER.error("[TrinketsSync] Failed initial load for {}", p.getGameProfile().getName(), e); }

            // +1 tick apply
            server.execute(() -> {
                try { SERVICE.loadFor(p); } catch (Exception ignored) {}
            });
            // +20 ticks (~1s) apply
            server.execute(() -> {
                server.execute(() -> {
                    try { SERVICE.loadFor(p); } catch (Exception ignored) {}
                });
            });
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.player;
            try { SERVICE.saveFor(player); } catch (Exception e) { LOGGER.error("[TrinketsSync] Failed save for {}", player.getGameProfile().getName(), e); }
        });

        ServerTickEvents.START_SERVER_TICK.register(server -> SERVICE.maybeAutosave(server));
    }
}
