package com.orionkey.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.constant.ErrorCode;
import com.orionkey.entity.Order;
import com.orionkey.entity.OrderItem;
import com.orionkey.entity.PaymentChannel;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.OrderItemRepository;
import com.orionkey.repository.OrderRepository;
import com.orionkey.repository.PaymentChannelRepository;
import com.orionkey.service.BepusdtService;
import com.orionkey.service.BepusdtService.BepusdtConfig;
import com.orionkey.service.BepusdtService.BepusdtPaymentResult;
import com.orionkey.service.AlipayService;
import com.orionkey.service.AlipayService.AlipayConfig;
import com.orionkey.service.AlipayService.AlipayOrderQueryResult;
import com.orionkey.service.AlipayService.AlipayPaymentResult;
import com.orionkey.service.EpayService;
import com.orionkey.service.EpayService.ChannelConfig;
import com.orionkey.service.EpayService.EpayResult;
import com.orionkey.service.WxpayService;
import com.orionkey.service.WxpayService.WxpayConfig;
import com.orionkey.service.WxpayService.WxpayOrderQueryResult;
import com.orionkey.service.WxpayService.WxpayPaymentResult;
import com.orionkey.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    /** channel_code → 易支付 type 映射（仅 provider_type=epay 时使用） */
    private static final Map<String, String> EPAY_TYPE_MAP = Map.of(
            "alipay", "alipay",
            "wechat", "wxpay"
    );

    private final PaymentChannelRepository paymentChannelRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final EpayService epayService;
    private final BepusdtService bepusdtService;
    private final WxpayService wxpayService;
    private final AlipayService alipayService;
    private final ObjectMapper objectMapper;

    /** 应用公网地址（application.yml: app.base-url），用于自动生成微信/支付宝回调地址 */
    @Value("${app.base-url:http://localhost:8083}")
    private String appBaseUrl;

    /** 原生微信支付回调路径（相对 context-path） */
    private static final String WXPAY_WEBHOOK_PATH = "/api/payments/webhook/wxpay";
    /** 原生支付宝回调路径（相对 context-path） */
    private static final String ALIPAY_WEBHOOK_PATH = "/api/payments/webhook/alipay";

    @Override
    public Map<String, Object> createPayment(UUID orderId, String paymentMethod, BigDecimal amount) {
        return createPayment(orderId, paymentMethod, amount, null);
    }

    @Override
    public Map<String, Object> createPayment(UUID orderId, String paymentMethod, BigDecimal amount, String device) {
        // 1. 查找渠道并验证已启用
        PaymentChannel channel = paymentChannelRepository.findByChannelCodeAndIsDeleted(paymentMethod, 0)
                .filter(PaymentChannel::isEnabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付渠道不可用"));

        // 2. 查找订单
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));

        // 3. 幂等：已有支付URL直接返回（paymentUrl 或 qrcodeUrl 任一存在即可）
        if ((order.getPaymentUrl() != null && !order.getPaymentUrl().isEmpty())
                || (order.getQrcodeUrl() != null && !order.getQrcodeUrl().isEmpty())) {
            log.info("Returning cached payment URL for order: {}", orderId);
            return buildResult(order);
        }

        // 4. 按 providerType 路由到不同的支付实现
        String providerType = channel.getProviderType();
        switch (providerType) {
            case "epay" -> createEpayPayment(channel, order, paymentMethod, amount, device);
            case "native_alipay" -> createNativeAlipayPayment(channel, order, amount, device);
            case "native_wxpay" -> createNativeWxpayPayment(channel, order, amount);
            case "usdt" -> createBepusdtPayment(channel, order, amount);
            default -> throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "不支持的支付提供商类型: " + providerType);
        }

        return buildResult(order);
    }

    /**
     * BEpusdt USDT 下单流程
     */
    private void createBepusdtPayment(PaymentChannel channel, Order order, BigDecimal amount) {
        BepusdtConfig config = buildBepusdtConfig(channel);
        String productName = buildProductName(order.getId());

        BepusdtPaymentResult result = bepusdtService.createPayment(
                config, order.getId().toString(), amount, productName);

        order.setPaymentUrl(result.paymentUrl());
        order.setUsdtWalletAddress(result.walletAddress());
        order.setUsdtCryptoAmount(result.cryptoAmount());
        order.setUsdtTradeId(result.tradeId());
        order.setUsdtChain(channel.getChannelCode());
        orderRepository.save(order);
    }

    /**
     * 从渠道的 config_data JSON 构建 BepusdtConfig。
     */
    public BepusdtConfig buildBepusdtConfig(PaymentChannel channel) {
        Map<String, String> cfg = parseConfigData(channel.getConfigData());

        String apiUrl = requireConfig(cfg, "api_url", channel.getChannelCode());
        String apiToken = requireConfig(cfg, "api_token", channel.getChannelCode());
        String notifyUrl = requireConfig(cfg, "notify_url", channel.getChannelCode());
        String redirectUrl = cfg.getOrDefault("redirect_url", "");
        String tradeType = cfg.getOrDefault("trade_type", "usdt.trc20");
        String fiat = cfg.getOrDefault("fiat", "CNY");
        int timeout = Integer.parseInt(cfg.getOrDefault("timeout", "900"));
        String fixedRate = cfg.getOrDefault("fixed_rate", "");

        return new BepusdtConfig(apiUrl, apiToken, notifyUrl, redirectUrl,
                tradeType, fiat, timeout, fixedRate);
    }

    /**
     * 易支付下单流程
     */
    private void createEpayPayment(PaymentChannel channel, Order order, String paymentMethod, BigDecimal amount, String device) {
        String epayType = EPAY_TYPE_MAP.get(paymentMethod.toLowerCase());
        if (epayType == null) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "该渠道不支持易支付");
        }

        ChannelConfig config = buildChannelConfig(channel);
        String productName = buildProductName(order.getId());

        EpayResult epayResult = epayService.createPayment(
                config,
                order.getId().toString(),
                epayType,
                productName,
                amount,
                order.getClientIp(),
                device
        );

        // 分别存储：payUrl 是 H5 跳转链接，qrcodeUrl 是二维码 URL
        order.setPaymentUrl(epayResult.payUrl());
        order.setQrcodeUrl(epayResult.qrcodeUrl());
        order.setEpayTradeNo(epayResult.tradeNo());
        orderRepository.save(order);
    }

    /**
     * 原生微信支付（APIv3 Native 扫码）下单流程
     */
    private void createNativeWxpayPayment(PaymentChannel channel, Order order, BigDecimal amount) {
        WxpayConfig config = buildWxpayConfig(channel);
        String productName = buildProductName(order.getId());

        WxpayPaymentResult result = wxpayService.createNativePayment(
                config, order.getId().toString(), productName, amount, order.getClientIp());

        order.setQrcodeUrl(result.codeUrl());
        orderRepository.save(order);
    }

    /**
     * 原生支付宝下单流程：PC 使用当面付预下单（二维码），移动端使用 WAP 手机网站支付（跳转链接）
     */
    private void createNativeAlipayPayment(PaymentChannel channel, Order order, BigDecimal amount, String device) {
        AlipayConfig config = buildAlipayConfig(channel);
        String productName = buildProductName(order.getId());

        boolean pc = device == null || "pc".equals(device);
        if (pc) {
            AlipayPaymentResult result = alipayService.createPrecreate(
                    config, order.getId().toString(), productName, amount);
            order.setQrcodeUrl(result.qrCode());
        } else {
            String wapPayUrl = alipayService.buildWapPayUrl(
                    config, order.getId().toString(), productName, amount);
            order.setPaymentUrl(wapPayUrl);
        }
        orderRepository.save(order);
    }

    /**
     * 从渠道的 config_data JSON 构建 WxpayConfig。
     * 商户私钥支持两种方式：private_key 直接填写 PEM 内容，或 private_key_path 指定文件路径。
     * 回调地址由系统根据 app.base-url 自动生成，无需手动配置。
     */
    public WxpayConfig buildWxpayConfig(PaymentChannel channel) {
        Map<String, String> cfg = parseConfigData(channel.getConfigData());

        String appid = requireConfig(cfg, "appid", channel.getChannelCode());
        String mchid = requireConfig(cfg, "mchid", channel.getChannelCode());
        String apiV3Key = requireConfig(cfg, "api_v3_key", channel.getChannelCode());
        String serialNo = requireConfig(cfg, "serial_no", channel.getChannelCode());

        // 私钥：优先使用 PEM 内容；若配置的是文件路径则读取文件
        String privateKey = resolveWxpayPrivateKey(cfg, channel.getChannelCode());

        // 回调地址自动生成（与 WebhookController 端点一致）
        String notifyUrl = trimTrailingSlash(appBaseUrl) + WXPAY_WEBHOOK_PATH;

        return new WxpayConfig(appid, mchid, apiV3Key, serialNo, privateKey, notifyUrl, "https://api.mch.weixin.qq.com");
    }

    /**
     * 解析微信支付商户私钥：private_key（PEM 内容）优先，其次 private_key_path（文件路径）。
     */
    private String resolveWxpayPrivateKey(Map<String, String> cfg, String channelCode) {
        String pem = cfg.get("private_key");
        if (pem != null && !pem.isBlank()) {
            // 包含 PEM 头则视为内容，否则按文件路径处理（兼容旧配置）
            if (pem.contains("-----BEGIN")) {
                return pem;
            }
            return readPrivateKeyFile(pem, channelCode);
        }
        String path = requireConfig(cfg, "private_key_path", channelCode);
        return readPrivateKeyFile(path, channelCode);
    }

    private String readPrivateKeyFile(String path, String channelCode) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                    "支付渠道 [" + channelCode + "] 读取微信支付商户私钥文件失败: " + path);
        }
    }

    /**
     * 从渠道的 config_data JSON 构建 AlipayConfig。
     * 回调地址由系统根据 app.base-url 自动生成，无需手动配置；网关固定使用支付宝官方网关。
     */
    public AlipayConfig buildAlipayConfig(PaymentChannel channel) {
        Map<String, String> cfg = parseConfigData(channel.getConfigData());

        String appId = requireConfig(cfg, "appid", channel.getChannelCode());
        String privateKey = requireConfig(cfg, "private_key", channel.getChannelCode());
        String alipayPublicKey = requireConfig(cfg, "alipay_public_key", channel.getChannelCode());

        // 回调地址自动生成（与 WebhookController 端点一致）
        String notifyUrl = trimTrailingSlash(appBaseUrl) + ALIPAY_WEBHOOK_PATH;

        return new AlipayConfig(appId, privateKey, alipayPublicKey, "https://openapi.alipay.com/gateway.do", notifyUrl);
    }

    /** 去掉 base-url 结尾多余的斜杠，避免拼接出双斜杠 */
    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) return url;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * 从渠道的 config_data JSON 构建 EpayService.ChannelConfig。
     * 所有必填字段均从数据库渠道配置读取，缺失则抛出异常。
     */
    public ChannelConfig buildChannelConfig(PaymentChannel channel) {
        Map<String, String> cfg = parseConfigData(channel.getConfigData());

        String pid = requireConfig(cfg, "pid", channel.getChannelCode());
        String key = requireConfig(cfg, "key", channel.getChannelCode());
        String apiUrl = requireConfig(cfg, "api_url", channel.getChannelCode());
        String notifyUrl = requireConfig(cfg, "notify_url", channel.getChannelCode());
        String returnUrl = requireConfig(cfg, "return_url", channel.getChannelCode());

        return new ChannelConfig(pid, key, apiUrl, notifyUrl, returnUrl);
    }

    private Map<String, Object> buildResult(Order order) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getId());
        // payment_url: 兼容旧逻辑，优先返回 qrcodeUrl（PC 二维码），fallback 到 paymentUrl（H5 跳转）
        String effectiveUrl = order.getQrcodeUrl() != null ? order.getQrcodeUrl() : order.getPaymentUrl();
        result.put("payment_url", effectiveUrl);
        result.put("qrcode_url", order.getQrcodeUrl());
        result.put("pay_url", order.getPaymentUrl());
        result.put("expires_at", order.getExpiresAt());

        // USDT 支付额外字段
        if (order.getUsdtWalletAddress() != null) {
            result.put("wallet_address", order.getUsdtWalletAddress());
            result.put("crypto_amount", order.getUsdtCryptoAmount());
            result.put("chain", order.getUsdtChain());
        }
        return result;
    }

    private String buildProductName(UUID orderId) {
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        if (items.isEmpty()) return "Orion Key 订单";
        String firstName = items.getFirst().getProductTitle();
        if (items.size() == 1) return firstName;
        return firstName + " 等" + items.size() + "件商品";
    }

    private Map<String, String> parseConfigData(String configData) {
        if (configData == null || configData.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(configData, new TypeReference<>() {});
            Map<String, String> result = new LinkedHashMap<>();
            for (var entry : raw.entrySet()) {
                if (entry.getValue() != null) {
                    result.put(entry.getKey(), entry.getValue().toString());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse channel config_data: {}", e.getMessage());
            return Map.of();
        }
    }

    /** repay 最小间隔（秒），防止频繁调用冲击支付网关 */
    private static final int REPAY_COOLDOWN_SECONDS = 10;

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> repay(UUID orderId, String device, UUID requestUserId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));

        // F9: 归属校验 — 已登录用户只能 repay 自己的订单
        if (order.getUserId() != null && requestUserId != null
                && !order.getUserId().equals(requestUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作此订单");
        }

        if (order.getStatus() != com.orionkey.constant.OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "订单状态不允许重新支付");
        }

        if (order.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
            order.setStatus(com.orionkey.constant.OrderStatus.EXPIRED);
            orderRepository.save(order);
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "订单已过期");
        }

        // 频率限制：距上次更新不足 REPAY_COOLDOWN_SECONDS 秒则拒绝
        if (order.getUpdatedAt() != null
                && order.getUpdatedAt().plusSeconds(REPAY_COOLDOWN_SECONDS).isAfter(java.time.LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "操作过于频繁，请稍后再试");
        }

        // 清除旧支付信息，跳过幂等缓存
        order.setPaymentUrl(null);
        order.setQrcodeUrl(null);
        order.setEpayTradeNo(null);
        orderRepository.save(order);

        // 重新创建支付
        return createPayment(order.getId(), order.getPaymentMethod(), order.getActualAmount(), device);
    }

    private static String requireConfig(Map<String, String> cfg, String field, String channelCode) {
        String value = cfg.get(field);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                    "支付渠道 [" + channelCode + "] 缺少必填配置项: " + field + "，请在后台「支付渠道管理」中完善配置");
        }
        return value;
    }

    // ════════════════ 主动查单 ════════════════

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 主动查单最小间隔（毫秒），防止轮询高频冲击支付网关 */
    private static final long ACTIVE_QUERY_INTERVAL_MS = 20_000;

    /** 订单 ID → 最近一次主动查单时间戳 */
    private final Map<UUID, Long> lastActiveQueryAt = new ConcurrentHashMap<>();

    @Override
    @Transactional
    public boolean settleByActiveQuery(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != com.orionkey.constant.OrderStatus.PENDING
                || order.getPaymentMethod() == null) {
            return false;
        }
        PaymentChannel channel = paymentChannelRepository
                .findByChannelCodeAndIsDeleted(order.getPaymentMethod(), 0)
                .filter(PaymentChannel::isEnabled)
                .orElse(null);
        if (channel == null) return false;

        BigDecimal expected = order.getActualAmount();
        boolean paid = switch (channel.getProviderType()) {
            case "native_wxpay" -> {
                WxpayOrderQueryResult r = wxpayService.queryOrder(
                        buildWxpayConfig(channel), order.getId().toString());
                yield r != null && "SUCCESS".equals(r.tradeState())
                        && r.total() != null
                        && BigDecimal.valueOf(r.total()).compareTo(expected.multiply(HUNDRED)) == 0;
            }
            case "native_alipay" -> {
                AlipayOrderQueryResult r = alipayService.queryOrder(
                        buildAlipayConfig(channel), order.getId().toString());
                yield r != null && isAlipayPaid(r.tradeStatus())
                        && r.totalAmount() != null
                        && new BigDecimal(r.totalAmount()).compareTo(expected) == 0;
            }
            case "epay" -> {
                EpayService.OrderQueryResult r = epayService.queryOrder(
                        buildChannelConfig(channel), order.getId().toString());
                yield r != null && isQueryStatusPaid(r.tradeStatus())
                        && r.money() != null
                        && new BigDecimal(r.money()).compareTo(expected) == 0;
            }
            default -> false;
        };

        if (paid) {
            markPaid(order, channel.getProviderType());
        }
        return paid;
    }

    @Override
    @Transactional
    public boolean maybeSettleByActiveQuery(UUID orderId) {
        long now = System.currentTimeMillis();
        Long last = lastActiveQueryAt.get(orderId);
        if (last != null && now - last < ACTIVE_QUERY_INTERVAL_MS) {
            return false;
        }
        lastActiveQueryAt.put(orderId, now);
        return settleByActiveQuery(orderId);
    }

    private void markPaid(Order order, String providerType) {
        if (order.getStatus() == com.orionkey.constant.OrderStatus.PENDING) {
            order.setStatus(com.orionkey.constant.OrderStatus.PAID);
            order.setPaidAt(java.time.LocalDateTime.now());
            orderRepository.save(order);
            log.info("Active query: order {} marked as PAID via {}", order.getId(), providerType);
        }
    }

    /** 定期清理主动查单节流缓存，防止订单堆积导致内存增长 */
    @Scheduled(fixedRate = 3_600_000)
    public void cleanupActiveQueryThrottle() {
        lastActiveQueryAt.clear();
    }

    private boolean isQueryStatusPaid(String status) {
        return "TRADE_SUCCESS".equals(status) || "1".equals(status);
    }

    private boolean isAlipayPaid(String tradeStatus) {
        return "TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus);
    }
}
