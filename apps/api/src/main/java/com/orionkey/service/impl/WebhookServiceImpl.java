package com.orionkey.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.OrderStatus;
import com.orionkey.exception.BusinessException;
import com.orionkey.entity.Order;
import com.orionkey.entity.PaymentChannel;
import com.orionkey.entity.WebhookEvent;
import com.orionkey.repository.OrderRepository;
import com.orionkey.repository.PaymentChannelRepository;
import com.orionkey.repository.WebhookEventRepository;
import com.orionkey.service.BepusdtService;
import com.orionkey.service.EpayService;
import com.orionkey.service.AlipayService;
import com.orionkey.service.AlipayService.AlipayConfig;
import com.orionkey.service.AlipayService.AlipayOrderQueryResult;
import com.orionkey.service.TxidVerifyService;
import com.orionkey.service.WebhookService;
import com.orionkey.service.WxpayService;
import com.orionkey.service.WxpayService.WxpayConfig;
import com.orionkey.service.WxpayService.WxpayNotificationResult;
import com.orionkey.service.WxpayService.WxpayOrderQueryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookServiceImpl implements WebhookService {

    private final WebhookEventRepository webhookEventRepository;
    private final OrderRepository orderRepository;
    private final PaymentChannelRepository paymentChannelRepository;
    private final EpayService epayService;
    private final BepusdtService bepusdtService;
    private final WxpayService wxpayService;
    private final AlipayService alipayService;
    private final ObjectMapper objectMapper;
    private final PaymentServiceImpl paymentService;
    private final TxidVerifyService txidVerifyService;

    @Override
    @Transactional
    public String processEpayCallback(Map<String, String> params) {
        String tradeNo = params.get("trade_no");
        String outTradeNo = params.get("out_trade_no");
        String tradeStatus = params.get("trade_status");
        String money = params.get("money");
        String sign = params.get("sign");

        log.info("Epay callback: out_trade_no={}, trade_status={}, money={}", outTradeNo, tradeStatus, money);

        // Use trade_no as event ID for idempotency
        String eventId = "epay_" + (tradeNo != null ? tradeNo : UUID.randomUUID().toString());
        Optional<WebhookEvent> existingEvent = webhookEventRepository.findByEventId(eventId);
        if (existingEvent.isPresent()) {
            log.info("Epay callback already processed: {}", eventId);
            return "SUCCESS";
        }

        // Step 1: Parse order ID
        UUID orderId;
        try {
            orderId = UUID.fromString(outTradeNo);
        } catch (IllegalArgumentException e) {
            log.error("Epay callback invalid out_trade_no: {}", outTradeNo);
            return "FAIL";
        }

        // Step 2: Resolve merchant key from order's channel config
        String merchantKey = resolveMerchantKey(orderId);

        // Step 3: Verify signature
        // F3: 签名失败不写入幂等表 — 否则攻击者可伪造回调占用 eventId，阻塞后续真实回调
        if (!epayService.verifySign(merchantKey, params, sign)) {
            log.error("Epay callback signature verification failed: out_trade_no={}, remote sign={}", outTradeNo, sign);
            return "FAIL";
        }

        // Step 4: Check trade status（非成功状态不写入幂等表，避免阻塞后续成功回调）
        if (!"TRADE_SUCCESS".equals(tradeStatus)) {
            log.info("Epay callback non-success status: {}, skipping (not saved to idempotency table)", tradeStatus);
            return "SUCCESS";
        }

        // Step 5: Process payment
        WebhookEvent event = new WebhookEvent();
        event.setEventId(eventId);
        event.setChannelCode("epay");
        event.setOrderId(orderId);
        event.setPayload(params.toString());

        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            event.setProcessResult("ORDER_NOT_FOUND");
            log.warn("Epay callback order not found: {}", orderId);
            webhookEventRepository.save(event);
            return "SUCCESS";
        }

        // Step 6: Verify amount matches (money 必须存在且与订单金额一致)
        if (money == null || money.isBlank()) {
            log.error("Epay callback missing money parameter: out_trade_no={}", outTradeNo);
            event.setProcessResult("MISSING_AMOUNT");
            webhookEventRepository.save(event);
            return "FAIL";
        }
        BigDecimal callbackAmount;
        try {
            callbackAmount = new BigDecimal(money);
        } catch (NumberFormatException e) {
            log.error("Epay callback invalid money format: {}, out_trade_no={}", money, outTradeNo);
            event.setProcessResult("INVALID_AMOUNT_FORMAT");
            webhookEventRepository.save(event);
            return "FAIL";
        }
        if (order.getActualAmount().compareTo(callbackAmount) != 0) {
            log.error("Epay callback amount mismatch: order={}, callback={}", order.getActualAmount(), callbackAmount);
            event.setProcessResult("AMOUNT_MISMATCH");
            webhookEventRepository.save(event);
            return "FAIL";
        }

        // Step 7: 服务端主动查询网关订单状态（防止伪造回调）
        EpayService.ChannelConfig channelConfig = resolveChannelConfig(order);
        if (channelConfig != null) {
            EpayService.OrderQueryResult queryResult = epayService.queryOrder(channelConfig, outTradeNo);
            if (queryResult == null) {
                // 网络/网关故障 — 不写入幂等表，返回 FAIL 触发网关重试
                log.warn("Epay callback deferred: server-side order query returned null (network issue?), out_trade_no={}", outTradeNo);
                return "FAIL";
            }
            // 查询 API 的 status 字段格式可能为 "TRADE_SUCCESS" 或 "1"（已支付），兼容两种
            if (!isQueryStatusPaid(queryResult.tradeStatus())) {
                log.error("Epay callback rejected: query status={}, expected TRADE_SUCCESS/1, out_trade_no={}",
                        queryResult.tradeStatus(), outTradeNo);
                event.setProcessResult("QUERY_STATUS_MISMATCH");
                webhookEventRepository.save(event);
                return "FAIL";
            }
            // 校验网关返回的金额与订单金额一致
            if (queryResult.money() != null) {
                try {
                    BigDecimal queryAmount = new BigDecimal(queryResult.money());
                    if (order.getActualAmount().compareTo(queryAmount) != 0) {
                        log.error("Epay callback rejected: query amount={}, order amount={}, out_trade_no={}",
                                queryAmount, order.getActualAmount(), outTradeNo);
                        event.setProcessResult("QUERY_AMOUNT_MISMATCH");
                        webhookEventRepository.save(event);
                        return "FAIL";
                    }
                } catch (NumberFormatException e) {
                    log.warn("Epay order query returned invalid money format: {}", queryResult.money());
                }
            }
            log.info("Epay callback server-side verification passed: out_trade_no={}, queryStatus={}", outTradeNo, queryResult.tradeStatus());
        } else {
            // 渠道配置不完整时降级为仅签名校验（已在 Step 3 通过），打 warn 日志
            log.warn("Epay callback: channel config incomplete, skipping server-side query verification for out_trade_no={}", outTradeNo);
        }

        // Step 8: Idempotent update order status
        if (order.getStatus() == OrderStatus.PENDING) {
            order.setStatus(OrderStatus.PAID);
            order.setPaidAt(LocalDateTime.now());
            orderRepository.save(order);
            event.setProcessResult("SUCCESS");
            log.info("Epay callback: order {} marked as PAID", orderId);
        } else {
            event.setProcessResult("SKIPPED_" + order.getStatus().name());
            log.info("Epay callback: order {} already {}", orderId, order.getStatus());
        }

        webhookEventRepository.save(event);
        return "SUCCESS";
    }

    @Override
    @Transactional
    public String processBepusdtCallback(Map<String, Object> params) {
        // BEpusdt 回调 JSON 含非 String 类型（amount: float64, status: int），
        // 转为 Map<String, String> 用于签名验证（Object.toString() 与 Go 的 fmt.Sprintf("%v", v) 输出一致）
        Map<String, String> signParams = new LinkedHashMap<>();
        for (var entry : params.entrySet()) {
            if (entry.getValue() != null) {
                signParams.put(entry.getKey(), entry.getValue().toString());
            }
        }

        String tradeId = signParams.get("trade_id");
        String orderId = signParams.get("order_id");
        String status = signParams.get("status");
        String blockTxId = signParams.get("block_transaction_id");
        String signature = signParams.get("signature");

        log.info("BEpusdt callback: trade_id={}, order_id={}, status={}, block_tx_id={}",
                tradeId, orderId, status, blockTxId);

        // 1. 幂等检查
        String eventId = "bepusdt_" + tradeId;
        if (webhookEventRepository.findByEventId(eventId).isPresent()) {
            log.info("BEpusdt callback already processed: {}", eventId);
            return "ok";
        }

        // 2. 解析订单
        UUID orderUuid;
        try {
            orderUuid = UUID.fromString(orderId);
        } catch (IllegalArgumentException e) {
            log.error("BEpusdt callback invalid order_id: {}", orderId);
            return "ok";
        }

        Order order = orderRepository.findById(orderUuid).orElse(null);
        if (order == null) {
            // F8: 订单未找到时不写入幂等表且返回 fail — 触发 BEpusdt 重试（可能是时序问题：回调先于订单落库）
            log.warn("BEpusdt callback order not found: {}, returning fail to trigger retry", orderId);
            return "fail";
        }

        // 3. 验签（apiToken 为空则拒绝，防止跳过签名验证）
        String apiToken = resolveBepusdtApiToken(order);
        if (apiToken == null) {
            log.error("BEpusdt callback rejected: api_token not configured for channel {}", order.getPaymentMethod());
            saveWebhookEvent(eventId, "usdt", order.getId(), signParams.toString(), "NO_API_TOKEN");
            return "fail";
        }
        if (!bepusdtService.verifySign(apiToken, signParams, signature)) {
            log.error("BEpusdt callback signature verification failed: trade_id={}", tradeId);
            // F3: 签名失败不写入幂等表 — 否则攻击者可伪造回调占用 eventId，阻塞后续真实回调
            return "fail";
        }

        // 4. 状态检查（只处理 status=2 即支付成功）
        // 注意：非成功状态不写入幂等表，否则后续 status=2 回调会被误拦截
        if (!"2".equals(status)) {
            log.info("BEpusdt callback non-success status: {}, skipping (not saved to idempotency table)", status);
            return "ok";
        }

        // 5. 金额校验（actual_amount 和 usdtCryptoAmount 必须都存在且一致）
        String actualAmount = signParams.get("actual_amount");
        if (actualAmount == null || actualAmount.isBlank() || order.getUsdtCryptoAmount() == null) {
            log.error("BEpusdt callback missing amount data: actual_amount={}, orderCrypto={}, order={}",
                    actualAmount, order.getUsdtCryptoAmount(), orderId);
            saveWebhookEvent(eventId, "usdt", order.getId(), signParams.toString(), "MISSING_AMOUNT");
            return "ok";
        }
        BigDecimal bepCallbackAmount;
        BigDecimal bepOrderAmount;
        try {
            bepCallbackAmount = new BigDecimal(actualAmount);
            bepOrderAmount = new BigDecimal(order.getUsdtCryptoAmount());
        } catch (NumberFormatException e) {
            log.error("BEpusdt callback invalid amount format: actual_amount={}, orderCrypto={}, order={}",
                    actualAmount, order.getUsdtCryptoAmount(), orderId);
            saveWebhookEvent(eventId, "usdt", order.getId(), signParams.toString(), "INVALID_AMOUNT_FORMAT");
            return "ok";
        }
        if (bepCallbackAmount.compareTo(bepOrderAmount) != 0) {
            log.error("BEpusdt callback amount mismatch: expected={}, actual={}, order={}",
                    bepOrderAmount, bepCallbackAmount, orderId);
            saveWebhookEvent(eventId, "usdt", order.getId(), signParams.toString(), "AMOUNT_MISMATCH");
            return "ok";
        }

        // 6. 链上验证 block_transaction_id（防止伪造回调 — 与 Epay 服务端查询网关等效）
        if (blockTxId == null || blockTxId.isBlank() || blockTxId.equals(tradeId)) {
            // status=2 但 block_transaction_id 不是真实链上哈希（等于 tradeId 或为空）
            // 不写入幂等表，返回 fail 触发 BEpusdt 重试（等待链上确认后重新回调）
            log.warn("BEpusdt callback status=2 but no real block_tx_id: trade_id={}, block_tx_id={}", tradeId, blockTxId);
            return "fail";
        }

        String chain = order.getUsdtChain() != null ? order.getUsdtChain() : order.getPaymentMethod();
        TxidVerifyService.ChainVerifyResult chainResult =
                txidVerifyService.verifyForWebhook(chain, blockTxId, order.getUsdtWalletAddress(), order.getUsdtCryptoAmount(), order.getCreatedAt());

        if (chainResult == null) {
            // 链上 API 查询失败（TronGrid/BscScan 不可用）— 不写入幂等表，返回 fail 触发重试
            log.warn("BEpusdt callback deferred: on-chain API unavailable, trade_id={}, txid={}", tradeId, blockTxId);
            return "fail";
        }
        if (!chainResult.verified()) {
            // 链上验证失败（交易不存在/未确认/地址不匹配/非USDT/金额不匹配）— 写入幂等表拒绝
            log.error("BEpusdt callback rejected by on-chain verification: {}, trade_id={}, txid={}",
                    chainResult.reason(), tradeId, blockTxId);
            saveWebhookEvent(eventId, "usdt", order.getId(), signParams.toString(),
                    "ONCHAIN_VERIFY_FAILED: " + chainResult.reason());
            return "ok";
        }
        log.info("BEpusdt callback on-chain verification passed: trade_id={}, txid={}", tradeId, blockTxId);

        // 7. TXID 唯一性前置检查（防止同一链上交易被关联到多个订单）
        Optional<Order> txidExisting = orderRepository.findByUsdtTxId(blockTxId);
        if (txidExisting.isPresent() && !txidExisting.get().getId().equals(order.getId())) {
            log.error("BEpusdt callback TXID collision: txid={} already used by order {}, current order {}",
                    blockTxId, txidExisting.get().getId(), order.getId());
            saveWebhookEvent(eventId, "usdt", order.getId(), signParams.toString(), "TXID_ALREADY_USED");
            return "ok";
        }

        // 8. 幂等更新订单状态（PENDING 和 EXPIRED 均可标记为 PAID，与 TXID 验证和管理员手动标记行为一致）
        if (order.getStatus() == OrderStatus.PENDING || order.getStatus() == OrderStatus.EXPIRED) {
            order.setStatus(OrderStatus.PAID);
            order.setPaidAt(LocalDateTime.now());
            order.setUsdtTxId(blockTxId);
            orderRepository.save(order);
            saveWebhookEvent(eventId, "usdt", order.getId(), signParams.toString(), "SUCCESS");
            log.info("BEpusdt callback: order {} marked as PAID, txid={}", orderId, blockTxId);
        } else {
            saveWebhookEvent(eventId, "usdt", order.getId(), signParams.toString(),
                    "SKIPPED_" + order.getStatus().name());
            log.info("BEpusdt callback: order {} already {}", orderId, order.getStatus());
        }

        return "ok";
    }

    /**
     * 原生微信支付 APIv3 回调处理：
     * 1) 平台证书验签 + APIv3 密钥解密资源
     * 2) 幂等检查（通知 ID）
     * 3) 金额校验（分转元）
     * 4) 服务端主动查单二次确认（防止伪造回调）
     * 5) 标记订单 PAID
     */
    @Override
    @Transactional
    public String processWxpayCallback(Map<String, String> headers, String rawBody) {
        log.info("Wxpay callback received");
        try {
            // 1. 用任一已启用的微信商户配置尝试验签 + 解密（同一通知可用任一商户配置解出）
            WxpayNotificationResult notification = null;
            for (PaymentChannel channel : paymentChannelRepository
                    .findByProviderTypeAndIsDeleted("native_wxpay", 0)) {
                if (!channel.isEnabled()) continue;
                try {
                    WxpayConfig config = paymentService.buildWxpayConfig(channel);
                    WxpayNotificationResult result = wxpayService.decryptNotification(config, headers, rawBody);
                    if (result != null) {
                        notification = result;
                        break;
                    }
                } catch (Exception e) {
                    log.warn("Wxpay notification attempt failed for channel {}: {}",
                            channel.getChannelCode(), e.getMessage());
                }
            }
            if (notification == null) {
                log.error("Wxpay callback rejected: signature verification or decryption failed for all channels");
                return "FAIL";
            }

            // 2. 仅处理支付成功事件（退款/关闭等事件直接应答，不处理）
            if (!"TRANSACTION.SUCCESS".equals(notification.eventType())) {
                log.info("Wxpay callback non-success event: {}, skipping", notification.eventType());
                return "SUCCESS";
            }

            // 3. 幂等检查
            String eventId = "wxpay_" + (notification.id() != null
                    ? notification.id() : notification.transactionId());
            if (webhookEventRepository.findByEventId(eventId).isPresent()) {
                log.info("Wxpay callback already processed: {}", eventId);
                return "SUCCESS";
            }

            // 4. 解析订单
            UUID orderId;
            try {
                orderId = UUID.fromString(notification.outTradeNo());
            } catch (Exception e) {
                log.error("Wxpay callback invalid out_trade_no: {}", notification.outTradeNo());
                return "FAIL";
            }
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                log.warn("Wxpay callback order not found: {}, returning FAIL to trigger retry", orderId);
                return "FAIL";
            }

            // 5. 金额校验（分转元，与订单金额比较）
            if (notification.total() == null) {
                log.error("Wxpay callback missing total: out_trade_no={}", orderId);
                return "FAIL";
            }
            BigDecimal callbackAmount = BigDecimal.valueOf(notification.total())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (order.getActualAmount().compareTo(callbackAmount) != 0) {
                log.error("Wxpay callback amount mismatch: order={}, callback={}",
                        order.getActualAmount(), callbackAmount);
                saveWebhookEvent(eventId, "wxpay", orderId, rawBody, "AMOUNT_MISMATCH");
                return "FAIL";
            }

            // 6. 服务端主动查单二次确认（防止伪造回调）
            PaymentChannel orderChannel = resolveChannelForOrder(order, "native_wxpay");
            WxpayOrderQueryResult queryResult = wxpayService.queryOrder(
                    paymentService.buildWxpayConfig(orderChannel), orderId.toString());
            if (queryResult == null) {
                log.warn("Wxpay callback deferred: server-side order query failed, out_trade_no={}", orderId);
                return "FAIL";
            }
            if (!"SUCCESS".equals(queryResult.tradeState())) {
                log.error("Wxpay callback rejected: query trade_state={}, out_trade_no={}",
                        queryResult.tradeState(), orderId);
                saveWebhookEvent(eventId, "wxpay", orderId, rawBody, "QUERY_STATUS_MISMATCH");
                return "FAIL";
            }

            // 7. 幂等更新订单状态（PENDING / EXPIRED 均可标记 PAID，与 USDT 行为一致）
            if (order.getStatus() == OrderStatus.PENDING || order.getStatus() == OrderStatus.EXPIRED) {
                order.setStatus(OrderStatus.PAID);
                order.setPaidAt(LocalDateTime.now());
                orderRepository.save(order);
                saveWebhookEvent(eventId, "wxpay", orderId, rawBody, "SUCCESS");
                log.info("Wxpay callback: order {} marked as PAID", orderId);
            } else {
                saveWebhookEvent(eventId, "wxpay", orderId, rawBody,
                        "SKIPPED_" + order.getStatus().name());
                log.info("Wxpay callback: order {} already {}", orderId, order.getStatus());
            }
            return "SUCCESS";
        } catch (Exception e) {
            log.error("Wxpay callback processing error", e);
            return "FAIL";
        }
    }

    /**
     * 原生支付宝异步通知处理：
     * 1) RSA2 验签 + app_id 校验
     * 2) 幂等检查（交易号 trade_no）
     * 3) 金额校验
     * 4) 服务端主动查单二次确认（防止伪造回调）
     * 5) 标记订单 PAID
     */
    @Override
    @Transactional
    public String processAlipayCallback(Map<String, String> params) {
        String outTradeNo = params.get("out_trade_no");
        String tradeNo = params.get("trade_no");
        String tradeStatus = params.get("trade_status");
        String totalAmount = params.get("total_amount");
        String appId = params.get("app_id");
        String sign = params.get("sign");

        log.info("Alipay callback: out_trade_no={}, trade_status={}, total_amount={}",
                outTradeNo, tradeStatus, totalAmount);

        // 1. 解析订单
        UUID orderId;
        try {
            orderId = UUID.fromString(outTradeNo);
        } catch (Exception e) {
            log.error("Alipay callback invalid out_trade_no: {}", outTradeNo);
            return "fail";
        }
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("Alipay callback order not found: {}, returning fail to trigger retry", orderId);
            return "fail";
        }

        // 2. 幂等检查（支付宝每个交易号 trade_no 唯一）
        String eventId = "alipay_" + (tradeNo != null ? tradeNo : orderId);
        if (webhookEventRepository.findByEventId(eventId).isPresent()) {
            log.info("Alipay callback already processed: {}", eventId);
            return "success";
        }

        // 3. 验签 + app_id 校验
        PaymentChannel orderChannel = resolveChannelForOrder(order, "native_alipay");
        AlipayConfig config = paymentService.buildAlipayConfig(orderChannel);
        if (!alipayService.verifySign(config.alipayPublicKey(), params, sign)) {
            log.error("Alipay callback signature verification failed: out_trade_no={}", outTradeNo);
            return "fail";
        }
        if (appId != null && !appId.equals(config.appId())) {
            log.error("Alipay callback app_id mismatch: expected={}, actual={}, out_trade_no={}",
                    config.appId(), appId, outTradeNo);
            return "fail";
        }

        // 4. 仅处理支付成功状态（WAIT_BUYER_PAY / TRADE_CLOSED 等直接应答）
        if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
            log.info("Alipay callback non-success status: {}, skipping (not saved to idempotency table)", tradeStatus);
            return "success";
        }

        // 5. 金额校验
        if (totalAmount == null || totalAmount.isBlank()) {
            log.error("Alipay callback missing total_amount: out_trade_no={}", outTradeNo);
            return "fail";
        }
        BigDecimal callbackAmount;
        try {
            callbackAmount = new BigDecimal(totalAmount);
        } catch (NumberFormatException e) {
            log.error("Alipay callback invalid total_amount: {}, out_trade_no={}", totalAmount, outTradeNo);
            return "fail";
        }
        if (order.getActualAmount().compareTo(callbackAmount) != 0) {
            log.error("Alipay callback amount mismatch: order={}, callback={}",
                    order.getActualAmount(), callbackAmount);
            saveWebhookEvent(eventId, "alipay", orderId, params.toString(), "AMOUNT_MISMATCH");
            return "fail";
        }

        // 6. 服务端主动查单二次确认（防止伪造回调）
        AlipayOrderQueryResult queryResult = alipayService.queryOrder(config, outTradeNo);
        if (queryResult == null) {
            log.warn("Alipay callback deferred: server-side order query failed, out_trade_no={}", outTradeNo);
            return "fail";
        }
        if (!"TRADE_SUCCESS".equals(queryResult.tradeStatus()) && !"TRADE_FINISHED".equals(queryResult.tradeStatus())) {
            log.error("Alipay callback rejected: query trade_status={}, out_trade_no={}",
                    queryResult.tradeStatus(), outTradeNo);
            saveWebhookEvent(eventId, "alipay", orderId, params.toString(), "QUERY_STATUS_MISMATCH");
            return "fail";
        }
        if (queryResult.totalAmount() != null) {
            try {
                BigDecimal queryAmount = new BigDecimal(queryResult.totalAmount());
                if (order.getActualAmount().compareTo(queryAmount) != 0) {
                    log.error("Alipay callback rejected: query amount={}, order amount={}, out_trade_no={}",
                            queryAmount, order.getActualAmount(), outTradeNo);
                    saveWebhookEvent(eventId, "alipay", orderId, params.toString(), "QUERY_AMOUNT_MISMATCH");
                    return "fail";
                }
            } catch (NumberFormatException e) {
                log.warn("Alipay order query returned invalid amount: {}", queryResult.totalAmount());
            }
        }

        // 7. 幂等更新订单状态（PENDING / EXPIRED 均可标记 PAID，与 USDT 行为一致）
        if (order.getStatus() == OrderStatus.PENDING || order.getStatus() == OrderStatus.EXPIRED) {
            order.setStatus(OrderStatus.PAID);
            order.setPaidAt(LocalDateTime.now());
            orderRepository.save(order);
            saveWebhookEvent(eventId, "alipay", orderId, params.toString(), "SUCCESS");
            log.info("Alipay callback: order {} marked as PAID", orderId);
        } else {
            saveWebhookEvent(eventId, "alipay", orderId, params.toString(),
                    "SKIPPED_" + order.getStatus().name());
            log.info("Alipay callback: order {} already {}", orderId, order.getStatus());
        }
        return "success";
    }

    private void saveWebhookEvent(String eventId, String channelCode, UUID orderId,
                                   String payload, String processResult) {
        WebhookEvent event = new WebhookEvent();
        event.setEventId(eventId);
        event.setChannelCode(channelCode);
        event.setOrderId(orderId != null ? orderId : UUID.fromString("00000000-0000-0000-0000-000000000000"));
        event.setPayload(payload);
        event.setProcessResult(processResult);
        webhookEventRepository.save(event);
    }

    /**
     * 从已有 Order 对象查找渠道 config_data 中的 BEpusdt API Token。
     */
    private String resolveBepusdtApiToken(Order order) {
        if (order.getPaymentMethod() != null) {
            PaymentChannel channel = paymentChannelRepository
                    .findByChannelCodeAndIsDeleted(order.getPaymentMethod(), 0)
                    .orElse(null);
            if (channel != null && channel.getConfigData() != null) {
                try {
                    Map<String, Object> cfg = objectMapper.readValue(
                            channel.getConfigData(), new TypeReference<>() {});
                    Object token = cfg.get("api_token");
                    if (token != null && !token.toString().isBlank()) {
                        return token.toString();
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse channel config for api_token: {}", e.getMessage());
                }
            }
        }
        log.warn("Cannot resolve BEpusdt API token for order {}", order.getId());
        return null;
    }

    /**
     * 根据订单的 paymentMethod 查找渠道 config_data 中的 merchant key。
     * 所有配置均从数据库读取，缺失则抛出异常。
     */
    private String resolveMerchantKey(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order != null && order.getPaymentMethod() != null) {
            PaymentChannel channel = paymentChannelRepository
                    .findByChannelCodeAndIsDeleted(order.getPaymentMethod(), 0)
                    .orElse(null);
            if (channel != null && channel.getConfigData() != null) {
                try {
                    Map<String, Object> cfg = objectMapper.readValue(
                            channel.getConfigData(), new TypeReference<>() {});
                    Object key = cfg.get("key");
                    if (key != null && !key.toString().isBlank()) {
                        return key.toString();
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse channel config for key resolution: {}", e.getMessage());
                }
            }
        }
        log.error("Cannot resolve merchant key for order {}: channel config missing 'key' field", orderId);
        throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                "支付渠道配置缺少 key，请在后台「支付渠道管理」中完善配置");
    }

    /**
     * 判断查询 API 返回的 status 是否表示"已支付"。
     * 不同 Epay 网关实现可能返回 "TRADE_SUCCESS"（字符串）或 "1"（数字），兼容两种格式。
     */
    private boolean isQueryStatusPaid(String status) {
        return "TRADE_SUCCESS".equals(status) || "1".equals(status);
    }

    /**
     * 根据订单的支付渠道解析渠道配置（校验 providerType 匹配）。
     */
    private PaymentChannel resolveChannelForOrder(Order order, String providerType) {
        PaymentChannel channel = null;
        if (order.getPaymentMethod() != null) {
            channel = paymentChannelRepository
                    .findByChannelCodeAndIsDeleted(order.getPaymentMethod(), 0)
                    .orElse(null);
        }
        if (channel == null || !providerType.equals(channel.getProviderType())) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                    "支付渠道配置缺失或类型不匹配: " + order.getPaymentMethod());
        }
        return channel;
    }

    /**
     * 从订单关联的支付渠道解析完整的 ChannelConfig（pid/key/apiUrl/notifyUrl/returnUrl）。
     * 用于 webhook 回调后发起服务端主动查询。配置不完整时返回 null（降级为仅签名校验）。
     */
    private EpayService.ChannelConfig resolveChannelConfig(Order order) {
        if (order.getPaymentMethod() == null) return null;
        PaymentChannel channel = paymentChannelRepository
                .findByChannelCodeAndIsDeleted(order.getPaymentMethod(), 0)
                .orElse(null);
        if (channel == null) return null;
        try {
            return paymentService.buildChannelConfig(channel);
        } catch (Exception e) {
            log.warn("Failed to build ChannelConfig for order query: {}", e.getMessage());
            return null;
        }
    }
}
