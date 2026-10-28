package cc.origessentials.command;

import cc.origessentials.OrigEssentialsPlugin;
import cc.origessentials.config.PluginConfig;
import cc.origessentials.locale.LanguageManager;
import cc.origessentials.util.LogMasker;
import cc.origessentials.util.SecureRandomUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * /orig 主指令處理器
 *
 * 子指令：
 *   help       - 說明
 *   version    - 版本資訊
 *   status     - 插件狀態
 *   reload     - 重新載入設定
 *   debug      - 診斷資訊
 *   pair       - 配對 Web 面板
 *   unpair     - 解除配對
 *   bind       - UUID 綁定
 *   web        - Web 狀態及重連
 *   pl         - 彩蛋
 */
public class OrigCommand implements CommandExecutor, TabCompleter {

    private final OrigEssentialsPlugin plugin;
    private final LanguageManager lang;

    public OrigCommand(OrigEssentialsPlugin plugin) {
        this.plugin = plugin;
        this.lang = new LanguageManager(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                              @NotNull String label, @NotNull String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";

        switch (sub) {
            case "help":      return cmdHelp(sender);
            case "version":   return cmdVersion(sender);
            case "status":    return cmdStatus(sender);
            case "reload":    return cmdReload(sender);
            case "debug":     return cmdDebug(sender);
            case "pair":      return cmdPair(sender, args);
            case "unpair":    return cmdUnpair(sender);
            case "bind":      return cmdBind(sender, args);
            case "web":       return cmdWeb(sender, args);
            case "pl":        return cmdPl(sender);
            default:          return cmdHelp(sender);
        }
    }

    // ── help ────────────────────────────────────────────────

    private boolean cmdHelp(CommandSender sender) {
        lang.send(sender, "command.help.title");
        lang.send(sender, "command.help.list_orig");
        lang.send(sender, "command.help.list_version");
        lang.send(sender, "command.help.list_status");
        lang.send(sender, "command.help.list_reload");
        lang.send(sender, "command.help.list_debug");
        lang.send(sender, "command.help.list_pair");
        lang.send(sender, "command.help.list_bind");
        lang.send(sender, "command.help.list_pl");
        if (sender.hasPermission("origessentials.admin")) {
            lang.send(sender, "command.help.list_unpair");
            lang.send(sender, "command.help.list_web");
        }
        return true;
    }

    // ── version ─────────────────────────────────────────────

    private boolean cmdVersion(CommandSender sender) {
        if (!sender.hasPermission("origessentials.command.version")) {
            lang.send(sender, "general.no_permission");
            return true;
        }
        String pv = plugin.getDescription().getVersion();
        String mv = Bukkit.getVersion();
        String jv = System.getProperty("java.version");
        lang.send(sender, "command.version.title");
        lang.send(sender, "command.version.plugin", pv);
        lang.send(sender, "command.version.minecraft", mv);
        lang.send(sender, "command.version.java", jv);
        return true;
    }

    // ── status ──────────────────────────────────────────────

    private boolean cmdStatus(CommandSender sender) {
        if (!sender.hasPermission("origessentials.command.status")) {
            lang.send(sender, "general.no_permission");
            return true;
        }
        PluginConfig cfg = plugin.getPluginConfig();
        var tasks = plugin.getTaskScheduler();
        boolean webOk = tasks.getWebClient().isConnected();
        String webStatus = webOk ? lang.get("web.connected") : lang.get("web.disconnected");
        String lastHB = tasks.getWebClient().getLastHeartbeatAt() > 0
                ? fmtTime(tasks.getWebClient().getLastHeartbeatAt()) : "—";

        lang.send(sender, "command.status.title");
        lang.send(sender, "command.status.plugin", plugin.getDescription().getVersion());
        lang.send(sender, "command.status.web", webStatus);
        lang.send(sender, "command.status.lastHb", lastHB);
        lang.send(sender, "command.status.players",
                String.valueOf(Bukkit.getOnlinePlayers().size()),
                String.valueOf(Bukkit.getMaxPlayers()));
        lang.send(sender, "command.status.bindings",
                String.valueOf(plugin.getBindingRepository().getBindingCount()));
        return true;
    }

    // ── reload ──────────────────────────────────────────────

    private boolean cmdReload(CommandSender sender) {
        if (!sender.hasPermission("origessentials.command.reload")) {
            lang.send(sender, "general.no_permission");
            return true;
        }
        PluginConfig oldCfg = plugin.getPluginConfig();

        // 建立新配置並驗證
        PluginConfig newCfg = new PluginConfig(plugin);
        var issues = newCfg.validate();
        if (!issues.isEmpty()) {
            lang.send(sender, "command.reload.failed");
            plugin.getSlf4jLogger().warn("[OrigEssentials] 重新載入失敗，設定有問題: {}", issues);
            return true;
        }

        // 安全替換
        plugin.getPluginConfig().reload(); // 原地更新
        lang.send(sender, "command.reload.success");
        plugin.getSlf4jLogger().info("[OrigEssentials] 設定已重新載入");

        // 若 Web 狀態改變，重新啟動/停止輪詢
        if (newCfg.isWebEnabled() && !oldCfg.isWebEnabled()) {
            plugin.getTaskScheduler().start();
        } else if (!newCfg.isWebEnabled() && oldCfg.isWebEnabled()) {
            plugin.getTaskScheduler().shutdown();
        }

        return true;
    }

    // ── debug ───────────────────────────────────────────────

    private boolean cmdDebug(CommandSender sender) {
        if (!sender.hasPermission("origessentials.command.debug")) {
            lang.send(sender, "general.no_permission");
            return true;
        }
        PluginConfig cfg = plugin.getPluginConfig();
        var sb = new StringBuilder();
        sb.append(lang.get("command.debug.title")).append("\n");
        sb.append("  插件版本: ").append(plugin.getDescription().getVersion()).append("\n");
        sb.append("  Minecraft: ").append(Bukkit.getVersion()).append("\n");
        sb.append("  Java: ").append(System.getProperty("java.version")).append("\n");
        sb.append("  Web 啟用: ").append(cfg.isWebEnabled()).append("\n");
        sb.append("  API 位址: ").append(maskOrShow(cfg.getApiEndpoint(), cfg.isRedactSecretsInLogs())).append("\n");
        sb.append("  伺服器 ID: ").append(cfg.getServerId().isBlank() ? "未設定" : cfg.getServerId()).append("\n");
        sb.append("  已配對: ").append(cfg.hasValidCredentials()).append("\n");
        sb.append("  TLS 驗證: ").append(cfg.isVerifyTls()).append("\n");
        sb.append("  玩家數: ").append(plugin.getPlayerRepository().getPlayerCount()).append("\n");
        sb.append("  綁定數: ").append(plugin.getBindingRepository().getBindingCount()).append("\n");
        sender.sendMessage(sb.toString());
        return true;
    }

    // ── pair ────────────────────────────────────────────────

    private boolean cmdPair(CommandSender sender, String[] args) {
        if (!sender.hasPermission("origessentials.command.pair")) {
            lang.send(sender, "general.no_permission");
            return true;
        }
        if (args.length < 2) {
            lang.send(sender, "command.pair.usage");
            return true;
        }
        String code = args[1];
        if (code.length() < 4) {
            lang.send(sender, "binding.invalid_code");
            return true;
        }

        lang.send(sender, "command.pair.requesting");
        var wc = plugin.getTaskScheduler().getWebClient();

        wc.pair(code).whenComplete((result, error) -> {
            if (error != null) {
                lang.send(sender, "command.pair.failed", error.getMessage());
                return;
            }
            String key = result.has("credential")
                    ? result.getAsJsonObject("credential").get("token").getAsString()
                    : result.has("apiKey") ? result.get("apiKey").getAsString() : null;

            if (key != null && !key.isBlank()) {
                plugin.getPluginConfig().setApiKey(key);
                wc.markConnected(key);
                lang.send(sender, "command.pair.success");
                plugin.getSlf4jLogger().info("[OrigEssentials] 配對成功，伺服器 ID: {}", plugin.getPluginConfig().getServerId());
            } else {
                lang.send(sender, "command.pair.noCredential");
            }
        });

        return true;
    }

    // ── unpair ──────────────────────────────────────────────

    private boolean cmdUnpair(CommandSender sender) {
        if (!sender.hasPermission("origessentials.command.unpair")) {
            lang.send(sender, "general.no_permission");
            return true;
        }
        var wc = plugin.getTaskScheduler().getWebClient();
        if (!wc.isConnected()) {
            lang.send(sender, "unpair.notPaired");
            return true;
        }
        plugin.getPluginConfig().clearCredentials();
        wc.disconnect();
        lang.send(sender, "unpair.success");
        return true;
    }

    // ── bind ────────────────────────────────────────────────

    private boolean cmdBind(CommandSender sender, String[] args) {
        if (!sender.hasPermission("origessentials.command.bind")) {
            lang.send(sender, "general.no_permission");
            return true;
        }
        if (!(sender instanceof Player player)) {
            lang.send(sender, "general.player_only");
            return true;
        }
        if (!plugin.getPluginConfig().isBindingEnabled()) {
            lang.send(sender, "binding.disabled");
            return true;
        }

        if (args.length < 2) {
            lang.send(sender, "command.bind.usage");
            return true;
        }

        String sub = args[1].toLowerCase();
        switch (sub) {
            case "confirm":
                if (args.length < 3) {
                    lang.send(sender, "command.bind.usage");
                    return true;
                }
                cmdBindConfirm(player, args[2]);
                break;
            case "status":
                cmdBindStatus(player);
                break;
            case "unbind":
                cmdBindUnbind(player);
                break;
            default:
                lang.send(sender, "command.bind.usage");
                break;
        }
        return true;
    }

    private void cmdBindConfirm(Player player, String code) {
        // 1. 在本機綁定碼表中查找
        String uuid = plugin.getBindingRepository().getWebAccountId(player.getUniqueId().toString());
        if (uuid != null) {
            lang.send(player, "binding.already_bound");
            return;
        }

        // 2. 後端驗證綁定碼（透過 Web API）
        lang.send(player, "binding.validating");

        // TODO: 後端 API 未就緒時，暫不執行實際驗證
        // 此處僅記錄意圖，實際驗證需等待後端部署
        plugin.getSlf4jLogger().info("[OrigEssentials] 玩家 {} 嘗試綁定，代碼: {}",
                player.getName(), SecureRandomUtil.mask(code));

        lang.send(player, "binding.notImplemented");
    }

    private void cmdBindStatus(Player player) {
        String bound = plugin.getBindingRepository().getWebAccountId(player.getUniqueId().toString());
        if (bound != null) {
            lang.send(player, "binding.status.bound", bound.substring(0, Math.min(8, bound.length())));
        } else {
            lang.send(player, "binding.status.unbound");
        }
    }

    private void cmdBindUnbind(Player player) {
        plugin.getBindingRepository().revokeBinding(player.getUniqueId().toString());
        lang.send(player, "binding.unbound");
    }

    // ── web ─────────────────────────────────────────────────

    private boolean cmdWeb(CommandSender sender, String[] args) {
        if (!sender.hasPermission("origessentials.command.web")) {
            lang.send(sender, "general.no_permission");
            return true;
        }
        var wc = plugin.getTaskScheduler().getWebClient();
        if (args.length >= 2 && "reconnect".equalsIgnoreCase(args[1])) {
            lang.send(sender, "web.reconnecting");
            wc.disconnect();
            // 重新連線需要新配對
            lang.send(sender, "web.pairing_required");
            return true;
        }
        lang.send(sender, "command.web.status");
        lang.send(sender, "web.connected".equals(wc.getLastError())
                ? "web.connected" : "web.disconnected");
        return true;
    }

    // ── pl ──────────────────────────────────────────────────

    private boolean cmdPl(CommandSender sender) {
        if (!sender.hasPermission("origessentials.command.pl")) {
            lang.send(sender, "general.no_permission");
            return true;
        }
        if (sender instanceof Player player) {
            player.sendMessage(lang.get("pl.message"));
            PluginConfig cfg = plugin.getPluginConfig();
            var plCfg = cfg.getRawConfig();
            if (plCfg.getBoolean("commands.pl.effects.blindness.enabled", false)) {
                int dur = plCfg.getInt("commands.pl.effects.blindness.duration-ticks", 40);
                int amp = plCfg.getInt("commands.pl.effects.blindness.amplifier", 0);
                player.addPotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS.createEffect(dur, amp));
            }
            if (plCfg.getBoolean("commands.pl.effects.slowness.enabled", false)) {
                int dur = plCfg.getInt("commands.pl.effects.slowness.duration-ticks", 40);
                int amp = plCfg.getInt("commands.pl.effects.slowness.amplifier", 0);
                player.addPotionEffect(org.bukkit.potion.PotionEffectType.SLOW.createEffect(dur, amp));
            }
        } else {
            sender.sendMessage(lang.get("pl.message"));
        }
        return true;
    }

    // ── Tab 補全 ────────────────────────────────────────────

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = Arrays.asList("help","version","status","reload","debug","pair","unpair","bind","web","pl");
            return filterStart(subs, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if ("pair".equals(sub)) return null;
            if ("bind".equals(sub)) return filterStart(Arrays.asList("confirm","status","unbind"), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filterStart(List<String> list, String prefix) {
        List<String> result = new ArrayList<>();
        for (String s : list) {
            if (s.startsWith(prefix.toLowerCase())) result.add(s);
        }
        return result;
    }

    // ── 輔助 ────────────────────────────────────────────────

    private String maskOrShow(String value, boolean redact) {
        if (!redact || value.isBlank()) return value;
        return value.length() > 8 ? value.substring(0, 4) + "****" : "****";
    }

    private String fmtTime(long millis) {
        long secs = (System.currentTimeMillis() - millis) / 1000;
        if (secs < 60) return secs + "s ago";
        if (secs < 3600) return (secs / 60) + "m ago";
        return (secs / 3600) + "h ago";
    }
}