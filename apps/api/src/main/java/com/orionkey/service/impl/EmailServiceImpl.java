package com.orionkey.service.impl;

import com.orionkey.entity.CardKey;
import com.orionkey.entity.Order;
import com.orionkey.entity.OrderItem;
import com.orionkey.entity.SiteConfig;
import com.orionkey.entity.User;
import com.orionkey.repository.CardKeyRepository;
import com.orionkey.repository.OrderItemRepository;
import com.orionkey.repository.OrderRepository;
import com.orionkey.repository.SiteConfigRepository;
import com.orionkey.repository.UserRepository;
import com.orionkey.service.EmailService;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final UserRepository userRepository;

    // SMTP 默认值：来自环境变量（application.yml 的 spring.mail.* / mail.*）。
    // 管理后台「网站设置 → 邮箱发件」可覆盖（smtp_host / smtp_port / smtp_username /
    // smtp_password / mail_from / mail_from_name / mail_site_url / mail_enabled）。
    // 注意：以下全部用 String 注入，避免 .env 中 MAIL_* 为空字符串时
    // Spring 把 "" 转 int/boolean 失败导致整个应用启动崩溃（生产 .env 常见空值）。
    @Value("${mail.enabled:false}")
    private String mailEnabledDefault;

    @Value("${mail.site-url:https://noepay.cn}")
    private String siteUrlDefault;

    @Value("${spring.mail.host:}")
    private String smtpHostDefault;

    @Value("${spring.mail.port:465}")
    private String smtpPortDefault;

    @Value("${spring.mail.username:}")
    private String smtpUsernameDefault;

    @Value("${spring.mail.password:}")
    private String smtpPasswordDefault;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ═══════════ 配置读取（后台 site-config 优先，环境变量兜底） ═══════════

    private String cfg(String key, String fallback) {
        return siteConfigRepository.findByConfigKey(key)
                .map(SiteConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(fallback);
    }

    private boolean cfgEnabled() {
        // 后台未显式设置 mail_enabled 时默认开启（与前端「启用邮件自动发货通知」开关默认 ON 保持一致）
        return siteConfigRepository.findByConfigKey("mail_enabled")
                .map(c -> "true".equalsIgnoreCase(c.getConfigValue()))
                .orElse(true);
    }

    /**
     * 根据后台配置动态构建邮件发送器（每次发送时读取最新配置，改完后台立即生效）。
     * 465 → 强制 SSL；587/25 → STARTTLS（自动升级）。
     */
    private JavaMailSender buildMailSender() {
        String host = cfg("smtp_host", smtpHostDefault);
        String portStr = cfg("smtp_port", smtpPortDefault);
        int port = 465;
        try {
            port = Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid smtp_port '{}', fallback to 465", portStr);
        }
        String username = cfg("smtp_username", smtpUsernameDefault);
        String password = cfg("smtp_password", smtpPasswordDefault);

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        if (port == 465) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
        }
        return sender;
    }

    /** 设置发件人（后台 mail_from_name 可选，填写时显示"名称 <邮箱>"） */
    private void applyFrom(MimeMessageHelper helper) throws Exception {
        String from = cfg("mail_from", smtpUsernameDefault);
        String fromName = cfg("mail_from_name", "");
        if (fromName.isBlank()) {
            helper.setFrom(from);
        } else {
            helper.setFrom(new InternetAddress(from, fromName, "UTF-8"));
        }
    }

    private String siteUrl() {
        return cfg("mail_site_url", siteUrlDefault);
    }

    private String siteName() {
        return siteConfigRepository.findByConfigKey("site_name")
                .map(SiteConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse("Nova key");
    }

    // ═══════════ 发货邮件 ═══════════

    @Async
    @Override
    public void sendDeliveryEmail(UUID orderId) {
        if (!cfgEnabled()) {
            log.warn("Mail disabled (mail_enabled=false), skip delivery email for order {}", orderId);
            return;
        }

        // SMTP 配置检查
        String host = cfg("smtp_host", smtpHostDefault);
        String username = cfg("smtp_username", smtpUsernameDefault);
        if (host == null || host.isBlank() || username == null || username.isBlank()) {
            log.error("SMTP not configured (smtp_host={}, smtp_username={}), cannot send delivery email for order {}. Please configure SMTP in admin panel → 网站设置 → 邮箱发件.",
                    host, username, orderId);
            return;
        }

        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null || order.getEmail() == null || order.getEmail().isBlank()) {
                log.warn("Cannot send delivery email: order {} not found or no email", orderId);
                return;
            }

            // 收件人：注册用户 → 注册邮箱 + 查询邮箱；匿名用户 → 查询邮箱（去重）
            Set<String> recipients = new LinkedHashSet<>();
            recipients.add(order.getEmail().trim());
            if (order.getUserId() != null) {
                userRepository.findById(order.getUserId()).ifPresent(u -> {
                    if (u.getEmail() != null && !u.getEmail().isBlank()) {
                        recipients.add(u.getEmail().trim());
                    }
                });
            }

            log.info("Sending delivery email for order {} to {} (host={}, port={})",
                    orderId, recipients, host, cfg("smtp_port", smtpPortDefault));

            List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
            List<CardKey> keys = cardKeyRepository.findByOrderId(orderId);
            Map<UUID, OrderItem> itemMap = items.stream()
                    .collect(Collectors.toMap(OrderItem::getId, i -> i));
            Map<UUID, List<CardKey>> grouped = keys.stream()
                    .filter(k -> k.getOrderItemId() != null)
                    .collect(Collectors.groupingBy(CardKey::getOrderItemId));

            String html = buildHtml(order, itemMap, grouped);

            JavaMailSender sender = buildMailSender();
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            applyFrom(helper);
            helper.setTo(recipients.toArray(new String[0]));
            helper.setSubject("【" + siteName() + "】订单发货通知 - " + orderId.toString().substring(0, 8));
            helper.setText(html, true);

            sender.send(message);
            log.info("✓ Delivery email sent for order {} to {}", orderId, recipients);
        } catch (Exception e) {
            // 发货邮件失败不影响发货状态，只记录日志（用户仍可在订单查询页看到卡密）
            log.error("✗ Failed to send delivery email for order {}: {}", orderId, e.getMessage(), e);
        }
    }

    // ═══════════ 测试邮件（管理后台「发送测试邮件」） ═══════════

    @Override
    public void sendTestEmail(String toEmail) {
        String name = siteName();
        JavaMailSender sender = buildMailSender();
        MimeMessage message = sender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            applyFrom(helper);
            helper.setTo(toEmail);
            helper.setSubject("【" + name + "】邮件服务配置测试");
            helper.setText(buildTestHtml(name), true);
            sender.send(message);
            log.info("Test email sent to {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send test email to {}: {}", toEmail, e.getMessage());
            throw new RuntimeException("发送失败：" + e.getMessage(), e);
        }
    }

    // ═══════════ 管理员通知邮件（消息通知渠道） ═══════════

    @Override
    public void sendNoticeEmail(String to, String subject, String content) {
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("通知收件邮箱为空");
        }
        JavaMailSender sender = buildMailSender();
        MimeMessage message = sender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            applyFrom(helper);
            helper.setTo(to.split("\\s*,\\s*"));
            helper.setSubject(subject);
            helper.setText(buildNoticeHtml(subject, content), true);
            sender.send(message);
            log.info("Notice email sent to {}", to);
        } catch (Exception e) {
            log.error("Failed to send notice email to {}: {}", to, e.getMessage());
            throw new RuntimeException("通知邮件发送失败：" + e.getMessage(), e);
        }
    }

    /** 发送营销邮件：直接透传自定义 HTML 排版（营销活动推广用） */
    @Override
    public void sendMarketingEmail(String to, String subject, String html) {
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("营销收件邮箱为空");
        }
        JavaMailSender sender = buildMailSender();
        MimeMessage message = sender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            applyFrom(helper);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            log.info("Marketing email sent to {}", to);
        } catch (Exception e) {
            log.error("Failed to send marketing email to {}: {}", to, e.getMessage());
            throw new RuntimeException("营销邮件发送失败：" + e.getMessage(), e);
        }
    }

    /** 通知邮件 HTML：复用与发货邮件一致的品牌样式 */
    private String buildNoticeHtml(String subject, String content) {
        String name = siteName();
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">");
        sb.append("</head><body style=\"margin:0;padding:0;background-color:#f4f4f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;\">");
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#f4f4f7;padding:24px 0;\">");
        sb.append("<tr><td align=\"center\">");
        sb.append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;width:100%;background-color:#ffffff;border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08);\">");
        sb.append("<tr><td style=\"background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:24px 40px;text-align:center;\">");
        sb.append("<h1 style=\"margin:0;color:#ffffff;font-size:20px;font-weight:600;\">").append(escapeHtml(name)).append("</h1>");
        sb.append("</td></tr>");
        sb.append("<tr><td style=\"padding:24px 40px;\">");
        sb.append("<h2 style=\"margin:0 0 12px;color:#333333;font-size:18px;\">").append(escapeHtml(subject)).append("</h2>");
        // 内容按行转换为 <p>，保留换行
        String[] lines = content == null ? new String[0] : content.split("\\r?\\n");
        for (String line : lines) {
            if (line.isBlank()) continue;
            sb.append("<p style=\"margin:0 0 8px;color:#555555;font-size:14px;line-height:1.7;\">")
              .append(escapeHtml(line)).append("</p>");
        }
        sb.append("</td></tr>");
        sb.append("<tr><td style=\"background-color:#f8f9fa;padding:20px 40px;text-align:center;border-top:1px solid #eee;\">");
        sb.append("<p style=\"margin:0;color:#999;font-size:12px;\">此邮件由系统自动发送，请勿直接回复</p>");
        sb.append("</td></tr>");
        sb.append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    // ═══════════ HTML 模板 ═══════════

    private String buildHtml(Order order, Map<UUID, OrderItem> itemMap,
                             Map<UUID, List<CardKey>> grouped) {
        UUID orderId = order.getId();
        BigDecimal amount = order.getActualAmount() != null ? order.getActualAmount() : order.getTotalAmount();
        // 支付时间优先展示 paidAt，未标记时回退到下单时间
        LocalDateTime paidTime = order.getPaidAt() != null ? order.getPaidAt() : order.getCreatedAt();
        String orderUrl = siteUrl() + "/order/query?orderId=" + orderId;
        String paymentLabel = paymentMethodLabel(order.getPaymentMethod());

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>");
        sb.append("<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">");
        sb.append("</head><body style=\"margin:0;padding:0;background-color:#f4f4f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;\">");

        // Container
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#f4f4f7;padding:24px 0;\">");
        sb.append("<tr><td align=\"center\">");
        sb.append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;width:100%;background-color:#ffffff;border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08);\">");

        // Header
        sb.append("<tr><td style=\"background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:32px 40px;text-align:center;\">");
        sb.append("<h1 style=\"margin:0;color:#ffffff;font-size:24px;font-weight:600;letter-spacing:1px;\">")
          .append(escapeHtml(siteName())).append("</h1>");
        sb.append("</td></tr>");

        // Title
        sb.append("<tr><td style=\"padding:32px 40px 0;\">");
        sb.append("<h2 style=\"margin:0 0 8px;color:#333333;font-size:20px;font-weight:600;\">订单发货通知</h2>");
        sb.append("<p style=\"margin:0;color:#666666;font-size:14px;line-height:1.6;\">您的订单已完成支付并自动发货，以下是购买的卡密信息：</p>");
        sb.append("</td></tr>");

        // Order info
        sb.append("<tr><td style=\"padding:24px 40px;\">");
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#f8f9fa;border-radius:6px;padding:16px;\">");
        sb.append("<tr><td style=\"padding:4px 16px;\"><span style=\"color:#888;font-size:13px;\">订单编号</span></td>");
        sb.append("<td style=\"padding:4px 16px;text-align:right;\"><span style=\"color:#333;font-size:13px;font-family:monospace;\">")
          .append(orderId).append("</span></td></tr>");
        sb.append("<tr><td style=\"padding:4px 16px;\"><span style=\"color:#888;font-size:13px;\">支付金额</span></td>");
        sb.append("<td style=\"padding:4px 16px;text-align:right;\"><span style=\"color:#333;font-size:13px;font-weight:600;\">¥")
          .append(amount).append("</span></td></tr>");
        if (!paymentLabel.isBlank()) {
            sb.append("<tr><td style=\"padding:4px 16px;\"><span style=\"color:#888;font-size:13px;\">支付方式</span></td>");
            sb.append("<td style=\"padding:4px 16px;text-align:right;\"><span style=\"color:#333;font-size:13px;\">")
              .append(escapeHtml(paymentLabel)).append("</span></td></tr>");
        }
        if (paidTime != null) {
            sb.append("<tr><td style=\"padding:4px 16px;\"><span style=\"color:#888;font-size:13px;\">支付时间</span></td>");
            sb.append("<td style=\"padding:4px 16px;text-align:right;\"><span style=\"color:#333;font-size:13px;\">")
              .append(paidTime.format(DATE_FMT)).append("</span></td></tr>");
        }
        sb.append("</table>");
        sb.append("</td></tr>");

        // Card keys grouped by product
        sb.append("<tr><td style=\"padding:0 40px 24px;\">");
        for (Map.Entry<UUID, List<CardKey>> entry : grouped.entrySet()) {
            OrderItem item = itemMap.get(entry.getKey());
            if (item == null) continue;

            String title = escapeHtml(item.getProductTitle());
            if (item.getSpecName() != null && !item.getSpecName().isBlank()) {
                title += " <span style=\"color:#888;font-size:12px;\">[" + escapeHtml(item.getSpecName()) + "]</span>";
            }
            title += " <span style=\"color:#999;font-size:12px;\">x" + entry.getValue().size() + "</span>";

            sb.append("<div style=\"margin-bottom:16px;border:1px solid #e8e8e8;border-radius:6px;overflow:hidden;\">");
            sb.append("<div style=\"background-color:#f0f0f5;padding:10px 16px;font-size:14px;font-weight:600;color:#333;\">")
              .append(title).append("</div>");
            sb.append("<div style=\"padding:12px 16px;\">");
            for (CardKey key : entry.getValue()) {
                sb.append("<div style=\"background-color:#fafafa;border:1px solid #eee;border-radius:4px;padding:8px 12px;margin-bottom:6px;font-family:'Courier New',Courier,monospace;font-size:13px;color:#222;word-break:break-all;\">")
                  .append(escapeHtml(key.getContent())).append("</div>");
            }
            sb.append("</div></div>");
        }
        sb.append("</td></tr>");

        // Warning
        sb.append("<tr><td style=\"padding:0 40px 24px;\">");
        sb.append("<div style=\"background-color:#fff8e1;border:1px solid #ffe082;border-radius:6px;padding:12px 16px;font-size:13px;color:#f57f17;\">");
        sb.append("&#9888; 请妥善保管卡密信息，切勿泄露给他人。如有任何问题，请联系客服。");
        sb.append("</div></td></tr>");

        // CTA button
        sb.append("<tr><td style=\"padding:0 40px 32px;text-align:center;\">");
        sb.append("<a href=\"").append(escapeHtml(orderUrl)).append("\" style=\"display:inline-block;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:#ffffff;text-decoration:none;padding:12px 32px;border-radius:6px;font-size:14px;font-weight:600;\">查看订单详情</a>");
        sb.append("</td></tr>");

        // Footer
        sb.append("<tr><td style=\"background-color:#f8f9fa;padding:20px 40px;text-align:center;border-top:1px solid #eee;\">");
        sb.append("<p style=\"margin:0 0 4px;color:#999;font-size:12px;\">此邮件由系统自动发送，请勿直接回复</p>");
        sb.append("<p style=\"margin:0;color:#bbb;font-size:11px;\">&copy; ").append(java.time.Year.now().getValue())
          .append(" ").append(escapeHtml(siteName())).append("</p>");
        sb.append("</td></tr>");

        sb.append("</table></td></tr></table>");
        sb.append("</body></html>");

        return sb.toString();
    }

    private String buildTestHtml(String siteName) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"></head>");
        sb.append("<body style=\"margin:0;padding:0;background-color:#f4f4f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;\">");
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#f4f4f7;padding:24px 0;\">");
        sb.append("<tr><td align=\"center\">");
        sb.append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;width:100%;background-color:#ffffff;border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08);\">");
        sb.append("<tr><td style=\"background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:32px 40px;text-align:center;\">");
        sb.append("<h1 style=\"margin:0;color:#ffffff;font-size:24px;font-weight:600;\">").append(escapeHtml(siteName)).append("</h1>");
        sb.append("</td></tr>");
        sb.append("<tr><td style=\"padding:32px 40px;text-align:center;\">");
        sb.append("<div style=\"font-size:48px;line-height:1;\">&#9989;</div>");
        sb.append("<h2 style=\"margin:16px 0 8px;color:#333333;font-size:20px;\">邮件服务配置成功</h2>");
        sb.append("<p style=\"margin:0;color:#666666;font-size:14px;line-height:1.6;\">这是一封测试邮件，说明您的 SMTP 发件配置正常。<br>买家下单支付成功后，系统会自动将卡密发货到其邮箱。</p>");
        sb.append("</td></tr>");
        sb.append("<tr><td style=\"background-color:#f8f9fa;padding:20px 40px;text-align:center;border-top:1px solid #eee;\">");
        sb.append("<p style=\"margin:0;color:#999;font-size:12px;\">此邮件由系统自动发送，请勿直接回复</p>");
        sb.append("</td></tr>");
        sb.append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    private static String paymentMethodLabel(String method) {
        if (method == null || method.isBlank()) return "";
        return switch (method) {
            case "native_wxpay" -> "微信支付";
            case "native_alipay" -> "支付宝";
            case "epay" -> "易支付";
            case "balance" -> "余额支付";
            default -> method.startsWith("usdt_") ? "USDT 链上转账" : method;
        };
    }

    private static String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
    }
}
