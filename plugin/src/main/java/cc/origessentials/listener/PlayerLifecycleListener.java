package cc.origessentials.listener;

import cc.origessentials.OrigEssentialsPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家加入/離線事件監聽器
 */
public class PlayerLifecycleListener implements Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerLifecycleListener.class);
    private final OrigEssentialsPlugin plugin;

    public PlayerLifecycleListener(OrigEssentialsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        String uuid = player.getUniqueId().toString();
        String name = player.getName();
        plugin.getPlayerRepository().upsertPlayer(uuid, name);
        LOGGER.debug("[OrigEssentials] 玩家加入: {} ({})", name, uuid.substring(0, 8));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        LOGGER.debug("[OrigEssentials] 玩家離線: {}", event.getPlayer().getName());
    }
}