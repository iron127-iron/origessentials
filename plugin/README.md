# plugin/ — OrigEssentials Minecraft 插件

## 用途

OrigEssentials Minecraft 插件提供遊戲內功能，與 Web 管理面板無縫整合：

- **配對流程**：一次性配對碼連線至 Web 面板
- **遠端工作佇列**：接收面板發送的指令（主控台、狀態回傳）
- **UUID 綁定**：玩家綁定 Minecraft 帳號至 Web 帳號
- **離線容錯**：面板離線時插件仍可正常運作

## 支援版本

- **Minecraft**：1.20.1 – 1.20.4
- **核心**：Paper / Purpur（Folia 相容）
- **Java**：JDK 17+（已使用 Temurin 25 LTS 驗證）

## 建置環境

### 必要工具

| 工具 | 版本要求 |
|------|----------|
| Java JDK | 17+（推薦 Temurin 25 LTS） |
| Gradle | 8.6（隨 wrapper 提供） |
| Git | 2.30+ |

### 環境變數（可選）

```bash
# 若需使用特定 Paper API 版本
export PAPER_API_VERSION=1.20.4-R0.1-SNAPSHOT
```

## 建置方式

```bash
# 進入插件目錄
cd plugin

# 賦予 gradlew 執行權限（Linux/macOS）
chmod +x ./gradlew

# 完整建置（含測試）
./gradlew build

# 僅產出 JAR（不含測試）
./gradlew shadowJar

# 輸出路徑：build/libs/OrigEssentials-0.1.0-SNAPSHOT.jar
```

## JAR 安裝方式

```bash
# 1. 停止 Minecraft 伺服器
# 2. 複製 JAR 至 plugins 目錄
cp build/libs/OrigEssentials-*.jar /path/to/server/plugins/

# 3. 啟動伺服器（首次會建立 config.yml）
# 4. 編輯 plugins/OrigEssentials/config.yml

# 5. 熱重新載入（或重啟伺服器）
/orig reload
```

## 設定檔說明

設定檔路徑：`plugins/OrigEssentials/config.yml`

### 必要欄位

```yaml
web-panel:
  enabled: true                              # 啟用 Web 面板連線
  api-endpoint: "https://panel.example.com"  # Web 面板 API 位址
  server-id: "your-server-external-id"       # 託管商提供的伺服器 ID
```

### 安全欄位（配對後自動填入）

```yaml
  pairing-key: ""   # 配對用一次性金鑰（勿手動填寫）
  api-key: ""       # 配對後由後端發放（勿手動填寫）
```

### 連線參數

```yaml
  connection:
    poll-interval: 10    # 輪詢間隔（秒），預設 10
    timeout: 10000       # 請求超時（毫秒），預設 10000
    auto-reconnect: true # 是否自動重連
```

### 安全設定

```yaml
  security:
    allow-remote-actions: false   # 是否允許遠端電源操作
    allow-player-data-read: false # 是否允許讀取玩家資料
    allow-file-management: false  # 是否允許檔案管理
    verify-tls: true              # 強制 TLS 驗證（production 不得設 false）
```

### 停用 Web 面板

```yaml
web-panel:
  enabled: false  # 完全停用所有連線與輪詢，插件基本功能仍正常運作
```

## Web 面板連線方式

插件使用 HTTP/HTTPS 主動請求，不依賴任何入站連接埠：

1. 插件定期輪詢 `POST /api/v1/servers/{serverId}/jobs/poll`
2. 收到工作後驗證簽章與到期時間
3. 執行後回報結果至 `POST /api/v1/servers/{serverId}/jobs/{jobId}/result`
4. 心跳維持連線：`POST /api/v1/servers/{serverId}/heartbeat`

## 配對及憑證輪替流程

### 初次配對

```
1. 管理員在 Web 面板建立配對碼
2. 玩家/管理員在伺服器執行: /orig pair <配對碼>
3. 插件發送配對請求至後端
4. 後端驗證配對碼（一次性，立即失效）
5. 後端發放 server-specific API Key
6. 插件儲存 API Key 至 config.yml
7. Web 面板顯示「已配對」
```

### 憑證輪替

- API Key 設有過期時間，到期自動失效
- 管理員可在面板撤銷既有憑證
- 撤銷後插件需重新配對

### 解除配對

```
/orig unpair
```

## 權限節點

| 權限節點 | 用途 |
|----------|------|
| `origessentials.command.orig` | 主指令 |
| `origessentials.command.origreload` | 重新載入設定 |
| `origessentials.command.origstatus` | 查看狀態 |
| `origessentials.command.origpair` | 配對 |
| `origessentials.command.origunpair` | 解除配對 |
| `origessentials.command.origdebug` | 診斷資訊 |
| `origessentials.command.origbind` | UUID 綁定 |
| `origessentials.unpair` | 解除配對（管理員） |

## 指令與使用範例

| 指令 | 權限 | 說明 |
|------|------|------|
| `/orig` | `origessentials.command.orig` | 顯示幫助 |
| `/orig status` | `origessentials.command.origstatus` | 查看插件狀態 |
| `/orig reload` | `origessentials.command.origreload` | 重新載入設定 |
| `/orig pair <code>` | `origessentials.command.origpair` | 配對 Web 面板 |
| `/orig unpair` | `origessentials.unpair` | 解除配對 |
| `/orig debug` | `origessentials.command.origdebug` | 輸出診斷（過濾敏感資料） |
| `/orig bind <code>` | `origessentials.command.origbind` | 綁定 Minecraft UUID |

## 測試方法

```bash
# 執行所有測試
./gradlew test

# 執行單一測試類別
./gradlew test --tests "com.origessentials.plugin.connection.WebPanelClientTest"

# 生成測試報告
./gradlew test --info
```

測試涵蓋：
- 配對碼遮罩函數
- API 端點正規化
- 路徑編碼
- 配置讀取預設值

## 常見錯誤及疑難排解

### 錯誤：Web 面板功能已停用

```
[OrigEssentials] Web 面板功能已停用，插件以離線模式運作。
```
**原因**：`config.yml` 中 `web-panel.enabled: false`  
**處理**：將 `enabled` 設為 `true` 並填寫必要欄位

### 錯誤：api-endpoint 未設定

```
[OrigEssentials] web-panel.api-endpoint 未設定，Web 面板功能將無法運作。
```
**處理**：在 config.yml 中填寫正確的 API 位址

### 錯誤：配對失敗

可能原因：
- API 位址錯誤（確認 HTTPS 且可連線）
- 配對碼已過期（重新生成）
- 伺服器已配對（先執行 `/orig unpair`）

### 錯誤：TLS 驗證失敗

若 `verify-tls: true` 但憑證無效，檢查：
- API 位址是否使用 HTTPS
- 伺服器系統時間是否正確
- 憑證是否由受信任 CA 簽署

## 升級及回復方式

### 升級

```bash
# 1. 備份現有設定
cp plugins/OrigEssentials/config.yml config.yml.backup

# 2. 停止伺服器
stop

# 3. 更新 JAR
rm plugins/OrigEssentials/*.jar
cp OrigEssentials-*.jar plugins/OrigEssentials/

# 4. 恢復設定（比較差異）
diff config.yml.backup plugins/OrigEssentials/config.yml

# 5. 啟動伺服器
start
```

### 回復

```bash
# 停止伺服器
# 恢復舊版 JAR
# 恢復 config.yml
# 啟動伺服器
```

> **注意**：部分設定變更需要重新啟動伺服器才能生效（例如 `allow-remote-actions`）。
> 僅限熱載入的設定可使用 `/orig reload`。