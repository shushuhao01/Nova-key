package com.orionkey.service;

import com.orionkey.entity.MarketingRecipient;
import com.orionkey.entity.SiteConfig;
import com.orionkey.repository.MarketingRecipientRepository;
import com.orionkey.repository.SiteConfigRepository;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.FlagTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 退信（DSN）处理服务
 *
 * SMTP 发送成功只代表"对方邮件服务器已接受投递"，真实投递失败时对方会异步回一封
 * multipart/report 格式的退信邮件（DSN）。这里通过 IMAP 定时拉取退信邮箱，
 * 解析 message/delivery-status 中 Action=failed 的收件人，按邮箱匹配最近一次营销邮件
 * 收件人快照并回写 bounced / bouncedAt / bounceReason。
 *
 * 配置项（site_configs）：
 * bounce_enabled / imap_host / imap_port / imap_ssl / imap_username / imap_password / imap_folder
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailBounceService {

    /** 单轮最多扫描的未读邮件数，避免邮箱积压时一次处理过多 */
    private static final int MAX_SCAN = 200;

    /** 退信原因最大长度（avoid 超长诊断信息） */
    private static final int MAX_REASON_LENGTH = 500;

    private final SiteConfigRepository siteConfigRepository;
    private final MarketingRecipientRepository recipientRepository;
    private final PlatformTransactionManager transactionManager;

    /** 退信检测是否启用 */
    public boolean isEnabled() {
        return "true".equalsIgnoreCase(cfg("bounce_enabled", "false"));
    }

    /** 定时任务入口：未启用时直接跳过，异常只记日志不影响其他任务 */
    @Scheduled(cron = "${mail.bounce.cron:0 */10 * * * *}")
    public void scheduledPoll() {
        if (!isEnabled()) {
            return;
        }
        try {
            int processed = pollBounces();
            if (processed > 0) {
                log.info("退信检测：本次回写 {} 个退信收件人", processed);
            }
        } catch (Exception e) {
            log.error("退信检测执行失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 立即拉取退信邮箱并回写退信状态。
     *
     * @return 本次回写的收件人数量
     */
    public int pollBounces() {
        if (!isEnabled()) {
            log.warn("退信检测未启用（bounce_enabled != true），跳过");
            return 0;
        }
        String host = cfg("imap_host", "");
        String username = cfg("imap_username", "");
        String password = cfg("imap_password", "");
        if (host.isBlank() || username.isBlank() || password.isBlank()) {
            log.warn("退信检测已启用，但 IMAP 主机 / 账号 / 密码未配置完整，跳过本轮");
            return 0;
        }
        boolean ssl = "true".equalsIgnoreCase(cfg("imap_ssl", "true"));
        int defaultPort = ssl ? 993 : 143;
        int port = parsePort(cfg("imap_port", String.valueOf(defaultPort)), defaultPort);
        String folderName = cfg("imap_folder", "INBOX");

        Map<String, String> reasonByEmail = new HashMap<>();
        Store store = null;
        Folder folder = null;
        try {
            String protocol = ssl ? "imaps" : "imap";
            Properties props = new Properties();
            props.put("mail.store.protocol", protocol);
            props.put("mail." + protocol + ".host", host);
            props.put("mail." + protocol + ".port", String.valueOf(port));
            props.put("mail." + protocol + ".connectiontimeout", "10000");
            props.put("mail." + protocol + ".timeout", "15000");
            props.put("mail." + protocol + ".writetimeout", "15000");
            if (ssl) {
                props.put("mail.imaps.ssl.enable", "true");
            }
            Session session = Session.getInstance(props);
            store = session.getStore(protocol);
            store.connect(host, port, username, password);

            folder = store.getFolder(folderName);
            folder.open(Folder.READ_WRITE);

            // 只扫描未读邮件；非 DSN 的普通未读邮件不改 SEEN，避免干扰人工查看
            Message[] messages = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            int scanned = Math.min(messages.length, MAX_SCAN);
            for (int i = 0; i < scanned; i++) {
                Message msg = messages[i];
                try {
                    if (!isDeliveryReport(msg)) {
                        continue;
                    }
                    List<Bounce> bounces = new ArrayList<>();
                    collectBounces(msg, bounces);
                    if (bounces.isEmpty()) {
                        continue;
                    }
                    for (Bounce b : bounces) {
                        reasonByEmail.putIfAbsent(b.email().toLowerCase(Locale.ROOT), b.reason());
                    }
                    // 处理成功的退信邮件标记已读，避免每轮重复解析
                    msg.setFlag(Flags.Flag.SEEN, true);
                } catch (Exception e) {
                    log.warn("解析退信邮件失败（subject={}）：{}", safeSubject(msg), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("连接退信邮箱失败（{}:{}）：{}", host, port, e.getMessage());
        } finally {
            closeQuietly(folder, store);
        }

        if (reasonByEmail.isEmpty()) {
            return 0;
        }
        return writeBack(reasonByEmail);
    }

    /** 是否为退信报告邮件（multipart/report） */
    private boolean isDeliveryReport(Message msg) throws Exception {
        String contentType = msg.getContentType();
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/report");
    }

    /** 递归遍历邮件结构，收集 message/delivery-status 中的失败收件人 */
    private void collectBounces(Part part, List<Bounce> out) throws Exception {
        String contentType = part.getContentType();
        if (contentType == null) {
            return;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.startsWith("multipart/")) {
            Object content = part.getContent();
            if (content instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    collectBounces(multipart.getBodyPart(i), out);
                }
            }
        } else if (lower.startsWith("message/delivery-status")) {
            out.addAll(parseDeliveryStatus(part));
        }
    }

    /** 解析 message/delivery-status：按空行分块，每个收件人一个状态块 */
    private List<Bounce> parseDeliveryStatus(Part part) {
        List<Bounce> result = new ArrayList<>();
        String recipient = null;
        String action = null;
        String status = null;
        String diagnostic = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(part.getInputStream(), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    addBounce(result, recipient, action, status, diagnostic);
                    recipient = null;
                    action = null;
                    status = null;
                    diagnostic = null;
                    continue;
                }
                int idx = line.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                String name = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(idx + 1).trim();
                switch (name) {
                    case "final-recipient", "original-recipient" -> {
                        if (recipient == null || "final-recipient".equals(name)) {
                            recipient = afterSemicolon(value);
                        }
                    }
                    case "action" -> action = value;
                    case "status" -> status = value;
                    case "diagnostic-code" -> diagnostic = afterSemicolon(value);
                    default -> {
                        // 其他字段忽略
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取 DSN 内容失败：{}", e.getMessage());
        }
        addBounce(result, recipient, action, status, diagnostic);
        return result;
    }

    /** 只收集 Action=failed 的收件人，并拼接 Status / Diagnostic-Code 作为退信原因 */
    private void addBounce(List<Bounce> out, String recipient, String action,
                           String status, String diagnostic) {
        if (recipient == null || recipient.isBlank()) {
            return;
        }
        if (action == null || !"failed".equalsIgnoreCase(action.trim())) {
            return;
        }
        StringBuilder reason = new StringBuilder();
        if (status != null && !status.isBlank()) {
            reason.append("Status: ").append(status.trim());
        }
        if (diagnostic != null && !diagnostic.isBlank()) {
            if (reason.length() > 0) {
                reason.append("；");
            }
            reason.append(diagnostic.trim());
        }
        out.add(new Bounce(recipient.trim(), reason.length() == 0 ? "对方邮件服务器拒收" : reason.toString()));
    }

    /** 取分号后的值："rfc822; a@b.com" → "a@b.com" */
    private String afterSemicolon(String value) {
        int idx = value.lastIndexOf(';');
        return (idx >= 0 ? value.substring(idx + 1) : value).trim();
    }

    /** 回写退信状态：按邮箱匹配最近一次发送记录，只标记首次退信 */
    private int writeBack(Map<String, String> reasonByEmail) {
        LocalDateTime now = LocalDateTime.now();
        int[] marked = {0};
        new TransactionTemplate(transactionManager).executeWithoutResult(txStatus -> {
            for (Map.Entry<String, String> entry : reasonByEmail.entrySet()) {
                List<MarketingRecipient> rows =
                        recipientRepository.findByEmailIgnoreCaseOrderByCreatedAtDesc(entry.getKey());
                if (rows.isEmpty()) {
                    // 非营销邮件产生的退信（如订单通知），无需回写
                    continue;
                }
                MarketingRecipient latest = rows.get(0);
                if (latest.getBounced() == 1) {
                    continue;
                }
                int n = recipientRepository.markBounced(latest.getId(), now, truncate(entry.getValue()));
                if (n > 0) {
                    marked[0] += n;
                    log.info("退信回写：{}（{}）", latest.getEmail(), entry.getValue());
                }
            }
        });
        return marked[0];
    }

    private String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return "对方邮件服务器拒收";
        }
        String trimmed = reason.trim();
        return trimmed.length() > MAX_REASON_LENGTH ? trimmed.substring(0, MAX_REASON_LENGTH) : trimmed;
    }

    private int parsePort(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private String safeSubject(Message msg) {
        try {
            return msg.getSubject();
        } catch (Exception e) {
            return "";
        }
    }

    private void closeQuietly(Folder folder, Store store) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(false);
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private String cfg(String key, String fallback) {
        return siteConfigRepository.findByConfigKey(key)
                .map(SiteConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .orElse(fallback);
    }

    /** 单条退信解析结果 */
    private record Bounce(String email, String reason) {
    }
}
