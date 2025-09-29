package net.lucasdev.trinketssync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class Config {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static Config INSTANCE;

    public String mysqlHost = "127.0.0.1";
    public int mysqlPort = 3306;
    public String mysqlDatabase = "trinkets_sync";
    public String mysqlUser = "trinkets";
    public String mysqlPassword = "password";
    public boolean createTableIfMissing = true;
    public boolean loadOnJoin = true;
    public boolean saveOnQuit = true;
    public int autosaveSeconds = 300;

    public boolean redisEnabled = true;
    public String redisHost = "127.0.0.1";
    public int redisPort = 6379;
    public String redisPassword = "";
    public String redisChannel = "trinkets:sync";

    public int applySecondPassTicks = 100;   // ~5s
    public int applyThirdPassTicks  = 120;   // ~6s
    public int skipSaveMsAfterLoad  = 12000; // 12s

    public String postApplyCleanupRegex = "";

    public static void load() {
        try {
            File dir = new File("config");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "trinkets-sync.json");
            if (f.exists()) {
                try (FileReader r = new FileReader(f)) {
                    INSTANCE = GSON.fromJson(r, Config.class);
                }
            } else {
                INSTANCE = new Config();
                try (FileWriter w = new FileWriter(f)) {
                    GSON.toJson(INSTANCE, w);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config", e);
        }
    }
}
