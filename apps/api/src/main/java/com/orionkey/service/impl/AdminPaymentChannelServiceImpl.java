package com.orionkey.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.constant.ErrorCode;
import com.orionkey.entity.PaymentChannel;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.PaymentChannelRepository;
import com.orionkey.service.AdminPaymentChannelService;
import com.orionkey.service.AlipayService;
import com.orionkey.service.WxpayService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
@RequiredArgsConstructor
public class AdminPaymentChannelServiceImpl implements AdminPaymentChannelService {

    private final PaymentChannelRepository paymentChannelRepository;
    private final ObjectMapper objectMapper;
    private final WxpayService wxpayService;
    private final AlipayService alipayService;
    private final PaymentServiceImpl paymentService;
    private final RestTemplate restTemplate;

    /** 应用公网地址（application.yml: app.base-url），用于自动生成原生支付回调地址 */
    @Value("${app.base-url:http://localhost:8083}")
    private String appBaseUrl;

    /** 原生微信/支付宝回调路径（相对 context-path） */
    private static final String WXPAY_WEBHOOK_PATH = "/api/payments/webhook/wxpay";
    private static final String ALIPAY_WEBHOOK_PATH = "/api/payments/webhook/alipay";

    @Override
    public List<?> listChannels() {
        return paymentChannelRepository.findByIsDeletedOrderBySortOrderAsc(0).stream()
                .map(this::toMap).toList();
    }

    @Override
    @Transactional
    public void createChannel(Map<String, Object> req) {
        PaymentChannel channel = new PaymentChannel();
        channel.setChannelCode((String) req.get("channel_code"));
        channel.setChannelName((String) req.get("channel_name"));
        if (req.containsKey("provider_type")) {
            channel.setProviderType((String) req.get("provider_type"));
        }
        if (req.containsKey("config_data")) {
            channel.setConfigData(serializeConfigData(req.get("config_data")));
        }
        if (req.containsKey("is_enabled")) channel.setEnabled((boolean) req.get("is_enabled"));
        if (req.containsKey("sort_order")) channel.setSortOrder(((Number) req.get("sort_order")).intValue());
        paymentChannelRepository.save(channel);
    }

