package cc.origessentials.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SecureRandomUtil 單元測試
 */
class SecureRandomUtilTest {

    @Test
    void testGenerateNumericCode() {
        String code = SecureRandomUtil.generateNumericCode(8);
        assertNotNull(code);
        assertEquals(8, code.length());
        assertTrue(code.matches("\\d{8}"));
    }

    @Test
    void testGenerateNumericCodeLengths() {
        assertEquals(4, SecureRandomUtil.generateNumericCode(4).length());
        assertEquals(16, SecureRandomUtil.generateNumericCode(16).length());
    }

    @Test
    void testGenerateNumericCodeInvalidLength() {
        assertThrows(IllegalArgumentException.class, () -> SecureRandomUtil.generateNumericCode(0));
        assertThrows(IllegalArgumentException.class, () -> SecureRandomUtil.generateNumericCode(65));
    }

    @Test
    void testGenerateAlphanumericCode() {
        String code = SecureRandomUtil.generateAlphanumericCode(12);
        assertNotNull(code);
        assertEquals(12, code.length());
    }

    @Test
    void testGenerateUuidRaw() {
        String uuid = SecureRandomUtil.generateUuidRaw();
        assertNotNull(uuid);
        assertEquals(32, uuid.length());
        assertFalse(uuid.contains("-"));
    }

    @Test
    void testGenerateToken() {
        String token = SecureRandomUtil.generateToken();
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void testMask() {
        assertEquals("ab****56", SecureRandomUtil.mask("abc123def456"));
        assertEquals("****", SecureRandomUtil.mask("ab"));
        assertEquals("****", SecureRandomUtil.mask(null));
    }

    @Test
    void testMaskShort() {
        assertEquals("abcd****", SecureRandomUtil.maskShort("abcdefghijklmnop"));
        assertEquals("****", SecureRandomUtil.maskShort("abc"));
    }

    @Test
    void testUniqueCodes() {
        String a = SecureRandomUtil.generateNumericCode(8);
        String b = SecureRandomUtil.generateNumericCode(8);
        // 隨機性測試：不應相同（概率極高）
        assertNotEquals(a, b);
    }
}