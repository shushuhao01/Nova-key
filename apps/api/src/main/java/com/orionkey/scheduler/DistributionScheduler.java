package com.orionkey.scheduler;

import com.orionkey.constant.CommissionStatus;
import com.orionkey.constant.WithdrawalStatus;
import com.orionkey.entity.CommissionRecord;
import com.orionkey.entity.Distributor;
import com.orionkey.entity.PaymentChannel;
import com.orionkey.entity.WithdrawalRecord;
import com.orionkey.repository.CommissionRecordRepository;
import com.orionkey.repository.DistributorRepository;
import com.orionkey.repository.PaymentChannelRepository;
import com.orionkey.repository.WithdrawalRecordRepository;
import com.orionkey.service.DistributionService;
import com.orionkey.service.impl.PaymentServiceImpl;
import com.orionkey.service.WxpayService;
import com.orionkey.service.WxpayService.WxpayConfig;
import com.orionkey.service.WxpayService.WxpayTransferQueryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 分销推广定时任务
 *
 * 1. 佣金结算（每小时）— PENDING 佣金超过 settle_delay_days 的转为 SETTLED
 * 2. 提现超时处理（每30分钟）— PROCESSING 超过24小时先查微信真实状态，再决定退回或补账
 * 3. 余额对账（每天）— 核对余额一致性
 * 4. 微信转账状态轮询（每5分钟）— 回调丢失时兜底补账
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributionScheduler {

    private final DistributionService distributionService;
    private final CommissionRecordRepository commissionRecordRepository;
    private final WithdrawalRecordRepository withdrawalRecordRepository;
    private final DistributorRepository distributorRepository;
    private final PaymentServiceImpl paymentServiceImpl;
    private final PaymentChannelRepository paymentChannelRepository;
    private final WxpayService wxpayService;

    /**
     * 佣金结算 — 每小时执行
     * 扫描 PENDING 状态佣金记录，超过结算延迟期的转为 SETTLED，入可提现余额。
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void settlePendingCommissions() {
        try {
            distributionService.settlePendingCommissions();
        } catch (Exception e) {
            log.error("佣金结算定时任务失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 提现超时处理 — 每30分钟执行
     * PROCESSING 状态超过24小时的提现，先向微信查询真实状态：
     * 微信已终态 → 走回调补账；仍是中间态 → 等待微信侧超时关闭；无法查询 → 退回余额。
     */
    @Scheduled(cron = "0 */30 * * * *")
    @Transactional
    public void handleWithdrawalTimeout() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<WithdrawalRecord> timeoutList = withdrawalRecordRepository.findProcessingTimeout(cutoff);
        if (timeoutList.isEmpty()) return;

        log.info("发现 {} 笔超时提现待处理", timeoutList.size());
        WxpayConfig config = findTransferWxpayConfig();
        for (WithdrawalRecord wr : timeoutList) {
            try {
                // 先查微信真实状态：终态（成功/失败）走回调补账，避免"已到账仍被退回"造成重复打款
                if (config != null && wr.getOutBillNo() != null && !wr.getOutBillNo().isBlank()) {
                    WxpayTransferQueryResult r = wxpayService.queryTransfer(config, wr.getOutBillNo());
                    if (r != null && r.state() != null) {
                        String state = r.state();
                        boolean terminal = "FINISHED".equalsIgnoreCase(state) || "SUCCESS".equalsIgnoreCase(state)
                                || "CLOSED".equalsIgnoreCase(state) || "FAILED".equalsIgnoreCase(state)
                                || "FAIL".equalsIgnoreCase(state) || "CANCELLED".equalsIgnoreCase(state);
                        if (terminal) {
                            log.info("提现 {} 超时，微信侧终态 {}，回调补账", wr.getId(), state);
                            distributionService.handleTransferCallback(wr.getOutBillNo(), state, r.failReason());
                            continue;
                        }
                        // 微信侧仍是中间态（如 WAIT_USER_CONFIRM）：交给微信侧超时关闭机制，不盲目退回
                        log.info("提现 {} 超时但微信侧状态 {}，等待微信终态", wr.getId(), state);
                        continue;
                    }
                }

                // 无法查询微信状态（无配置/查询失败）：保守退回余额并标记失败
                Distributor d = distributorRepository.findById(wr.getDistributorId()).orElse(null);
                if (d == null) continue;

                BigDecimal amount = wr.getAmount();
                d.setFrozenBalance(d.getFrozenBalance().subtract(amount));
                d.setAvailableBalance(d.getAvailableBalance().add(amount));
                distributorRepository.save(d);

                wr.setStatus(WithdrawalStatus.FAILED);
                wr.setFailReason("超时未确认收款，自动撤销并退回余额");
                withdrawalRecordRepository.save(wr);

                log.info("提现 {} 超时处理完成，金额 {} 已退回分销员 {}", wr.getId(), amount, d.getId());
            } catch (Exception e) {
                log.error("提现 {} 超时处理失败: {}", wr.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 微信转账状态轮询 — 每5分钟执行
     * 回调丢失时兜底：主动查询 PROCESSING 且有商户单号的提现，微信返回终态即补账
     * （成功 → 冻结转已提现；失败 → 退回余额；中间态保持不动）。
     */
    @Scheduled(cron = "0 */5 * * * *")
    @Transactional
    public void pollWxpayTransferStatus() {
        List<WithdrawalRecord> processingList = withdrawalRecordRepository.findProcessingWithOutBillNo();
        if (processingList.isEmpty()) return;

        WxpayConfig config = findTransferWxpayConfig();
        if (config == null) {
            log.warn("转账状态轮询：未找到启用的 native_wxpay 渠道配置，跳过 {} 笔", processingList.size());
            return;
        }

        for (WithdrawalRecord wr : processingList) {
            try {
                WxpayTransferQueryResult r = wxpayService.queryTransfer(config, wr.getOutBillNo());
                if (r == null || r.state() == null) continue;
                String state = r.state();
                boolean terminal = "FINISHED".equalsIgnoreCase(state) || "SUCCESS".equalsIgnoreCase(state)
                        || "CLOSED".equalsIgnoreCase(state) || "FAILED".equalsIgnoreCase(state)
                        || "FAIL".equalsIgnoreCase(state) || "CANCELLED".equalsIgnoreCase(state);
                if (terminal) {
                    log.info("转账轮询：提现 {} 微信状态 {}，兜底补账", wr.getId(), state);
                    distributionService.handleTransferCallback(wr.getOutBillNo(), state, r.failReason());
                }
            } catch (Exception e) {
                log.error("转账轮询失败：withdrawal={}, error={}", wr.getId(), e.getMessage());
            }
        }
    }

    /** 查找启用的 native_wxpay 渠道对应的微信转账配置（无渠道返回 null） */
    private WxpayConfig findTransferWxpayConfig() {
        List<PaymentChannel> channels = paymentChannelRepository.findByProviderTypeAndIsDeleted("native_wxpay", 0);
        PaymentChannel channel = channels.stream().filter(PaymentChannel::isEnabled).findFirst().orElse(null);
        if (channel == null) return null;
        return paymentServiceImpl.buildWxpayConfig(channel);
    }

    /**
     * 余额对账 — 每天凌晨2点执行
     * 核对每个分销员的余额一致性：理论可提现 = SUM(SETTLED佣金) - SUM(SUCCESS提现)
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional(readOnly = true)
    public void balanceReconciliation() {
        List<Distributor> distributors = distributorRepository.findAll();
        int anomalyCount = 0;

        for (Distributor d : distributors) {
            try {
                // 理论已结算佣金
                BigDecimal settledCommission = commissionRecordRepository
                        .sumByDistributorAndStatus(d.getId(), CommissionStatus.SETTLED.name());
                if (settledCommission == null) settledCommission = BigDecimal.ZERO;

                // 理论可提现 = 已结算佣金 - 已提现金额
                BigDecimal expectedAvailable = settledCommission.subtract(d.getWithdrawnAmount());
                BigDecimal actualAvailable = d.getAvailableBalance() != null ? d.getAvailableBalance() : BigDecimal.ZERO;

                if (expectedAvailable.compareTo(actualAvailable) != 0) {
                    anomalyCount++;
                    log.warn("余额对账异常：分销员 {} 理论可提现={} 实际可提现={} 差额={}",
                            d.getId(), expectedAvailable, actualAvailable,
                            expectedAvailable.subtract(actualAvailable));
                }
            } catch (Exception e) {
                log.error("分销员 {} 余额对账失败: {}", d.getId(), e.getMessage());
            }
        }

        if (anomalyCount > 0) {
            log.warn("余额对账完成，发现 {} 个异常分销员", anomalyCount);
        } else {
            log.info("余额对账完成，所有分销员余额一致（共 {} 人）", distributors.size());
        }
    }
}
