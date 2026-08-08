package com.orionkey.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.common.PageResult;
import com.orionkey.constant.ErrorCode;
import com.orionkey.entity.MarketingCampaign;
import com.orionkey.entity.SiteConfig;
import com.orionkey.entity.User;
import com.orionkey.entity.UserCoupon;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.MarketingCampaignRepository;
import com.orionkey.repository.SiteConfigRepository;
import com.orionkey.repository.UserCouponRepository;
import com.orionkey.repository.UserRepository;
import com.orionkey.service.EmailService;
import com.orionkey.service.MarketingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketingServiceImpl implements MarketingService {

    private final MarketingCampaignRepository campaignRepository;
    private final UserCouponRepository couponRepository;
    private final UserRepository userRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    @Value("${app.base-url:https://noepay.cn}")
    private String baseUrlDefault;

    // ═══════════ 管理后台：营销活动 CRUD ═══════════

    @Override
    @Transactional(readOnly = true)
    public PageResult<?> listCampaigns(String keyword, String status, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<MarketingCampaign> p;
        if (keyword != null && !keyword.isBlank()) {
            p = campaignRepository.findByTitleContaining(keyword, pageable);
        } else if (status != null && !status.isBlank()) {
            p = campaignRepository.findByStatus(status, pageable);
        } else {
            p = campaignRepository.findAll(pageable);
        }
        List<Map<String, Object>> list = p.getContent().stream().map(this::toCampaignMap).toList();
        return PageResult.of(p, list);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getCampaign(UUID id) {
        return toCampaignMap(campaignRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "营销活动不存在")));
    }

    @Override
    @Transactional
    public Map<String, Object> createCampaign(Map<String, Object> body) {
        MarketingCampaign c = new MarketingCampaign();
        applyCampaignBody(c, body);
        c.setStatus("DRAFT");
        c.setSentCount(0);
        return toCampaignMap(campaignRepository.save(c));
    }

    @Override
    @Transactional
    public Map<String, Object> updateCampaign(UUID id, Map<String, Object> body) {
        MarketingCampaign c = campaignRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "营销活动不存在"));
        if ("SENT".equals(c.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已发送的活动不可修改");
        }
        applyCampaignBody(c, body);
        return toCampaignMap(campaignRepository.save(c));
    }

    @Override
    @Transactional
    public void deleteCampaign(UUID id) {
        MarketingCampaign c = campaignRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "营销活动不存在"));
        campaignRepository.delete(c);
    }

    private void applyCampaignBody(MarketingCampaign c, Map<String, Object> body) {
        if (body.get("title") instanceof String s && !s.isBlank()) {
            c.setTitle(s.trim());
        }
        if (body.containsKey("subject")) {
            c.setSubject(body.get("subject") == null ? "" : String.valueOf(body.get("subject")).trim());
        }
        if (body.containsKey("content")) {
            c.setContent(body.get("content") == null ? "" : String.valueOf(body.get("content")));
        }
        if (body.get("audience_type") instanceof String at) {
            c.setAudienceType(at);
        }
        if (body.containsKey("target_json")) {
            c.setTargetJson(body.get("target_json") == null ? null : String.valueOf(body.get("target_json")));
        }
        if (body.containsKey("coupon_type")) {
            String t = body.get("coupon_type") == null ? null : String.valueOf(body.get("coupon_type"));
            c.setCouponType("AMOUNT".equals(t) || "PERCENT".equals(t) ? t : null);
        }
        if (body.get("coupon_value") instanceof Number n) {
            c.setCouponValue(BigDecimal.valueOf(n.doubleValue()));
        }
        if (body.get("coupon_min_amount") instanceof Number n) {
            c.setCouponMinAmount(BigDecimal.valueOf(n.doubleValue()));
        }
        if (body.containsKey("coupon_code")) {
            String code = body.get("coupon_code") == null ? null : String.valueOf(body.get("coupon_code")).trim().toUpperCase();
            if (code != null && code.isBlank()) code = null;
            // 核销码唯一性检查
            final String finalCode = code;
            if (code != null) {
                boolean dup = campaignRepository.findAll().stream()
                        .filter(x -> !x.getId().equals(c.getId()))
                        .anyMatch(x -> finalCode.equals(x.getCouponCode()));
                if (dup) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "核销码已被其他活动使用");
                }
            }
            c.setCouponCode(code);
        }
        if (body.get("coupon_quantity") instanceof Number n) {
            c.setCouponQuantity(Math.max(0, n.intValue()));
        }
        if (body.get("coupon_valid_from") instanceof String s && !s.isBlank()) {
            c.setCouponValidFrom(LocalDateTime.parse(s));
        } else {
            c.setCouponValidFrom(null);
        }
        if (body.get("coupon_valid_to") instanceof String s && !s.isBlank()) {
            c.setCouponValidTo(LocalDateTime.parse(s));
        } else {
            c.setCouponValidTo(null);
        }
        if (body.containsKey("coupon_scope")) {
            String scope = body.get("coupon_scope") == null ? null : String.valueOf(body.get("coupon_scope"));
            c.setCouponScope("SPECIFIC".equals(scope) ? "SPECIFIC" : "ALL");
        }
        if (body.containsKey("coupon_product_ids")) {
            String raw = body.get("coupon_product_ids") == null ? null : String.valueOf(body.get("coupon_product_ids"));
            if ("SPECIFIC".equals(c.getCouponScope()) && raw != null && !raw.isBlank()) {
                c.setCouponProductIds(raw);
            } else {
                c.setCouponProductIds(null);
            }
        }
    }

    @Override
    @Transactional
    public Map<String, Object> sendCampaign(UUID id) {
        MarketingCampaign c = campaignRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "营销活动不存在"));
        List<String> recipients = resolveAudience(c);
        if (recipients.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "没有可发送的收件人，请检查受众设置");
        }
        c.setStatus("SENT");
        c.setSentCount(recipients.size());
        campaignRepository.save(c);
        // 异步批量发送，返回后不阻塞管理端
        doSendAsync(c.getId(), c.getTitle(), c.getSubject(), renderContent(c), recipients);
        return Map.of("sent", recipients.size());
    }

    /** 解析受众邮箱列表 */
    private List<String> resolveAudience(MarketingCampaign c) {
        List<String> emails = new ArrayList<>();
        switch (c.getAudienceType() == null ? "ALL_USERS" : c.getAudienceType()) {
            case "ALL_USERS" -> userRepository.findAll().stream()
                    .filter(u -> u.getIsDeleted() == 0)
                    .map(User::getEmail)
                    .filter(e -> e != null && !e.isBlank())
                    .forEach(emails::add);
            case "USER_IDS" -> {
                // 支持 UUID / 用户名 / 邮箱混合输入，逐一解析为注册用户
                List<String> tokens = parseStringList(c.getTargetJson());
                Set<UUID> seen = new HashSet<>();
                for (String token : tokens) {
                    User u = null;
                    UUID uuid = parseUuid(token);
                    if (uuid != null) {
                        u = userRepository.findById(uuid).orElse(null);
                    }
                    if (u == null) {
                        u = userRepository.findByUsernameOrEmail(token, token).orElse(null);
                    }
                    if (u != null && u.getIsDeleted() == 0 && seen.add(u.getId())
                            && u.getEmail() != null && !u.getEmail().isBlank()) {
                        emails.add(u.getEmail());
                    }
                }
            }
            case "EMAILS" -> {
                if (c.getTargetJson() != null && !c.getTargetJson().isBlank()) {
                    try {
                        List<String> raw = objectMapper.readValue(c.getTargetJson(), new TypeReference<List<String>>() {});
                        raw.stream().map(String::trim).filter(e -> !e.isBlank()).forEach(emails::add);
                    } catch (Exception e) {
                        log.warn("Invalid EMAILS target_json: {}", c.getTargetJson());
                    }
                }
            }
            default -> { }
        }
        return emails.stream().distinct().toList();
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 渲染邮件正文占位符：{site_url} / {claim_url} / {coupon_code} */
    private String renderContent(MarketingCampaign c) {
        String content = c.getContent() == null ? "" : c.getContent();
        String siteUrl = siteUrl();
        String claimUrl = c.getCouponCode() != null
                ? siteUrl + "/coupons/claim?code=" + c.getCouponCode()
                : siteUrl;
        return content
                .replace("{site_url}", siteUrl)
                .replace("{claim_url}", claimUrl)
                .replace("{coupon_code}", c.getCouponCode() == null ? "" : c.getCouponCode());
    }

    @Async
    public void doSendAsync(UUID campaignId, String title, String subject, String content, List<String> recipients) {
        String finalSubject = (subject == null || subject.isBlank())
                ? "【" + siteName() + "】" + title
                : subject;
        int ok = 0;
        for (String to : recipients) {
            try {
                emailService.sendMarketingEmail(to, finalSubject, content);
                ok++;
            } catch (Exception e) {
                log.warn("Marketing email to {} failed: {}", to, e.getMessage());
            }
        }
        log.info("Marketing campaign {} sent, {}/{} ok", campaignId, ok, recipients.size());
    }

    // ═══════════ 前台：优惠券领取 / 校验 ═══════════

    @Override
    @Transactional
    public Map<String, Object> claimCoupon(String code, UUID userId, String email) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请输入优惠券核销码");
        }
        String c = code.trim().toUpperCase();
        MarketingCampaign found = findByCouponCode(c);
        if (found == null || found.getCouponType() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "优惠券不存在或已下架");
        }
        // 行锁防并发超发：同一活动同时领取时串行执行数量与去重检查
        MarketingCampaign campaign = campaignRepository.findByIdForUpdate(found.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "优惠券不存在或已下架"));
        LocalDateTime now = LocalDateTime.now();
        if (campaign.getCouponValidFrom() != null && now.isBefore(campaign.getCouponValidFrom())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "优惠券还未到生效时间");
        }
        if (campaign.getCouponValidTo() != null && now.isAfter(campaign.getCouponValidTo())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "优惠券已过有效期");
        }
        long claimed = couponRepository.countByCampaignId(campaign.getId());
        if (campaign.getCouponQuantity() > 0 && claimed >= campaign.getCouponQuantity()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "优惠券已被领完");
        }
        if (userId != null) {
            if (couponRepository.existsByCodeAndUserId(c, userId)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "您已领取过该优惠券，请直接使用核销码下单");
            }
        } else {
            if (email == null || email.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "请先登录或提供邮箱后领取");
            }
            if (couponRepository.existsByCodeAndEmail(c, email.trim().toLowerCase())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "该邮箱已领取过此优惠券，请直接使用核销码下单");
            }
        }

        UserCoupon uc = new UserCoupon();
        uc.setCampaignId(campaign.getId());
        uc.setCode(c);
        uc.setUserId(userId);
        uc.setEmail(email == null ? null : email.trim().toLowerCase());
        uc.setType(campaign.getCouponType());
        uc.setValue(campaign.getCouponValue());
        uc.setStatus("CLAIMED");
        uc.setClaimedAt(now);
        uc.setValidFrom(campaign.getCouponValidFrom());
        uc.setValidTo(campaign.getCouponValidTo());
        // 快照适用范围，防止活动后续修改影响已领取的券
        uc.setScope(campaign.getCouponScope() == null ? "ALL" : campaign.getCouponScope());
        uc.setProductIds(campaign.getCouponProductIds());
        couponRepository.save(uc);

        return Map.of(
                "code", c,
                "coupon_type", campaign.getCouponType(),
                "coupon_value", campaign.getCouponValue() == null ? BigDecimal.ZERO : campaign.getCouponValue(),
                "valid_from", campaign.getCouponValidFrom(),
                "valid_to", campaign.getCouponValidTo());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> validateCoupon(String code, UUID userId, String email, BigDecimal amount, List<UUID> productIds) {
        CouponCheck check = checkCoupon(code, userId, email, amount, productIds);
        if (!check.usable()) {
            return Map.of("valid", false, "message", check.message());
        }
        UserCoupon uc = check.coupon();
        BigDecimal discount = computeDiscount(uc, amount == null ? BigDecimal.ZERO : amount);
        return Map.of("valid", true, "discount", discount, "coupon_type", uc.getType(),
                "coupon_value", uc.getValue() == null ? BigDecimal.ZERO : uc.getValue(),
                "message", "优惠券有效");
    }

    @Override
    @Transactional
    public BigDecimal applyCoupon(String code, UUID userId, String email, BigDecimal totalAmount, UUID orderId, List<UUID> productIds) {
        if (code == null || code.isBlank()) {
            return BigDecimal.ZERO;
        }
        CouponCheck check = checkCoupon(code.trim(), userId, email, totalAmount, productIds);
        if (!check.usable()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, check.message());
        }
        BigDecimal discount = computeDiscount(check.coupon(), totalAmount);
        int updated = couponRepository.markUsed(check.coupon().getId(), LocalDateTime.now(), orderId);
        if (updated <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "优惠券已被使用");
        }
        return discount;
    }

    /** 校验结果：usable=true 表示可用；message 用于向前台返回具体原因 */
    private record CouponCheck(boolean usable, String message, UserCoupon coupon) {
        static CouponCheck fail(String message) {
            return new CouponCheck(false, message, null);
        }
        static CouponCheck ok(UserCoupon coupon) {
            return new CouponCheck(true, null, coupon);
        }
    }

    /**
     * 校验优惠券并返回详细原因：
     * 不存在/未领取/已使用/未生效/已过期/未达满减门槛/不适用当前商品。
     */
    private CouponCheck checkCoupon(String code, UUID userId, String email, BigDecimal amount, List<UUID> productIds) {
        if (code == null || code.isBlank()) {
            return CouponCheck.fail("请输入优惠券核销码");
        }
        String c = code.trim().toUpperCase();
        MarketingCampaign campaign = findByCouponCode(c);
        if (campaign == null || campaign.getCouponType() == null) {
            return CouponCheck.fail("优惠券不存在或已下架");
        }
        UserCoupon uc = null;
        if (userId != null) {
            uc = couponRepository.findFirstByCodeAndUserIdAndStatusOrderByCreatedAtDesc(c, userId, "CLAIMED").orElse(null);
        }
        if (uc == null && email != null && !email.isBlank()) {
            uc = couponRepository.findFirstByCodeAndEmailAndStatusOrderByCreatedAtDesc(c, email.trim().toLowerCase(), "CLAIMED").orElse(null);
        }
        if (uc == null) {
            boolean used = (userId != null && couponRepository.existsByCodeAndUserIdAndStatus(c, userId, "USED"))
                    || (email != null && !email.isBlank()
                        && couponRepository.existsByCodeAndEmailAndStatus(c, email.trim().toLowerCase(), "USED"));
            return used ? CouponCheck.fail("优惠券已使用") : CouponCheck.fail("您尚未领取该优惠券，请先领取后再使用");
        }
        LocalDateTime now = LocalDateTime.now();
        if (uc.getValidFrom() != null && now.isBefore(uc.getValidFrom())) {
            return CouponCheck.fail("优惠券还未到生效时间");
        }
        if (uc.getValidTo() != null && now.isAfter(uc.getValidTo())) {
            return CouponCheck.fail("优惠券已过期");
        }
        if (campaign.getCouponMinAmount() != null && campaign.getCouponMinAmount().compareTo(BigDecimal.ZERO) > 0
                && amount != null && amount.compareTo(campaign.getCouponMinAmount()) < 0) {
            return CouponCheck.fail("订单金额未达到满减门槛 "
                    + campaign.getCouponMinAmount().stripTrailingZeros().toPlainString() + " 元");
        }
        // 适用范围：仅指定商品可用时，订单中必须包含可用商品
        if ("SPECIFIC".equals(uc.getScope())) {
            List<UUID> allowed = parseUuidList(uc.getProductIds());
            if (allowed.isEmpty() || productIds == null || productIds.isEmpty()
                    || productIds.stream().noneMatch(allowed::contains)) {
                return CouponCheck.fail("该优惠券仅限指定商品使用，当前商品不适用");
            }
        }
        return CouponCheck.ok(uc);
    }

    /** 解析 JSON 字符串形式的 UUID 列表 */
    private List<UUID> parseUuidList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<UUID>>() {}).stream()
                    .filter(Objects::nonNull).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 计算抵扣金额：AMOUNT 立减（不超过订单金额）；PERCENT 按减免百分比 */
    private BigDecimal computeDiscount(UserCoupon uc, BigDecimal amount) {
        if (uc.getValue() == null || uc.getValue().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if ("PERCENT".equals(uc.getType())) {
            BigDecimal rate = uc.getValue().compareTo(BigDecimal.valueOf(100)) > 0
                    ? BigDecimal.valueOf(100) : uc.getValue();
            return amount.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    .min(amount);
        }
        return uc.getValue().min(amount);
    }

    private MarketingCampaign findByCouponCode(String code) {
        return campaignRepository.findAll().stream()
                .filter(x -> code.equals(x.getCouponCode()))
                .findFirst().orElse(null);
    }

    // ═══════════ 辅助 ═══════════

    private Map<String, Object> toCampaignMap(MarketingCampaign c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("title", c.getTitle());
        map.put("subject", c.getSubject());
        map.put("content", c.getContent());
        map.put("audience_type", c.getAudienceType());
        map.put("target_json", c.getTargetJson());
        map.put("status", c.getStatus());
        map.put("sent_count", c.getSentCount());
        map.put("coupon_type", c.getCouponType());
        map.put("coupon_value", c.getCouponValue());
        map.put("coupon_min_amount", c.getCouponMinAmount());
        map.put("coupon_code", c.getCouponCode());
        map.put("coupon_quantity", c.getCouponQuantity());
        map.put("coupon_claimed", couponRepository.countByCampaignId(c.getId()));
        map.put("coupon_valid_from", c.getCouponValidFrom());
        map.put("coupon_valid_to", c.getCouponValidTo());
        map.put("coupon_scope", c.getCouponScope());
        map.put("coupon_product_ids", c.getCouponProductIds());
        map.put("created_at", c.getCreatedAt());
        map.put("updated_at", c.getUpdatedAt());
        return map;
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
