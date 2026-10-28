package cc.origessentials;

import cc.origessentials.bootstrap.PluginBootstrap;
import cc.origessentials.command.OrigCommand;
import cc.origessentials.config.PluginConfig;
import cc.origessentials.listener.PlCommandListener;
import cc.origessentials.listener.PlayerLifecycleListener;
import cc.origessentials.storage.PlayerRepository;
import cc.origessentials.task.TaskScheduler;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * OrigEssentials 主插件
 *
 * Minecraft 伺服器管理插件，整合 Web 面板、UUID 綁定及遠端工作佇列。
 * 所有外部連線強制 HTTPS，禁用時不建立任何連線。
 */
public class OrigEssentialsPlugin extends JavaPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrigEssentialsPlugin.class);
    private static final String PLUGIN_NAME = "OrigEssentials";
    private static OrigEssentialsPlugin INSTANCE;

    private PluginConfig config;
    private PlayerRepository playerRepository;
    private cc.origessentials.binding.BindingRepository bindingRepository;
    private TaskScheduler taskScheduler;
    private ScheduledExecutorService managementPool;

    @Override
    public void onEnable() {
        INSTANCE = this;

        // 建立必要目錄
        ensureDirectories();

        // 初始化配置
        this.config = new PluginConfig(this);

        LOGGER.info("[{}] 正在啟動 v{} ...", PLUGIN_NAME, getDescription().getVersion());

        // 初始化資料庫
        try {
            this.playerRepository = new PlayerRepository(this);
            this.bindingRepository = new cc.origessentials.binding.BindingRepository(this);
        } catch (Exception e) {
            LOGGER.error("[{}] 資料庫初始化失敗: {}", PLUGIN_NAME, e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 初始化執行緒池
        this.managementPool = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "origessentials-mgmt");
            t.setDaemon(true);
            return t;
        });
        this.taskScheduler = new TaskScheduler(this, managementPool);

        // 註冊指令
        registerCommands();

        // 註冊事件
        getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(this), this);
        getServer().getPluginManager().registerEvents(new PlCommandListener(this), this);

        // 驗證設定
        var issues = config.validate();
        if (!issues.isEmpty()) {
            LOGGER.warn("[{}] 設定警告: {}", PLUGIN_NAME, issues);
        }

        // 若 Web 已啟用，啟動連線
        if (config.isWebEnabled()) {
            taskScheduler.start();
            LOGGER.info("[{}] Web 面板整合已啟用", PLUGIN_NAME);
        } else {
            LOGGER.info("[{}] Web 面板整合已停用，以離線模式運作", PLUGIN_NAME);
        }

        LOGGER.info("[{}] v{} 啟動完成！", PLUGIN_NAME, getDescription().getVersion());
    }

    @Override
    public void onDisable() {
        LOGGER.info("[{}] 正在關閉 ...", PLUGIN_NAME);

        if (taskScheduler != null) taskScheduler.shutdown();
        if (managementPool != null && !managementPool.isShutdown()) managementPool.shutdownNow();
        try {
            if (playerRepository != null) playerRepository.close();
            if (bindingRepository != null) bindingRepository.close();
        } catch (Exception e) {
            LOGGER.warn("[{}] 資料庫關閉時發生錯誤: {}", PLUGIN_NAME, e.getMessage());
        }

        LOGGER.info("[{}] 已關閉。", PLUGIN_NAME);
        INSTANCE = null;
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.createDirectories(new File(getDataFolder(), "logs").toPath());
        } catch (IOException e) {
            LOGGER.warn("[{}] 無法建立資料目錄: {}", PLUGIN_NAME, e.getMessage());
        }
    }

    private void registerCommands() {
        OrigCommand origCommand = new OrigCommand(this);

        var cmds = new String[]{"orig", "origstatus", "origreload", "origpair",
                "origunpair", "origdebug", "origbind", "origweb", "oe"};
        for (String name : cmds) {
            var cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(origCommand);
                cmd.setTabCompleter(origCommand);
            }
        }
    }

    // ── Getter ──────────────────────────────────────────────
    public static OrigEssentialsPlugin getInstance() { return INSTANCE; }
    /** 取得強化版設定管理器 */
    public PluginConfig getPluginConfig() { return config; }
    public PlayerRepository getPlayerRepository() { return playerRepository; }
    public cc.origessentials.binding.BindingRepository getBindingRepository() { return bindingRepository; }
    public TaskScheduler getTaskScheduler() { return taskScheduler; }
    /** 取得 SLF4J Logger（避免與 Bukkit getLogger() 名稱衝突） */
    public org.slf4j.Logger getSlf4jLogger() { return LOGGER; }
}