package cc.origessentials.binding;

import cc.origessentials.OrigEssentialsPlugin;
import cc.origessentials.util.LogMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 帳號綁定記錄存儲
 *
 * 保存 Web 帳號與 Minecraft UUID 的綁定關係。
 * 綁定關係由後端主導，本機僅作為本地快取及离线支援。
 */
public class BindingRepository implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(BindingRepository.class);
    private static final String TABLE = "account_bindings";
    private static final String VERSION_KEY = "binding_repo_version";
    private static final int SCHEMA_VERSION = 1;

    private final OrigEssentialsPlugin plugin;
    private Connection conn;
    private final ScheduledExecutorService pool;

    public BindingRepository(OrigEssentialsPlugin plugin) throws SQLException {
        this.plugin = plugin;
        this.pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "origessentials-bindings");
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
                        "web_account_id TEXT NOT NULL, " +
                        "minecraft_uuid TEXT NOT NULL, " +
                        "minecraft_username TEXT, " +
                        "bound_at INTEGER NOT NULL, " +
                        "last_verified_at INTEGER, " +
                        "revoked_at INTEGER)");
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_ab_uuid ON " + TABLE + "(minecraft_uuid)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ab_account ON " + TABLE + "(web_account_id)");
                stmt.execute("INSERT OR REPLACE INTO plugin_metadata (key, value) VALUES ('" +
                        VERSION_KEY + "', '" + SCHEMA_VERSION + "')");
                conn.commit();
                LOGGER.info("[OrigEssentials] Account binding schema migrated to v{}", SCHEMA_VERSION);
            }
        }
    }

    /**
     * 新增綁定記錄
     */
    public void addBinding(String webAccountId, String minecraftUuid, String minecraftUsername) {
        pool.execute(() -> {
            try {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO " + TABLE +
                        " (id, web_account_id, minecraft_uuid, minecraft_username, bound_at, last_verified_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)");
                ps.setString(1, java.util.UUID.randomUUID().toString());
                ps.setString(2, webAccountId);
                ps.setString(3, minecraftUuid);
                ps.setString(4, minecraftUsername);
                ps.setLong(5, System.currentTimeMillis());
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
                ps.close();
                LOGGER.info("[OrigEssentials] 新增 UUID 綁定: web={} uuid={}",
                        webAccountId != null ? webAccountId.substring(0, 8) : "null",
                        minecraftUuid.substring(0, 8));
            } catch (SQLException e) {
                LOGGER.warn("[OrigEssentials] 綁定寫入失敗: {}", LogMasker.mask(e.getMessage()));
            }
        });
    }

    /**
     * 取得玩家的綁定 UUID（以 UUID 查）
     */
    public String getWebAccountId(String minecraftUuid) {
        try {
            return pool.submit(() -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT web_account_id FROM " + TABLE +
                        " WHERE minecraft_uuid = ? AND revoked_at IS NULL")) {
                    ps.setString(1, minecraftUuid);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString("web_account_id") : null;
                    }
                }
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("[OrigEssentials] 綁定查詢失敗: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解除綁定
     */
    public void revokeBinding(String minecraftUuid) {
        pool.execute(() -> {
            try {
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE " + TABLE + " SET revoked_at = ? WHERE minecraft_uuid = ? AND revoked_at IS NULL");
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, minecraftUuid);
                ps.executeUpdate();
                ps.close();
            } catch (SQLException e) {
                LOGGER.warn("[OrigEssentials] 解除綁定失敗: {}", e.getMessage());
            }
        });
    }

    /**
     * 取得綁定總數
     */
    public long getBindingCount() {
        try {
            return pool.submit(() -> {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM " + TABLE + " WHERE revoked_at IS NULL")) {
                    return rs.next() ? rs.getLong("cnt") : 0;
                }
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException e) {
            LOGGER.warn("[OrigEssentials] 綁定庫關閉失敗: {}", e.getMessage());
        }
        if (pool != null && !pool.isShutdown()) pool.shutdownNow();
    }
}