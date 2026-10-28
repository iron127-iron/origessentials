package cc.origessentials.util;

/**
 * 敏感資料遮罩工具
 * 在記錄日誌時自動遮蔽 API Key、Token 等敏感資訊
 */
public class LogMasker {

    private static volatile boolean enabled = true;
    private static final String[] SENSITIVE_PATTERNS = {
            "apikey", "api_key", "token", "secret", "password",
            "pairing-key", "pairing_key", "authorization"
    };

    private LogMasker() {}

    public static void init(boolean enabled) {
        LogMasker.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 遮蔽訊息中的敏感資訊
     */
    public static String mask(String message) {
        if (!enabled || message == null) return message;
        String result = message;
        for (String pattern : SENSITIVE_PATTERNS) {
            int idx = result.toLowerCase().indexOf(pattern);
            if (idx >= 0) {
                int colon = result.indexOf(':', idx);
                if (colon >= 0) {
                    int valueStart = colon + 1;
                    int valueEnd = result.indexOf(' ', valueStart);
                    if (valueEnd < 0) valueEnd = result.length();
                    String value = result.substring(valueStart, valueEnd).trim();
                    if (value.length() > 4) {
                        result = result.substring(0, valueStart) + "[REDACTED]" + result.substring(valueEnd);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 遮蔽 JSON 中的敏感欄位
     */
    public static String maskJson(String json) {
        if (!enabled || json == null) return json;
        String result = json;
        for (String key : SENSITIVE_PATTERNS) {
            String qkey = "\"" + key + "\"";
            String pat = qkey + "\\s*:\\s*\"([^\"]{4,})\"";
            String rep = qkey + ": \"[REDACTED]\"";
            result = result.replaceAll(pat, rep);
            String camelKey = key.replace("_", "");
            qkey = "\"" + camelKey + "\"";
            pat = qkey + "\\s*:\\s*\"([^\"]{4,})\"";
            rep = qkey + ": \"[REDACTED]\"";
            result = result.replaceAll(pat, rep);
        }
        return result;
    }
}