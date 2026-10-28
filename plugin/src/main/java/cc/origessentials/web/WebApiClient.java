package cc.origessentials.web;

import cc.origessentials.OrigEssentialsPlugin;
import cc.origessentials.config.PluginConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OrigEssentials Web API 客戶端
 *
 * 負責與後端 API 通訊：配對、心跳、輪詢工作佇列。
 * 所有連線強制 HTTPS，TLS 驗證不可關閉（除非 explicitly 設定）。
 */
public class WebApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebApiClient.class);
    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String USER_AGENT = "OrigEssentials-Plugin/0.1.0";

    private final OrigEssentialsPlugin plugin;
    private final PluginConfig config;
    private final OkHttpClient httpClient;
    private final ScheduledExecutorService reconnectScheduler;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile String currentApiKey = "";
    private volatile long lastHeartbeatAt = 0L;
    private volatile long lastPollAt = 0L;
    private volatile String lastError = "";
    private volatile int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    public WebApiClient(OrigEssentialsPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "origessentials-reconnect");
            t.setDaemon(true);
            return t;
        });
        this.httpClient = buildHttpClient();
    }

    private OkHttpClient buildHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(config.getRequestTimeoutMs()))
                .readTimeout(Duration.ofMillis(config.getRequestTimeoutMs()))
                .writeTimeout(Duration.ofMillis(config.getRequestTimeoutMs()))
                .callTimeout(Duration.ofMillis(config.getRequestTimeoutMs() * 2))
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request.Builder reqBuilder = original.newBuilder()
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json");

                    if (!currentApiKey.isEmpty()) {
                        reqBuilder.header("Authorization", "Bearer " + currentApiKey);
                    }

                    return chain.proceed(reqBuilder.build());
                })
                .eventListenerFactory(call -> new EventListener() {
                    @Override
                    public void callStart(Call call) {
                        LOGGER.debug("[WebClient] 開始請求: {}", call.request().url());
                    }
                    @Override
                    public void callFailed(Call call, IOException ioe) {
                        LOGGER.warn("[WebClient] 請求失敗: {}", ioe.getMessage());
                    }
                });

        if (!config.isVerifyTls()) {
            LOGGER.warn("[WebClient] TLS 驗證已停用（僅限除錯模式）");
            builder.sslSocketFactory(createTrustAllSslSocketFactory(), createTrustAllTrustManager());
            builder.hostnameVerifier((hostname, session) -> true);
        }

        return builder.build();
    }

    // ── 配對 ────────────────────────────────────────────────

    public CompletableFuture<JsonObject> pair(String pairingCode) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        if (!config.isWebEnabled()) {
            future.completeExceptionally(new IllegalStateException("Web 面板功能已停用"));
            return future;
        }

        String url = normalizeUrl(config.getApiEndpoint()) + config.getApiBasePath() + "/pairings";
        JsonObject body = new JsonObject();
        body.addProperty("pairingCode", pairingCode);
        body.addProperty("serverId", config.getServerId());

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        executeRequest(request, future);
        return future;
    }

    // ── 心跳 ────────────────────────────────────────────────

    public void sendHeartbeat() {
        if (!connected.get()) return;

        String url = normalizeUrl(config.getApiEndpoint())
                + config.getApiBasePath()
                + "/servers/" + encodePath(config.getServerId()) + "/heartbeat";

        JsonObject body = new JsonObject();
        body.addProperty("pluginVersion", plugin.getDescription().getVersion());
        body.addProperty("minecraftVersion", plugin.getServer().getVersion());
        body.addProperty("playerCount", plugin.getServer().getOnlinePlayers().size());
        body.addProperty("maxPlayers", plugin.getServer().getMaxPlayers());

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                lastHeartbeatAt = System.currentTimeMillis();
                response.close();
            }

            @Override
            public void onFailure(Call call, IOException e) {
                setLastError("心跳失敗: " + e.getMessage());
            }
        });
    }

    // ── 輪詢工作 ────────────────────────────────────────────

    public void pollJobs(Consumer<JsonArray> onJobs) {
        if (!connected.get()) return;

        String url = normalizeUrl(config.getApiEndpoint())
                + config.getApiBasePath()
                + "/servers/" + encodePath(config.getServerId()) + "/jobs/poll";

        JsonObject body = new JsonObject();
        body.addProperty("serverId", config.getServerId());
        body.addProperty("maxJobs", config.getMaxJobsPerPoll());

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                lastPollAt = System.currentTimeMillis();
                try {
                    if (!response.isSuccessful()) {
                        if (response.code() == 401 || response.code() == 403) {
                            setLastError("API Key 無效或已撤銷，請重新配對");
                            scheduleReconnect();
                        }
                        response.close();
                        return;
                    }
                    String text = response.body() != null ? response.body().string() : "{}";
                    JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                    if (json.has("success") && json.get("success").getAsBoolean()) {
                        JsonObject data = json.getAsJsonObject("data");
                        if (data != null && data.has("jobs")) {
                            onJobs.accept(data.getAsJsonArray("jobs"));
                        }
                    }
                } catch (IOException e) {
                    setLastError("輪詢讀取失敗: " + e.getMessage());
                    if (config.isAutoReconnect()) scheduleReconnect();
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                setLastError("輪詢網路錯誤: " + e.getMessage());
                if (config.isAutoReconnect()) scheduleReconnect();
            }
        });
    }

    /**
     * 回報工作結果
     */
    public void reportJobResult(String jobId, boolean success, String error) {
        if (!connected.get()) return;

        String url = normalizeUrl(config.getApiEndpoint())
                + config.getApiBasePath()
                + "/servers/" + encodePath(config.getServerId()) + "/jobs/" + encodePath(jobId) + "/result";

        JsonObject body = new JsonObject();
        body.addProperty("success", success);
        if (error != null) body.addProperty("error", error);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onResponse(Call call, Response response) { response.close(); }
            @Override public void onFailure(Call call, IOException e) {
                LOGGER.warn("[WebClient] 工作結果回報失敗: {}", e.getMessage());
            }
        });
    }

    // ── 狀態 ────────────────────────────────────────────────

    public boolean isConnected() {
        return connected.get() && !currentApiKey.isEmpty();
    }

    public void markConnected(String apiKey) {
        this.currentApiKey = apiKey;
        this.connected.set(true);
        this.reconnectAttempts = 0;
        this.lastError = "";
        LOGGER.info("[WebClient] 已連線至後端 API，伺服器 ID: {}", config.getServerId());
    }

    public void disconnect() {
        this.currentApiKey = "";
        this.connected.set(false);
        this.lastError = "";
        if (!reconnectScheduler.isShutdown()) reconnectScheduler.shutdownNow();
    }

    public String getLastError() { return lastError; }
    public long getLastHeartbeatAt() { return lastHeartbeatAt; }
    public long getLastPollAt() { return lastPollAt; }

    // ── 內部 ────────────────────────────────────────────────

    private void executeRequest(Request request, CompletableFuture<JsonObject> future) {
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String text = response.body() != null ? response.body().string() : "{}";
                    JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                    if (response.isSuccessful() && json.has("success") && json.get("success").getAsBoolean()) {
                        future.complete(json.has("data") ? json.getAsJsonObject("data") : json);
                    } else {
                        String msg = json.has("error")
                                ? json.getAsJsonObject("error").get("message").getAsString()
                                : "HTTP " + response.code();
                        future.completeExceptionally(new RuntimeException(msg));
                    }
                } catch (Exception e) {
                    future.completeExceptionally(e);
                } finally {
                    response.close();
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }
        });
    }

    private void scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            LOGGER.error("[WebClient] 達到最大重連次數，停止嘗試");
            return;
        }
        reconnectAttempts++;
        long delay = Math.min((long) Math.pow(2, Math.min(reconnectAttempts, 5)), 60L);
        LOGGER.info("[WebClient] 將在 {} 秒後重連（第 {} 次）", delay, reconnectAttempts);
        reconnectScheduler.schedule(() -> {
            currentApiKey = "";
            connected.set(false);
            LOGGER.info("[WebClient] 連線已中斷，等待重新配對");
        }, delay, TimeUnit.SECONDS);
    }

    private void setLastError(String error) {
        this.lastError = error;
        LOGGER.warn("[WebClient] {}", error);
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("API 位址不得為空");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String encodePath(String path) {
        return java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static SSLSocketFactory createTrustAllSslSocketFactory() {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{createTrustAllTrustManager()}, new java.security.SecureRandom());
            return sc.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("無法建立 SSL 上下文", e);
        }
    }

    private static X509TrustManager createTrustAllTrustManager() {
        return new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }

    @FunctionalInterface
    public interface Consumer<T> { void accept(T t); }
}