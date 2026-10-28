package cc.origessentials.binding;

import cc.origessentials.OrigEssentialsPlugin;
import cc.origessentials.util.SecureRandomUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * UUID 綁定碼管理
 *
 * 產生一次性、短效驗證碼供玩家綁定 Minecraft UUID 至 Web 帳號。
 * 綁定碼僅儲存於本機 SQLite，不經過網路傳輸。
 * 實際綁定關係由後端維護。
 */
public class BindingCodeManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BindingCodeManager.class);
    private static final String TABLE = "binding_codes";
    private static final String VERSION_KEY = "binding_schema_version";
    private static final int SCHEMA_VERSION = 1;

    private final OrigEssentialsPlugin plugin;
    private Connection conn;
    private final ScheduledExecutorService pool;

    public BindingCodeManager(OrigEssentialsPlugin plugin) throws SQLException {
        this.plugin = plugin;
        this.pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "origessentials-binding");
            t.setDaemon(true);
            return t;
        });
        connect();
        migrate();
    }

    private void connect() throws SQLException {
        String url = "jdbc:sqlite:" + new java.io.File(plugin.getDataFolder(), "data.db").getAbsolutePath();
        this.conn = DriverManager.getConnection(url);
        this.conn.setAutoCommit(true);
    }

    private void migrate() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // 確保 metadata 表存在
            stmt.execute("CREATE TABLE IF NOT EXISTS plugin_metadata (" +
                    "key TEXT PRIMARY KEY, value TEXT NOT NULL)");

            int currentVer = 0;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT value FROM plugin_metadata WHERE key = '" + VERSION_KEY + "'")) {
                if (rs.next()) currentVer = Integer.parseInt(rs.getString("value"));
            }

            if (currentVer < SCHEMA_VERSION) {
                stmt.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                        "id TEXT PRIMARY KEY, " +
                        "code TEXT NOT NULL UNIQUE, " +
                        "minecraft_uuid TEXT NOT NULL, " +
                        "minecraft_username TEXT, " +
                        "created_at INTEGER NOT NULL, " +
                        "expires_at INTEGER NOT NULL, " +
                        "used_at INTEGER, " +
                        "claimed_by_web_account TEXT)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_bc_code ON " + TABLE + "(code)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_bc_expires ON " + TABLE + "(expires_at)");
                stmt.execute("INSERT OR REPLACE INTO plugin_metadata (key, value) VALUES ('" +
                        VERSION_KEY + "', '" + SCHEMA_VERSION + "')");
                conn.commit();
                LOGGER.info("[OrigEssentials] Binding schema migrated to v{}", SCHEMA_VERSION);
            }
        }
    }

    /**
     * 為指定 UUID 產生一次性綁定碼
     */
    public String generateCode(String minecraftUuid, String minecraftUsername) {
        int length = plugin.getPluginConfig().getBindCodeLength();
        int expiryMs = plugin.getPluginConfig().getBindCodeExpirySeconds() * 1000;
        String code = SecureRandomUtil.generateNumericCode(length);
        long now = System.currentTimeMillis();

        pool.execute(() -> {
            try {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + TABLE + " (id, code, minecraft_uuid, minecraft_username, created_at, expires_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)");
                ps.setString(1, SecureRandomUtil.generateUuidRaw());
                ps.setString(2, code);
                ps.setString(3, minecraftUuid);
                ps.setString(4, minecraftUsername);
                ps.setLong(5, now);
                ps.setLong(6, now + expiryMs);
                ps.executeUpdate();
                ps.close();
                LOGGER.info("[OrigEssentials] 產生綁定碼: uuid={}, 剩餘秒數={}",
                        minecraftUuid.substring(0, 8), plugin.getPluginConfig().getBindCodeExpirySeconds());
            } catch (SQLException e) {
                if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                    LOGGER.warn("[OrigEssentials] 重複產生綁定碼（唯一性衝突）");
                } else {
                    LOGGER.warn("[OrigEssentials] 綁定碼寫入失敗: {}", e.getMessage());
                }
            }
        });

        return code;
    }

    /**
     * 驗證並消耗綁定碼
     * @return null 表示無效或已過期，否則回傳 minecraft_uuid
     */
    public String consumeCode(String code) {
        try {
            return pool.submit(() -> {
                try {
                    // 檢查是否存在且未過期
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT minecraft_uuid, expires_at, used_at FROM " + TABLE +
                            " WHERE code = ? AND used_at IS NULL AND expires_at > ?")) {
                        ps.setString(1, code);
                        ps.setLong(2, System.currentTimeMillis());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) return null;
                            long expiresAt = rs.getLong("expires_at");
                            String uuid = rs.getString("minecraft_uuid");

                            // 標記為已使用
                            try (PreparedStatement update = conn.prepareStatement(
                                    "UPDATE " + TABLE + " SET used_at = ? WHERE code = ?")) {
                                update.setLong(1, System.currentTimeMillis());
                                update.setString(2, code);
                                update.executeUpdate();
                            }
                            return uuid;
                        }
                    }
                } catch (SQLException e) {
                    LOGGER.warn("[OrigEssentials] 綁定碼驗證失敗: {}", e.getMessage());
                    return null;
                }
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("[OrigEssentials] 綁定碼查詢逾時: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 清除過期的綁定碼
     */
    public void cleanupExpired() {
        pool.execute(() -> {
            try {
                PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM " + TABLE + " WHERE expires_at < ?");
                ps.setLong(1, System.currentTimeMillis());
                int deleted = ps.executeUpdate();
                ps.close();
                LOGGER.debug("[OrigEssentials] 已清除 {} 筆過期綁定碼", deleted);
            } catch (SQLException e) {
                LOGGER.warn("[OrigEssentials] 清除過期綁定碼失敗: {}", e.getMessage());
            }
        });
    }

    public void close() {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException e) {
            LOGGER.warn("[OrigEssentials] 綁定資料庫關閉失敗: {}", e.getMessage());
        }
        if (pool != null && !pool.isShutdown()) pool.shutdownNow();
    }
}