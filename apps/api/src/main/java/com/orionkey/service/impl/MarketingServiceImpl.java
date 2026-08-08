package com.orionkey.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.common.PageResult;
import com.orionkey.constant.ErrorCode;
import com.orionkey.entity.MarketingCampaign;
import com.orionkey.entity.MarketingRecipient;
import com.orionkey.entity.SiteConfig;
import com.orionkey.entity.User;
import com.orionkey.entity.UserCoupon;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.MarketingCampaignRepository;
import com.orionkey.repository.MarketingRecipientRepository;
import com.orionkey.repository.SiteConfigRepository;
import com.orionkey.repository.UserCouponRepository;
import com.orionkey.repository.UserRepository;
import com.orionkey.service.MarketingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketingServiceImpl implements MarketingService {

    private final MarketingCampaignRepository campaignRepository;
    private final MarketingRecipientRepository recipientRepository;
    private final UserCouponRepository couponRepository;
    private final UserRepository userRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final MarketingMailSender mailSender;
    private final ObjectMapper objectMapper;

    @Value("${app.base-url:https://noepay.cn}")
    private String baseUrlDefault;

    // ═══════════ 优惠券管理（recordType=COUPON） ═══════════

    @Override
    @Transactional(readOnly = true)
    public PageResult<?> listCoupons(String keyword, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<MarketingCampaign> p = keyword != null && !keyword.isBlank()
                ? campaignRepository.findCoupons(keyword.trim(), pageable)
                : campaignRepository.findCoupons(null, pageable);
        List<Map<String, Object>> list = p.getContent().stream().map(this::toCouponMap).toList();
        return PageResult.of(p, list);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getCoupon(UUID id) {
        return toCouponMap(requireCoupon(id));
    }

    @Override
    @Transactional
    public Map<String, Object> createCoupon(Map<String, Object> body) {
        MarketingCampaign c = new MarketingCampaign();
        c.setRecordType("COUPON");
        applyCouponBody(c, body);
        c.setStatus("DRAFT");
        campaignRepository.save(c);
        return toCouponMap(c);
    }

    @Override
    @Transactional
    public Map<String, Object> updateCoupon(UUID id, Map<String, Object> body) {
        MarketingCampaign c = requireCoupon(id);
        applyCouponBody(c, body);
        campaignRepository.save(c);
        return toCouponMap(c);
    }

    @Override
    @Transactional
    public void cancelCoupon(UUID id) {
        MarketingCampaign c = requireCoupon(id);
        c.setIsCanceled(1);
        campaignRepository.save(c);
        log.info("Coupon {} canceled", id);
    }

    @Override
    @Transactional
    public void deleteCoupon(UUID id) {
        MarketingCampaign c = requireCoupon(id);
        long claimed = couponRepository.countByCampaignId(c.getId());
        if (claimed > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该优惠券已有用户领取，无法删除，请改为作废");
        }
        campaignRepository.delete(c);
    }

    private MarketingCampaign requireCoupon(UUID id) {
        MarketingCampaign c = campaignRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "优惠券不存在"));
        if (!"COUPON".equals(c.getRecordType())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "优惠券不存在");
        }
        return c;
    }

    private void applyCouponBody(MarketingCampaign c, Map<String, Object> body) {
        String title = str(body.get("title"));
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "优惠券名称不能为空");
        }
        c.setTitle(title.trim());
        String type = str(body.get("coupon_type"));
        if (!"AMOUNT".equals(type) && !"PERCENT".equals(type)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择优惠券类型");
        }
        c.setCouponType(type);
        if (!(body.get("coupon_value") instanceof Number n) || BigDecimal.valueOf(n.doubleValue()).compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写正确的优惠券金额/比例");
        }
        c.setCouponValue(BigDecimal.valueOf(n.doubleValue()));
        c.setCouponMinAmount(body.get("coupon_min_amount") instanceof Number m
                ? BigDecimal.valueOf(m.doubleValue()) : BigDecimal.ZERO);
        String code = str(body.get("coupon_code"));
        if (code != null) {
            code = code.trim().toUpperCase();
            if (code.isBlank()) code = null;
        }
        if (code != null) {
            final String normalizedCode = code;
            boolean dup = campaignRepository.findAll().stream()
                    .filter(x -> !x.getId().equals(c.getId()))
                    .anyMatch(x -> normalizedCode.equals(x.getCouponCode()));
            if (dup) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "核销码已被其他优惠券使用");
            }
        }
        c.setCouponCode(code);
        // 发行数量：默认 1（0 表示不限制，兼容旧数据）
        int quantity = body.get("coupon_quantity") instanceof Number q ? Math.max(0, q.intValue()) : 1;
        if (body.get("coupon_quantity") == null && c.getCouponQuantity() == 0) {
            quantity = 1;
        }
        c.setCouponQuantity(quantity);
        LocalDateTime from = parseDateTime(body.get("coupon_valid_from"));
        LocalDateTime to = parseDateTime(body.get("coupon_valid_to"));
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "生效时间不能晚于结束时间");
        }
        c.setCouponValidFrom(from);
        c.setCouponValidTo(to);
        c.setCouponScope("SPECIFIC".equals(str(body.get("coupon_scope"))) ? "SPECIFIC" : "ALL");
        String productIds = str(body.get("coupon_product_ids"));
        if ("SPECIFIC".equals(c.getCouponScope()) && productIds != null && !productIds.isBlank()) {
            c.setCouponProductIds(productIds);
        } else {
            c.setCouponProductIds(null);
        }
    }

    // ═══════════ 营销邮件（recordType=EMAIL） ═══════════

    @Override
    @Transactional(readOnly = true)
    public PageResult<?> listEmailCampaigns(String keyword, String status, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        String kw = keyword != null && !keyword.isBlank() ? keyword.trim() : null;
        String st = status != null && !status.isBlank() ? status : null;
        Page<MarketingCampaign> p = campaignRepository.findEmailCampaigns(kw, st, pageable);
        List<Map<String, Object>> list = p.getContent().stream().map(this::toEmailMap).toList();
        return PageResult.of(p, list);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getEmailCampaign(UUID id) {
        return toEmailMap(requireEmailCampaign(id));
    }

    @Override
    @Transactional
    public Map<String, Object> createEmailCampaign(Map<String, Object> body) {
        MarketingCampaign c = new MarketingCampaign();
        c.setRecordType("EMAIL");
        applyEmailBody(c, body);
        LocalDateTime now = LocalDateTime.now();
        c.setStatus(c.getSendAt() != null && c.getSendAt().isAfter(now) ? "SCHEDULED" : "DRAFT");
        c.setSentCount(0);
        campaignRepository.save(c);
        return toEmailMap(c);
    }

    @Override
    @Transactional
    public Map<String, Object> updateEmailCampaign(UUID id, Map<String, Object> body) {
        MarketingCampaign c = requireEmailCampaign(id);
        if ("SENT".equals(c.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已发送的活动不可修改");
        }
        applyEmailBody(c, body);
        LocalDateTime now = LocalDateTime.now();
        if (c.getSendAt() != null && c.getSendAt().isAfter(now)) {
            c.setStatus("SCHEDULED");
        } else {
            c.setStatus("DRAFT");
        }
        campaignRepository.save(c);
        return toEmailMap(c);
    }

    @Override
    @Transactional
    public void deleteEmailCampaign(UUID id) {
        MarketingCampaign c = requireEmailCampaign(id);
        campaignRepository.delete(c);
    }

    private MarketingCampaign requireEmailCampaign(UUID id) {
        MarketingCampaign c = campaignRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "营销邮件不存在"));
        if ("COUPON".equals(c.getRecordType())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "营销邮件不存在");
        }
        return c;
    }

    private void applyEmailBody(MarketingCampaign c, Map<String, Object> body) {
        String title = str(body.get("title"));
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮件标题不能为空");
        }
        c.setTitle(title.trim());
        if (body.containsKey("subject")) {
            c.setSubject(body.get("subject") == null ? null : String.valueOf(body.get("subject")).trim());
        }
        if (body.containsKey("content")) {
            c.setContent(body.get("content") == null ? null : String.valueOf(body.get("content")));
        }
        String audience = str(body.get("audience_type"));
        if (audience == null || !List.of("ALL_USERS", "USER_IDS", "EMAILS").contains(audience)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择受众类型");
        }
        c.setAudienceType(audience);
        if (body.containsKey("target_json")) {
            c.setTargetJson(body.get("target_json") == null ? null : String.valueOf(body.get("target_json")));
        }
        if (body.containsKey("send_at")) {
            c.setSendAt(parseDateTime(body.get("send_at")));
        }
        // 关联优惠券（可选）：必须是存在且配置了优惠内容的优惠券记录
        if (body.containsKey("coupon_ref_id")) {
            String ref = str(body.get("coupon_ref_id"));
            UUID refId = null;
            if (ref != null && !ref.isBlank()) {
                try {
                    refId = UUID.fromString(ref);
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "关联优惠券参数无效");
                }
                MarketingCampaign coupon = campaignRepository.findById(refId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "关联的优惠券不存在"));
                if (coupon.getCouponType() == null) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "关联的记录不是优惠券");
                }
            }
            c.setCouponRefId(refId);
        }
    }

    @Override
    @Transactional
    public Map<String, Object> sendEmailCampaign(UUID id) {
        MarketingCampaign c = requireEmailCampaign(id);
        if ("SENT".equals(c.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该邮件已发送，不可重复发送");
        }
        LocalDateTime now = LocalDateTime.now();
        if (c.getSendAt() != null && c.getSendAt().isAfter(now)) {
            c.setStatus("SCHEDULED");
            campaignRepository.save(c);
            return Map.of("scheduled", true, "send_at", c.getSendAt());
        }
        int sent = sendNow(c);
        return Map.of("scheduled", false, "sent", sent);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> campaignRecipients(UUID id, int page, int pageSize) {
        requireEmailCampaign(id);
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100));
        Page<MarketingRecipient> p = recipientRepository.findByCampaignIdOrderByCreatedAtDesc(id, pageable);
        List<Map<String, Object>> list = p.getContent().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("email", r.getEmail());
            m.put("username", r.getUsername());
            m.put("code", r.getCode());
            m.put("delivered", r.getDelivered());
            m.put("error", r.getError());
            m.put("sent_at", r.getSentAt());
            return m;
        }).toList();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("list", list);
        res.put("total", p.getTotalElements());
        res.put("page", page);
        res.put("page_size", pageSize);
        res.put("total_pages", p.getTotalPages());
        res.put("delivered", recipientRepository.countByCampaignIdAndDelivered(id, 1));
        res.put("failed", recipientRepository.countByCampaignIdAndDelivered(id, 0));
        return res;
    }

    /** 立即发送（也供定时任务调用）。返回收件人数。 */
    @Transactional
    public int sendNow(MarketingCampaign c) {
        List<Recipient> recipients = resolveRecipients(c);
        if (recipients.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "没有可发送的收件人，请检查受众设置");
        }
        MarketingCampaign coupon = resolveLinkedCoupon(c);
        Map<String, String> codeByEmail = null;
        if (coupon != null) {
            codeByEmail = allocateCoupons(coupon, recipients);
        }
        c.setStatus("SENT");
        c.setSentCount(recipients.size());
        c.setFailedCount(0);
        c.setSendAt(LocalDateTime.now());
        campaignRepository.save(c);
        // 快照收件人（用于"发送用户"弹窗与送达统计）
        List<UUID> recipientIds = new ArrayList<>(recipients.size());
        for (Recipient r : recipients) {
            MarketingRecipient mr = new MarketingRecipient();
            mr.setCampaignId(c.getId());
            mr.setUserId(r.userId());
            mr.setEmail(r.email());
            mr.setUsername(r.username());
            mr.setCode(codeByEmail == null ? null : codeByEmail.get(r.email()));
            recipientRepository.save(mr);
            recipientIds.add(mr.getId());
        }
        // 异步逐封发送（占位符按收件人替换）
        mailSender.sendAsync(c.getId(), coupon, recipients, codeByEmail, recipientIds);
        return recipients.size();
    }

    /** 定时任务：到点自动发送 SCHEDULED 邮件 */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void processScheduledCampaigns() {
        List<MarketingCampaign> due = campaignRepository.findByStatusAndSendAtLessThanEqual("SCHEDULED", LocalDateTime.now());
        for (MarketingCampaign c : due) {
            try {
                sendNow(c);
                log.info("Scheduled campaign {} sent", c.getId());
            } catch (Exception e) {
                log.error("Scheduled campaign {} send failed: {}", c.getId(), e.getMessage(), e);
            }
        }
    }

    /** 收件人（包内可见，供 MarketingMailSender 异步发送使用） */
    record Recipient(UUID userId, String email, String username) {
    }

    /** 解析受众为收件人列表（按邮箱去重） */
    private List<Recipient> resolveRecipients(MarketingCampaign c) {
        List<Recipient> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String audience = c.getAudienceType() == null ? "ALL_USERS" : c.getAudienceType();
        switch (audience) {
            case "ALL_USERS" -> {
                for (User u : userRepository.findAll()) {
                    if (u.getIsDeleted() == 0 && u.getEmail() != null && !u.getEmail().isBlank()) {
                        String email = u.getEmail().toLowerCase();
                        if (seen.add(email)) {
                            list.add(new Recipient(u.getId(), email, u.getUsername()));
                        }
                    }
                }
            }
            case "USER_IDS" -> {
                List<String> tokens = parseStringList(c.getTargetJson());
                for (String token : tokens) {
                    User u = null;
                    UUID uuid = parseUuid(token);
                    if (uuid != null) {
                        u = userRepository.findById(uuid).orElse(null);
                    }
                    if (u == null) {
                        u = userRepository.findByUsernameOrEmail(token, token).orElse(null);
                    }
                    if (u != null && u.getIsDeleted() == 0 && u.getEmail() != null && !u.getEmail().isBlank()) {
                        String email = u.getEmail().toLowerCase();
                        if (seen.add(email)) {
                            list.add(new Recipient(u.getId(), email, u.getUsername()));
                        }
                    }
                }
            }
            case "EMAILS" -> {
                List<String> emails = parseStringList(c.getTargetJson());
                for (String e : emails) {
                    String email = e.trim().toLowerCase();
                    if (!email.isBlank() && seen.add(email)) {
                        list.add(new Recipient(null, email, email.split("@")[0]));
                    }
                }
            }
            default -> {
            }
        }
        return list;
    }

    /** 关联优惠券：couponRefId 优先；兼容旧数据（EMAIL 记录内联优惠配置） */
    private MarketingCampaign resolveLinkedCoupon(MarketingCampaign c) {
        if (c.getCouponRefId() != null) {
            MarketingCampaign coupon = campaignRepository.findById(c.getCouponRefId()).orElse(null);
            if (coupon == null || coupon.getCouponType() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "关联的优惠券不存在或已删除");
            }
            return coupon;
        }
        return c.getCouponType() != null ? c : null;
    }

    /**
     * 优惠券分配：校验发行数量 ≥ 收件人数（不足报"分配不足"），
     * 并按收件人预创建 UserCoupon（唯一码模式：每个收件人一个自动生成的唯一核销码；
     * 单码模式：使用活动核销码，数量上限仍按发行数量控制）。
     */
    private Map<String, String> allocateCoupons(MarketingCampaign coupon, List<Recipient> recipients) {
        if (coupon.getIsCanceled() == 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "关联的优惠券已作废");
        }
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getCouponValidFrom() != null && now.isBefore(coupon.getCouponValidFrom())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "关联的优惠券还未到生效时间");
        }
        if (coupon.getCouponValidTo() != null && now.isAfter(coupon.getCouponValidTo())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "关联的优惠券已过有效期");
        }
        long issued = couponRepository.countByCampaignId(coupon.getId());
        long quantity = coupon.getCouponQuantity();
        if (quantity > 0 && issued + recipients.size() > quantity) {
            long shortage = issued + recipients.size() - quantity;
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "优惠券发行数量不足：还需 " + shortage + " 张，当前剩余 " + Math.max(0, quantity - issued)
                            + " 张，请先增加发行数量或减少收件人");
        }
        Map<String, String> codeByEmail = new HashMap<>();
        String singleCode = coupon.getCouponCode();
        for (Recipient r : recipients) {
            if (codeByEmail.containsKey(r.email())) {
                continue;
            }
            String code = (singleCode != null && !singleCode.isBlank()) ? singleCode : generateUniqueCode();
            codeByEmail.put(r.email(), code);
            createUserCoupon(coupon, r, code);
        }
        return codeByEmail;
    }

    private void createUserCoupon(MarketingCampaign coupon, Recipient r, String code) {
        UserCoupon uc = new UserCoupon();
        uc.setCampaignId(coupon.getId());
        uc.setCode(code);
        uc.setUserId(r.userId());
        uc.setEmail(r.email());
        uc.setType(coupon.getCouponType());
        uc.setValue(coupon.getCouponValue());
        uc.setStatus("CLAIMED");
        uc.setClaimedAt(LocalDateTime.now());
        uc.setValidFrom(coupon.getCouponValidFrom());
        uc.setValidTo(coupon.getCouponValidTo());
        uc.setScope(coupon.getCouponScope() == null ? "ALL" : coupon.getCouponScope());
        uc.setProductIds(coupon.getCouponProductIds());
        couponRepository.save(uc);
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 生成全局唯一的核销码（NK-8位） */
    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 50; attempt++) {
            StringBuilder sb = new StringBuilder("NK-");
            for (int i = 0; i < 8; i++) {
                sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            }
            String code = sb.toString();
            if (findByCouponCode(code) == null && couponRepository.findFirstByCodeOrderByCreatedAtDesc(code).isEmpty()) {
                return code;
            }
        }
        return "NK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    // ═══════════ 前台：优惠券领取 / 我的优惠券 / 核销校验 ═══════════

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
        if (campaign.getIsCanceled() == 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "优惠券已作废");
        }
        LocalDateTime now = LocalDateTime.now();
        if (campaign.getCouponValidFrom() != null && now.isBefore(campaign.getCouponValidFrom())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "优惠券还未到生效时间");
        }
        if (campaign.getCouponValidTo() != null && now.isAfter(campaign.getCouponValidTo())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "优惠券已过有效期");
        }
        // 幂等：该用户/邮箱已领取（含邮件预分配）→ 直接返回成功
        Optional<UserCoupon> existing = findClaimed(c, userId, email);
        if (existing.isPresent()) {
            return toClaimResult(existing.get(), campaign);
        }
        // 数量限制
        long claimed = couponRepository.countByCampaignId(campaign.getId());
        if (campaign.getCouponQuantity() > 0 && claimed >= campaign.getCouponQuantity()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "优惠券已被领完");
        }
        // 唯一码模式（非活动核销码）：该码已被其他用户领取时拒绝
        if (!c.equals(campaign.getCouponCode()) && couponRepository.findFirstByCodeOrderByCreatedAtDesc(c).isPresent()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该核销码已被其他用户领取");
        }
        if (userId == null && (email == null || email.isBlank())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请先登录或提供邮箱后领取");
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
        uc.setScope(campaign.getCouponScope() == null ? "ALL" : campaign.getCouponScope());
        uc.setProductIds(campaign.getCouponProductIds());
        couponRepository.save(uc);
        return toClaimResult(uc, campaign);
    }

    private Optional<UserCoupon> findClaimed(String code, UUID userId, String email) {
        if (userId != null) {
            Optional<UserCoupon> byUser = couponRepository.findFirstByCodeAndUserIdAndStatusOrderByCreatedAtDesc(code, userId, "CLAIMED");
            if (byUser.isPresent()) return byUser;
            byUser = couponRepository.findFirstByCodeAndUserIdAndStatusOrderByCreatedAtDesc(code, userId, "USED");
            if (byUser.isPresent()) return byUser;
        }
        if (email != null && !email.isBlank()) {
            String e = email.trim().toLowerCase();
            Optional<UserCoupon> byEmail = couponRepository.findFirstByCodeAndEmailAndStatusOrderByCreatedAtDesc(code, e, "CLAIMED");
            if (byEmail.isPresent()) return byEmail;
            byEmail = couponRepository.findFirstByCodeAndEmailAndStatusOrderByCreatedAtDesc(code, e, "USED");
            if (byEmail.isPresent()) return byEmail;
        }
        return Optional.empty();
    }

    private Map<String, Object> toClaimResult(UserCoupon uc, MarketingCampaign campaign) {
        return Map.of(
                "code", uc.getCode(),
                "coupon_type", uc.getType(),
                "coupon_value", uc.getValue() == null ? BigDecimal.ZERO : uc.getValue(),
                "coupon_title", campaign.getTitle(),
                "valid_from", uc.getValidFrom(),
                "valid_to", uc.getValidTo());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<?> myCoupons(UUID userId, String status, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100),
                Sort.by(Sort.Direction.DESC, "claimedAt"));
        Page<UserCoupon> p;
        if ("USED".equals(status)) {
            p = couponRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, "USED", pageable);
        } else if ("CLAIMED".equals(status) || "EXPIRED".equals(status)) {
            p = couponRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, "CLAIMED", pageable);
        } else {
            p = couponRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        }
        List<Map<String, Object>> list = p.getContent().stream().map(this::toMyCouponMap).toList();
        return PageResult.of(p, list);
    }

    private Map<String, Object> toMyCouponMap(UserCoupon uc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", uc.getId());
        m.put("code", uc.getCode());
        m.put("type", uc.getType());
        m.put("value", uc.getValue() == null ? BigDecimal.ZERO : uc.getValue());
        m.put("valid_from", uc.getValidFrom());
        m.put("valid_to", uc.getValidTo());
        m.put("claimed_at", uc.getClaimedAt());
        m.put("used_at", uc.getUsedAt());
        m.put("order_id", uc.getOrderId());
        m.put("scope", uc.getScope() == null ? "ALL" : uc.getScope());
        m.put("product_ids", parseUuidList(uc.getProductIds()));
        String displayStatus = "CLAIMED";
        if ("USED".equals(uc.getStatus())) {
            displayStatus = "USED";
        } else if (uc.getValidTo() != null && LocalDateTime.now().isAfter(uc.getValidTo())) {
            displayStatus = "EXPIRED";
        }
        m.put("status", displayStatus);
        MarketingCampaign campaign = uc.getCampaignId() == null ? null
                : campaignRepository.findById(uc.getCampaignId()).orElse(null);
        m.put("campaign_title", campaign == null ? "优惠券" : campaign.getTitle());
        m.put("coupon_min_amount", campaign == null ? BigDecimal.ZERO : (campaign.getCouponMinAmount() == null ? BigDecimal.ZERO : campaign.getCouponMinAmount()));
        return m;
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

    private CouponCheck checkCoupon(String code, UUID userId, String email, BigDecimal amount, List<UUID> productIds) {
        if (code == null || code.isBlank()) {
            return CouponCheck.fail("请输入优惠券核销码");
        }
        String c = code.trim().toUpperCase();
        MarketingCampaign campaign = findByCouponCode(c);
        if (campaign == null || campaign.getCouponType() == null) {
            return CouponCheck.fail("优惠券不存在或已下架");
        }
        if (campaign.getIsCanceled() == 1) {
            return CouponCheck.fail("优惠券已作废");
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

    /** 查找核销码所属活动：先匹配活动核销码（单码），再匹配自动生成的唯一码（UserCoupon 反查） */
    private MarketingCampaign findByCouponCode(String code) {
        for (MarketingCampaign x : campaignRepository.findAll()) {
            if (code.equals(x.getCouponCode())) {
                return x;
            }
        }
        return couponRepository.findFirstByCodeOrderByCreatedAtDesc(code)
                .map(uc -> campaignRepository.findById(uc.getCampaignId()).orElse(null))
                .orElse(null);
    }

    // ═══════════ 辅助 ═══════════

    private Map<String, Object> toCouponMap(MarketingCampaign c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("title", c.getTitle());
        map.put("coupon_type", c.getCouponType());
        map.put("coupon_value", c.getCouponValue());
        map.put("coupon_min_amount", c.getCouponMinAmount());
        map.put("coupon_code", c.getCouponCode());
        map.put("coupon_quantity", c.getCouponQuantity());
        map.put("coupon_claimed", couponRepository.countByCampaignId(c.getId()));
        map.put("coupon_used", couponRepository.countByCampaignIdAndStatus(c.getId(), "USED"));
        map.put("coupon_valid_from", c.getCouponValidFrom());
        map.put("coupon_valid_to", c.getCouponValidTo());
        map.put("coupon_scope", c.getCouponScope());
        map.put("coupon_product_ids", c.getCouponProductIds());
        map.put("is_canceled", c.getIsCanceled());
        map.put("created_at", c.getCreatedAt());
        map.put("updated_at", c.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toEmailMap(MarketingCampaign c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("title", c.getTitle());
        map.put("subject", c.getSubject());
        map.put("content", c.getContent());
        map.put("audience_type", c.getAudienceType());
        map.put("target_json", c.getTargetJson());
        map.put("status", c.getStatus());
        map.put("sent_count", c.getSentCount());
        map.put("failed_count", c.getFailedCount());
        map.put("send_at", c.getSendAt());
        map.put("is_canceled", c.getIsCanceled());
        map.put("coupon_ref_id", c.getCouponRefId());
        if (c.getCouponRefId() != null) {
            map.put("coupon_title", campaignRepository.findById(c.getCouponRefId())
                    .map(MarketingCampaign::getTitle).orElse(null));
        }
        map.put("recipient_count", recipientRepository.countByCampaignId(c.getId()));
        map.put("created_at", c.getCreatedAt());
        map.put("updated_at", c.getUpdatedAt());
        return map;
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

    private LocalDateTime parseDateTime(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        if (s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "时间格式无效：" + s);
        }
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

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
