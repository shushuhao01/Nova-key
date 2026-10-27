package com.orionkey.util;

import java.util.regex.Pattern;

/**
 * 手机号工具：规范化与格式校验（中国大陆手机号）。
 */
public final class PhoneUtils {

    private static final Pattern CN_MOBILE = Pattern.compile("^1[3-9]\\d{9}$");

    private PhoneUtils() {
    }

    /** 规范化：去除空格 / 连字符 / 括号，并剥离 +86、0086、86 国际区号前缀 */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.replaceAll("[\\s\\-()]", "");
        if (v.startsWith("+86")) {
            v = v.substring(3);
        } else if (v.startsWith("0086")) {
            v = v.substring(4);
        } else if (v.startsWith("86") && v.length() == 13) {
            v = v.substring(2);
        }
        return v;
    }

    /** 校验中国大陆手机号：11 位、1 开头、第二位 3-9 */
    public static boolean isValidCnMobile(String phone) {
        return phone != null && CN_MOBILE.matcher(phone).matches();
    }
}
