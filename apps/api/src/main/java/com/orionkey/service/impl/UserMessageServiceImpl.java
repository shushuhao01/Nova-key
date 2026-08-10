package com.orionkey.service.impl;

import com.orionkey.constant.UserMessageCategory;
import com.orionkey.entity.NotificationTemplate;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserMessageServiceImpl implements UserMessageService {

    private final UserMessageRepository messageRepository;
    private final NotificationTemplateRepository templateRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final EmailService emailService;

    @Async
    @Override
    public void sendUserMessage(UUID userId, String email, String templateCode, Map<String, Object> vars) {
        try {
            // 1. 查模板
            NotificationTemplate t = templateRepository.findByCode(templateCode).orElse(null);
            if (t == null || !t.isEnabled()) {
                return;
            }

            // 2. 渲染
            Map<String, Object> merged = new LinkedHashMap<>();
            if (vars != null) merged.putAll(vars);
            merged.putIfAbsent("site_name", siteName());
            merged.putIfAbsent("time", LocalDateTime.now().toString());

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

            // 4. 邮件同步（仅当管理后台启用邮箱发件 且 用户有邮箱时）
            if (email != null && !email.isBlank() && mailEnabled() && t.getChannels().contains("EMAIL")) {
                try {
                    emailService.sendNoticeEmail(email, "【" + siteName() + "】" + title, content);
                } catch (Exception e) {
                    log.warn("User message email sync failed for {}: {}", email, e.getMessage());
                }
            }

            messageRepository.save(msg);
        } catch (Exception e) {
            log.error("Failed to send user message {}: {}", templateCode, e.getMessage());
        }
    }

    @Override
    public long unreadCount(UUID userId) {
        return messageRepository.countByUserIdAndReadFalse(userId);
    }

    @Override
    public List<UserMessage> recentMessages(UUID userId, int limit) {
        return messageRepository.findTop5ByUserIdOrderByCreatedAtDesc(userId);
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
    public int markAllRead(UUID userId) {
        return messageRepository.markAllRead(userId);
    }

    @Override
    public void clearAll(UUID userId) {
        List<UserMessage> msgs = messageRepository.findByUserId(userId, null, false,
                PageRequest.of(0, Integer.MAX_VALUE)).getContent();
        messageRepository.deleteAll(msgs);
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
