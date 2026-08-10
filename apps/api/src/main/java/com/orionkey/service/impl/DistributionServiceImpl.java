package com.orionkey.service.impl;

import com.orionkey.constant.CommissionStatus;
import com.orionkey.constant.DistributorStatus;
import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.WithdrawalStatus;
import com.orionkey.entity.*;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.*;
import com.orionkey.service.DistributionService;
import com.orionkey.service.NotificationService;
import com.orionkey.service.UserMessageService;
import com.orionkey.service.WxpayService;
import com.orionkey.service.WxpayService.WxpayConfig;
import com.orionkey.service.WxpayService.WxpayTransferResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DistributionServiceImpl implements DistributionService {

    private final DistributorRepository distributorRepository;
    private final DistributionRuleRepository ruleRepository;
    private final CommissionTierRepository tierRepository;
    private final ProductCommissionRepository productCommissionRepository;
    private final DistributorProductRateRepository distributorProductRateRepository;
    private final CustomerBindingRepository customerBindingRepository;
    private final PromotionLinkRepository promotionLinkRepository;
    private final CommissionRecordRepository commissionRecordRepository;
    private final WithdrawalRecordRepository withdrawalRecordRepository;
    private final DistributionClickRepository clickRepository;
    private final UserMessageService userMessageService;
    private final NotificationService notificationService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final PaymentChannelRepository paymentChannelRepository;
    private final WxpayService wxpayService;
    private final PaymentServiceImpl paymentServiceImpl;

    @Value("${app.base-url:https://noepay.cn}")
    private String baseUrl;

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ════════════════════════════════════════════════════════════════
    //  ── 分销员 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public Map<String, Object> applyDistributor(UUID userId, String inviteCode) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户未登录");
        }
        if (distributorRepository.findByUserId(userId).isPresent()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "您已是分销员");
        }
        DistributionRule rule = getOrCreateRule();
        if (!rule.isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分销功能未开启");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));

        Distributor d = new Distributor();
        d.setUserId(userId);
        d.setDistributorCode(generateDistributorCode());
        d.setInviteCode(generateUniqueInviteCode());
        d.setSubRate(rule.getDefaultSubRate());

        // 上级分销员
        if (inviteCode != null && !inviteCode.isBlank()) {
            distributorRepository.findByInviteCode(inviteCode.trim()).ifPresent(parent -> {
                d.setParentId(parent.getId());
            });
        }

        boolean autoApprove = rule.isAutoApprove();
        d.setStatus(autoApprove ? DistributorStatus.APPROVED : DistributorStatus.PENDING);
        if (autoApprove) {
            d.setApprovedAt(LocalDateTime.now());
        }
        distributorRepository.save(d);
        log.info("Distributor applied: userId={}, code={}, status={}", userId, d.getDistributorCode(), d.getStatus());

        // 发送用户消息
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("distributor_code", d.getDistributorCode());
            vars.put("status", d.getStatus().name());
            userMessageService.sendUserMessage(userId, user.getEmail(),
                    autoApprove ? "DIST_APPROVED" : "DIST_APPLIED", vars);
        } catch (Exception e) {
            log.warn("Failed to send distributor apply message: {}", e.getMessage());
        }

        // 自动审核通过时通知管理员
        if (autoApprove) {
            try {
                Map<String, Object> adminVars = new LinkedHashMap<>();
                adminVars.put("distributor_code", d.getDistributorCode());
                adminVars.put("user_email", user.getEmail());
                notificationService.sendTemplate("DIST_APPLIED", adminVars);
            } catch (Exception e) {
                log.warn("Failed to notify admin for distributor approval: {}", e.getMessage());
            }
        }

        return distributorToMap(d, user);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getDistributorProfile(UUID userId) {
        Distributor d = requireDistributorByUserId(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", d.getStatus().name());
        m.put("distributor_code", d.getDistributorCode());
        m.put("available_balance", d.getAvailableBalance());
        m.put("total_commission", d.getTotalCommission());
        m.put("withdrawn_amount", d.getWithdrawnAmount());
        m.put("frozen_balance", d.getFrozenBalance());
        m.put("invite_code", d.getInviteCode());
        m.put("wechat_bound", d.getWechatOpenid() != null && !d.getWechatOpenid().isBlank());
        m.put("parent_id", d.getParentId());
        m.put("sub_rate", d.getSubRate());
        m.put("custom_rate", d.getCustomRate());
        m.put("approved_at", d.getApprovedAt());
        return m;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getDistributorStats(UUID userId) {
        Distributor d = requireDistributorByUserId(userId);
        UUID distId = d.getId();

        long promotionProductCount = promotionLinkRepository.findByDistributorId(distId, PageRequest.of(0, 1))
                .getTotalElements();
        long totalClicks = clickRepository.countByDistributorId(distId);

        BigDecimal totalSales = BigDecimal.ZERO;
        BigDecimal pendingCommission = nullSafe(commissionRecordRepository.sumByDistributorAndStatus(distId, CommissionStatus.PENDING));
        long subCount = distributorRepository.findByParentId(distId).size();
        long customerCount = customerBindingRepository.countByDistributorId(distId);

        // 汇总推广链接的销售额
        Page<PromotionLink> links = promotionLinkRepository.findByDistributorId(distId, PageRequest.of(0, Integer.MAX_VALUE));
        for (PromotionLink pl : links.getContent()) {
            if (pl.getTotalSales() != null) {
                totalSales = totalSales.add(pl.getTotalSales());
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("promotion_product_count", promotionProductCount);
        m.put("total_clicks", totalClicks);
        m.put("total_sales", totalSales.setScale(2, RoundingMode.HALF_UP));
        m.put("pending_commission", pendingCommission.setScale(2, RoundingMode.HALF_UP));
        m.put("available_balance", d.getAvailableBalance());
        m.put("withdrawn_amount", d.getWithdrawnAmount());
        m.put("subordinate_count", subCount);
        m.put("customer_count", customerCount);
        return m;
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 管理后台：分销员管理 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminListDistributors(String status, String keyword, int page, int pageSize) {
        Pageable pageable = toPageable(page, pageSize);
        DistributorStatus statusEnum = parseStatus(status);
        Page<Distributor> dp = distributorRepository.findAdminList(statusEnum, keyword, pageable);

        // 批量查用户信息
        Set<UUID> userIds = dp.getContent().stream().map(Distributor::getUserId).collect(Collectors.toSet());
        Map<UUID, User> userMap = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, u -> u));

        List<Map<String, Object>> items = dp.getContent().stream().map(d -> {
            User u = userMap.get(d.getUserId());
            Map<String, Object> m = distributorToMap(d, u);
            m.put("customer_count", customerBindingRepository.countByDistributorId(d.getId()));
            m.put("subordinate_count", distributorRepository.findByParentId(d.getId()).size());
            return m;
        }).toList();

        return pageResult(items, dp.getTotalElements(), page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminGetDistributor(UUID id) {
        Distributor d = distributorRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));
        User user = userRepository.findById(d.getUserId()).orElse(null);
        Map<String, Object> m = distributorToMap(d, user);
        m.put("customer_count", customerBindingRepository.countByDistributorId(d.getId()));
        m.put("subordinate_count", distributorRepository.findByParentId(d.getId()).size());
        m.put("pending_commission", nullSafe(commissionRecordRepository.sumByDistributorAndStatus(d.getId(), CommissionStatus.PENDING)));
        m.put("settled_commission", nullSafe(commissionRecordRepository.sumByDistributorAndStatus(d.getId(), CommissionStatus.SETTLED)));
        return m;
    }

    @Override
    @Transactional
    public void adminUpdateDistributorStatus(UUID id, String status, String reason) {
        Distributor d = distributorRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));
        DistributorStatus newStatus = parseStatus(status);
        if (newStatus == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的状态");
        }
        d.setStatus(newStatus);
        switch (newStatus) {
            case APPROVED -> d.setApprovedAt(LocalDateTime.now());
            case DISABLED -> d.setDisabledAt(LocalDateTime.now());
            default -> {}
        }
        distributorRepository.save(d);
        log.info("Distributor {} status updated to {}", id, newStatus);

        User user = userRepository.findById(d.getUserId()).orElse(null);
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("distributor_code", d.getDistributorCode());
            vars.put("status", newStatus.name());
            if (reason != null) vars.put("reason", reason);
            userMessageService.sendUserMessage(d.getUserId(), user != null ? user.getEmail() : null,
                    "DIST_STATUS_" + newStatus.name(), vars);
        } catch (Exception e) {
            log.warn("Failed to send distributor status message: {}", e.getMessage());
        }

        try {
            Map<String, Object> adminVars = new LinkedHashMap<>();
            adminVars.put("distributor_code", d.getDistributorCode());
            adminVars.put("status", newStatus.name());
            notificationService.sendTemplate("DIST_STATUS_CHANGED", adminVars);
        } catch (Exception e) {
            log.warn("Failed to notify admin for distributor status change: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void adminUpdateDistributorRate(UUID id, BigDecimal customRate, BigDecimal subRate) {
        Distributor d = distributorRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));
        if (customRate != null) {
            d.setCustomRate(customRate.setScale(4, RoundingMode.HALF_UP));
        }
        if (subRate != null) {
            d.setSubRate(subRate.setScale(4, RoundingMode.HALF_UP));
        }
        distributorRepository.save(d);
        log.info("Distributor {} rate updated: customRate={}, subRate={}", id, customRate, subRate);
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 管理后台：规则配置 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getRules() {
        return ruleToMap(getOrCreateRule());
    }

    @Override
    @Transactional
    public void updateRules(Map<String, Object> request) {
        DistributionRule rule = getOrCreateRule();
        if (request.containsKey("default_rate")) {
            rule.setDefaultRate(toBigDecimal(request.get("default_rate"), rule.getDefaultRate()).setScale(4, RoundingMode.HALF_UP));
        }
        if (request.containsKey("enabled")) {
            rule.setEnabled(toBool(request.get("enabled"), rule.isEnabled()));
        }
        if (request.containsKey("auto_approve")) {
            rule.setAutoApprove(toBool(request.get("auto_approve"), rule.isAutoApprove()));
        }
        if (request.containsKey("min_withdraw_amount")) {
            rule.setMinWithdrawAmount(toBigDecimal(request.get("min_withdraw_amount"), rule.getMinWithdrawAmount()).setScale(2, RoundingMode.HALF_UP));
        }
        if (request.containsKey("settle_delay_days")) {
            rule.setSettleDelayDays(toInt(request.get("settle_delay_days"), rule.getSettleDelayDays()));
        }
        if (request.containsKey("withdraw_fee_rate")) {
            rule.setWithdrawFeeRate(toBigDecimal(request.get("withdraw_fee_rate"), rule.getWithdrawFeeRate()).setScale(4, RoundingMode.HALF_UP));
        }
        if (request.containsKey("binding_protection_days")) {
            rule.setBindingProtectionDays(toInt(request.get("binding_protection_days"), rule.getBindingProtectionDays()));
        }
        if (request.containsKey("tier_enabled")) {
            rule.setTierEnabled(toBool(request.get("tier_enabled"), rule.isTierEnabled()));
        }
        if (request.containsKey("sub_distribution_enabled")) {
            rule.setSubDistributionEnabled(toBool(request.get("sub_distribution_enabled"), rule.isSubDistributionEnabled()));
        }
        if (request.containsKey("default_sub_rate")) {
            rule.setDefaultSubRate(toBigDecimal(request.get("default_sub_rate"), rule.getDefaultSubRate()).setScale(4, RoundingMode.HALF_UP));
        }
        ruleRepository.save(rule);
        log.info("Distribution rules updated");
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 管理后台：商品佣金 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminListProductCommissions(int page, int pageSize) {
        Pageable pageable = toPageable(page, pageSize);
        Page<Product> pp = productRepository.findAdminProducts(null, null, pageable);
        List<Map<String, Object>> items = pp.getContent().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("product_id", p.getId());
            m.put("product_title", p.getTitle());
            m.put("base_price", p.getBasePrice());
            m.put("enabled", p.isEnabled());
            ProductCommission pc = productCommissionRepository.findByProductId(p.getId()).orElse(null);
            m.put("custom_rate", pc != null ? pc.getCustomRate() : null);
            m.put("excluded", pc != null && pc.isExcluded());
            return m;
        }).toList();
        return pageResult(items, pp.getTotalElements(), page, pageSize);
    }

    @Override
    @Transactional
    public void adminUpdateProductCommission(UUID productId, BigDecimal customRate, boolean excluded) {
        productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "商品不存在"));
        ProductCommission pc = productCommissionRepository.findByProductId(productId).orElse(null);
        if (pc == null) {
            pc = new ProductCommission();
            pc.setProductId(productId);
        }
        pc.setCustomRate(customRate != null ? customRate.setScale(4, RoundingMode.HALF_UP) : null);
        pc.setExcluded(excluded);
        productCommissionRepository.save(pc);
        log.info("Product commission updated: productId={}, rate={}, excluded={}", productId, customRate, excluded);
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 管理后台：佣金记录 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminListCommissions(UUID distributorId, String status, int page, int pageSize) {
        Pageable pageable = toPageable(page, pageSize);
        CommissionStatus statusEnum = parseCommissionStatus(status);
        Page<CommissionRecord> cp = commissionRecordRepository.findAdminList(distributorId, statusEnum, pageable);

        Set<UUID> distIds = cp.getContent().stream().map(CommissionRecord::getDistributorId).collect(Collectors.toSet());
        Map<UUID, Distributor> distMap = distIds.isEmpty() ? Map.of()
                : distributorRepository.findAllById(distIds).stream().collect(Collectors.toMap(Distributor::getId, d -> d));

        List<Map<String, Object>> items = cp.getContent().stream().map(cr -> {
            Map<String, Object> m = commissionToMap(cr);
            Distributor d = distMap.get(cr.getDistributorId());
            m.put("distributor_code", d != null ? d.getDistributorCode() : null);
            return m;
        }).toList();
        return pageResult(items, cp.getTotalElements(), page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminCommissionStats() {
        List<Distributor> all = distributorRepository.findAll();
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal pendingTotal = BigDecimal.ZERO;
        BigDecimal settledTotal = BigDecimal.ZERO;
        BigDecimal cancelledTotal = BigDecimal.ZERO;
        BigDecimal todayTotal = BigDecimal.ZERO;
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        for (Distributor d : all) {
            totalCommission = totalCommission.add(nullSafe(d.getTotalCommission()));
            pendingTotal = pendingTotal.add(nullSafe(commissionRecordRepository.sumByDistributorAndStatus(d.getId(), CommissionStatus.PENDING)));
            settledTotal = settledTotal.add(nullSafe(commissionRecordRepository.sumByDistributorAndStatus(d.getId(), CommissionStatus.SETTLED)));
            cancelledTotal = cancelledTotal.add(nullSafe(commissionRecordRepository.sumByDistributorAndStatus(d.getId(), CommissionStatus.CANCELLED)));
        }

        // 今日佣金
        for (CommissionRecord cr : commissionRecordRepository.findAll()) {
            if (cr.getCreatedAt() != null && !cr.getCreatedAt().isBefore(todayStart) && cr.getStatus() != CommissionStatus.CANCELLED) {
                todayTotal = todayTotal.add(nullSafe(cr.getCommissionAmount()));
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_commission", totalCommission.setScale(2, RoundingMode.HALF_UP));
        m.put("pending_commission", pendingTotal.setScale(2, RoundingMode.HALF_UP));
        m.put("settled_commission", settledTotal.setScale(2, RoundingMode.HALF_UP));
        m.put("cancelled_commission", cancelledTotal.setScale(2, RoundingMode.HALF_UP));
        m.put("today_commission", todayTotal.setScale(2, RoundingMode.HALF_UP));
        return m;
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 管理后台：提现管理 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminListWithdrawals(String status, int page, int pageSize) {
        Pageable pageable = toPageable(page, pageSize);
        WithdrawalStatus statusEnum = parseWithdrawalStatus(status);
        Page<WithdrawalRecord> wp = withdrawalRecordRepository.findAdminList(statusEnum, pageable);

        Set<UUID> distIds = wp.getContent().stream().map(WithdrawalRecord::getDistributorId).collect(Collectors.toSet());
        Map<UUID, Distributor> distMap = distIds.isEmpty() ? Map.of()
                : distributorRepository.findAllById(distIds).stream().collect(Collectors.toMap(Distributor::getId, d -> d));

        List<Map<String, Object>> items = wp.getContent().stream().map(wr -> {
            Map<String, Object> m = withdrawalToMap(wr);
            Distributor d = distMap.get(wr.getDistributorId());
            m.put("distributor_code", d != null ? d.getDistributorCode() : null);
            return m;
        }).toList();
        return pageResult(items, wp.getTotalElements(), page, pageSize);
    }

    @Override
    @Transactional
    public void adminApproveWithdrawal(UUID id) {
        WithdrawalRecord wr = withdrawalRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "提现记录不存在"));
        if (wr.getStatus() != WithdrawalStatus.PENDING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅待审核的提现可审批");
        }
        Distributor d = distributorRepository.findById(wr.getDistributorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));

        wr.setStatus(WithdrawalStatus.APPROVED);
        wr.setApprovedAt(LocalDateTime.now());

        BigDecimal amount = wr.getActualAmount() != null ? wr.getActualAmount() : wr.getAmount();

        // 尝试发起微信商家转账到零钱（需分销员已绑定 openid + 已配置 native_wxpay 渠道）
        boolean transferred = tryWxpayTransfer(wr, d, amount);

        if (!transferred) {
            // 手动打款模式：审批通过后保持冻结，等管理员线下支付后点击"手动结算"才扣余额
            log.info("Withdrawal {} approved (manual), distributor {} frozen = {}",
                    id, d.getId(), d.getFrozenBalance());
        } else {
            // 微信转账已发起：冻结金额保持不变，等微信回调确认转账成功后再转为已提现
            log.info("Withdrawal {} approved (wxpay transfer initiated), distributor {} frozen = {}",
                    id, d.getId(), d.getFrozenBalance());
        }

        withdrawalRecordRepository.save(wr);

        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("amount", wr.getAmount());
            vars.put("actual_amount", wr.getActualAmount());
            userMessageService.sendUserMessage(d.getUserId(), null, "WITHDRAWAL_APPROVED", vars);
        } catch (Exception e) {
            log.warn("Failed to send withdrawal approved message: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void adminManualSettle(UUID id, BigDecimal actualAmount) {
        WithdrawalRecord wr = withdrawalRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "提现记录不存在"));
        // 仅 APPROVED（审批通过待打款）或 PROCESSING（微信转账中但需手动兜底）可手动结算
        if (wr.getStatus() != WithdrawalStatus.APPROVED && wr.getStatus() != WithdrawalStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅已通过或转账中的提现可手动结算");
        }
        Distributor d = distributorRepository.findById(wr.getDistributorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));

        // 实际结算金额：优先用传入的，其次用记录的实到金额，最后用提现金额
        BigDecimal settleAmount = (actualAmount != null && actualAmount.compareTo(BigDecimal.ZERO) > 0)
                ? actualAmount
                : (wr.getActualAmount() != null ? wr.getActualAmount() : wr.getAmount());

        // 从冻结余额扣减，转入已提现
        d.setFrozenBalance(d.getFrozenBalance().subtract(wr.getAmount()));
        d.setWithdrawnAmount(d.getWithdrawnAmount().add(settleAmount));
        distributorRepository.save(d);

        // 更新提现记录
        wr.setActualAmount(settleAmount);
        wr.setStatus(WithdrawalStatus.SUCCESS);
        wr.setTransferredAt(LocalDateTime.now());
        wr.setCompletedAt(LocalDateTime.now());
        withdrawalRecordRepository.save(wr);

        log.info("Withdrawal {} manually settled, distributor {} frozen -= {}, withdrawn += {}",
                id, d.getId(), wr.getAmount(), settleAmount);

        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("amount", settleAmount);
            userMessageService.sendUserMessage(d.getUserId(), null, "WITHDRAWAL_SUCCESS", vars);
        } catch (Exception e) {
            log.warn("Failed to send withdrawal success message: {}", e.getMessage());
        }
    }

    /**
     * 尝试发起微信商家转账到零钱。
     * 条件：分销员已绑定 openid + 存在已启用的 native_wxpay 渠道。
     * 成功：状态改为 PROCESSING，保存 outBillNo/transferBillNo/packageInfo，返回 true。
     * 失败：保持 APPROVED 状态，记录失败原因，返回 false（走手动打款模式）。
     */
    private boolean tryWxpayTransfer(WithdrawalRecord wr, Distributor d, BigDecimal amount) {
        if (d.getWechatOpenid() == null || d.getWechatOpenid().isBlank()) {
            log.info("Distributor {} has no wechat openid, skip wxpay transfer", d.getId());
            return false;
        }

        // 查找已启用的 native_wxpay 渠道
        List<PaymentChannel> wxpayChannels = paymentChannelRepository
                .findByProviderTypeAndIsDeleted("native_wxpay", 0);
        if (wxpayChannels.isEmpty()) {
            log.info("No native_wxpay channel configured, skip wxpay transfer");
            return false;
        }
        PaymentChannel channel = wxpayChannels.stream()
                .filter(PaymentChannel::isEnabled)
                .findFirst()
                .orElse(null);
        if (channel == null) {
            log.info("No enabled native_wxpay channel, skip wxpay transfer");
            return false;
        }

        try {
            WxpayConfig config = paymentServiceImpl.buildWxpayConfig(channel);
            String outBillNo = "WD" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
            String transferNotifyUrl = baseUrl.replaceAll("/+$", "") + "/api/payments/webhook/wxpay-transfer";

            WxpayTransferResult result = wxpayService.createTransfer(
                    config, outBillNo, d.getWechatOpenid(), amount, "佣金提现", transferNotifyUrl);

            wr.setOutBillNo(outBillNo);
            wr.setTransferBillNo(result.transferBillNo());
            wr.setPackageInfo(result.packageInfo());
            wr.setStatus(WithdrawalStatus.PROCESSING);
            wr.setTransferredAt(LocalDateTime.now());
            log.info("Wxpay transfer initiated: withdrawal={}, outBillNo={}, state={}",
                    wr.getId(), outBillNo, result.state());
            return true;
        } catch (Exception e) {
            log.warn("Wxpay transfer failed for withdrawal {}, fallback to manual: {}", wr.getId(), e.getMessage());
            wr.setFailReason("微信转账失败：" + e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional
    public void adminRejectWithdrawal(UUID id, String reason) {
        WithdrawalRecord wr = withdrawalRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "提现记录不存在"));
        if (wr.getStatus() != WithdrawalStatus.PENDING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅待审核的提现可拒绝");
        }
        Distributor d = distributorRepository.findById(wr.getDistributorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));

        wr.setStatus(WithdrawalStatus.REJECTED);
        wr.setFailReason(reason);
        withdrawalRecordRepository.save(wr);

        // 退回冻结金额到可用余额
        BigDecimal amount = wr.getAmount();
        d.setFrozenBalance(d.getFrozenBalance().subtract(amount));
        d.setAvailableBalance(d.getAvailableBalance().add(amount));
        distributorRepository.save(d);
        log.info("Withdrawal {} rejected, distributor {} available += {}", id, d.getId(), amount);

        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("amount", amount);
            vars.put("reason", reason);
            userMessageService.sendUserMessage(d.getUserId(), null, "WITHDRAWAL_REJECTED", vars);
        } catch (Exception e) {
            log.warn("Failed to send withdrawal rejected message: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 管理后台：统计 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminGetOverviewStats() {
        List<Distributor> all = distributorRepository.findAll();
        long totalDistributors = all.size();
        long pendingCount = distributorRepository.countByStatus(DistributorStatus.PENDING);

        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal availableBalance = BigDecimal.ZERO;
        BigDecimal frozenBalance = BigDecimal.ZERO;
        BigDecimal withdrawnAmount = BigDecimal.ZERO;
        BigDecimal pendingCommission = BigDecimal.ZERO;
        long todayNewCount = 0;
        BigDecimal todayCommission = BigDecimal.ZERO;
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        for (Distributor d : all) {
            totalCommission = totalCommission.add(nullSafe(d.getTotalCommission()));
            availableBalance = availableBalance.add(nullSafe(d.getAvailableBalance()));
            frozenBalance = frozenBalance.add(nullSafe(d.getFrozenBalance()));
            withdrawnAmount = withdrawnAmount.add(nullSafe(d.getWithdrawnAmount()));
            pendingCommission = pendingCommission.add(nullSafe(commissionRecordRepository.sumByDistributorAndStatus(d.getId(), CommissionStatus.PENDING)));
            if (d.getCreatedAt() != null && !d.getCreatedAt().isBefore(todayStart)) {
                todayNewCount++;
            }
        }

        // 今日佣金总额
        for (CommissionRecord cr : commissionRecordRepository.findAll()) {
            if (cr.getCreatedAt() != null && !cr.getCreatedAt().isBefore(todayStart) && cr.getStatus() != CommissionStatus.CANCELLED) {
                todayCommission = todayCommission.add(nullSafe(cr.getCommissionAmount()));
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_distributors", totalDistributors);
        m.put("pending_distributors", pendingCount);
        m.put("total_commission", totalCommission.setScale(2, RoundingMode.HALF_UP));
        m.put("pending_commission", pendingCommission.setScale(2, RoundingMode.HALF_UP));
        m.put("available_balance", availableBalance.setScale(2, RoundingMode.HALF_UP));
        m.put("frozen_balance", frozenBalance.setScale(2, RoundingMode.HALF_UP));
        m.put("withdrawn_amount", withdrawnAmount.setScale(2, RoundingMode.HALF_UP));
        m.put("today_new_distributors", todayNewCount);
        m.put("today_commission", todayCommission.setScale(2, RoundingMode.HALF_UP));
        return m;
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 前台：推广商品 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listPromotionProducts(int page, int pageSize) {
        Pageable pageable = toPageable(page, pageSize);
        Page<Product> pp = productRepository.findPublicProducts(null, pageable);
        List<Map<String, Object>> items = pp.getContent().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("product_id", p.getId());
            m.put("product_title", p.getTitle());
            m.put("cover_url", p.getCoverUrl());
            m.put("base_price", p.getBasePrice());
            ProductCommission pc = productCommissionRepository.findByProductId(p.getId()).orElse(null);
            m.put("excluded", pc != null && pc.isExcluded());
            m.put("custom_rate", pc != null ? pc.getCustomRate() : null);
            return m;
        }).toList();
        return pageResult(items, pp.getTotalElements(), page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listMyPromotionProducts(UUID userId, int page, int pageSize) {
        Distributor d = requireDistributorByUserId(userId);
        Pageable pageable = toPageable(page, pageSize);
        Page<PromotionLink> lp = promotionLinkRepository.findByDistributorId(d.getId(), pageable);

        List<Map<String, Object>> items = lp.getContent().stream()
                .filter(pl -> pl.getProductId() != null)
                .map(pl -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("link_id", pl.getId());
                    m.put("link_code", pl.getLinkCode());
                    m.put("product_id", pl.getProductId());
                    m.put("click_count", pl.getClickCount());
                    m.put("paid_count", pl.getPaidCount());
                    m.put("total_sales", pl.getTotalSales());
                    m.put("total_commission", pl.getTotalCommission());
                    productRepository.findById(pl.getProductId()).ifPresent(p -> {
                        m.put("product_title", p.getTitle());
                        m.put("cover_url", p.getCoverUrl());
                        m.put("base_price", p.getBasePrice());
                    });
                    return m;
                }).toList();
        return pageResult(items, lp.getTotalElements(), page, pageSize);
    }

    @Override
    @Transactional
    public Map<String, Object> generatePromotionLink(UUID userId, UUID productId) {
        Distributor d = requireDistributorByUserId(userId);
        if (d.getStatus() != DistributorStatus.APPROVED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分销员未审核通过");
        }
        PromotionLink link = promotionLinkRepository.findByDistributorIdAndProductId(d.getId(), productId).orElse(null);
        if (link == null) {
            link = new PromotionLink();
            link.setDistributorId(d.getId());
            link.setProductId(productId);
            link.setLinkCode(generateUniqueLinkCode());
            link.setClickCount(0);
            link.setUniqueClickCount(0);
            link.setPaidCount(0);
            link.setTotalSales(BigDecimal.ZERO);
            link.setTotalCommission(BigDecimal.ZERO);
            promotionLinkRepository.save(link);
            log.info("Promotion link created: distributor={}, product={}, code={}", d.getId(), productId, link.getLinkCode());
        }
        return linkToMap(link);
    }

    @Override
    @Transactional
    public Map<String, Object> generateStoreLink(UUID userId) {
        Distributor d = requireDistributorByUserId(userId);
        if (d.getStatus() != DistributorStatus.APPROVED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分销员未审核通过");
        }
        PromotionLink link = promotionLinkRepository.findByDistributorIdAndProductId(d.getId(), null).orElse(null);
        if (link == null) {
            link = new PromotionLink();
            link.setDistributorId(d.getId());
            link.setProductId(null);
            link.setLinkCode(generateUniqueLinkCode());
            link.setClickCount(0);
            link.setUniqueClickCount(0);
            link.setPaidCount(0);
            link.setTotalSales(BigDecimal.ZERO);
            link.setTotalCommission(BigDecimal.ZERO);
            promotionLinkRepository.save(link);
            log.info("Store promotion link created: distributor={}, code={}", d.getId(), link.getLinkCode());
        }
        return linkToMap(link);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listMyLinks(UUID userId, int page, int pageSize) {
        Distributor d = requireDistributorByUserId(userId);
        Pageable pageable = toPageable(page, pageSize);
        Page<PromotionLink> lp = promotionLinkRepository.findByDistributorId(d.getId(), pageable);
        List<Map<String, Object>> items = lp.getContent().stream().map(this::linkToMap).toList();
        return pageResult(items, lp.getTotalElements(), page, pageSize);
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 前台：佣金明细 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listMyCommissions(UUID userId, String status, int page, int pageSize) {
        Distributor d = requireDistributorByUserId(userId);
        Pageable pageable = toPageable(page, pageSize);
        CommissionStatus statusEnum = parseCommissionStatus(status);
        Page<CommissionRecord> cp;
        if (statusEnum != null) {
            // 复用 admin 查询（distributorId + status）
            cp = commissionRecordRepository.findAdminList(d.getId(), statusEnum, pageable);
        } else {
            cp = commissionRecordRepository.findByDistributorIdOrderByCreatedAtDesc(d.getId(), pageable);
        }
        List<Map<String, Object>> items = cp.getContent().stream().map(this::commissionToMap).toList();
        return pageResult(items, cp.getTotalElements(), page, pageSize);
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 前台：提现 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public Map<String, Object> applyWithdrawal(UUID userId, BigDecimal amount) {
        Distributor d = requireDistributorByUserId(userId);
        if (d.getStatus() != DistributorStatus.APPROVED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分销员未审核通过");
        }
        // S7: 悲观行锁 — 防止并发提现超扣余额（余额检查→扣减必须原子化）
        Distributor locked = distributorRepository.findByIdWithLock(d.getId()).orElse(d);
        DistributionRule rule = getOrCreateRule();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提现金额必须大于 0");
        }
        if (amount.compareTo(rule.getMinWithdrawAmount()) < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提现金额不能低于最低提现金额 " + rule.getMinWithdrawAmount());
        }
        if (locked.getAvailableBalance().compareTo(amount) < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "可提现余额不足");
        }

        // 计算手续费
        BigDecimal fee = amount.multiply(nullSafe(rule.getWithdrawFeeRate())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal actualAmount = amount.subtract(fee).setScale(2, RoundingMode.HALF_UP);

        // 冻结金额
        locked.setAvailableBalance(locked.getAvailableBalance().subtract(amount));
        locked.setFrozenBalance(locked.getFrozenBalance().add(amount));
        distributorRepository.save(locked);

        // 创建提现记录
        WithdrawalRecord wr = new WithdrawalRecord();
        wr.setDistributorId(locked.getId());
        wr.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        wr.setFee(fee);
        wr.setActualAmount(actualAmount);
        wr.setStatus(WithdrawalStatus.PENDING);
        wr.setAppliedAt(LocalDateTime.now());
        withdrawalRecordRepository.save(wr);
        log.info("Withdrawal applied: distributor={}, amount={}, actual={}", locked.getId(), amount, actualAmount);

        // 发送通知
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("amount", amount);
            vars.put("actual_amount", actualAmount);
            vars.put("fee", fee);
            userMessageService.sendUserMessage(locked.getUserId(), null, "WITHDRAWAL_APPLIED", vars);
        } catch (Exception e) {
            log.warn("Failed to send withdrawal applied message: {}", e.getMessage());
        }
        try {
            Map<String, Object> adminVars = new LinkedHashMap<>();
            adminVars.put("distributor_code", locked.getDistributorCode());
            adminVars.put("amount", amount);
            notificationService.sendTemplate("WITHDRAWAL_PENDING", adminVars);
        } catch (Exception e) {
            log.warn("Failed to notify admin for withdrawal: {}", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", wr.getId());
        result.put("amount", wr.getAmount());
        result.put("fee", wr.getFee());
        result.put("actual_amount", wr.getActualAmount());
        result.put("status", wr.getStatus().name());
        result.put("available_balance", locked.getAvailableBalance());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listMyWithdrawals(UUID userId, int page, int pageSize) {
        Distributor d = requireDistributorByUserId(userId);
        Pageable pageable = toPageable(page, pageSize);
        Page<WithdrawalRecord> wp = withdrawalRecordRepository.findByDistributorIdOrderByCreatedAtDesc(d.getId(), pageable);
        List<Map<String, Object>> items = wp.getContent().stream().map(this::withdrawalToMap).toList();
        return pageResult(items, wp.getTotalElements(), page, pageSize);
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 前台：下级分销员 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listSubordinates(UUID userId, int page, int pageSize) {
        Distributor d = requireDistributorByUserId(userId);
        List<Distributor> subs = distributorRepository.findByParentId(d.getId());
        // 手动分页
        int from = Math.min((page - 1) * pageSize, subs.size());
        int to = Math.min(from + pageSize, subs.size());
        List<Distributor> slice = subs.subList(from, to);

        Set<UUID> userIds = slice.stream().map(Distributor::getUserId).collect(Collectors.toSet());
        Map<UUID, User> userMap = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, u -> u));

        List<Map<String, Object>> items = slice.stream().map(sub -> {
            User u = userMap.get(sub.getUserId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sub.getId());
            m.put("distributor_code", sub.getDistributorCode());
            m.put("username", u != null ? u.getUsername() : null);
            m.put("email", u != null ? u.getEmail() : null);
            m.put("status", sub.getStatus().name());
            m.put("total_commission", sub.getTotalCommission());
            m.put("created_at", sub.getCreatedAt());
            return m;
        }).toList();
        return pageResult(items, subs.size(), page, pageSize);
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 前台：客户管理 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listMyCustomers(UUID userId, int page, int pageSize) {
        Distributor d = requireDistributorByUserId(userId);
        long total = customerBindingRepository.countByDistributorId(d.getId());

        // 手动分页查询客户绑定（无分页 repository 方法，用 findAll + filter）
        List<CustomerBinding> all = customerBindingRepository.findAll().stream()
                .filter(cb -> d.getId().equals(cb.getDistributorId()))
                .sorted(Comparator.comparing(CustomerBinding::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int from = Math.min((page - 1) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        List<CustomerBinding> slice = all.subList(from, to);

        List<Map<String, Object>> items = slice.stream().map(cb -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", cb.getId());
            m.put("customer_email", cb.getCustomerEmail());
            m.put("customer_user_id", cb.getCustomerUserId());
            m.put("product_id", cb.getProductId());
            m.put("purchase_count", cb.getPurchaseCount());
            m.put("last_purchase_at", cb.getLastPurchaseAt());
            m.put("protection_expires_at", cb.getProtectionExpiresAt());
            m.put("created_at", cb.getCreatedAt());
            return m;
        }).toList();
        return pageResult(items, total, page, pageSize);
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 公开：推广链接解析 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public Map<String, Object> resolvePromotionLink(String linkCode, String ip, String userAgent) {
        if (linkCode == null || linkCode.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "推广链接编码不能为空");
        }
        PromotionLink link = promotionLinkRepository.findByLinkCode(linkCode.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "推广链接不存在"));

        // 记录点击
        try {
            DistributionClick click = new DistributionClick();
            click.setDistributorId(link.getDistributorId());
            click.setPromotionLinkId(link.getId());
            click.setProductId(link.getProductId());
            click.setIp(ip);
            click.setUserAgent(userAgent);
            clickRepository.save(click);

            // 更新点击数
            link.setClickCount(link.getClickCount() + 1);
            // 判断是否独立访客
            if (ip != null && !ip.isBlank()) {
                long existing = clickRepository.countByPromotionLinkIdAndIp(link.getId(), ip);
                if (existing <= 1) {
                    link.setUniqueClickCount(link.getUniqueClickCount() + 1);
                }
            }
            promotionLinkRepository.save(link);
        } catch (Exception e) {
            log.warn("Failed to record promotion click: {}", e.getMessage());
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("distributor_id", link.getDistributorId());
        m.put("product_id", link.getProductId());
        m.put("promotion_link_id", link.getId());
        return m;
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 佣金预估 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> commissionPreview(UUID userId, List<UUID> productIds) {
        DistributionRule rule = getOrCreateRule();
        if (productIds == null || productIds.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("items", List.of());
            empty.put("total_commission", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            return empty;
        }

        // 查找当前用户分销员身份
        Distributor d = (userId != null) ? distributorRepository.findByUserId(userId).orElse(null) : null;

        // 全局最高分销员自定义比例
        BigDecimal maxDistributorRate = distributorRepository.findMaxCustomRate();

        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalCommission = BigDecimal.ZERO;

        for (UUID pid : productIds) {
            Product p = productRepository.findById(pid).orElse(null);
            if (p == null) continue;

            ProductCommission pc = productCommissionRepository.findByProductId(pid).orElse(null);
            boolean excluded = pc != null && pc.isExcluded();

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("product_id", p.getId());
            m.put("product_title", p.getTitle());
            m.put("base_price", p.getBasePrice());
            m.put("is_excluded", excluded);

            if (excluded) {
                m.put("commission_rate", BigDecimal.ZERO);
                m.put("commission_amount", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                m.put("max_commission_rate", BigDecimal.ZERO);
                items.add(m);
                continue;
            }

            // 计算最高佣金比例 = MAX(全局默认, 商品自定义, 所有分销员自定义最大值)
            BigDecimal maxRate = rule.getDefaultRate();
            if (pc != null && pc.getCustomRate() != null) {
                maxRate = maxRate.max(pc.getCustomRate());
            }
            if (maxDistributorRate != null) {
                maxRate = maxRate.max(maxDistributorRate);
            }

            BigDecimal rate;
            if (d != null && d.getStatus() == DistributorStatus.APPROVED) {
                // 已登录的分销员，用实际比例
                rate = resolveCommissionRate(d, pid, rule);
            } else {
                // 未登录或非分销员，用最高比例
                rate = maxRate;
            }

            BigDecimal commissionAmount = p.getBasePrice().multiply(rate).setScale(2, RoundingMode.HALF_UP);
            totalCommission = totalCommission.add(commissionAmount);

            m.put("commission_rate", rate);
            m.put("commission_amount", commissionAmount);
            m.put("max_commission_rate", maxRate);
            items.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total_commission", totalCommission.setScale(2, RoundingMode.HALF_UP));
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 佣金计算（订单支付成功时调用） ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void onOrderPaid(UUID orderId) {
        try {
            // M7: 幂等 — 该订单已生成过佣金记录则跳过（回调/主动查单多路径防重复入账）
            if (!commissionRecordRepository.findByOrderId(orderId).isEmpty()) {
                log.info("Order {} already has commission records, skip commission", orderId);
                return;
            }

            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单不存在"));

            // 只有 actualAmount > 0 的已付款订单才产生佣金
            if (order.getActualAmount() == null || order.getActualAmount().compareTo(BigDecimal.ZERO) <= 0) {
                log.info("Order {} actualAmount <= 0, skip commission", orderId);
                return;
            }

            UUID distId = order.getReferralDistributorId();
            if (distId == null) {
                log.info("Order {} has no referralDistributorId, skip commission", orderId);
                return;
            }

            // S6: 客户保护期绑定 — 客户在保护期内归属原推广员，防止被其他推广员"抢单"
            if (order.getEmail() != null && !order.getEmail().isBlank()) {
                String email = order.getEmail().trim().toLowerCase();
                CustomerBinding activeBinding = customerBindingRepository.findActiveBindingByEmail(email).orElse(null);
                if (activeBinding != null && !activeBinding.getDistributorId().equals(distId)) {
                    log.info("Customer {} already bound to distributor {} (protection period), reassign from {}",
                            email, activeBinding.getDistributorId(), distId);
                    order.setReferralDistributorId(activeBinding.getDistributorId());
                    distId = activeBinding.getDistributorId();
                }
            }

            Distributor d = distributorRepository.findById(distId).orElse(null);
            if (d == null || d.getStatus() != DistributorStatus.APPROVED) {
                log.warn("Distributor {} not found or not approved for order {}", distId, orderId);
                return;
            }

            // 自购不返佣
            if (d.getUserId() != null && d.getUserId().equals(order.getUserId())) {
                log.info("Self-purchase order {}, skip commission", orderId);
                return;
            }

            DistributionRule rule = getOrCreateRule();

            // 查找或创建客户绑定
            CustomerBinding binding = null;
            if (order.getEmail() != null && !order.getEmail().isBlank()) {
                String email = order.getEmail().trim().toLowerCase();
                binding = customerBindingRepository.findByCustomerEmailAndDistributorId(email, distId).orElse(null);
                if (binding == null) {
                    binding = new CustomerBinding();
                    binding.setDistributorId(distId);
                    binding.setCustomerEmail(email);
                    binding.setCustomerUserId(order.getUserId());
                    binding.setProductId(null);
                    binding.setPromotionLinkId(order.getPromotionLinkId());
                    binding.setProtectionExpiresAt(LocalDateTime.now().plusDays(rule.getBindingProtectionDays()));
                    binding.setPurchaseCount(0);
                    customerBindingRepository.save(binding);
                }
            }

            // 阶梯佣金
            int tierOrder = (binding != null ? binding.getPurchaseCount() : 0) + 1;
            BigDecimal tierRate = BigDecimal.ONE;
            if (rule.isTierEnabled()) {
                tierRate = resolveTierRate(tierOrder);
            }

            // 遍历订单项计算佣金
            List<OrderItem> orderItems = orderItemRepository.findByOrderId(orderId);
            // M1: 佣金基数 = 实际付款金额 × 订单项小计占比（优惠券/积分抵扣后分摊到各商品）
            BigDecimal totalSubtotal = orderItems.stream()
                    .map(OrderItem::getSubtotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal orderCommissionTotal = BigDecimal.ZERO;
            BigDecimal fullCommissionTotal = BigDecimal.ZERO;

            for (OrderItem oi : orderItems) {
                // 检查商品是否被排除
                if (isProductExcluded(oi.getProductId())) {
                    log.info("Product {} is excluded, skip commission for order item", oi.getProductId());
                    continue;
                }

                BigDecimal rate = resolveCommissionRate(d, oi.getProductId(), rule);
                BigDecimal base = totalSubtotal.compareTo(BigDecimal.ZERO) > 0
                        ? order.getActualAmount().multiply(oi.getSubtotal()).divide(totalSubtotal, 2, RoundingMode.HALF_UP)
                        : oi.getSubtotal();
                BigDecimal commissionAmount = base.multiply(rate).multiply(tierRate).setScale(2, RoundingMode.HALF_UP);
                if (commissionAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                fullCommissionTotal = fullCommissionTotal.add(commissionAmount);

                // 二级分销上级抽成
                BigDecimal parentAmount = BigDecimal.ZERO;
                UUID parentId = null;
                BigDecimal parentSubRateVal = null;
                if (rule.isSubDistributionEnabled() && d.getParentId() != null) {
                    Distributor parent = distributorRepository.findById(d.getParentId()).orElse(null);
                    if (parent != null && parent.getStatus() == DistributorStatus.APPROVED) {
                        BigDecimal subRate = parent.getSubRate() != null ? parent.getSubRate() : rule.getDefaultSubRate();
                        parentAmount = commissionAmount.multiply(subRate).setScale(2, RoundingMode.HALF_UP);
                        parentId = parent.getId();
                        parentSubRateVal = subRate;
                    }
                }

                // S3: 下级实得 = 佣金 − 上级抽成；上级抽成单独记佣金记录（总支出 = 佣金，平台不多付）
                BigDecimal selfAmount = commissionAmount.subtract(parentAmount).max(BigDecimal.ZERO);

                // 创建佣金记录（下级）
                CommissionRecord cr = new CommissionRecord();
                cr.setDistributorId(distId);
                cr.setOrderId(orderId);
                cr.setOrderItemId(oi.getId());
                cr.setProductId(oi.getProductId());
                cr.setProductTitle(oi.getProductTitle());
                cr.setOrderAmount(base);
                cr.setCommissionRate(rate);
                cr.setCommissionAmount(selfAmount);
                cr.setTierOrder(tierOrder);
                cr.setStatus(CommissionStatus.PENDING);
                if (parentId != null && parentAmount.compareTo(BigDecimal.ZERO) > 0) {
                    cr.setParentDistributorId(parentId);
                    cr.setParentCommissionAmount(parentAmount);

                    // 为上级创建独立佣金记录（抽成金额）
                    CommissionRecord parentCr = new CommissionRecord();
                    parentCr.setDistributorId(parentId);
                    parentCr.setOrderId(orderId);
                    parentCr.setOrderItemId(oi.getId());
                    parentCr.setProductId(oi.getProductId());
                    parentCr.setProductTitle(oi.getProductTitle());
                    parentCr.setOrderAmount(base);
                    parentCr.setCommissionRate(parentSubRateVal);
                    parentCr.setCommissionAmount(parentAmount);
                    parentCr.setTierOrder(tierOrder);
                    parentCr.setStatus(CommissionStatus.PENDING);
                    commissionRecordRepository.save(parentCr);
                }

                commissionRecordRepository.save(cr);
                orderCommissionTotal = orderCommissionTotal.add(selfAmount);
            }

            // 更新客户绑定购买次数
            if (binding != null) {
                binding.setPurchaseCount(binding.getPurchaseCount() + 1);
                binding.setLastPurchaseAt(LocalDateTime.now());
                customerBindingRepository.save(binding);
            }

            // 更新推广链接统计
            if (order.getPromotionLinkId() != null) {
                final BigDecimal finalCommissionTotal = fullCommissionTotal;
                promotionLinkRepository.findById(order.getPromotionLinkId()).ifPresent(link -> {
                    link.setPaidCount(link.getPaidCount() + 1);
                    link.setTotalSales(link.getTotalSales().add(order.getActualAmount()));
                    link.setTotalCommission(link.getTotalCommission().add(finalCommissionTotal));
                    promotionLinkRepository.save(link);
                });
            }

            // 发送佣金获得消息（下级实得金额）
            if (orderCommissionTotal.compareTo(BigDecimal.ZERO) > 0) {
                try {
                    User user = userRepository.findById(d.getUserId()).orElse(null);
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("order_id", orderId);
                    vars.put("commission_amount", orderCommissionTotal);
                    userMessageService.sendUserMessage(d.getUserId(), user != null ? user.getEmail() : null,
                            "COMMISSION_EARNED", vars);
                } catch (Exception e) {
                    log.warn("Failed to send commission earned message: {}", e.getMessage());
                }
            }

            log.info("Commission calculated for order {}: self={}, full={}", orderId, orderCommissionTotal, fullCommissionTotal);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to calculate commission for order {}: {}", orderId, e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 佣金结算（定时任务） ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void settlePendingCommissions() {
        DistributionRule rule = getOrCreateRule();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(rule.getSettleDelayDays());
        List<CommissionRecord> pending = commissionRecordRepository.findPendingSettlement(cutoff);
        if (pending.isEmpty()) {
            return;
        }
        log.info("Settling {} pending commission records", pending.size());

        Map<UUID, Distributor> distCache = new HashMap<>();
        for (CommissionRecord cr : pending) {
            try {
                Distributor d = distCache.computeIfAbsent(cr.getDistributorId(), id ->
                        distributorRepository.findById(id).orElse(null));
                if (d == null) continue;

                cr.setStatus(CommissionStatus.SETTLED);
                cr.setSettledAt(LocalDateTime.now());
                commissionRecordRepository.save(cr);

                d.setAvailableBalance(d.getAvailableBalance().add(cr.getCommissionAmount()));
                d.setTotalCommission(d.getTotalCommission().add(cr.getCommissionAmount()));
                distributorRepository.save(d);

                try {
                    User user = userRepository.findById(d.getUserId()).orElse(null);
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("commission_amount", cr.getCommissionAmount());
                    vars.put("product_title", cr.getProductTitle());
                    userMessageService.sendUserMessage(d.getUserId(), user != null ? user.getEmail() : null,
                            "COMMISSION_SETTLED", vars);
                } catch (Exception e) {
                    log.warn("Failed to send commission settled message: {}", e.getMessage());
                }
            } catch (Exception e) {
                log.error("Failed to settle commission {}: {}", cr.getId(), e.getMessage());
            }
        }
        log.info("Commission settlement completed: {} records settled", pending.size());
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 佣金取消（订单退款时调用） ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void cancelCommissions(UUID orderId) {
        List<CommissionRecord> records = commissionRecordRepository.findByOrderId(orderId);
        if (records.isEmpty()) {
            log.info("No commission records found for order {}", orderId);
            return;
        }
        log.info("Cancelling {} commission records for order {}", records.size(), orderId);

        Map<UUID, Distributor> distCache = new HashMap<>();
        for (CommissionRecord cr : records) {
            try {
                if (cr.getStatus() == CommissionStatus.CANCELLED) {
                    continue;
                }

                CommissionStatus oldStatus = cr.getStatus();
                cr.setStatus(CommissionStatus.CANCELLED);
                commissionRecordRepository.save(cr);

                // 已结算的佣金需要从余额扣减
                if (oldStatus == CommissionStatus.SETTLED) {
                    Distributor d = distCache.computeIfAbsent(cr.getDistributorId(), id ->
                            distributorRepository.findById(id).orElse(null));
                    if (d != null) {
                        BigDecimal balance = d.getAvailableBalance().subtract(cr.getCommissionAmount());
                        d.setAvailableBalance(balance);
                        d.setTotalCommission(d.getTotalCommission().subtract(cr.getCommissionAmount()));
                        distributorRepository.save(d);

                        if (balance.compareTo(BigDecimal.ZERO) < 0) {
                            log.warn("Distributor {} available balance went negative after cancellation: {}", d.getId(), balance);
                        }
                    }
                }

                // 发送取消通知
                Distributor d = distCache.computeIfAbsent(cr.getDistributorId(), id ->
                        distributorRepository.findById(id).orElse(null));
                if (d != null) {
                    try {
                        User user = userRepository.findById(d.getUserId()).orElse(null);
                        Map<String, Object> vars = new LinkedHashMap<>();
                        vars.put("order_id", orderId);
                        vars.put("commission_amount", cr.getCommissionAmount());
                        vars.put("product_title", cr.getProductTitle());
                        userMessageService.sendUserMessage(d.getUserId(), user != null ? user.getEmail() : null,
                                "COMMISSION_CANCELLED", vars);
                    } catch (Exception e) {
                        log.warn("Failed to send commission cancelled message: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to cancel commission {}: {}", cr.getId(), e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 微信转账结果回调（提现到账确认） ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void handleTransferCallback(String outBillNo, String state, String failReason) {
        if (outBillNo == null || outBillNo.isBlank()) {
            log.warn("Transfer callback missing out_bill_no");
            return;
        }
        WithdrawalRecord wr = withdrawalRecordRepository.findByOutBillNo(outBillNo).orElse(null);
        if (wr == null) {
            log.warn("Transfer callback: withdrawal not found for outBillNo={}", outBillNo);
            return;
        }
        // 幂等：已终态（SUCCESS/FAILED）的记录不再处理
        if (wr.getStatus() == WithdrawalStatus.SUCCESS || wr.getStatus() == WithdrawalStatus.FAILED) {
            log.info("Transfer callback: withdrawal {} already {}, skip", wr.getId(), wr.getStatus());
            return;
        }

        Distributor d = distributorRepository.findById(wr.getDistributorId()).orElse(null);
        if (d == null) {
            log.warn("Transfer callback: distributor not found for withdrawal {}", wr.getId());
            return;
        }

        if ("FINISHED".equalsIgnoreCase(state) || "SUCCESS".equalsIgnoreCase(state)) {
            // 转账成功：冻结 → 已提现
            BigDecimal settleAmount = wr.getActualAmount() != null ? wr.getActualAmount() : wr.getAmount();
            d.setFrozenBalance(d.getFrozenBalance().subtract(wr.getAmount()));
            d.setWithdrawnAmount(d.getWithdrawnAmount().add(settleAmount));
            distributorRepository.save(d);

            wr.setStatus(WithdrawalStatus.SUCCESS);
            wr.setCompletedAt(LocalDateTime.now());
            wr.setFailReason(null);
            withdrawalRecordRepository.save(wr);
            log.info("Transfer callback: withdrawal {} FINISHED, distributor {} frozen -= {}, withdrawn += {}",
                    wr.getId(), d.getId(), wr.getAmount(), settleAmount);

            try {
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("amount", settleAmount);
                userMessageService.sendUserMessage(d.getUserId(), null, "WITHDRAWAL_SUCCESS", vars);
            } catch (Exception e) {
                log.warn("Failed to send withdrawal success message: {}", e.getMessage());
            }
        } else {
            // 转账失败/关闭：冻结退回可用余额
            d.setFrozenBalance(d.getFrozenBalance().subtract(wr.getAmount()));
            d.setAvailableBalance(d.getAvailableBalance().add(wr.getAmount()));
            distributorRepository.save(d);

            wr.setStatus(WithdrawalStatus.FAILED);
            wr.setFailReason(failReason != null && !failReason.isBlank() ? "微信转账失败：" + failReason : "微信转账失败");
            withdrawalRecordRepository.save(wr);
            log.info("Transfer callback: withdrawal {} {} -> FAILED, refund frozen={} to distributor {}",
                    wr.getId(), state, wr.getAmount(), d.getId());

            try {
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("amount", wr.getAmount());
                vars.put("reason", wr.getFailReason());
                userMessageService.sendUserMessage(d.getUserId(), null, "WITHDRAWAL_REJECTED", vars);
            } catch (Exception e) {
                log.warn("Failed to send withdrawal failed message: {}", e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 阶梯佣金配置 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCommissionTiers() {
        return tierRepository.findAllByOrderByTierOrderAsc().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("tier_order", t.getTierOrder());
            m.put("rate", t.getRate());
            m.put("enabled", t.isEnabled());
            return m;
        }).toList();
    }

    @Override
    @Transactional
    public void updateCommissionTiers(List<Map<String, Object>> tiers) {
        if (tiers == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "阶梯配置不能为空");
        }
        // 删除旧配置
        tierRepository.deleteAll();

        // 创建新配置
        for (Map<String, Object> tier : tiers) {
            CommissionTier t = new CommissionTier();
            t.setTierOrder(toInt(tier.get("tier_order"), 1));
            BigDecimal rate = toBigDecimal(tier.get("rate"), BigDecimal.ONE);
            t.setRate(rate.setScale(4, RoundingMode.HALF_UP));
            t.setEnabled(toBool(tier.get("enabled"), true));
            tierRepository.save(t);
        }
        log.info("Commission tiers updated: {} tiers", tiers.size());
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 辅助方法 ──
    // ════════════════════════════════════════════════════════════════

    private DistributionRule getOrCreateRule() {
        return ruleRepository.getRule().orElseGet(() -> {
            DistributionRule r = new DistributionRule();
            ruleRepository.save(r);
            log.info("Default distribution rule created");
            return r;
        });
    }

    private Distributor requireDistributorByUserId(UUID userId) {
        return distributorRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));
    }

    /**
     * 佣金比例优先级：
     * 1. 分销员 × 商品 自定义比例
     * 2. 商品 自定义比例
     * 3. 分销员 默认比例
     * 4. 全局默认比例
     */
    private BigDecimal resolveCommissionRate(Distributor d, UUID productId, DistributionRule rule) {
        // 1. 分销员 × 商品 自定义比例
        Optional<DistributorProductRate> dpr = distributorProductRateRepository
                .findByDistributorIdAndProductId(d.getId(), productId);
        if (dpr.isPresent()) {
            return dpr.get().getCustomRate();
        }
        // 2. 商品自定义比例
        Optional<ProductCommission> pc = productCommissionRepository.findByProductId(productId);
        if (pc.isPresent() && pc.get().getCustomRate() != null) {
            return pc.get().getCustomRate();
        }
        // 3. 分销员默认比例
        if (d.getCustomRate() != null) {
            return d.getCustomRate();
        }
        // 4. 全局默认比例
        return rule.getDefaultRate();
    }

    private boolean isProductExcluded(UUID productId) {
        return productCommissionRepository.findByProductId(productId)
                .map(ProductCommission::isExcluded)
                .orElse(false);
    }

    private BigDecimal resolveTierRate(int tierOrder) {
        List<CommissionTier> tiers = tierRepository.findByEnabledTrueOrderByTierOrderAsc();
        if (tiers.isEmpty()) {
            return BigDecimal.ONE;
        }
        // 查找匹配的阶梯
        for (CommissionTier t : tiers) {
            if (t.getTierOrder() == tierOrder) {
                return t.getRate();
            }
        }
        // 超过最大阶梯数：第 N+1 次起不再返佣（设计文档：超档 0%）
        log.info("Tier order {} exceeds max tier {}, no commission", tierOrder,
                tiers.get(tiers.size() - 1).getTierOrder());
        return BigDecimal.ZERO;
    }

    private String generateDistributorCode() {
        String datePart = LocalDate.now().format(DATE_FMT);
        for (int i = 0; i < 20; i++) {
            int seq = ThreadLocalRandom.current().nextInt(10000);
            String code = String.format("D%s%04d", datePart, seq);
            if (distributorRepository.findByDistributorCode(code).isEmpty()) {
                return code;
            }
        }
        return "D" + datePart + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private String generateUniqueInviteCode() {
        for (int i = 0; i < 20; i++) {
            String code = randomCode(8);
            if (distributorRepository.findByInviteCode(code).isEmpty()) {
                return code;
            }
        }
        return randomCode(8);
    }

    private String generateUniqueLinkCode() {
        for (int i = 0; i < 20; i++) {
            String code = randomCode(6);
            if (promotionLinkRepository.findByLinkCode(code).isEmpty()) {
                return code;
            }
        }
        return randomCode(6);
    }

    private String randomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(CODE_CHARS.charAt(rnd.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private String buildPromotionUrl(String linkCode) {
        String base = baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://noepay.cn";
        return base + (base.endsWith("/") ? "" : "/") + "?ref=" + linkCode;
    }

    private Pageable toPageable(int page, int pageSize) {
        return PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100));
    }

    private Map<String, Object> pageResult(List<?> items, long total, int page, int pageSize) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("total", total);
        m.put("page", page);
        m.put("page_size", pageSize);
        return m;
    }

    private DistributorStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return DistributorStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的分销员状态: " + status);
        }
    }

    private CommissionStatus parseCommissionStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return CommissionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的佣金状态: " + status);
        }
    }

    private WithdrawalStatus parseWithdrawalStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return WithdrawalStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的提现状态: " + status);
        }
    }

    private BigDecimal nullSafe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private BigDecimal toBigDecimal(Object o, BigDecimal def) {
        if (o == null) return def;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(o.toString());
        } catch (Exception e) {
            return def;
        }
    }

    private boolean toBool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(o.toString());
    }

    private int toInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (Exception e) {
            return def;
        }
    }

    // ── Map 转换 ──

    private Map<String, Object> distributorToMap(Distributor d, User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("user_id", d.getUserId());
        m.put("username", user != null ? user.getUsername() : null);
        m.put("email", user != null ? user.getEmail() : null);
        m.put("distributor_code", d.getDistributorCode());
        m.put("status", d.getStatus().name());
        m.put("custom_rate", d.getCustomRate());
        m.put("parent_id", d.getParentId());
        m.put("sub_rate", d.getSubRate());
        m.put("wechat_bound", d.getWechatOpenid() != null && !d.getWechatOpenid().isBlank());
        m.put("total_commission", d.getTotalCommission());
        m.put("available_balance", d.getAvailableBalance());
        m.put("frozen_balance", d.getFrozenBalance());
        m.put("withdrawn_amount", d.getWithdrawnAmount());
        m.put("invite_code", d.getInviteCode());
        m.put("approved_at", d.getApprovedAt());
        m.put("disabled_at", d.getDisabledAt());
        m.put("created_at", d.getCreatedAt());
        return m;
    }

    private Map<String, Object> ruleToMap(DistributionRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("default_rate", r.getDefaultRate());
        m.put("enabled", r.isEnabled());
        m.put("auto_approve", r.isAutoApprove());
        m.put("min_withdraw_amount", r.getMinWithdrawAmount());
        m.put("settle_delay_days", r.getSettleDelayDays());
        m.put("withdraw_fee_rate", r.getWithdrawFeeRate());
        m.put("binding_protection_days", r.getBindingProtectionDays());
        m.put("tier_enabled", r.isTierEnabled());
        m.put("sub_distribution_enabled", r.isSubDistributionEnabled());
        m.put("default_sub_rate", r.getDefaultSubRate());
        return m;
    }

    private Map<String, Object> linkToMap(PromotionLink pl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", pl.getId());
        m.put("distributor_id", pl.getDistributorId());
        m.put("product_id", pl.getProductId());
        m.put("link_code", pl.getLinkCode());
        m.put("url", buildPromotionUrl(pl.getLinkCode()));
        m.put("click_count", pl.getClickCount());
        m.put("unique_click_count", pl.getUniqueClickCount());
        m.put("paid_count", pl.getPaidCount());
        m.put("total_sales", pl.getTotalSales());
        m.put("total_commission", pl.getTotalCommission());
        m.put("created_at", pl.getCreatedAt());
        return m;
    }

    private Map<String, Object> commissionToMap(CommissionRecord cr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", cr.getId());
        m.put("distributor_id", cr.getDistributorId());
        m.put("order_id", cr.getOrderId());
        m.put("order_item_id", cr.getOrderItemId());
        m.put("product_id", cr.getProductId());
        m.put("product_title", cr.getProductTitle());
        m.put("order_amount", cr.getOrderAmount());
        m.put("commission_rate", cr.getCommissionRate());
        m.put("commission_amount", cr.getCommissionAmount());
        m.put("tier_order", cr.getTierOrder());
        m.put("status", cr.getStatus().name());
        m.put("parent_distributor_id", cr.getParentDistributorId());
        m.put("parent_commission_amount", cr.getParentCommissionAmount());
        m.put("settled_at", cr.getSettledAt());
        m.put("created_at", cr.getCreatedAt());
        return m;
    }

    private Map<String, Object> withdrawalToMap(WithdrawalRecord wr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", wr.getId());
        m.put("distributor_id", wr.getDistributorId());
        m.put("amount", wr.getAmount());
        m.put("fee", wr.getFee());
        m.put("actual_amount", wr.getActualAmount());
        m.put("status", wr.getStatus().name());
        m.put("fail_reason", wr.getFailReason());
        m.put("out_bill_no", wr.getOutBillNo());
        m.put("transfer_bill_no", wr.getTransferBillNo());
        m.put("applied_at", wr.getAppliedAt());
        m.put("approved_at", wr.getApprovedAt());
        m.put("transferred_at", wr.getTransferredAt());
        m.put("completed_at", wr.getCompletedAt());
        m.put("created_at", wr.getCreatedAt());
        return m;
    }
}
