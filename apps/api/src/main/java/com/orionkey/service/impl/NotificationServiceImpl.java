package com.orionkey.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.constant.CardKeyStatus;
import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.OrderStatus;
import com.orionkey.common.PageResult;
import com.orionkey.entity.*;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.*;
import com.orionkey.service.EmailService;
import com.orionkey.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 消息通知服务实现。
 * <ul>
 *   <li>预设模板（注册/下单/支付/发货/库存预警/日报/周报/月报）首次启动自动写入，已有不覆盖；</li>
 *   <li>渠道：钉钉机器人、企业微信机器人（webhook markdown）、通知邮箱（复用 SMTP）；</li>
 *   <li>事件触发 {@link #sendTemplate(String, Map)} 异步发送，任何渠道异常仅记日志；</li>
 *   <li>系统消息写入 system_messages 供后台铃铛查看/已读/清空；</li>
 *   <li>定时报表：日报（每日）、周报（周一）、月报（每月1日）、库存预警（每日），模板勾选启用才发送。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationTemplateRepository templateRepository;
    private final NotificationChannelRepository channelRepository;
    private final SystemMessageRepository messageRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final VisitStatsRepository visitStatsRepository;
    private final ProductRepository productRepository;
    private final CardKeyRepository cardKeyRepository;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy年M月");

    /** 渠道编码常量 */
    private static final String CH_DINGTALK = "DINGTALK";
    private static final String CH_WECOM = "WECOM";
    private static final String CH_EMAIL = "EMAIL";
    /** 所有渠道（模板默认勾选） */
    private static final String ALL_CHANNELS = CH_DINGTALK + "," + CH_WECOM + "," + CH_EMAIL;

    private final RestClient restClient = RestClient.builder().build();

    // ═══════════ 预设模板 / 渠道初始化 ═══════════

    /**
     * 首次启动时写入预设模板与渠道（幂等：按 code/channelType 存在则跳过，不覆盖后台修改）。
     */
    @jakarta.annotation.PostConstruct
    @Transactional
    public void initDefaults() {
        seedChannels();
        seedTemplates();
        log.info("Notification templates/channels initialized");
    }

    private void seedChannels() {
        Map<String, Object> d = Map.of("webhook_url", "");
        channelRepository.findByChannelType(CH_DINGTALK).orElseGet(() -> {
            NotificationChannel c = new NotificationChannel();
            c.setChannelType(CH_DINGTALK);
            c.setName("钉钉机器人");
            c.setConfigJson(writeJson(d));
            c.setEnabled(true);
            c.setSortOrder(1);
            return channelRepository.save(c);
        });
        channelRepository.findByChannelType(CH_WECOM).orElseGet(() -> {
            NotificationChannel c = new NotificationChannel();
            c.setChannelType(CH_WECOM);
            c.setName("企业微信机器人");
            c.setConfigJson(writeJson(d));
            c.setEnabled(true);
            c.setSortOrder(2);
            return channelRepository.save(c);
        });
        channelRepository.findByChannelType(CH_EMAIL).orElseGet(() -> {
            NotificationChannel c = new NotificationChannel();
            c.setChannelType(CH_EMAIL);
            c.setName("通知邮箱");
            c.setConfigJson(writeJson(Map.of("email_to", "")));
            c.setEnabled(true);
            c.setSortOrder(3);
            return channelRepository.save(c);
        });
    }

    /** 预设消息模板（全部默认关闭，管理员在后台勾选启用与渠道） */
    private void seedTemplates() {
        List<Map<String, Object>> list = List.of(
                template("REGISTER", "新用户注册", "USER",
                        "新用户注册通知",
                        "{site_name}：新用户 {username}（{email}）于 {time} 注册成功。",
                        ALL_CHANNELS, false, 10),
                template("ORDER_CREATED", "新订单提交", "ORDER",
                        "新订单提交通知",
                        "{site_name}：新订单 {order_no}\n商品：{product} x{quantity}\n金额：¥{amount}\n支付方式：{payment_method}\n提交时间：{time}",
                        ALL_CHANNELS, false, 20),
                template("ORDER_PAID", "订单支付成功", "ORDER",
                        "订单支付成功通知",
                        "{site_name}：订单 {order_no} 已支付 ¥{amount}（{payment_method}），等待自动发货。\n支付时间：{time}",
                        ALL_CHANNELS, false, 30),
                template("ORDER_DELIVERED", "订单发货完成", "ORDER",
                        "订单发货通知",
                        "{site_name}：订单 {order_no} 已自动发货，共 {quantity} 张卡密（商品：{product}）。\n发货时间：{time}",
                        ALL_CHANNELS, false, 40),
                template("ORDER_REFUNDED", "订单退款通知", "ORDER",
                        "订单退款通知",
                        "{site_name}：订单 {order_no} 已退款 ¥{amount}。\n退款原因：{reason}\n退款时间：{time}",
                        ALL_CHANNELS, false, 45),
                template("TXID_REVIEW", "USDT 交易待审核", "ORDER",
                        "USDT 交易待人工审核",
                        "{site_name}：订单 {order_no} 提交的 TXID {txid} 未自动匹配，已进入人工审核队列，请及时处理。",
                        ALL_CHANNELS, false, 50),
                template("LOW_STOCK", "库存预警", "SYSTEM",
                        "库存不足预警",
                        "{site_name}：商品「{product}」剩余库存仅 {stock} 张，低于预警阈值 {threshold}，请及时补货。",
                        ALL_CHANNELS, false, 60, true),
                template("DAILY_REPORT", "每日经营数据", "REPORT",
                        "每日经营数据日报",
                        "{site_name} 经营数据日报（{date}）\n销售额：¥{sales}（环比 {yoy}%）\n成交订单：{orders} 笔\n新增用户：{users} 人\n访问 UV：{uv}",
                        ALL_CHANNELS, false, 70, true),
                template("WEEKLY_REPORT", "周经营账单", "REPORT",
                        "周经营账单",
                        "{site_name} 周经营账单（{week_range}）\n销售额：¥{sales}\n成交订单：{orders} 笔\n新增用户：{users} 人",
                        ALL_CHANNELS, false, 80, true),
                template("MONTHLY_REPORT", "月经营账单", "REPORT",
                        "月经营账单",
                        "{site_name} 月经营账单（{month}）\n销售额：¥{sales}\n成交订单：{orders} 笔\n新增用户：{users} 人",
                        ALL_CHANNELS, false, 90, true),
                template("DATA_SUMMARY", "数据汇总与环比", "REPORT",
                        "数据汇总与环比",
                        "{site_name} 数据汇总（{date}）\n今日销售额：¥{sales}（环比 {yoy}%）\n今日成交订单：{orders} 笔\n新增用户：{users} 人\n访问 UV：{uv}",
                        ALL_CHANNELS, false, 100),
                // ── 分销推广（管理员视角） ──
                template("DIST_APPLIED", "分销员申请通知", "DISTRIBUTION",
                        "新分销员申请",
                        "{site_name}：新用户 {user_email}（{distributor_code}）提交了分销员申请，请及时审核。\n申请时间：{time}",
                        ALL_CHANNELS, false, 110),
                template("DIST_STATUS_CHANGED", "分销员状态变更", "DISTRIBUTION",
                        "分销员状态变更",
                        "{site_name}：分销员 {user_email}（{distributor_code}）状态已变更为 {status}。\n变更时间：{time}",
                        ALL_CHANNELS, false, 120),
                template("WITHDRAWAL_PENDING", "提现申请待审核", "DISTRIBUTION",
                        "提现申请待审核",
                        "{site_name}：分销员 {distributor_code}（{user_email}）提交提现申请 ¥{amount}，请及时处理。\n申请时间：{time}",
                        ALL_CHANNELS, false, 130),
                // ── 分销提现（用户站内信 + 邮箱） ──
                template("WITHDRAWAL_APPLIED", "提现申请提交成功", "DISTRIBUTION",
                        "提现申请已提交",
                        "{site_name}：您的提现申请 ¥{amount} 已提交，平台将在审核通过后打款至您的微信零钱。\n申请时间：{time}",
                        ALL_CHANNELS, false, 140),
                template("WITHDRAWAL_APPROVED", "提现审核通过", "DISTRIBUTION",
                        "提现审核通过",
                        "{site_name}：您的提现申请 ¥{amount} 已审核通过，正在为您打款至微信零钱。\n审核时间：{time}",
                        ALL_CHANNELS, false, 150),
                template("WITHDRAWAL_SUCCESS", "提现到账通知", "DISTRIBUTION",
                        "提现已到账",
                        "{site_name}：您的提现 ¥{amount} 已成功打款至微信零钱，请注意查收。\n到账时间：{time}",
                        ALL_CHANNELS, false, 160),
                template("WITHDRAWAL_REJECTED", "提现申请被拒", "DISTRIBUTION",
                        "提现申请未通过",
                        "{site_name}：您的提现申请 ¥{amount} 未通过审核。\n原因：{reason}\n处理时间：{time}",
                        ALL_CHANNELS, false, 170)
        );
        for (Map<String, Object> t : list) {
            String code = (String) t.get("code");
            templateRepository.findByCode(code).orElseGet(() -> {
                NotificationTemplate nt = new NotificationTemplate();
                nt.setCode(code);
                nt.setName((String) t.get("name"));
                nt.setCategory((String) t.get("category"));
                nt.setTitle((String) t.get("title"));
                nt.setContent((String) t.get("content"));
                nt.setChannels((String) t.get("channels"));
                nt.setEnabled(Boolean.TRUE.equals(t.get("enabled")));
                nt.setAutoTrigger(Boolean.TRUE.equals(t.get("autoTrigger")));
                nt.setSortOrder((Integer) t.get("sortOrder"));
                return templateRepository.save(nt);
            });
        }
    }

    private Map<String, Object> template(String code, String name, String category, String title,
                                         String content, String channels, boolean enabled, int sortOrder) {
        return template(code, name, category, title, content, channels, enabled, sortOrder, false);
    }

    private Map<String, Object> template(String code, String name, String category, String title,
                                         String content, String channels, boolean enabled, int sortOrder, boolean autoTrigger) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("category", category);
        m.put("title", title);
        m.put("content", content);
        m.put("channels", channels);
        m.put("enabled", enabled);
        m.put("autoTrigger", autoTrigger);
        m.put("sortOrder", sortOrder);
        return m;
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ═══════════ 模板 / 渠道 / 消息 管理（供 Controller 调用） ═══════════

    /** 模板列表（分页 + 分类/启用状态筛选） */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> listTemplates(int page, int pageSize, String category, Boolean enabled) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100),
                Sort.by(Sort.Direction.ASC, "sortOrder").and(Sort.by(Sort.Direction.ASC, "createdAt")));
        Page<NotificationTemplate> pageResult = templateRepository.findByFilters(
                StringUtils.hasText(category) ? category : null, enabled, pageable);
        List<Map<String, Object>> list = pageResult.getContent().stream().map(this::toTemplateMap).toList();
        return PageResult.of(list, pageResult.getNumber() + 1, pageResult.getSize(), pageResult.getTotalElements());
    }

    /** 新增自定义模板（编码唯一；排到模板列表末尾） */
    @Transactional
    public NotificationTemplate createTemplate(Map<String, Object> body) {
        String code = str(body.get("code")).toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
        String name = str(body.get("name"));
        if (code.isBlank() || name.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模板编码与名称不能为空");
        }
        if (templateRepository.findByCode(code).isPresent()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模板编码已存在: " + code);
        }
        NotificationTemplate t = new NotificationTemplate();
        t.setCode(code);
        t.setName(name);
        t.setCategory(str(body.get("category")).isBlank() ? "USER" : str(body.get("category")));
        t.setTitle(str(body.get("title")).isBlank() ? name : str(body.get("title")));
        t.setContent(str(body.get("content")).isBlank() ? name : str(body.get("content")));
        t.setChannels(str(body.get("channels")).isBlank() ? ALL_CHANNELS : str(body.get("channels")));
        t.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        t.setAutoTrigger(Boolean.TRUE.equals(body.get("auto_trigger")));
        t.setSortOrder(templateRepository.findMaxSortOrder() + 10);
        return templateRepository.save(t);
    }

    private Map<String, Object> toTemplateMap(NotificationTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("code", t.getCode());
        m.put("name", t.getName());
        m.put("category", t.getCategory());
        m.put("title", t.getTitle());
        m.put("content", t.getContent());
        m.put("channels", t.getChannels());
        m.put("enabled", t.isEnabled());
        m.put("auto_trigger", t.isAutoTrigger());
        m.put("sort_order", t.getSortOrder());
        return m;
    }

    /** 更新模板（enabled / channels） */
    @Transactional
    public void updateTemplate(UUID id, Map<String, Object> body) {
        NotificationTemplate t = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在"));
        if (body.containsKey("enabled")) {
            t.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        }
        Object ch = body.get("channels");
        if (ch instanceof String s && !s.isBlank()) {
            t.setChannels(s);
        }
        if (body.get("title") instanceof String title && !title.isBlank()) {
            t.setTitle(title);
        }
        if (body.get("content") instanceof String content && !content.isBlank()) {
            t.setContent(content);
        }
        templateRepository.save(t);
    }

    /** 渠道配置列表 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listChannels() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (NotificationChannel c : channelRepository.findAllByOrderBySortOrderAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("channel_type", c.getChannelType());
            m.put("name", c.getName());
            m.put("enabled", c.isEnabled());
            m.put("sort_order", c.getSortOrder());
            // 合并配置字段到顶层，便于前端直接编辑
            try {
                Map<String, Object> cfg = c.getConfigJson() == null || c.getConfigJson().isBlank()
                        ? Map.of() : objectMapper.readValue(c.getConfigJson(), new TypeReference<Map<String, Object>>() {
                });
                m.putAll(cfg);
            } catch (Exception e) {
                log.warn("Invalid channel config json for {}: {}", c.getChannelType(), c.getConfigJson());
            }
            out.add(m);
        }
        return out;
    }

    /** 保存渠道配置（按 channelType upsert，允许添加新渠道） */
    @Transactional
    public void saveChannel(String channelType, Map<String, Object> body) {
        NotificationChannel c = channelRepository.findByChannelType(channelType).orElseGet(() -> {
            NotificationChannel nc = new NotificationChannel();
            nc.setChannelType(channelType);
            nc.setName(String.valueOf(body.getOrDefault("name", channelType)));
            return nc;
        });
        if (body.get("name") instanceof String name && !name.isBlank()) {
            c.setName(name);
        }
        if (body.containsKey("enabled")) {
            c.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        if (body.get("webhook_url") instanceof String url) {
            cfg.put("webhook_url", url.trim());
        }
        // 钉钉加签密钥：安全设置选择「加签」时必填（企微/邮箱渠道会忽略该字段）
        if (body.get("secret") instanceof String secret) {
            cfg.put("secret", secret.trim());
        }
        if (body.get("email_to") instanceof String to) {
            cfg.put("email_to", to.trim());
        }
        if (!cfg.isEmpty()) {
            c.setConfigJson(writeJson(cfg));
        }
        channelRepository.save(c);
    }

    // ═══════════ 系统消息（后台铃铛） ═══════════

    @Transactional(readOnly = true)
    public Page<SystemMessage> listMessages(int page, int pageSize, Boolean unreadOnly) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return unreadOnly != null && unreadOnly
                ? messageRepository.findByReadFalseOrderByCreatedAtDesc(pageable)
                : messageRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return messageRepository.countByReadFalse();
    }

    @Transactional
    public void markRead(UUID id) {
        messageRepository.findById(id).ifPresent(m -> {
            m.setRead(true);
            messageRepository.save(m);
        });
    }

    @Transactional
    public int markAllRead() {
        return messageRepository.markAllRead();
    }

    @Transactional
    public void clearAll() {
        messageRepository.deleteAll();
    }

    // ═══════════ 事件通知发送 ═══════════

    /**
     * 发送模板通知（异步，不阻塞业务，异常仅记日志）：
     * 模板必须 enabled 才发送；写入系统消息始终执行（供铃铛查看）。
     */
    @Async
    @Override
    @Transactional
    public void sendTemplate(String code, Map<String, Object> vars) {
        try {
            NotificationTemplate t = templateRepository.findByCode(code).orElse(null);
            if (t == null) {
                log.warn("Notification template not found: {}", code);
                return;
            }
            Map<String, Object> merged = new LinkedHashMap<>();
            if (vars != null) {
                merged.putAll(vars);
            }
            merged.putIfAbsent("site_name", siteName());
            merged.putIfAbsent("time", LocalDateTime.now().format(FMT));

            String title = render(t.getTitle(), merged);
            String content = render(t.getContent(), merged);

            // 1. 始终写入系统消息（铃铛）
            SystemMessage msg = new SystemMessage();
            msg.setTitle(title);
            msg.setContent(content);
            msg.setMessageType(t.getCategory());
            messageRepository.save(msg);

            // 2. 模板未启用则不向外分发
            if (!t.isEnabled()) {
                return;
            }
            for (String chType : t.getChannels().split("\\s*,\\s*")) {
                if (chType.isBlank()) continue;
                channelRepository.findByChannelType(chType.trim()).ifPresent(ch -> {
                    if (!ch.isEnabled()) return;
                    try {
                        dispatch(ch, title, content);
                    } catch (Exception e) {
                        log.error("Notification dispatch failed: template={}, channel={}, err={}",
                                code, chType, e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            log.error("sendTemplate({}) failed: {}", code, e.getMessage(), e);
        }
    }

    /** 按渠道类型分发 */
    private void dispatch(NotificationChannel ch, String title, String content) {
        Map<String, Object> cfg;
        try {
            cfg = ch.getConfigJson() == null || ch.getConfigJson().isBlank()
                    ? Map.of() : objectMapper.readValue(ch.getConfigJson(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("Invalid channel config for {}: {}", ch.getChannelType(), ch.getConfigJson());
            return;
        }
        switch (ch.getChannelType()) {
            case CH_DINGTALK -> {
                String url = str(cfg.get("webhook_url"));
                if (url.isBlank()) {
                    log.debug("DingTalk webhook_url not configured");
                    return;
                }
                // 加签模式：需把 timestamp + sign 拼到 webhook 地址上，否则钉钉返回签名不匹配
                String secret = str(cfg.get("secret"));
                if (!secret.isBlank()) {
                    url = dingTalkSignedUrl(url, secret);
                }
                postWebhook(url, Map.of(
                        "msgtype", "markdown",
                        "markdown", Map.of("title", title, "text", "### " + title + "\n\n" + content)));
            }
            case CH_WECOM -> {
                String url = str(cfg.get("webhook_url"));
                if (url.isBlank()) {
                    log.debug("WeCom webhook_url not configured");
                    return;
                }
                postWebhook(url, Map.of(
                        "msgtype", "markdown",
                        "markdown", Map.of("content", title + "\n\n" + content)));
            }
            case CH_EMAIL -> {
                String to = str(cfg.get("email_to"));
                if (to.isBlank()) {
                    log.debug("Notice email_to not configured");
                    return;
                }
                emailService.sendNoticeEmail(to, "【" + siteName() + "】" + title, content);
            }
            default -> log.warn("Unknown notification channel type: {}", ch.getChannelType());
        }
    }

    private void postWebhook(String url, Map<String, Object> body) {
        restClient.post()
                .uri(url)
                .header("Content-Type", "application/json;charset=UTF-8")
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * 钉钉加签：timestamp + "\n" + secret 做 HmacSHA256，结果 Base64 后 URL 编码，
     * 追加到 webhook 地址（官方文档 https://open.dingtalk.com/document/orgapp/custom-robot-access）：
     * <pre>webhook?access_token=xxx&amp;timestamp=xxx&amp;sign=xxx</pre>
     */
    private String dingTalkSignedUrl(String webhookUrl, String secret) {
        try {
            long timestamp = System.currentTimeMillis();
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
            String sep = webhookUrl.contains("?") ? "&" : "?";
            return webhookUrl + sep + "timestamp=" + timestamp + "&sign=" + sign;
        } catch (Exception e) {
            log.warn("DingTalk sign compute failed, send without sign: {}", e.getMessage());
            return webhookUrl;
        }
    }

    /** 渲染模板：{key} → 值 */
    private String render(String text, Map<String, Object> vars) {
        if (text == null) return "";
        String out = text;
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            if (e.getValue() != null) {
                out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private String siteName() {
        return siteConfigRepository.findByConfigKey("site_name")
                .map(SiteConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse("Nova key");
    }

    // ═══════════ 测试发送 ═══════════

    /**
     * 同步测试发送指定模板：写入系统消息，并按模板勾选的渠道逐项分发，
     * 返回每个渠道的检测结果（与支付渠道测试连接风格一致），便于前端展示 ✅/❌。
     */
    @Transactional
    public Map<String, Object> testSend(String code) {
        NotificationTemplate t = templateRepository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在: " + code));
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("site_name", siteName());
        merged.put("time", LocalDateTime.now().format(FMT));
        String title = render(t.getTitle(), merged);
        String content = render(t.getContent(), merged);

        // 写入系统消息（铃铛）
        SystemMessage msg = new SystemMessage();
        msg.setTitle(title);
        msg.setContent(content);
        msg.setMessageType(t.getCategory());
        messageRepository.save(msg);

        List<Map<String, Object>> items = new ArrayList<>();
        boolean templateEnabled = t.isEnabled();
        items.add(Map.of("name", "模板状态", "status", templateEnabled,
                "message", templateEnabled ? "模板已启用" : "模板未启用，仅写入系统消息，未向外部渠道发送"));

        for (String chType : t.getChannels().split("\\s*,\\s*")) {
            String type = chType.trim();
            if (type.isBlank()) continue;
            NotificationChannel ch = channelRepository.findByChannelType(type).orElse(null);
            if (ch == null) {
                items.add(Map.of("name", type, "status", false, "message", "渠道不存在"));
                continue;
            }
            if (!ch.isEnabled()) {
                items.add(Map.of("name", ch.getName(), "status", false, "message", "渠道未启用"));
                continue;
            }
            if (!templateEnabled) {
                items.add(Map.of("name", ch.getName(), "status", false, "message", "模板未启用，跳过"));
                continue;
            }
            try {
                dispatch(ch, title, content);
                items.add(Map.of("name", ch.getName(), "status", true, "message", "发送成功"));
            } catch (Exception e) {
                items.add(Map.of("name", ch.getName(), "status", false, "message", "发送失败：" + e.getMessage()));
            }
        }

        boolean passed = items.stream().allMatch(i -> Boolean.TRUE.equals(i.get("status")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("template_code", code);
        result.put("template_name", t.getName());
        result.put("passed", passed);
        result.put("items", items);
        result.put("message", passed ? "通知测试发送通过" : "部分渠道未发送成功，请根据上方 ❌ 项检查配置");
        return result;
    }

    // ═══════════ 定时报表 ═══════════

    /** 每日 09:00：经营日报 + 库存预警 */
    @Scheduled(cron = "0 0 9 * * ?")
    public void dailyReportJob() {
        LocalDate today = LocalDate.now();
        sendDailyReport(today);
        sendLowStockAlert(today);
    }

    /** 每周一 09:05：周账单 */
    @Scheduled(cron = "0 5 9 ? * MON")
    public void weeklyReportJob() {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        LocalDate start = monday.minusWeeks(1);
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = monday.atStartOfDay();
        Map<String, Object> vars = baseReportVars();
        vars.put("week_range", start.format(DAY_FMT) + " ~ " + monday.minusDays(1).format(DAY_FMT));
        vars.put("sales", money(orderRepository.sumSalesBetween(from, to)));
        vars.put("orders", orderRepository.countPaidOrdersBetween(from, to));
        vars.put("users", userRepository.countByCreatedAtBetween(from, to));
        sendTemplate("WEEKLY_REPORT", vars);
    }

    /** 每月 1 日 09:10：月账单 */
    @Scheduled(cron = "0 10 9 1 * ?")
    public void monthlyReportJob() {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate prevMonthStart = monthStart.minusMonths(1);
        LocalDateTime from = prevMonthStart.atStartOfDay();
        LocalDateTime to = monthStart.atStartOfDay();
        Map<String, Object> vars = baseReportVars();
        vars.put("month", prevMonthStart.format(MONTH_FMT));
        vars.put("sales", money(orderRepository.sumSalesBetween(from, to)));
        vars.put("orders", orderRepository.countPaidOrdersBetween(from, to));
        vars.put("users", userRepository.countByCreatedAtBetween(from, to));
        sendTemplate("MONTHLY_REPORT", vars);
    }

    private void sendDailyReport(LocalDate date) {
        LocalDateTime from = date.minusDays(1).atStartOfDay();
        LocalDateTime to = date.atStartOfDay();
        LocalDateTime prevFrom = date.minusDays(2).atStartOfDay();
        BigDecimal sales = orderRepository.sumSalesBetween(from, to);
        BigDecimal prevSales = orderRepository.sumSalesBetween(prevFrom, from);
        Map<String, Object> vars = baseReportVars();
        vars.put("date", date.minusDays(1).format(DAY_FMT));
        vars.put("sales", money(sales));
        vars.put("yoy", yoy(sales, prevSales));
        vars.put("orders", orderRepository.countPaidOrdersBetween(from, to));
        vars.put("users", userRepository.countByCreatedAtBetween(from, to));
        VisitStats vs = visitStatsRepository.findByVisitDate(date.minusDays(1)).orElse(null);
        vars.put("uv", vs != null ? vs.getUv() : 0);
        sendTemplate("DAILY_REPORT", vars);
    }

    private void sendLowStockAlert(LocalDate date) {
        List<Product> products = productRepository.findAll().stream()
                .filter(p -> p.getIsDeleted() == 0 && p.isEnabled())
                .toList();
        for (Product p : products) {
            long available = cardKeyRepository.countByProductIdAndStatus(p.getId(), CardKeyStatus.AVAILABLE);
            if (available <= p.getLowStockThreshold()) {
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("product", p.getTitle());
                vars.put("stock", available);
                vars.put("threshold", p.getLowStockThreshold());
                sendTemplate("LOW_STOCK", vars);
            }
        }
    }

    private Map<String, Object> baseReportVars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("site_name", siteName());
        vars.put("time", LocalDateTime.now().format(FMT));
        return vars;
    }

    private String money(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** 环比百分比（保留 1 位小数，可为负） */
    private String yoy(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return current != null && current.compareTo(BigDecimal.ZERO) > 0 ? "+100%" : "0%";
        }
        BigDecimal delta = current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 1, java.math.RoundingMode.HALF_UP);
        return (delta.compareTo(BigDecimal.ZERO) > 0 ? "+" : "") + delta.toPlainString() + "%";
    }
}
