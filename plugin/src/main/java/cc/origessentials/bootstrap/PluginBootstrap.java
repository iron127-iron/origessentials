package cc.origessentials.bootstrap;

import cc.origessentials.OrigEssentialsPlugin;
import cc.origessentials.command.OrigCommand;
import cc.origessentials.config.PluginConfig;
import cc.origessentials.listener.PlCommandListener;
import cc.origessentials.listener.PlayerLifecycleListener;
import cc.origessentials.storage.PlayerRepository;
import cc.origessentials.task.TaskScheduler;
import org.bukkit.command.PluginCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * 插件啟動協調器
 *
 * 按順序初始化各模組並註冊指令與監聽器。
 */
public class PluginBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginBootstrap.class);

    private final OrigEssentialsPlugin plugin;
    private final PluginConfig config;
    private final PlayerRepository playerRepo;
    private final TaskScheduler taskScheduler;

    public PluginBootstrap(OrigEssentialsPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getPluginConfig();
        this.playerRepo = plugin.getPlayerRepository();
        this.taskScheduler = plugin.getTaskScheduler();
    }

    /**
     * 註冊所有指令
     */
    public void registerCommands() {
        OrigCommand origCommand = new OrigCommand(plugin);

        // 主指令
        PluginCommand orig = plugin.getCommand("orig");
        if (orig != null) {
            orig.setExecutor(origCommand);
            orig.setTabCompleter(origCommand);
        }

        // 子指令別名
        registerAlias("origstatus", origCommand);
        registerAlias("origreload", origCommand);
        registerAlias("origpair", origCommand);
        registerAlias("origunpair", origCommand);
        registerAlias("origdebug", origCommand);
        registerAlias("origbind", origCommand);
        registerAlias("origweb", origCommand);
        registerAlias("oe", origCommand);
    }

    /**
     * 註冊子指令別名
     */
    private void registerAlias(String name, OrigCommand executor) {
        PluginCommand cmd = plugin.getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
            LOGGER.debug("[Bootstrap] 註冊指令: /{}", name);
        }
    }

    /**
     * 註冊事件監聽器
     */
    public void registerListeners() {
        plugin.getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(plugin), plugin);
        plugin.getServer().getPluginManager().registerEvents(new PlCommandListener(plugin), plugin);
        LOGGER.debug("[Bootstrap] 事件監聽器已註冊");
    }

    /**
     * 初始化 Web 連線（若啟用）
     */
    public void initWeb() {
        if (!config.isWebEnabled()) {
            LOGGER.info("[Bootstrap] Web 面板整合已停用，以離線模式運作。");
            return;
        }

        var issues = config.validate();
        if (!issues.isEmpty()) {
            LOGGER.warn("[Bootstrap] Web 設定有問題，將停用 Web 功能: {}", issues);
            return;
        }

        taskScheduler.start();
        LOGGER.info("[Bootstrap] Web 面板整合已啟用");
    }
}