    @Override
    @Transactional
    public void updateChannel(UUID id, Map<String, Object> req) {
        PaymentChannel channel = paymentChannelRepository.findById(id)
                .filter(c -> c.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "支付渠道不存在"));
        if (req.containsKey("channel_name")) channel.setChannelName((String) req.get("channel_name"));
        if (req.containsKey("config_data")) {
            channel.setConfigData(mergeConfigData(req.get("config_data"), channel.getConfigData()));
        }
        if (req.containsKey("is_enabled")) channel.setEnabled((boolean) req.get("is_enabled"));
        if (req.containsKey("sort_order")) channel.setSortOrder(((Number) req.get("sort_order")).intValue());
        paymentChannelRepository.save(channel);
    }

    @Override
    @Transactional
    public void deleteChannel(UUID id) {
        PaymentChannel channel = paymentChannelRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "支付渠道不存在"));
        channel.setIsDeleted(1);
        paymentChannelRepository.save(channel);
    }

    @Override
    public Map<String, Object> testChannel(UUID id) {
        PaymentChannel channel = paymentChannelRepository.findById(id)
                .filter(c -> c.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "支付渠道不存在"));
        try {
            String message = switch (channel.getProviderType()) {
                case "native_wxpay" -> wxpayService.testConnection(paymentService.buildWxpayConfig(channel));
                case "native_alipay" -> alipayService.testConnection(paymentService.buildAlipayConfig(channel));
                case "epay" -> testEpayConnectivity(channel);
                case "usdt" -> testBepusdtConnectivity(channel);
                default -> throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                        "暂不支持测试该渠道类型：" + channel.getProviderType());
            };
            return Map.of("success", true, "message", message);
        } catch (BusinessException e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getRawConfig(UUID id) {
        PaymentChannel channel = paymentChannelRepository.findById(id)
                .filter(c -> c.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "支付渠道不存在"));
        Map<String, Object> config = deserializeConfigData(channel.getConfigData());
        if (config == null) {
            config = new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>(config);
        // 附上系统生成的回调地址，方便前端统一展示
        Object notifyUrl = withDefaultNotifyUrl(channel).get("notify_url");
        if (notifyUrl != null) {
            result.put("notify_url", notifyUrl);
        }
        return result;
    }

    /** 易支付渠道测试：校验配置完整性 + 探测网关可达性 */
    private String testEpayConnectivity(PaymentChannel channel) {
        Map<String, Object> cfg = deserializeConfigData(channel.getConfigData());
        String pid = strValue(cfg, "pid");
        String key = strValue(cfg, "key");
        String apiUrl = strValue(cfg, "api_url");
        if (pid.isBlank() || key.isBlank() || apiUrl.isBlank()) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                    "易支付配置不完整：请填写 商户ID(pid)、商户密钥(key)、API地址(api_url)");
        }
        probeGateway(apiUrl, "易支付");
        return "连接成功：易支付网关可访问，商户配置完整（PID=" + pid
                + "）。建议用一笔 0.01 元订单实际验证收款回调";
    }

    /** USDT（BEpusdt）渠道测试：校验配置完整性 + 探测服务可达性 */
    private String testBepusdtConnectivity(PaymentChannel channel) {
        Map<String, Object> cfg = deserializeConfigData(channel.getConfigData());
        String apiUrl = strValue(cfg, "api_url");
        String apiToken = strValue(cfg, "api_token");
        if (apiUrl.isBlank() || apiToken.isBlank()) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                    "USDT 配置不完整：请填写 BEpusdt 服务地址(api_url)、API Token(api_token)");
        }
        probeGateway(apiUrl, "BEpusdt");
        return "连接成功：BEpusdt 服务可访问，API Token 已配置";
    }

    /** 探测支付网关/服务地址可达性：4xx/5xx 也视为可达（网关在线），网络异常才报错 */
    private void probeGateway(String apiUrl, String name) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(java.util.List.of(MediaType.TEXT_HTML, MediaType.APPLICATION_JSON, MediaType.ALL));
            restTemplate.exchange(apiUrl, org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
        } catch (HttpStatusCodeException ignored) {
            // 网关返回 4xx/5xx 说明服务在线
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                    "无法访问 " + name + " 服务 " + apiUrl + "：" + e.getMessage());
        }
    }

    private static String strValue(Map<String, Object> cfg, String key) {
        if (cfg == null) return "";
        Object v = cfg.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 需要在 API 响应中脱敏的敏感字段名 */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "api_token", "key", "secret", "password", "private_key",
            "api_v3_key", "alipay_public_key", "api_key"
    );

    private Map<String, Object> toMap(PaymentChannel c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("channel_code", c.getChannelCode());
        map.put("channel_name", c.getChannelName());
        map.put("provider_type", c.getProviderType());
        map.put("config_data", maskSensitiveConfigData(withDefaultNotifyUrl(c)));
        map.put("is_enabled", c.isEnabled());
        map.put("sort_order", c.getSortOrder());
        map.put("created_at", c.getCreatedAt());
        return map;
    }

    /**
     * 原生微信/支付宝渠道：回调地址始终由系统按站点公网地址（app.base-url）生成，
     * 不依赖数据库存值（put 覆盖），保证前端始终能展示完整回调地址，
     * 且站点域名变更后地址自动跟随，不会沿用旧地址。
     */
    private Map<String, Object> withDefaultNotifyUrl(PaymentChannel c) {
        Map<String, Object> config = deserializeConfigData(c.getConfigData());
        if (config == null) {
            config = new LinkedHashMap<>();
        }
        String webhookPath = switch (c.getProviderType()) {
            case "native_wxpay" -> WXPAY_WEBHOOK_PATH;
            case "native_alipay" -> ALIPAY_WEBHOOK_PATH;
            default -> null;
        };
        if (webhookPath != null) {
            String base = appBaseUrl == null ? "" : appBaseUrl.endsWith("/")
                    ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
            config.put("notify_url", base + webhookPath);
        }
        return config;
    }

    /**
     * 对 configData 中的敏感字段进行脱敏处理，前端仅显示部分字符。
     */
    private Map<String, Object> maskSensitiveConfigData(Map<String, Object> configData) {
        if (configData == null) return null;
        Map<String, Object> masked = new LinkedHashMap<>(configData);
        for (String sensitiveKey : SENSITIVE_KEYS) {
            if (masked.containsKey(sensitiveKey)) {
                Object val = masked.get(sensitiveKey);
                if (val instanceof String s && !s.isEmpty()) {
                    // 保留前 4 位和后 2 位，中间用 **** 替代
                    if (s.length() <= 8) {
                        masked.put(sensitiveKey, s.substring(0, Math.min(2, s.length())) + "****");
                    } else {
                        masked.put(sensitiveKey, s.substring(0, 4) + "****" + s.substring(s.length() - 2));
                    }
                }
            }
        }
        return masked;
    }

    /**
     * 合并新配置与旧配置：对敏感字段，如果新值包含掩码标记 (****)，
     * 则保留数据库中的原始值，防止管理员编辑渠道时将脱敏值覆写回数据库。
     */
    @SuppressWarnings("unchecked")
    private String mergeConfigData(Object newConfigData, String existingConfigDataJson) {
        if (newConfigData == null) return null;

        Map<String, Object> newConfig;
        if (newConfigData instanceof Map<?, ?> m) {
            newConfig = new LinkedHashMap<>((Map<String, Object>) m);
        } else if (newConfigData instanceof String s) {
            Map<String, Object> parsed = deserializeConfigData(s);
            if (parsed == null) return s;
            newConfig = new LinkedHashMap<>(parsed);
        } else {
            return serializeConfigData(newConfigData);
        }

        Map<String, Object> oldConfig = deserializeConfigData(existingConfigDataJson);
        if (oldConfig != null) {
            for (String sensitiveKey : SENSITIVE_KEYS) {
                Object newVal = newConfig.get(sensitiveKey);
                if (newVal instanceof String s && s.contains("****")) {
                    Object oldVal = oldConfig.get(sensitiveKey);
                    if (oldVal != null) {
                        newConfig.put(sensitiveKey, oldVal);
                    }
                }
            }
        }

        return serializeConfigData(newConfig);
    }

    private String serializeConfigData(Object configData) {
        if (configData == null) return null;
        if (configData instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(configData);
        } catch (JsonProcessingException e) {
            return configData.toString();
        }
    }

    private Map<String, Object> deserializeConfigData(String configData) {
        if (configData == null || configData.isBlank()) return null;
        try {
            return objectMapper.readValue(configData, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
