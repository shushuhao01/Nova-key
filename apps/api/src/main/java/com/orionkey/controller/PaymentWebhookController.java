package com.orionkey.controller;

import com.orionkey.service.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/payments/webhook")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final WebhookService webhookService;

    /**
     * 易支付 GET callback — returns plain text "SUCCESS"
     */
    @GetMapping(value = "/epay", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> handleEpayCallback(@RequestParam Map<String, String> params) {
        log.info("Epay callback received: {}", params);
        String result = webhookService.processEpayCallback(params);
        return ResponseEntity.ok(result);
    }

    /**
     * BEpusdt USDT 支付回调 — POST JSON，返回 "ok" 表示成功
     */
    @PostMapping(value = "/usdt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> handleBepusdtCallback(@RequestBody Map<String, Object> params) {
        log.info("BEpusdt callback received: {}", params);
        String result = webhookService.processBepusdtCallback(params);
        return ResponseEntity.ok(result);
    }

    /**
     * 原生微信支付 APIv3 回调 — POST JSON（含验签请求头 + 加密资源）
     * 需读取原始请求体用于签名验证，成功返回 "SUCCESS"，失败返回 500 触发微信重试
     */
    @PostMapping(value = "/wxpay", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> handleWxpayCallback(HttpServletRequest request) {
        Map<String, String> headers = lowercaseHeaders(request);
        String rawBody;
        try {
            rawBody = readRawBody(request);
        } catch (IOException e) {
            log.error("Wxpay callback failed to read body", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("FAIL");
        }
        // 不打印完整 body 与请求头：报文含加密资源/签名信息，仅记录长度便于排查
        log.info("Wxpay callback received, bodyLength={}", rawBody.length());

        String result = webhookService.processWxpayCallback(headers, rawBody);
        if ("FAIL".equals(result)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("FAIL");
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 原生支付宝异步通知 — POST form-urlencoded，返回 "success" 表示成功
     */
    @PostMapping(value = "/alipay", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> handleAlipayCallback(@RequestParam Map<String, String> params) {
        log.info("Alipay callback received: {}", params);
        String result = webhookService.processAlipayCallback(params);
        if ("fail".equals(result)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("fail");
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 微信商家转账结果回调（分销佣金提现到账确认）— POST JSON，成功返回 "SUCCESS"，失败返回 500 触发微信重试
     */
    @PostMapping(value = "/wxpay-transfer", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> handleWxpayTransferCallback(HttpServletRequest request) {
        Map<String, String> headers = lowercaseHeaders(request);
        String rawBody;
        try {
            rawBody = readRawBody(request);
        } catch (IOException e) {
            log.error("Wxpay transfer callback failed to read body", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("FAIL");
        }
        // 不打印完整 body 与请求头：报文含加密资源/签名信息，仅记录长度便于排查
        log.info("Wxpay transfer callback received, bodyLength={}", rawBody.length());

        String result = webhookService.processWxpayTransferCallback(headers, rawBody);
        if ("FAIL".equals(result)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("FAIL");
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 微信支付退款结果通知 — POST JSON（含验签请求头 + 加密资源）
     * 退款到账 / 关闭 / 异常时收敛订单退款终态，成功返回 "SUCCESS"，失败返回 500 触发微信重试
     */
    @PostMapping(value = "/wxpay-refund", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> handleWxpayRefundCallback(HttpServletRequest request) {
        Map<String, String> headers = lowercaseHeaders(request);
        String rawBody;
        try {
            rawBody = readRawBody(request);
        } catch (IOException e) {
            log.error("Wxpay refund callback failed to read body", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("FAIL");
        }
        // 不打印完整 body 与请求头：报文含加密资源/签名信息，仅记录长度便于排查
        log.info("Wxpay refund callback received, bodyLength={}", rawBody.length());

        String result = webhookService.processWxpayRefundCallback(headers, rawBody);
        if ("FAIL".equals(result)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("FAIL");
        }
        return ResponseEntity.ok(result);
    }

    /** 读取请求头并统一转为小写键（微信 APIv3 验签要求小写头名） */
    private static Map<String, String> lowercaseHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        var headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.put(name.toLowerCase(), request.getHeader(name));
        }
        return headers;
    }

    /** 读取原始请求体（微信 APIv3 必须用原始报文参与验签，不能反序列化后再拼接） */
    private static String readRawBody(HttpServletRequest request) throws IOException {
        try (var is = request.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
