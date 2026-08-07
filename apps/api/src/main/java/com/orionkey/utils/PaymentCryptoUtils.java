package com.orionkey.utils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * 支付相关加解密工具：
 * - RSA2（SHA256withRSA）签名 / 验签（支付宝、微信 APIv3）
 * - PKCS#8 / PKCS#1 PEM 私钥解析、SPKI / PKCS#1 PEM 公钥解析
 * - 微信支付 APIv3 回调资源 AES-256-GCM 解密
 */
public final class PaymentCryptoUtils {

    private static final String RSA = "RSA";
    private static final String SHA256_WITH_RSA = "SHA256withRSA";

    private PaymentCryptoUtils() {
    }

    // ── 私钥解析（微信 apiclient_key.pem / 支付宝应用私钥） ──

    public static PrivateKey parsePrivateKey(String pem) {
        String cleaned = pem.trim();
        if (cleaned.contains("BEGIN PRIVATE KEY")) {
            byte[] der = base64Decode(stripPem(cleaned, "PRIVATE KEY"));
            try {
                return KeyFactory.getInstance(RSA).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (Exception e) {
                throw new IllegalArgumentException("PKCS#8 私钥解析失败", e);
            }
        }
        if (cleaned.contains("BEGIN RSA PRIVATE KEY")) {
            return parsePkcs1PrivateKey(base64Decode(stripPem(cleaned, "RSA PRIVATE KEY")));
        }
        // 无 PEM 头：支付宝应用私钥常见裸 Base64（PKCS#8 或 PKCS#1），自动识别解析
        try {
            byte[] der = base64Decode(cleaned.replaceAll("\\s", ""));
            try {
                return KeyFactory.getInstance(RSA).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (Exception pkcs8Err) {
                return parsePkcs1PrivateKey(der);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("不支持的私钥格式（需 PKCS#8 或 PKCS#1 PEM，或裸 Base64）", e);
        }
    }

    // ── 公钥解析（支付宝公钥） ──

    public static PublicKey parsePublicKey(String pem) {
        String cleaned = pem.trim();
        if (cleaned.contains("BEGIN PUBLIC KEY")) {
            byte[] der = base64Decode(stripPem(cleaned, "PUBLIC KEY"));
            try {
                return KeyFactory.getInstance(RSA).generatePublic(new X509EncodedKeySpec(der));
            } catch (Exception e) {
                throw new IllegalArgumentException("公钥解析失败", e);
            }
        }
        if (cleaned.contains("BEGIN RSA PUBLIC KEY")) {
            return parsePkcs1PublicKey(base64Decode(stripPem(cleaned, "RSA PUBLIC KEY")));
        }
        throw new IllegalArgumentException("不支持的公钥格式（需 SPKI 或 PKCS#1 PEM）");
    }

    // ── RSA2 签名 / 验签 ──

    public static String sign(PrivateKey privateKey, String message) {
        try {
            Signature sig = Signature.getInstance(SHA256_WITH_RSA);
            sig.initSign(privateKey);
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new RuntimeException("RSA 签名失败", e);
        }
    }

    public static boolean verify(PublicKey publicKey, String message, String base64Signature) {
        try {
            Signature sig = Signature.getInstance(SHA256_WITH_RSA);
            sig.initVerify(publicKey);
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(base64Signature));
        } catch (Exception e) {
            return false;
        }
    }

    // ── 微信 APIv3 AES-256-GCM 解密 ──

    public static String decryptAesGcm(String apiV3Key, String nonce, String associatedData, String ciphertextBase64) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"), spec);
            if (associatedData != null && !associatedData.isEmpty()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertextBase64));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES-256-GCM 解密失败: " + e.getMessage(), e);
        }
    }

    // ── 辅助 ──

    private static String stripPem(String pem, String marker) {
        return pem.replace("-----BEGIN " + marker + "-----", "")
                .replace("-----END " + marker + "-----", "")
                .replaceAll("\\s", "");
    }

    private static byte[] base64Decode(String s) {
        return Base64.getDecoder().decode(s);
    }

    // ── PKCS#1 DER 解析 ──

    private static PrivateKey parsePkcs1PrivateKey(byte[] der) {
        try {
            Tlv seq = readTlv(der, 0);
            int pos = seq.valueStart;
            readTlv(der, pos);              // version
            pos = skip(der, pos);
            Tlv modulus = readTlv(der, pos);
            pos = modulus.headerEnd;
            readTlv(der, pos);              // publicExponent
            pos = skip(der, pos);
            Tlv privateExponent = readTlv(der, pos);
            KeyFactory kf = KeyFactory.getInstance(RSA);
            return kf.generatePrivate(new RSAPrivateKeySpec(
                    new BigInteger(1, integerValue(der, modulus)),
                    new BigInteger(1, integerValue(der, privateExponent))));
        } catch (Exception e) {
            throw new IllegalArgumentException("PKCS#1 私钥解析失败", e);
        }
    }

    private static PublicKey parsePkcs1PublicKey(byte[] der) {
        try {
            Tlv seq = readTlv(der, 0);
            int pos = seq.valueStart;
            Tlv modulus = readTlv(der, pos);
            pos = modulus.headerEnd;
            Tlv publicExponent = readTlv(der, pos);
            KeyFactory kf = KeyFactory.getInstance(RSA);
            return kf.generatePublic(new RSAPublicKeySpec(
                    new BigInteger(1, integerValue(der, modulus)),
                    new BigInteger(1, integerValue(der, publicExponent))));
        } catch (Exception e) {
            throw new IllegalArgumentException("PKCS#1 公钥解析失败", e);
        }
    }

    private static int skip(byte[] der, int pos) {
        return readTlv(der, pos).headerEnd;
    }

    private static final class Tlv {
        int tag;
        int length;
        int valueStart;
        int headerEnd;
    }

    private static Tlv readTlv(byte[] data, int pos) {
        Tlv t = new Tlv();
        t.tag = data[pos++] & 0xFF;
        int len = data[pos++] & 0xFF;
        if ((len & 0x80) != 0) {
            int numBytes = len & 0x7F;
            len = 0;
            for (int i = 0; i < numBytes; i++) {
                len = (len << 8) | (data[pos++] & 0xFF);
            }
        }
        t.length = len;
        t.valueStart = pos;
        t.headerEnd = pos + len;
        return t;
    }

    private static byte[] integerValue(byte[] data, Tlv t) {
        int start = t.valueStart;
        int len = t.length;
        // 去掉前导 0x00（无符号正整数补位）
        if (len > 1 && data[start] == 0) {
            start++;
            len--;
        }
        return Arrays.copyOfRange(data, start, start + len);
    }
}
