package cc.origessentials.locale;

import cc.origessentials.OrigEssentialsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;

/**
 * 語言檔管理器
 *
 * 載入 message.yml，提供帶顏色碼及 placeholder 的訊息轉換。
 * 缺失的 key 回傳預設值，不會拋出例外。
 */
public class LanguageManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(LanguageManager.class);
    private static final String DEFAULT_PREFIX = "&8[&bOrigEssentials&8] ";

    private final OrigEssentialsPlugin plugin;
    private final File messageFile;
    private final FileConfiguration messages;
    private final Map<String, String> cache = new HashMap<>();

    public LanguageManager(OrigEssentialsPlugin plugin) {
        this.plugin = plugin;
        this.messageFile = new File(plugin.getDataFolder(), "message.yml");
        this.messages = YamlConfiguration.loadConfiguration(messageFile);
        loadDefaults();
    }

    private void loadDefaults() {
        if (!messageFile.exists()) {
            try (InputStream is = plugin.getResource("message.yml")) {
                if (is != null) {
                    YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                            new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
                    messages.setDefaults(defaults);
                }
            } catch (IOException e) {
                LOGGER.warn("[OrigEssentials] 無法載入預設語言檔: {}", e.getMessage());
            }
            try {
                Files.createDirectories(messageFile.getParentFile().toPath());
                messages.save(messageFile);
            } catch (IOException e) {
                LOGGER.error("[OrigEssentials] 無法儲存語言檔: {}", e.getMessage());
            }
        }
    }

    public void reload() {
        try {
            messages.load(messageFile);
        } catch (Exception e) {
            LOGGER.error("[OrigEssentials] 讀取語言檔失敗: {}", e.getMessage());
        }
        cache.clear();
    }

    /**
     * 取得翻譯後訊息（支援 & 顏色碼及 {n} placeholder）
     */
    public String get(String key, Object... args) {
        String raw = messages.getString(key);
        if (raw == null) {
            raw = "&c[未知訊息: " + key + "]";
        }
        String result = raw;
        for (int i = 0; i < args.length; i++) {
            result = result.replace("{" + i + "}", String.valueOf(args[i]));
            result = result.replace("%" + (i + 1) + "%", String.valueOf(args[i]));
        }
        return ChatColor.translateAlternateColorCodes('&', result);
    }

    /**
     * 取得字首
     */
    public String getPrefix() {
        return get("prefix", DEFAULT_PREFIX);
    }

    /**
     * 發送訊息給 Sender
     */
    public void send(org.bukkit.command.CommandSender sender, String key, Object... args) {
        sender.sendMessage(getPrefix() + get(key, args));
    }

    /**
     * 發送多行訊息
     */
    public void sendMulti(org.bukkit.command.CommandSender sender, String... keys) {
        for (String key : keys) {
            send(sender, key);
        }
    }

    /**
     * 檢查 key 是否存在
     */
    public boolean has(String key) {
        return messages.contains(key);
    }
}