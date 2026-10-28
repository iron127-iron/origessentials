package cc.origessentials.util;

import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 密碼學安全工具
 */
public final class SecureRandomUtil {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private SecureRandomUtil() {}

    /**
     * 產生指定長度的數字驗證碼
     */
    public static String generateNumericCode(int length) {
        if (length <= 0 || length > 64) throw new IllegalArgumentException("長度必須在 1-64 之間");
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(SECURE_RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    /**
     * 產生指定長度的隨機字串（字母數字混合）
     */
    public static String generateAlphanumericCode(int length) {
        if (length <= 0 || length > 64) throw new IllegalArgumentException("長度必須在 1-64 之間");
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(SECURE_RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * 產生 UUID（不含連字號）
     */
    public static String generateUuidRaw() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 產生 16 位元組的隨機 token（Base64 編碼，不含 == 結尾）
     */
    public static String generateToken() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 遮蔽字串（僅顯示首尾各 2 字元）
     */
    public static String mask(String value) {
        if (value == null || value.length() <= 4) return "****";
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    /**
     * 遮蔽字串（僅顯示前 4 字元）
     */
    public static String maskShort(String value) {
        if (value == null || value.length() <= 4) return "****";
        return value.substring(0, 4) + "****";
    }
}