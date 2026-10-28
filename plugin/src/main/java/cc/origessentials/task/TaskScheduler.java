package cc.origessentials.task;

import cc.origessentials.OrigEssentialsPlugin;
import cc.origessentials.config.PluginConfig;
import cc.origessentials.web.WebApiClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 遠端工作排程器
 *
 * 定期輪詢後端取得待執行工作，驗證後在正確執行緒執行。
 * 電源操作由後端 Adapter 處理，插件僅執行遊戲內操作。
 */
public class TaskScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskScheduler.class);

    private final OrigEssentialsPlugin plugin;
    private final ScheduledExecutorService pool;
    private final WebApiClient webClient;
    private final Set<String> processedJobIds = new HashSet<>();
    private volatile boolean running = false;
    private volatile long lastJobProcessed = 0L;

    public TaskScheduler(OrigEssentialsPlugin plugin, ScheduledExecutorService pool) {
        this.plugin = plugin;
        this.pool = pool;
        this.webClient = new WebApiClient(plugin, plugin.getPluginConfig());
    }

    /**
     * 啟動輪詢（若 Web 已啟用）
     */
    public void start() {
        if (!plugin.getPluginConfig().isWebEnabled()) {
            LOGGER.debug("[TaskScheduler] Web 功能未啟用，跳過輪詢");
            return;
        }

        PluginConfig cfg = plugin.getPluginConfig();
        int interval = cfg.getPollIntervalSeconds();
        if (interval <= 0) interval = 10;

        running = true;
        pool.scheduleAtFixedRate(() -> {
            if (!running || !webClient.isConnected()) return;
            try {
                webClient.pollJobs(jobs -> {
                    if (jobs == null || jobs.isEmpty()) return;
                    for (int i = 0; i < jobs.size() && i < cfg.getMaxJobsPerPoll(); i++) {
                        handleJob(jobs.get(i).getAsJsonObject());
                    }
                });
            } catch (Exception e) {
                LOGGER.warn("[TaskScheduler] 輪詢發生錯誤: {}", e.getMessage());
            }
        }, interval, interval, TimeUnit.SECONDS);

        // 心跳每 30 秒
        pool.scheduleAtFixedRate(() -> {
            if (running && webClient.isConnected()) {
                webClient.sendHeartbeat();
            }
        }, 30, 30, TimeUnit.SECONDS);

        LOGGER.info("[TaskScheduler] 輪詢已啟動，間隔 {} 秒", interval);
    }

    /**
     * 處理單一工作
     */
    private void handleJob(JsonObject job) {
        String jobId = job.has("id") ? job.get("id").getAsString() : null;
        String type = job.has("type") ? job.get("type").getAsString() : "unknown";

        if (jobId == null) {
            LOGGER.warn("[TaskScheduler] 收到無 ID 的工作，忽略");
            return;
        }

        // 冪等性檢查
        if (processedJobIds.contains(jobId)) {
            LOGGER.debug("[TaskScheduler] 重複工作 ID {}，跳過", jobId);
            return;
        }

        // 到期檢查
        if (job.has("expiresAt")) {
            long expiresAt = job.get("expiresAt").getAsLong();
            if (expiresAt > 0 && System.currentTimeMillis() > expiresAt) {
                webClient.reportJobResult(jobId, false, "工作已過期");
                return;
            }
        }

        processedJobIds.add(jobId);
        // 限制快取大小
        if (processedJobIds.size() > 1000) processedJobIds.clear();

        LOGGER.info("[TaskScheduler] 收到工作: id={}, type={}", jobId, type);

        switch (type) {
            case "server_status":
                handleStatusJob(jobId, job);
                break;
            case "console_command":
                handleConsoleJob(jobId, job);
                break;
            default:
                LOGGER.warn("[TaskScheduler] 未知工作類型: {}，拒絕執行", type);
                webClient.reportJobResult(jobId, false, "未知工作類型: " + type);
                break;
        }
    }

    private void handleStatusJob(String jobId, JsonObject job) {
        // 狀態檢查是讀取操作，直接回報
        JsonObject result = new JsonObject();
        result.addProperty("online", Bukkit.getServer().getOnlinePlayers().size());
        result.addProperty("maxPlayers", Bukkit.getServer().getMaxPlayers());
        result.addProperty("version", Bukkit.getServer().getVersion());
        webClient.reportJobResult(jobId, true, null);
    }

    private void handleConsoleJob(String jobId, JsonObject job) {
        if (!plugin.getPluginConfig().isAllowConsoleCommands()) {
            webClient.reportJobResult(jobId, false, "主控台指令功能已停用");
            return;
        }

        String command = job.has("payload")
                ? job.getAsJsonObject("payload").get("command").getAsString()
                : "";

        if (command.isBlank()) {
            webClient.reportJobResult(jobId, false, "指令為空");
            return;
        }

        // 限制長度
        if (command.length() > plugin.getPluginConfig().getMaxInputLength()) {
            webClient.reportJobResult(jobId, false, "指令過長");
            return;
        }

        // 在主執行緒執行
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                boolean executed = Bukkit.getServer().dispatchCommand(
                        Bukkit.getServer().getConsoleSender(), command);
                webClient.reportJobResult(jobId, executed, executed ? null : "指令執行失敗");
            } catch (Exception e) {
                webClient.reportJobResult(jobId, false, e.getMessage());
            }
        });
    }

    public void shutdown() {
        running = false;
        processedJobIds.clear();
        webClient.disconnect();
        LOGGER.info("[TaskScheduler] 已停止");
    }

    public WebApiClient getWebClient() { return webClient; }
    public boolean isRunning() { return running; }
}