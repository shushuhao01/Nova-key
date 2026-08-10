package com.orionkey.util;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

/**
 * 微信公众号消息加解密（与微信公众平台「消息加解密方式」配套）。
 * <ul>
 *   <li>明文模式：不使用本类</li>
 *   <li>兼容模式 / 安全模式：POST 请求体为 &lt;xml&gt;&lt;Encrypt&gt;...&lt;/Encrypt&gt;&lt;/xml&gt;，
 *       需用 msg_signature 验签，安全模式还需用 EncodingAESKey 解密（AES-256-CBC）</li>
 * </ul>
 * 解密后的明文字节结构：16 字节随机串 + 4 字节网络序消息体长度 + 消息体(XML) + appid
 */
public class WXBizMsgCrypt {

    private static final Random RANDOM = new Random();
    private static final char[] BASE_62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final String token;
    private final String aesKey43;
    private final String appid;

    public WXBizMsgCrypt(String token, String aesKey43, String appid) {
        this.token = token;
        this.aesKey43 = aesKey43;
        this.appid = appid;
    }

    /** 验证 GET 请求签名：sha1(sort(token, timestamp, nonce)) */
    public boolean verifySignature(String signature, String timestamp, String nonce) {
        if (signature == null || token == null || token.isBlank()) return false;
        return sha1(sort(token, timestamp, nonce, null)).equalsIgnoreCase(signature);
    }

    /** 验证消息签名：sha1(sort(token, timestamp, nonce, encrypt)) */
    public boolean verifyMsgSignature(String msgSignature, String timestamp, String nonce, String encrypt) {
        if (msgSignature == null || token == null || token.isBlank() || encrypt == null) return false;
        return sha1(sort(token, timestamp, nonce, encrypt)).equalsIgnoreCase(msgSignature);
    }

    /** 解密 Encrypt 字段，返回明文 XML（校验 appid） */
    public String decrypt(String encrypt) {
        if (aesKey43 == null || aesKey43.isBlank() || appid == null || appid.isBlank()) {
            throw new IllegalStateException("EncodingAESKey 或 AppID 未配置，无法解密");
        }
        try {
            byte[] aesKey = Base64.getDecoder().decode((aesKey43 + "=").getBytes(StandardCharsets.UTF_8));
            byte[] iv = Arrays.copyOfRange(aesKey, 0, 16);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encrypt));
            // 前 16 字节随机串；随后 4 字节（网络序）为消息体长度
            int len = ((decrypted[16] & 0xFF) << 24) | ((decrypted[17] & 0xFF) << 16)
                    | ((decrypted[18] & 0xFF) << 8) | (decrypted[19] & 0xFF);
            if (len < 0 || 20 + len > decrypted.length) {
                throw new IllegalStateException("解密消息长度非法");
            }
            String xml = new String(decrypted, 20, len, StandardCharsets.UTF_8);
            String gotAppid = new String(decrypted, 20 + len, decrypted.length - 20 - len, StandardCharsets.UTF_8);
            if (!appid.equalsIgnoreCase(gotAppid)) {
                throw new IllegalStateException("解密消息 AppID 校验失败");
            }
            return xml;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("消息解密失败：" + e.getMessage(), e);
        }
    }

    /** 加密文本消息为 <xml><Encrypt>...</Encrypt></xml>（回复消息使用） */
    public String encrypt(String xml) {
        if (aesKey43 == null || aesKey43.isBlank() || appid == null || appid.isBlank()) {
            throw new IllegalStateException("EncodingAESKey 或 AppID 未配置，无法加密");
        }
        try {
            byte[] aesKey = Base64.getDecoder().decode((aesKey43 + "=").getBytes(StandardCharsets.UTF_8));
            byte[] iv = Arrays.copyOfRange(aesKey, 0, 16);
            byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
            byte[] randomBytes = new byte[16];
            RANDOM.nextBytes(randomBytes);
            byte[] lenBytes = new byte[]{
                    (byte) (xmlBytes.length >> 24),
                    (byte) (xmlBytes.length >> 16),
                    (byte) (xmlBytes.length >> 8),
                    (byte) xmlBytes.length};
            byte[] appidBytes = appid.getBytes(StandardCharsets.UTF_8);
            byte[] plain = new byte[16 + 4 + xmlBytes.length + appidBytes.length];
            System.arraycopy(randomBytes, 0, plain, 0, 16);
            System.arraycopy(lenBytes, 0, plain, 16, 4);
            System.arraycopy(xmlBytes, 0, plain, 20, xmlBytes.length);
            System.arraycopy(appidBytes, 0, plain, 20 + xmlBytes.length, appidBytes.length);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            String encrypt = Base64.getEncoder().encodeToString(cipher.doFinal(plain));
            return "<xml><Encrypt><![CDATA[" + encrypt + "]]></Encrypt></xml>";
        } catch (Exception e) {
            throw new IllegalStateException("消息加密失败：" + e.getMessage(), e);
        }
    }

    /** 生成 16 字节随机串（Base64，用于加密时填充） */
    public static String genRandomStr() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String sort(String token, String timestamp, String nonce, String encrypt) {
        String[] arr = encrypt == null || encrypt.isBlank()
                ? new String[]{token, timestamp, nonce}
                : new String[]{token, timestamp, nonce, encrypt};
        Arrays.sort(arr);
        return String.join("", arr);
    }

    private static String sha1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 计算失败", e);
        }
    }

    /** 生成 Base62 随机字符串（Token / EncodingAESKey 生成用） */
    public static String randomBase62(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(BASE_62[RANDOM.nextInt(BASE_62.length)]);
        }
        return sb.toString();
    }
}
