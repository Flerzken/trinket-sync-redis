package net.lucasdev.trinketssync;

import com.google.gson.Gson;
import redis.clients.jedis.*;

import java.util.UUID;
import java.util.function.Consumer;

public class RedisBus {
    public record SyncMessage(UUID uuid, String base64, long updatedAt, String originId) {}

    private final Config cfg;
    private final JedisPool pool;
    private Thread subThread;
    private final Gson gson = new Gson();

    public RedisBus(Config cfg) {
        this.cfg = cfg;
        JedisPoolConfig pc = new JedisPoolConfig();
        pc.setMaxTotal(4);
        if (cfg.redisPassword != null && !cfg.redisPassword.isEmpty()) {
            pool = new JedisPool(pc, cfg.redisHost, cfg.redisPort, 2000, cfg.redisPassword);
        } else {
            pool = new JedisPool(pc, cfg.redisHost, cfg.redisPort);
        }
    }

    public void startSubscribe(Consumer<SyncMessage> consumer) {
        subThread = new Thread(() -> {
            try (Jedis j = pool.getResource()) {
                j.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        try {
                            SyncMessage msg = gson.fromJson(message, SyncMessage.class);
                            consumer.accept(msg);
                        } catch (Exception e) {
                            TrinketsSyncMod.LOGGER.error("[tsync] Redis parse error", e);
                        }
                    }
                }, cfg.redisChannel);
            } catch (Exception e) {
                TrinketsSyncMod.LOGGER.error("[tsync] Redis subscribe error", e);
            }
        }, "TrinketsSync-RedisSub");
        subThread.setDaemon(true);
        subThread.start();
    }

    public void publish(java.util.UUID uuid, String base64, long updatedAt) {
        try (Jedis j = pool.getResource()) {
            String json = gson.toJson(new SyncMessage(uuid, base64, updatedAt, TrinketsSyncMod.SERVER_INSTANCE_ID));
            j.publish(cfg.redisChannel, json);
        } catch (Exception e) {
            TrinketsSyncMod.LOGGER.error("[tsync] Redis publish error", e);
        }
    }

    public void close() {
        try { if (subThread != null) subThread.interrupt(); } catch (Exception ignored) {}
        pool.close();
    }
}
