package com.orionkey.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.exception.BusinessException;
import com.orionkey.service.WxpayService.WxpayConfig;
import com.orionkey.service.WxpayService.WxpayOrderQueryResult;
import com.orionkey.service.WxpayService.WxpayRefundQueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 原生微信支付服务层单元测试（离线，Mock RestTemplate，不访问真实商户环境）。
 * 重点覆盖历史缺陷修复后的行为契约：
 * - queryOrder：404 ORDER_NOT_EXIST 视为「订单不存在」正常结果，网关/网络异常才标记 error
 * - queryRefund：404 RESOURCE_NOT_EXISTS 视为正常结果并回显退款单号，其他异常标记 error
 * - createRefund：金额分转换采用 DOWN 截断（不再向上取整），微信拒绝受理时不当作成功
 * - serialMatches：平台证书 serial 前导补零比较
 * - truncate：错误详情截断，避免超长日志
 */
class WxpayServiceImplTest {

    /** 一次生成、全部用例复用，避免每个用例重复生成 RSA 密钥 */
    private static final String TEST_PRIVATE_KEY_PEM;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            String body = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
            TEST_PRIVATE_KEY_PEM = "-----BEGIN PRIVATE KEY-----\n"
                    + body.replaceAll("(.{64})", "$1\n")
                    + "\n-----END PRIVATE KEY-----";
        } catch (Exception e) {
            throw new IllegalStateException("生成测试用 RSA 密钥失败", e);
        }
    }

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;
    private WxpayServiceImpl service;
    private WxpayConfig config;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        objectMapper = new ObjectMapper();
        service = new WxpayServiceImpl(restTemplate, objectMapper);
        config = new WxpayConfig("wx-test-appid", "1900000001", "0123456789abcdef0123456789abcdef",
                "SERIAL123", TEST_PRIVATE_KEY_PEM, null, null, null, null, null, null);
    }

    // ────────────────────────────── queryOrder ──────────────────────────────

    @Test
    @DisplayName("queryOrder: 404 ORDER_NOT_EXIST 视为订单不存在，而非查询失败")
    void queryOrder_notFoundWithOrderNotExist_isNormalResult() {
        stubExchangeThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                HttpHeaders.EMPTY, "{\"code\":\"ORDER_NOT_EXIST\",\"message\":\"订单不存在\"}"
                        .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        WxpayOrderQueryResult result = service.queryOrder(config, "OUT-TRADE-1");

        assertFalse(result.isError(), "订单不存在属于正常查询结果，不应标记为错误");
        assertNull(result.tradeState());
        assertNull(result.total());
        assertNull(result.transactionId());
    }

    @Test
    @DisplayName("queryOrder: 查询成功时解析交易状态/金额/微信单号")
    void queryOrder_success_parsesFields() {
        stubExchangeReturn(new ResponseEntity<>(
                "{\"trade_state\":\"SUCCESS\",\"transaction_id\":\"4200001234\","
                        + "\"amount\":{\"total\":5000}}",
                HttpStatus.OK));

        WxpayOrderQueryResult result = service.queryOrder(config, "OUT-TRADE-2");

        assertFalse(result.isError());
        assertEquals("SUCCESS", result.tradeState());
        assertEquals(5000, result.total());
        assertEquals("4200001234", result.transactionId());
    }

    @Test
    @DisplayName("queryOrder: 网关错误（非订单不存在）必须置 error，避免被误判为未支付")
    void queryOrder_gatewayError_reportsError() {
        stubExchangeThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error", HttpHeaders.EMPTY,
                "{\"code\":\"SYSTEM_ERROR\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

        WxpayOrderQueryResult result = service.queryOrder(config, "OUT-TRADE-3");

        assertTrue(result.isError(), "网关异常必须标记为查询失败");
        assertNotNull(result.error());
        assertTrue(result.error().contains("SYSTEM_ERROR"));
    }

    @Test
    @DisplayName("queryOrder: 网络异常（非 HTTP 状态码）同样标记 error")
    void queryOrder_networkError_reportsError() {
        stubExchangeThrow(new org.springframework.web.client.ResourceAccessException("connection reset"));

        WxpayOrderQueryResult result = service.queryOrder(config, "OUT-TRADE-4");

        assertTrue(result.isError());
        assertNotNull(result.error());
    }

    // ────────────────────────────── queryRefund ──────────────────────────────

    @Test
    @DisplayName("queryRefund: 404 RESOURCE_NOT_EXISTS 视为退款单不存在，并回显退款单号")
    void queryRefund_notFoundWithResourceNotExists_isNormalResult() {
        stubExchangeThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                HttpHeaders.EMPTY, "{\"code\":\"RESOURCE_NOT_EXISTS\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

        WxpayRefundQueryResult result = service.queryRefund(config, "REF-1");

        assertFalse(result.isError(), "退款单不存在属于正常查询结果");
        assertEquals("REF-1", result.outRefundNo());
        assertNull(result.status());
        assertNull(result.refundId());
    }

    @Test
    @DisplayName("queryRefund: 查询成功时解析退款状态与金额")
    void queryRefund_success_parsesFields() {
        stubExchangeReturn(new ResponseEntity<>(
                "{\"refund_id\":\"5000001\",\"out_refund_no\":\"REF-2\",\"status\":\"SUCCESS\","
                        + "\"amount\":{\"refund\":199}}",
                HttpStatus.OK));

        WxpayRefundQueryResult result = service.queryRefund(config, "REF-2");

        assertFalse(result.isError());
        assertEquals("5000001", result.refundId());
        assertEquals("REF-2", result.outRefundNo());
        assertEquals("SUCCESS", result.status());
        assertEquals(199, result.refundAmount());
    }

    @Test
    @DisplayName("queryRefund: 网关错误标记 error，避免终态回查把失败当作未退款")
    void queryRefund_gatewayError_reportsError() {
        stubExchangeThrow(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                HttpHeaders.EMPTY, "{\"code\":\"SYSTEM_ERROR\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

        WxpayRefundQueryResult result = service.queryRefund(config, "REF-3");

        assertTrue(result.isError());
        assertNotNull(result.error());
    }

    // ────────────────────────────── createRefund ──────────────────────────────

    @Test
    @DisplayName("createRefund: 金额转分向下截断，0.0199 元应为 1 分（不能进位成 2 分）")
    void createRefund_truncatesAmountDownward() throws Exception {
        stubExchangeReturn(new ResponseEntity<>(
                "{\"refund_id\":\"5000002\",\"out_refund_no\":\"REF-4\",\"status\":\"PROCESSING\"}",
                HttpStatus.OK));

        service.createRefund(config, "OUT-TRADE-5", "REF-4",
                new BigDecimal("0.0199"), new BigDecimal("1.00"), "测试退款", null);

        Map<String, Object> body = capturedRequestBody(HttpMethod.POST);
        @SuppressWarnings("unchecked")
        Map<String, Object> amount = (Map<String, Object>) body.get("amount");
        assertEquals(1, amount.get("refund"), "0.0199 元应向下截断为 1 分，而非四舍五入为 2 分");
        assertEquals(100, amount.get("total"));
    }

    @Test
    @DisplayName("createRefund: 不足 1 分的金额（0.005 元）应被拒绝，而不是进位为 1 分")
    void createRefund_belowOneCent_rejected() {
        assertThrows(BusinessException.class, () -> service.createRefund(config, "OUT-TRADE-6", "REF-5",
                new BigDecimal("0.005"), new BigDecimal("1.00"), null, null));
    }

    @Test
    @DisplayName("createRefund: 微信拒绝受理（CLOSED/ABNORMAL）时抛错，不能当作退款成功")
    void createRefund_rejectedByWechat_throws() {
        stubExchangeReturn(new ResponseEntity<>(
                "{\"refund_id\":\"5000003\",\"out_refund_no\":\"REF-6\",\"status\":\"CLOSED\"}",
                HttpStatus.OK));

        assertThrows(BusinessException.class, () -> service.createRefund(config, "OUT-TRADE-7", "REF-6",
                new BigDecimal("1.00"), new BigDecimal("1.00"), null, null));
    }

    @Test
    @DisplayName("createRefund: 响应既无 refund_id 也无 out_refund_no 时抛错")
    void createRefund_emptyResponse_throws() {
        stubExchangeReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        assertThrows(BusinessException.class, () -> service.createRefund(config, "OUT-TRADE-8", "REF-7",
                new BigDecimal("1.00"), new BigDecimal("1.00"), null, null));
    }

    // ────────────────────────────── 内部工具方法 ──────────────────────────────

    @Test
    @DisplayName("serialMatches: 证书 serial 不保留前导 0，应按声明长度补零比较")
    void serialMatches_padsLeadingZeros() throws Exception {
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getSerialNumber()).thenReturn(new BigInteger("abc", 16));

        Method method = WxpayServiceImpl.class.getDeclaredMethod("serialMatches", X509Certificate.class, String.class);
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(null, cert, "abc"), "大小写不敏感应匹配");
        assertTrue((Boolean) method.invoke(null, cert, "0000ABC"), "微信声明带前导 0 时应匹配");
        assertFalse((Boolean) method.invoke(null, cert, "abd"), "不同 serial 不应匹配");
    }

    @Test
    @DisplayName("truncate: 超长错误详情截断为 300 字符")
    void truncate_limitsLength() throws Exception {
        Method method = WxpayServiceImpl.class.getDeclaredMethod("truncate", String.class);
        method.setAccessible(true);

        String longText = "x".repeat(500);
        assertEquals(300, ((String) method.invoke(null, longText)).length());
        assertNull(method.invoke(null, (String) null));
        assertEquals("short", method.invoke(null, "short"));
    }

    // ────────────────────────────── 测试辅助 ──────────────────────────────

    private void stubExchangeReturn(ResponseEntity<String> response) {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(response);
    }

    private void stubExchangeThrow(RuntimeException exception) {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenThrow(exception);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedRequestBody(HttpMethod method) throws Exception {
        org.mockito.ArgumentCaptor<HttpEntity<?>> captor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(method), captor.capture(), eq(String.class));
        String json = (String) captor.getValue().getBody();
        return objectMapper.readValue(json, Map.class);
    }
}
