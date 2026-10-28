package cc.origessentials.listener;

import cc.origessentials.OrigEssentialsPlugin;
import cc.origessentials.config.PluginConfig;
import cc.origessentials.locale.LanguageManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * /pl 彩蛋指令監聽器
 *
 * 攔截 /pl、/plugins、/bukkit:plugins、/paper:plugins 等別名，
 * 對有權限的執行者顯示自訂訊息。
 * 不會阻礙伺服器正常運作或其他插件的指令功能。
 */
public class PlCommandListener implements Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlCommandListener.class);
    private static final Set<String> PL_ALIASES = new HashSet<>(Arrays.asList(
            "pl", "plugins", "pluginlist"
    ));

    private final OrigEssentialsPlugin plugin;
    private final LanguageManager lang;

    public PlCommandListener(OrigEssentialsPlugin plugin) {
        this.plugin = plugin;
        this.lang = new LanguageManager(plugin);
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        String[] parts = msg.split(" ", 2);
        String cmd = parts[0].toLowerCase();

        // 只處理精確匹配（不含參數的別名）
        if (!PL_ALIASES.contains(cmd.replaceFirst("^/", ""))) return;

        // 若為子命令（如 /paper:plugins），不攔截
        if (msg.contains(":")) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("origessentials.command.pl")) return;

        // 顯示自訂訊息
        event.setCancelled(true);
        player.sendMessage(lang.get("pl.message"));

        // 可選：施加效果
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

        LOGGER.debug("[OrigEssentials] 玩家 {} 執行 /pl", player.getName());
    }
}