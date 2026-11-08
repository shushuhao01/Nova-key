package com.orionkey.service.impl;

import com.orionkey.constant.UserMessageCategory;
import com.orionkey.entity.NotificationTemplate;
import com.orionkey.entity.Order;
import com.orionkey.entity.UserMessage;
import com.orionkey.service.EmailService;
import com.orionkey.repository.NotificationTemplateRepository;
import com.orionkey.repository.SiteConfigRepository;
import com.orionkey.repository.UserMessageRepository;
import com.orionkey.service.UserMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserMessageServiceImpl implements UserMessageService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserMessageRepository messageRepository;
    private final NotificationTemplateRepository templateRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final EmailService emailService;

    @Async
    @Override
    public void sendUserMessage(UUID userId, String email, String templateCode, Map<String, Object> vars) {
        try {
            // 1. 查模板（站内信与系统消息一致：模板存在即写入，不受 enabled 开关影响；
            //    enabled 只决定是否向外部分发邮件，避免后台未启用模板时用户铃铛收不到任何消息）
            NotificationTemplate t = templateRepository.findByCode(templateCode).orElse(null);
            if (t == null) {
                return;
            }

            // 2. 渲染
            Map<String, Object> merged = new LinkedHashMap<>();
            if (vars != null) merged.putAll(vars);
            merged.putIfAbsent("site_name", siteName());
            merged.putIfAbsent("time", LocalDateTime.now().format(TIME_FMT));

            String title = render(t.getTitle(), merged);
            String content = render(t.getContent(), merged);

            // 3. 写入 user_message（指定 userId，防串户）
            UserMessage msg = new UserMessage();
            msg.setUserId(userId);
            msg.setEmail(email);
            msg.setTemplateCode(templateCode);
            msg.setCategory(parseCategory(t.getCategory()));
            msg.setTitle(title);
            msg.setContent(content);

            // 4. 邮件同步（受模板启用 + EMAIL 渠道 + 全局邮件开关控制；站内信不受影响）
            if (email != null && !email.isBlank() && mailEnabled() && t.isEnabled()
                    && t.getChannels() != null && t.getChannels().contains("EMAIL")) {
                try {
                    emailService.sendNoticeEmail(email, "【" + siteName() + "】" + title, content);
                } catch (Exception e) {
                    log.warn("User message email sync failed for {}: {}", email, e.getMessage());
                }
            }

            // 5. 站内消息：必须绑定用户；匿名订单（userId 为空）只发邮件，不落站内消息，避免产生无人可见的孤立记录
            if (userId != null) {
                messageRepository.save(msg);
            }
        } catch (Exception e) {
            log.error("Failed to send user message {}: {}", templateCode, e.getMessage());
        }
    }

    @Override
    public void sendDirectMessage(UUID userId, String email, UserMessageCategory category, String title, String content) {
        if (userId == null) return;
        try {
            UserMessage msg = new UserMessage();
            msg.setUserId(userId);
            msg.setEmail(email);
            msg.setCategory(category == null ? UserMessageCategory.SYSTEM : category);
            msg.setTitle(title);
            msg.setContent(content);
            messageRepository.save(msg);
        } catch (Exception e) {
            log.error("Failed to save direct user message for {}: {}", userId, e.getMessage());
        }
    }

    @Override
    public void sendOrderPaidNotice(Order order, String paymentMethodLabel) {
        if (order == null || order.getUserId() == null) return;
        BigDecimal amount = order.getActualAmount() != null ? order.getActualAmount() : order.getTotalAmount();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("order_no", shortOrderNo(order));
        vars.put("amount", amount != null ? amount.toPlainString() : "0");
        vars.put("payment_method", paymentMethodLabel != null ? paymentMethodLabel : "");
        sendUserMessage(order.getUserId(), null, "ORDER_PAID_USER", vars);
    }

    @Override
    public void sendOrderDeliveredNotice(Order order, String product, int quantity) {
        if (order == null || order.getUserId() == null) return;
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("order_no", shortOrderNo(order));
        vars.put("product", product != null && !product.isBlank() ? product : "-");
        vars.put("quantity", quantity);
        sendUserMessage(order.getUserId(), null, "ORDER_DELIVERED_USER", vars);
    }

    /** 与后台订单通知保持一致，取订单号前 8 位 */
    private static String shortOrderNo(Order order) {
        return order.getId() != null ? order.getId().toString().substring(0, 8) : "-";
    }

    @Override
    public long unreadCount(UUID userId) {
        return messageRepository.countByUserIdAndReadFalse(userId);
    }

    @Override
    public List<UserMessage> recentMessages(UUID userId, int limit) {
        if (userId == null) return List.of();
        int size = Math.min(Math.max(limit, 1), 50);
        return messageRepository.findByUserId(userId, null, false,
                PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
    }

    @Override
    public Page<UserMessage> listMessages(UUID userId, String category, boolean unreadOnly, int page, int pageSize) {
        PageRequest pageable = PageRequest.of(Math.max(0, page - 1), pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        UserMessageCategory cat = null;
        if (category != null && !category.isBlank()) {
            try {
                cat = UserMessageCategory.valueOf(category);
            } catch (IllegalArgumentException ignored) {
                // 非法分类按全部处理
            }
        }
        return messageRepository.findByUserId(userId, cat, unreadOnly, pageable);
    }

    @Override
    public void markRead(UUID userId, UUID messageId) {
        messageRepository.findById(messageId).ifPresent(msg -> {
            if (msg.getUserId() != null && msg.getUserId().equals(userId)) {
                msg.setRead(true);
                msg.setReadAt(LocalDateTime.now());
                messageRepository.save(msg);
            }
        });
    }

    @Override
    @Transactional
    public int markAllRead(UUID userId) {
        return messageRepository.markAllRead(userId);
    }

    @Override
    @Transactional
    public void clearAll(UUID userId) {
        messageRepository.deleteAll(messageRepository.findAllByUserId(userId));
    }

    // ═══════════ Helpers ═══════════

    private String render(String template, Map<String, Object> vars) {
        if (template == null) return "";
        String result = template;
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private UserMessageCategory parseCategory(String cat) {
        if (cat == null) return UserMessageCategory.SYSTEM;
        try {
            return UserMessageCategory.valueOf(cat);
        } catch (IllegalArgumentException e) {
            return UserMessageCategory.SYSTEM;
        }
    }

    private String siteName() {
        return siteConfigRepository.findByConfigKey("site_name")
                .map(c -> c.getConfigValue())
                .filter(v -> v != null && !v.isBlank())
                .orElse("Nova Key");
    }

    private boolean mailEnabled() {
        return siteConfigRepository.findByConfigKey("mail_enabled")
                .map(c -> "true".equalsIgnoreCase(c.getConfigValue()))
                .orElse(true);
    }
}
