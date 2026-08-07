package com.orionkey.utils;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 支付核心加解密工具单元测试（离线，无需真实商户环境）：
 * - RSA2（SHA256withRSA）签名与验签（支付宝 / 微信 APIv3 商户签名同算法）
 * - PKCS#8 私钥 / SPKI 公钥 PEM 解析
 * - 微信 APIv3 AES-256-GCM 回调资源解密
 */
class PaymentCryptoUtilsTest {

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static String toPem(String base64Body, String marker) {
        return "-----BEGIN " + marker + "-----\n"
                + base64Body.replaceAll("(.{64})", "$1\n")
                + "\n-----END " + marker + "-----";
    }

    private static String privateKeyPem(PrivateKey key) {
        return toPem(Base64.getEncoder().encodeToString(key.getEncoded()), "PRIVATE KEY");
    }

    private static String publicKeyPem(PublicKey key) {
        return toPem(Base64.getEncoder().encodeToString(key.getEncoded()), "PUBLIC KEY");
    }

    @Test
    void rsaSignAndVerify() throws Exception {
        KeyPair pair = generateKeyPair();
        PrivateKey privateKey = PaymentCryptoUtils.parsePrivateKey(privateKeyPem(pair.getPrivate()));
        PublicKey publicKey = PaymentCryptoUtils.parsePublicKey(publicKeyPem(pair.getPublic()));

        // 模拟支付宝/微信签名消息
        String message = "app_id=2021000000000000&charset=utf-8&method=alipay.trade.query";
        String signature = PaymentCryptoUtils.sign(privateKey, message);

        assertNotNull(signature);
        assertTrue(PaymentCryptoUtils.verify(publicKey, message, signature),
                "同一消息的签名应验签通过");

        // 篡改消息后验签必须失败
        assertFalse(PaymentCryptoUtils.verify(publicKey, message + "&tampered=1", signature),
                "篡改消息后验签应失败");
    }

    @Test
    void aesGcmDecryptMatchesWechatApiV3() throws Exception {
        // 模拟微信支付 APIv3 回调资源加密：AES-256-GCM，nonce 为 12 字节，
        // 输出为 ciphertext||tag 拼接后 Base64（微信平台回调格式）
        String apiV3Key = "0123456789abcdef0123456789abcdef"; // 32 字节
        String nonce = "0123456789ab"; // 12 字节
        String associatedData = "transaction";
        String plaintext = "{\"out_trade_no\":\"c3a0c3e0-0000-0000-0000-000000000001\",\"trade_state\":\"SUCCESS\",\"amount\":{\"total\":5000}}";

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        String decrypted = PaymentCryptoUtils.decryptAesGcm(
                apiV3Key, nonce, associatedData, Base64.getEncoder().encodeToString(encrypted));

        assertEquals(plaintext, decrypted, "解密结果应与原文一致");

        // AAD 错误时应解密失败（微信平台会因 associated_data 不匹配而拒绝）
        assertThrows(RuntimeException.class,
                () -> PaymentCryptoUtils.decryptAesGcm(
                        apiV3Key, nonce, "wrong", Base64.getEncoder().encodeToString(encrypted)));
    }

    @Test
    void invalidPemRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PaymentCryptoUtils.parsePrivateKey("not-a-key"));
        assertThrows(IllegalArgumentException.class,
                () -> PaymentCryptoUtils.parsePublicKey("not-a-key"));
    }
}
