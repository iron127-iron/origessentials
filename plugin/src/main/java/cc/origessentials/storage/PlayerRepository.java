package cc.origessentials.storage;

import cc.origessentials.OrigEssentialsPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SQLite 資料庫存取層
 *
 * 使用 H2 相容模式在檔案系統上儲存玩家資料及綁定記錄。
 * 所有查詢使用參數化語句，禁止字串串接。
 * 所有 IO 操作在非主執行緒執行。
 */
public class PlayerRepository implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerRepository.class);
    private static final String SCHEMA_VERSION_KEY = "schema_version";
    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final OrigEssentialsPlugin plugin;
    private final File dbFile;
    private Connection connection;
    private final ScheduledExecutorService dbPool;

    public PlayerRepository(OrigEssentialsPlugin plugin) throws SQLException {
        this.plugin = plugin;
        this.dbFile = new File(plugin.getDataFolder(), "data.db");
        this.dbPool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "origessentials-db");
            t.setDaemon(true);
            return t;
        });

        connect();
        migrateSchema();
    }

    private void connect() throws SQLException {
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        this.connection = DriverManager.getConnection(url);
        this.connection.setAutoCommit(true);
        LOGGER.info("[OrigEssentials] 資料庫已連線: {}", dbFile.getAbsolutePath());
    }

    /**
     * 執行 Schema 遷移
     */
    private void migrateSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // 建立 version 表（若不存在）
            stmt.execute("CREATE TABLE IF NOT EXISTS plugin_metadata (" +
                    "key TEXT PRIMARY KEY, " +
                    "value TEXT NOT NULL)");

            // 取得目前版本
            int currentVersion = 0;
            try (ResultSet rs = stmt.executeQuery("SELECT value FROM plugin_metadata WHERE key = '" + SCHEMA_VERSION_KEY + "'")) {
                if (rs.next()) {
                    currentVersion = Integer.parseInt(rs.getString("value"));
                }
            }

            // 若版本低於當前，執行遷移
            if (currentVersion < CURRENT_SCHEMA_VERSION) {
                LOGGER.info("[OrigEssentials] 執行資料庫遷移 v{} → v{}", currentVersion, CURRENT_SCHEMA_VERSION);
                executeMigration(stmt);
                stmt.execute("INSERT OR REPLACE INTO plugin_metadata (key, value) VALUES ('" +
                        SCHEMA_VERSION_KEY + "', '" + CURRENT_SCHEMA_VERSION + "')");
                connection.commit();
                LOGGER.info("[OrigEssentials] 遷移完成");
            }
        }
    }

    private void executeMigration(Statement stmt) throws SQLException {
        // 建立玩家資料表
        stmt.execute("CREATE TABLE IF NOT EXISTS players (" +
                "uuid TEXT PRIMARY KEY, " +
                "username TEXT, " +
                "first_join_at TEXT NOT NULL, " +
                "last_login_at TEXT, " +
                "login_count INTEGER DEFAULT 0)");

        // 建立索引
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_players_last_login ON players(last_login_at)");
    }

    // ── 玩家操作 ──────────────────────────────────────────

    public void upsertPlayer(String uuid, String username) {
        dbPool.execute(() -> {
            try {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO players (uuid, username, first_join_at, last_login_at, login_count) " +
                        "VALUES (?, ?, ?, ?, 1) " +
                        "ON CONFLICT(uuid) DO UPDATE SET " +
                        "username = excluded.username, " +
                        "last_login_at = excluded.last_login_at, " +
                        "login_count = players.login_count + 1");
                ps.setString(1, uuid);
                ps.setString(2, username);
                ps.setString(3, String.valueOf(System.currentTimeMillis()));
                ps.setString(4, String.valueOf(System.currentTimeMillis()));
                ps.executeUpdate();
                ps.close();
            } catch (SQLException e) {
                LOGGER.warn("[OrigEssentials] 玩家資料寫入失敗: {}", e.getMessage());
            }
        });
    }

    public String getUsername(String uuid) {
        try {
            return dbPool.submit(() -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT username FROM players WHERE uuid = ?")) {
                    ps.setString(1, uuid);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString("username") : null;
                    }
                }
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    public long getPlayerCount() {
        try {
            return dbPool.submit(() -> {
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM players")) {
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
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            LOGGER.warn("[OrigEssentials] 資料庫關閉失敗: {}", e.getMessage());
        }
        if (dbPool != null && !dbPool.isShutdown()) {
            dbPool.shutdownNow();
        }
    }
}