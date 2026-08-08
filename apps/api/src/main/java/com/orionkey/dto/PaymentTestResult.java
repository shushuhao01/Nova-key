package com.orionkey.dto;

import java.util.List;

/**
 * 支付渠道连接测试的结构化结果。
 * <p>
 * 参考 CRM 项目支付配置的测试连接输出，逐项展示检测清单：
 * 微信：AppID / 商户号 / API密钥 / 证书 / 连接测试
 * 支付宝：AppID / 商家私钥 / 支付宝公钥 / 签名类型 / 连接测试
 * 前端据此渲染 ✅/❌ 逐项结果，而不是只弹一条 message。
 */
public record PaymentTestResult(
        /** 是否全部通过（passed 由所有 items.status 汇总） */
        boolean passed,
        /** 逐项检测结果 */
        List<TestItem> items,
        /** 汇总消息（成功/失败提示） */
        String message
) {

    /**
     * 单项检测结果。
     *
     * @param name    检测项名称（如 AppID、商户号、连接测试）
     * @param status  是否通过
     * @param message 该项的说明（如 "AppID已配置: wx5435ceeb470e967f"）
     */
    public record TestItem(String name, boolean status, String message) {
    }
}
