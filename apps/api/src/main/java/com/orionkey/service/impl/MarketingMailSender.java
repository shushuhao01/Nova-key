package com.orionkey.service.impl;

import com.orionkey.entity.MarketingCampaign;
import com.orionkey.entity.SiteConfig;
import com.orionkey.repository.MarketingCampaignRepository;
import com.orionkey.repository.MarketingRecipientRepository;
import com.orionkey.repository.SiteConfigRepository;
import com.orionkey.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 营销邮件异步发送器（独立 Bean，保证 @Async 通过 Spring 代理生效）。
 * 按收件人逐封渲染占位符（{username}/{site_url}/{claim_url}/{coupon_code}），
 * 邮件底部默认附带官网地址；发送后更新收件人送达状态与失败统计。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketingMailSender {

    private final MarketingCampaignRepository campaignRepository;
    private final MarketingRecipientRepository recipientRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final EmailService emailService;

    @Value("${app.base-url:https://noepay.cn}")
    private String baseUrlDefault;

    @Async
    @Transactional
    public void sendAsync(UUID campaignId, MarketingCampaign coupon, List<MarketingServiceImpl.Recipient> recipients,
                          Map<String, String> codeByEmail, List<UUID> recipientIds) {
        MarketingCampaign c = campaignRepository.findById(campaignId).orElse(null);
        if (c == null) {
            return;
        }
        String siteUrl = siteUrl();
        String siteName = siteName();
        String title = c.getTitle();
        int ok = 0;
        for (int i = 0; i < recipients.size(); i++) {
            MarketingServiceImpl.Recipient r = recipients.get(i);
            String code = codeByEmail == null ? null : codeByEmail.get(r.email());
            try {
                String subject = (c.getSubject() == null || c.getSubject().isBlank())
                        ? "【" + siteName + "】" + title
                        : c.getSubject();
                String html = renderForRecipient(c, r, coupon, code, siteUrl, siteName);
                emailService.sendMarketingEmail(r.email(), subject, html);
                recipientRepository.markDelivered(recipientIds.get(i), 1, LocalDateTime.now());
                ok++;
            } catch (Exception e) {
                log.warn("Marketing email to {} failed: {}", r.email(), e.getMessage());
                recipientRepository.markDelivered(recipientIds.get(i), 0, LocalDateTime.now());
            }
        }
        int failed = recipients.size() - ok;
        if (failed > 0 && c.getFailedCount() != failed) {
            c.setFailedCount(failed);
            campaignRepository.save(c);
        }
        log.info("Marketing campaign {} sent, {}/{} ok", campaignId, ok, recipients.size());
    }

    private String renderForRecipient(MarketingCampaign c, MarketingServiceImpl.Recipient r, MarketingCampaign coupon,
                                      String code, String siteUrl, String siteName) {
        String html = c.getContent() == null ? "" : c.getContent();
        // 富文本中上传的图片为相对路径（/api/uploads/xxx.png），邮件客户端无法解析 → 补全为绝对 URL
        String uploadBase = siteUrl + "/api/uploads/";
        html = html.replace("src=\"/api/uploads/", "src=\"" + uploadBase)
                .replace("src='/api/uploads/", "src='" + uploadBase)
                .replace("href=\"/api/uploads/", "href=\"" + uploadBase)
                .replace("href='/api/uploads/", "href='" + uploadBase);
        String username = (r.username() != null && !r.username().isBlank()) ? r.username() : "";
        String claimUrl = (coupon != null && code != null) ? siteUrl + "/coupons/claim?code=" + code : siteUrl;
        html = html.replace("{username}", username)
                .replace("{site_url}", siteUrl)
                .replace("{claim_url}", claimUrl)
                .replace("{coupon_code}", code == null ? "" : code);
        if (!html.contains(siteUrl)) {
            html += "<div style=\"margin-top:24px;padding-top:12px;border-top:1px solid #eee;font-size:12px;color:#999;\">"
                    + "<a href=\"" + siteUrl + "\" style=\"color:#999;\">" + escapeHtml(siteName) + " 官网</a> · "
                    + siteUrl + "</div>";
        }
        return html;
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String siteUrl() {
        return siteConfigRepository.findByConfigKey("mail_site_url")
                .map(SiteConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(baseUrlDefault);
    }

    private String siteName() {
        return siteConfigRepository.findByConfigKey("site_name")
                .map(SiteConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse("Nova key");
    }
}
