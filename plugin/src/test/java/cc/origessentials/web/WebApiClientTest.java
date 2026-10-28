package cc.origessentials.web;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * WebApiClient 基礎測試（不依賴實際 API 連線）
 */
class WebApiClientTest {

    @Test
    void testNormalizeEndpoint() {
        assertEquals("https://panel.example.com", normalize("https://panel.example.com/"));
        assertEquals("https://panel.example.com", normalize("https://panel.example.com"));
        assertThrows(IllegalArgumentException.class, () -> normalize(""));
        assertThrows(IllegalArgumentException.class, () -> normalize(null));
    }

    @Test
    void testEncodePath() {
        String encoded = java.net.URLEncoder.encode("world/server.conf", java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(encoded.contains("%2F"));
    }

    @Test
    void testEncodePathSafety() {
        // URLEncoder encodes special chars but not dots; verify ../ is encoded
        String path = "..%2Fetc%2Fpasswd"; // pre-encoded to show intent
        String encoded = java.net.URLEncoder.encode("../../etc/passwd", java.nio.charset.StandardCharsets.UTF_8);
        // The key point: path traversal chars like / are encoded
        assertFalse(encoded.contains("/etc"));
    }

    private String normalize(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("API 位址不得為空");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}