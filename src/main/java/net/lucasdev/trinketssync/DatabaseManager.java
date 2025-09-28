package net.lucasdev.trinketssync;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.Optional;
import java.util.UUID;

public class DatabaseManager {
    private final Config cfg;
    private HikariDataSource ds;

    public DatabaseManager(Config cfg) {
        this.cfg = cfg;
    }

    public void init() {
        HikariConfig hc = new HikariConfig();
        String jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true",
                cfg.mysqlHost, cfg.mysqlPort, cfg.mysqlDatabase);
        hc.setJdbcUrl(jdbcUrl);
        hc.setUsername(cfg.mysqlUser);
        hc.setPassword(cfg.mysqlPassword);
        hc.setMaximumPoolSize(5);
        ds = new HikariDataSource(hc);

        if (cfg.createTableIfMissing) {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS player_trinkets (" +
                        "uuid CHAR(36) NOT NULL PRIMARY KEY," +
                        "nbt_base64 MEDIUMTEXT NOT NULL," +
                        "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                        ")");
            } catch (SQLException e) {
                throw new RuntimeException("Failed to ensure table", e);
            }
        }
    }

    public void close() { if (ds != null) ds.close(); }

    public Optional<String> load(UUID uuid) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT nbt_base64 FROM player_trinkets WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.ofNullable(rs.getString(1));
            }
        } catch (SQLException e) {
            TrinketsSyncMod.LOGGER.error("[DB] load failed for {}", uuid, e);
        }
        return Optional.empty();
    }

    public void save(UUID uuid, String base64) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO player_trinkets (uuid, nbt_base64) VALUES (?, ?) " +
                             "ON DUPLICATE KEY UPDATE nbt_base64=VALUES(nbt_base64)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, base64);
            ps.executeUpdate();
        } catch (SQLException e) {
            TrinketsSyncMod.LOGGER.error("[DB] save failed for {}", uuid, e);
        }
    }
}
