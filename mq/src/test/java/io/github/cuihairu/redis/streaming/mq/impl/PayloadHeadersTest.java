package io.github.cuihairu.redis.streaming.mq.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PayloadHeaders
 */
class PayloadHeadersTest {

    @Test
    void testPayloadHashRefConstant() {
        assertEquals("x-payload-hash-ref", PayloadHeaders.PAYLOAD_HASH_REF);
    }

    @Test
    void testPayloadOriginalSizeConstant() {
        assertEquals("x-payload-original-size", PayloadHeaders.PAYLOAD_ORIGINAL_SIZE);
    }

    @Test
    void testPayloadStorageTypeConstant() {
        assertEquals("x-payload-storage-type", PayloadHeaders.PAYLOAD_STORAGE_TYPE);
    }

    @Test
    void testStorageTypeInlineConstant() {
        assertEquals("inline", PayloadHeaders.STORAGE_TYPE_INLINE);
    }

    @Test
    void testStorageTypeHashConstant() {
        assertEquals("hash", PayloadHeaders.STORAGE_TYPE_HASH);
    }

    @Test
    void testMaxInlinePayloadSizeIs64KB() {
        assertEquals(64 * 1024, PayloadHeaders.MAX_INLINE_PAYLOAD_SIZE);
        assertEquals(65536, PayloadHeaders.MAX_INLINE_PAYLOAD_SIZE);
    }

    @Test
    void testConstructorIsPrivate() throws Exception {
        java.lang.reflect.Constructor<PayloadHeaders> constructor = PayloadHeaders.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        PayloadHeaders instance = constructor.newInstance();
        assertNotNull(instance);
    }

    @Test
    void testAllConstantsArePublicStaticFinal() throws Exception {
        java.lang.reflect.Field hashRefField = PayloadHeaders.class.getDeclaredField("PAYLOAD_HASH_REF");
        assertTrue(java.lang.reflect.Modifier.isPublic(hashRefField.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isStatic(hashRefField.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isFinal(hashRefField.getModifiers()));

        java.lang.reflect.Field originalSizeField = PayloadHeaders.class.getDeclaredField("PAYLOAD_ORIGINAL_SIZE");
        assertTrue(java.lang.reflect.Modifier.isPublic(originalSizeField.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isStatic(originalSizeField.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isFinal(originalSizeField.getModifiers()));

        java.lang.reflect.Field storageTypeField = PayloadHeaders.class.getDeclaredField("PAYLOAD_STORAGE_TYPE");
        assertTrue(java.lang.reflect.Modifier.isPublic(storageTypeField.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isStatic(storageTypeField.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isFinal(storageTypeField.getModifiers()));

        java.lang.reflect.Field maxSizeField = PayloadHeaders.class.getDeclaredField("MAX_INLINE_PAYLOAD_SIZE");
        assertTrue(java.lang.reflect.Modifier.isPublic(maxSizeField.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isStatic(maxSizeField.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isFinal(maxSizeField.getModifiers()));
    }

    @Test
    void testStorageTypeConstantsAreDistinct() {
        assertNotEquals(PayloadHeaders.STORAGE_TYPE_INLINE, PayloadHeaders.STORAGE_TYPE_HASH);
    }

    @Test
    void testHeaderKeysArePrefixedWithX() {
        assertTrue(PayloadHeaders.PAYLOAD_HASH_REF.startsWith("x-"));
        assertTrue(PayloadHeaders.PAYLOAD_ORIGINAL_SIZE.startsWith("x-"));
        assertTrue(PayloadHeaders.PAYLOAD_STORAGE_TYPE.startsWith("x-"));
    }

    @Test
    void testHeaderKeysFollowNamingConvention() {
        assertTrue(PayloadHeaders.PAYLOAD_HASH_REF.contains("payload"));
        assertTrue(PayloadHeaders.PAYLOAD_HASH_REF.contains("hash"));
        assertTrue(PayloadHeaders.PAYLOAD_HASH_REF.contains("ref"));

        assertTrue(PayloadHeaders.PAYLOAD_ORIGINAL_SIZE.contains("payload"));
        assertTrue(PayloadHeaders.PAYLOAD_ORIGINAL_SIZE.contains("original"));
        assertTrue(PayloadHeaders.PAYLOAD_ORIGINAL_SIZE.contains("size"));

        assertTrue(PayloadHeaders.PAYLOAD_STORAGE_TYPE.contains("payload"));
        assertTrue(PayloadHeaders.PAYLOAD_STORAGE_TYPE.contains("storage"));
        assertTrue(PayloadHeaders.PAYLOAD_STORAGE_TYPE.contains("type"));
    }
}
