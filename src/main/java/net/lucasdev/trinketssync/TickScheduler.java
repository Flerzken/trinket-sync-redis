package net.lucasdev.trinketssync;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

public class TickScheduler {
    private static final List<Task> TASKS = new LinkedList<>();

    public static void init() {
        ServerTickEvents.START_SERVER_TICK.register(TickScheduler::tick);
    }

    public static void schedule(MinecraftServer server, int delayTicks, Runnable r) {
        TASKS.add(new Task(server, delayTicks, r));
    }

    private static void tick(MinecraftServer server) {
        Iterator<Task> it = TASKS.iterator();
        while (it.hasNext()) {
            Task t = it.next();
            if (t.server == server) {
                t.ticks--;
                if (t.ticks <= 0) {
                    try { t.r.run(); } finally { it.remove(); }
                }
            }
        }
    }

    private record Task(MinecraftServer server, int ticks, Runnable r) {}
}
