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
        String jdbcUrl = String.format(
            "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true" +
            "&tcpKeepAlive=true&connectTimeout=5000&socketTimeout=5000&tcpRcvBuf=65536&tcpSndBuf=65536",
            cfg.mysqlHost, cfg.mysqlPort, cfg.mysqlDatabase);
        hc.setJdbcUrl(jdbcUrl);
        hc.setUsername(cfg.mysqlUser);
        hc.setPassword(cfg.mysqlPassword);
        hc.setMaximumPoolSize(5);
        hc.setConnectionTimeout(5000);
        hc.setValidationTimeout(2000);
        hc.setIdleTimeout(300_000);
        hc.setMaxLifetime(1_200_000);
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

    public static record Row(String base64, long updatedAtMs) {}
public Optional<Row> load(UUID uuid) {
    try (Connection c = ds.getConnection();
         PreparedStatement ps = c.prepareStatement("SELECT nbt_base64, UNIX_TIMESTAMP(updated_at)*1000 FROM player_trinkets WHERE uuid=?")) {
        ps.setString(1, uuid.toString());
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String b64 = rs.getString(1);
                long ts = rs.getLong(2);
                return Optional.of(new Row(b64, ts));
            }
        }
    } catch (SQLException e) {
        TrinketsSyncMod.LOGGER.error("[DB] load failed for {}", uuid, e);
    }
    return Optional.empty();
}

