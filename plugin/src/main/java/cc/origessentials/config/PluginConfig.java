package cc.origessentials.config;

import cc.origessentials.OrigEssentialsPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.*;

/**
 * 插件配置管理器
 *
 * 讀取並驗證 config.yml 的所有欄位。
 * 所有範例值均為空或安全的預設值，不得填入真實憑證。
 */
public class PluginConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginConfig.class);

    private final File configFile;
    private final FileConfiguration configuration;
    private final OrigEssentialsPlugin plugin;

    // ── 已緩存的設定值 ─────────────────────────────────────
    private boolean webEnabled = false;
    private String apiEndpoint = "";
    private String apiBasePath = "/api/v1";
    private String serverId = "";
    private String pairingKey = "";
    private String apiKey = "";
    private int pollIntervalSeconds = 10;
    private int requestTimeoutMs = 10000;
    private boolean autoReconnect = true;
    private int maxRetries = 5;
    private int backoffMaxSeconds = 300;
    private boolean allowRemoteActions = false;
    private boolean allowPlayerDataRead = false;
    private boolean allowFileManager = false;
    private boolean allowConsoleCommands = false;
    private boolean verifyTls = true;
    private int maxInputLength = 2048;
    private long maxResponseBytes = 1048576L;
    private boolean redactSecretsInLogs = true;
    private boolean rejectInvalidTls = true;
    private boolean allowHttp = false;
    private boolean bindingEnabled = true;
    private int bindCodeLength = 8;
    private int bindCodeExpirySeconds = 300;
    private boolean bindOneTimeUse = true;
    private int maxJobsPerPoll = 5;

    public PluginConfig(OrigEssentialsPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        this.configuration = YamlConfiguration.loadConfiguration(configFile);
        saveDefaults();
        reload();
    }

    /**
     * 儲存預設設定檔（若不存在）
     */
    private void saveDefaults() {
        if (!configFile.exists()) {
            try (InputStream defStream = plugin.getResource("config.yml")) {
                if (defStream != null) {
                    YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                            new java.io.InputStreamReader(defStream, java.nio.charset.StandardCharsets.UTF_8));
                    configuration.setDefaults(defaults);
                }
            } catch (IOException e) {
                LOGGER.warn("[OrigEssentials] 無法載入預設配置: {}", e.getMessage());
            }

            try {
                Files.createDirectories(configFile.getParentFile().toPath());
                configuration.save(configFile);
                LOGGER.info("[OrigEssentials] 已建立預設設定檔: {}", configFile.getAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("[OrigEssentials] 無法儲存設定檔: {}", e.getMessage());
            }
        }
    }

    /**
     * 重新載入所有設定
     */
    public void reload() {
        try {
            configuration.load(configFile);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            LOGGER.error("[OrigEssentials] 讀取設定檔失敗: {}", e.getMessage());
        }

        // general
        webEnabled = getBoolean("web-panel.enabled", false);
        apiEndpoint = getString("web-panel.api-endpoint", "");
        apiBasePath = getString("web-panel.api-base-path", "/api/v1");
        serverId = getString("web-panel.server-id", "");
        pairingKey = getString("web-panel.pairing-key", "");
        apiKey = getString("web-panel.api-key", "");

        // connection
        pollIntervalSeconds = getInt("web-panel.connection.poll-interval", 10);
        requestTimeoutMs = getInt("web-panel.connection.timeout", 10000);
        autoReconnect = getBoolean("web-panel.connection.auto-reconnect", true);
        maxRetries = getInt("web-panel.connection.max-retries", 5);
        backoffMaxSeconds = getInt("web-panel.connection.backoff-max-seconds", 300);

        // security
        allowRemoteActions = getBoolean("web-panel.security.allow-remote-actions", false);
        allowPlayerDataRead = getBoolean("web-panel.security.allow-player-data-read", false);
        allowFileManager = getBoolean("web-panel.security.allow-file-management", false);
        allowConsoleCommands = getBoolean("web-panel.security.allow-console-commands", false);
        verifyTls = getBoolean("web-panel.security.verify-tls", true);

        // input/output limits
        maxInputLength = getInt("security.max-input-length", 2048);
        maxResponseBytes = getLong("security.max-response-bytes", 1048576L);
        redactSecretsInLogs = getBoolean("security.redact-secrets-in-logs", true);
        rejectInvalidTls = getBoolean("security.reject-invalid-tls", true);
        allowHttp = getBoolean("security.allow-http", false);

        // binding
        bindingEnabled = getBoolean("binding.enabled", true);
        bindCodeLength = getInt("binding.code-length", 8);
        bindCodeExpirySeconds = getInt("binding.expiry-seconds", 300);
        bindOneTimeUse = getBoolean("binding.one-time-use", true);

        // polling
        maxJobsPerPoll = getInt("web-panel.polling.max-jobs-per-poll", 5);

        LOGGER.debug("[OrigEssentials] 設定已重新載入");
    }

    /**
     * 儲存設定變更
     */
    public void save() {
        try {
            configuration.save(configFile);
        } catch (IOException e) {
            LOGGER.error("[OrigEssentials] 儲存設定檔失敗: {}", e.getMessage());
        }
    }

    // ── 驗證 ────────────────────────────────────────────────

    /**
     * 驗證設定，回傳問題清單。
     * 若 web-enabled=true 但缺少必要欄位，回傳錯誤提示。
     */
    public List<String> validate() {
        List<String> issues = new ArrayList<>();

        if (webEnabled) {
            if (apiEndpoint.isBlank()) {
                issues.add("web-panel.enabled=true 但 web-panel.api-endpoint 未設定");
            }
            if (serverId.isBlank()) {
                issues.add("web-panel.enabled=true 但 web-panel.server-id 未設定");
            }
            if (!apiEndpoint.isBlank()) {
                try {
                    URI uri = new URI(apiEndpoint);
                    if (!"https".equals(uri.getScheme()) && !allowHttp) {
                        issues.add("web-panel.api-endpoint 必須使用 HTTPS（除非 security.allow-http=true）");
                    }
                } catch (URISyntaxException e) {
                    issues.add("web-panel.api-endpoint 不是合法 URI: " + e.getMessage());
                }
            }
            if (pollIntervalSeconds <= 0) {
                issues.add("web-panel.connection.poll-interval 必須大於 0");
            }
            if (requestTimeoutMs <= 0) {
                issues.add("web-panel.connection.timeout 必須大於 0");
            }
            if (maxJobsPerPoll <= 0) {
                issues.add("web-panel.polling.max-jobs-per-poll 必須大於 0");
            }
            if (maxJobsPerPoll > 20) {
                issues.add("web-panel.polling.max-jobs-per-poll 不應超過 20");
            }
        }

        if (bindCodeLength < 4 || bindCodeLength > 64) {
            issues.add("binding.code-length 必須在 4-64 之間");
        }
        if (bindCodeExpirySeconds <= 0) {
            issues.add("binding.expiry-seconds 必須大於 0");
        }

        return issues;
    }

    // ── Getter ──────────────────────────────────────────────

    public boolean isWebEnabled() { return webEnabled; }
    public String getApiEndpoint() { return apiEndpoint; }
    public String getApiBasePath() { return apiBasePath; }
    public String getServerId() { return serverId; }
    public String getPairingKey() { return pairingKey; }
    public void setPairingKey(String key) { pairingKey = key; configuration.set("web-panel.pairing-key", key); save(); }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String key) { apiKey = key; configuration.set("web-panel.api-key", key); save(); }
    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public boolean isAutoReconnect() { return autoReconnect; }
    public int getMaxRetries() { return maxRetries; }
    public int getBackoffMaxSeconds() { return backoffMaxSeconds; }
    public boolean isAllowRemoteActions() { return allowRemoteActions; }
    public boolean isAllowPlayerDataRead() { return allowPlayerDataRead; }
    public boolean isAllowFileManager() { return allowFileManager; }
    public boolean isAllowConsoleCommands() { return allowConsoleCommands; }
    public boolean isVerifyTls() { return verifyTls; }
    public int getMaxInputLength() { return maxInputLength; }
    public long getMaxResponseBytes() { return maxResponseBytes; }
    public boolean isRedactSecretsInLogs() { return redactSecretsInLogs; }
    public boolean isRejectInvalidTls() { return rejectInvalidTls; }
    public boolean isAllowHttp() { return allowHttp; }
    public boolean isBindingEnabled() { return bindingEnabled; }
    public int getBindCodeLength() { return bindCodeLength; }
    public int getBindCodeExpirySeconds() { return bindCodeExpirySeconds; }
    public boolean isBindOneTimeUse() { return bindOneTimeUse; }
    public int getMaxJobsPerPoll() { return maxJobsPerPoll; }

    public FileConfiguration getRawConfig() { return configuration; }
    public File getConfigFile() { return configFile; }

    /**
     * 清除所有憑證（解除配對後呼叫）
     */
    public void clearCredentials() {
        this.apiKey = "";
        this.pairingKey = "";
        configuration.set("web-panel.api-key", null);
        configuration.set("web-panel.pairing-key", null);
        save();
    }

    /**
     * 檢查是否有有效的配對憑證
     */
    public boolean hasValidCredentials() {
        return !serverId.isBlank() && (apiKey != null && !apiKey.isBlank());
    }

    /**
     * 檢查是否缺少必要設定
     */
    public Map<String, String> getMissingRequiredFields() {
        Map<String, String> missing = new LinkedHashMap<>();
        if (apiEndpoint.isBlank()) missing.put("web-panel.api-endpoint", "未設定 API 位址");
        if (serverId.isBlank()) missing.put("web-panel.server-id", "未設定伺服器 ID");
        return missing;
    }

    // ── 輔助方法 ────────────────────────────────────────────

    private String getString(String path, String defaultValue) {
        String v = configuration.getString(path);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }

    private int getInt(String path, int defaultValue) {
        return configuration.getInt(path, defaultValue);
    }

    private long getLong(String path, long defaultValue) {
        return configuration.getLong(path, defaultValue);
    }

    private boolean getBoolean(String path, boolean defaultValue) {
        return configuration.getBoolean(path, defaultValue);
    }
}