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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                String html = renderForRecipient(c, r, coupon, code, siteUrl, siteName, recipientIds.get(i));
                emailService.sendMarketingEmail(r.email(), subject, html);
                recipientRepository.markDelivered(recipientIds.get(i), 1, LocalDateTime.now());
                ok++;
            } catch (Exception e) {
                log.warn("Marketing email to {} failed: {}", r.email(), e.getMessage());
                recipientRepository.markDelivered(recipientIds.get(i), 0, LocalDateTime.now());
            }
        }
        int failed = recipients.size() - ok;
        if (failed > 0) {
            // 只定向更新失败数：整体 save 会把异步线程早先读到的旧状态（DRAFT / sentCount=0）写回，
            // 导致"已发送却显示草稿"，因此这里不改动 status / sentCount。
            campaignRepository.updateFailedCount(campaignId, failed);
        }
        log.info("Marketing campaign {} sent, {}/{} ok", campaignId, ok, recipients.size());
    }

    /** 匹配 href="..." 属性（用于把站内链接改写为追踪跳转） */
    private static final Pattern HREF_PATTERN = Pattern.compile("href=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    /** 纯文本中的裸链接（正文里直接写出来的 URL） */
    private static final Pattern BARE_URL_PATTERN = Pattern.compile("https?://[^\\s<>\"'，。；）)]+");

    /** HTML 标签，用于跳过属性区域，避免把 href/src 里的 URL 再包一层链接 */
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]*>");

    private String renderForRecipient(MarketingCampaign c, MarketingServiceImpl.Recipient r, MarketingCampaign coupon,
                                      String code, String siteUrl, String siteName, UUID recipientId) {
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
        // 站内链接改为追踪跳转（记录点击），并在末尾插入 1x1 追踪像素（记录打开）
        if (recipientId != null) {
            // 正文里直接输入的裸链接（如"点击可达{claim_url}"）没有 <a> 标签，
            // 邮件客户端会自动识别成链接但不会经过追踪接口，这里先补成 <a> 再统一改写
            html = linkifyBareUrls(html);
            html = rewriteInternalLinks(html, siteUrl, recipientId);
            html = appendTrackingPixel(html, siteUrl, recipientId);
        }
        return html;
    }

    /** 把正文中裸写的链接补成 <a> 标签（已在 <a> 内的文本跳过，避免嵌套） */
    private String linkifyBareUrls(String html) {
        Matcher tag = HTML_TAG_PATTERN.matcher(html);
        StringBuilder out = new StringBuilder(html.length() + 256);
        int last = 0;
        int anchorDepth = 0;
        while (tag.find()) {
            String text = html.substring(last, tag.start());
            out.append(anchorDepth > 0 ? text : linkifyText(text));
            String t = tag.group();
            if (t.matches("(?i)<a(\\s[^>]*)?>")) {
                anchorDepth++;
            } else if (t.matches("(?i)</a\\s*>")) {
                anchorDepth = Math.max(0, anchorDepth - 1);
            }
            out.append(t);
            last = tag.end();
        }
        String tail = html.substring(last);
        out.append(anchorDepth > 0 ? tail : linkifyText(tail));
        return out.toString();
    }

    private String linkifyText(String text) {
        if (text == null || text.isEmpty() || !text.contains("http")) {
            return text;
        }
        Matcher m = BARE_URL_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 64);
        while (m.find()) {
            String url = m.group();
            // 末尾的中英文标点不属于链接，单独放回
            String trail = "";
            while (!url.isEmpty() && ".,;:!?、，。；：！？".indexOf(url.charAt(url.length() - 1)) >= 0) {
                trail = url.charAt(url.length() - 1) + trail;
                url = url.substring(0, url.length() - 1);
            }
            if (url.length() <= "https://".length()) {
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    "<a href=\"" + url + "\" style=\"color:#ea580c;\">" + url + "</a>" + trail));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 把邮件中的站内链接改写为 /api/marketing/track/click 跳转，用于统计点击 */
    private String rewriteInternalLinks(String html, String siteUrl, UUID recipientId) {
        Matcher m = HREF_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String href = m.group(1);
            String path = toInternalPath(href, siteUrl);
            String replaced = path == null ? href
                    : siteUrl + "/api/marketing/track/click?rid=" + recipientId
                      + "&amp;p=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
            m.appendReplacement(sb, Matcher.quoteReplacement("href=\"" + replaced + "\""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 站内链接（绝对站点地址或相对路径）转相对路径；外部链接返回 null 保持原样 */
    private String toInternalPath(String href, String siteUrl) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String h = href.trim();
        if (h.startsWith("#") || h.startsWith("mailto:") || h.startsWith("tel:") || h.startsWith("javascript:")) {
            return null;
        }
        if (h.equals(siteUrl)) {
            return "/";
        }
        if (h.startsWith(siteUrl + "/")) {
            return h.substring(siteUrl.length());
        }
        if (h.startsWith("/") && !h.startsWith("//")) {
            return h;
        }
        return null;
    }

    private String appendTrackingPixel(String html, String siteUrl, UUID recipientId) {
        return html + "<img src=\"" + siteUrl + "/api/marketing/track/open?rid=" + recipientId
                + "\" width=\"1\" height=\"1\" alt=\"\" style=\"display:none;border:0;\" />";
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
