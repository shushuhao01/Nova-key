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
        Map<String, String> headers = new HashMap<>();
        var headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.put(name.toLowerCase(), request.getHeader(name));
        }
        String rawBody;
        try (var is = request.getInputStream()) {
            rawBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Wxpay callback failed to read body", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("FAIL");
        }
        log.info("Wxpay callback received, headers={}, body={}", headers, rawBody);

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
        Map<String, String> headers = new HashMap<>();
        var headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.put(name.toLowerCase(), request.getHeader(name));
        }
        String rawBody;
        try (var is = request.getInputStream()) {
            rawBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Wxpay transfer callback failed to read body", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("FAIL");
        }
        log.info("Wxpay transfer callback received, headers={}, body={}", headers, rawBody);

        String result = webhookService.processWxpayTransferCallback(headers, rawBody);
        if ("FAIL".equals(result)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("FAIL");
        }
        return ResponseEntity.ok(result);
    }
}
