package com.orionkey.service.impl;

import com.orionkey.dto.TrackRequest;
import com.orionkey.entity.VisitLog;
import com.orionkey.entity.VisitSession;
import com.orionkey.entity.VisitStats;
import com.orionkey.repository.VisitLogRepository;
import com.orionkey.repository.VisitSessionRepository;
import com.orionkey.repository.VisitStatsRepository;
import com.orionkey.service.VisitConfigService;
import com.orionkey.service.VisitService;
import com.orionkey.util.IpLocationResolver;
import com.orionkey.util.RefererUtil;
import com.orionkey.util.UserAgentUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisitServiceImpl implements VisitService {

    /** 会话切分：超过该分钟数无活动则视为新会话 */
    private static final int SESSION_TIMEOUT_MINUTES = 30;

    private final VisitStatsRepository visitStatsRepository;
    private final VisitLogRepository visitLogRepository;
    private final VisitSessionRepository visitSessionRepository;
    private final IpLocationResolver ipLocationResolver;
    private final VisitConfigService visitConfigService;

    /** 当天已记录的访客集合（用于 UV 去重） */
    private volatile LocalDate currentDate = LocalDate.now();
    private final Set<String> todayVisitors = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    @Transactional
    public void track(String ip, String userAgent, TrackRequest request, boolean backendUser) {
        try {
            if (!visitConfigService.trackEnabled()) {
                return;
            }
            if (backendUser && visitConfigService.excludeAdmin()) {
                return;
            }
            boolean bot = UserAgentUtil.isBot(userAgent);
            if (bot && visitConfigService.excludeBot()) {
                return;
            }
            boolean whitelisted = matchIp(ip, visitConfigService.ipWhitelist());
            if (!whitelisted && matchIp(ip, visitConfigService.ipBlacklist())) {
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            String path = request != null ? request.getPath() : null;
            if (path == null || path.isBlank()) {
                path = "/";
            }
            String rawReferer = request != null ? request.getReferer() : null;
            String refererHost = RefererUtil.hostOf(rawReferer);
            String source = RefererUtil.classify(refererHost);
            String visitorId = request != null ? request.getVisitorId() : null;

            VisitLog visitLog = new VisitLog();
            visitLog.setVisitDate(now.toLocalDate());
            visitLog.setVisitHour(now.getHour());
            visitLog.setIp(truncate(ip, 64));
            visitLog.setDevice(UserAgentUtil.detectDeviceType(userAgent));
            visitLog.setOs(truncate(UserAgentUtil.detectOs(userAgent), 32));
            visitLog.setBrowser(truncate(UserAgentUtil.detectBrowser(userAgent), 32));
            visitLog.setSource(source);
            visitLog.setReferer(truncate(refererHost, 512));
            visitLog.setPath(truncate(path, 255));
            visitLog.setVisitorId(truncate(visitorId, 64));
            visitLog.setUserAgent(truncate(userAgent, 512));
            visitLog.setScreen(truncate(request != null ? request.getScreen() : null, 16));
            visitLog.setLang(truncate(request != null ? request.getLang() : null, 16));

            if (visitConfigService.geolocationEnabled() && !bot) {
                IpLocationResolver.Location location = ipLocationResolver.resolve(ip, visitConfigService.xdbPath());
                if (location != null) {
                    visitLog.setCountry(truncate(location.getCountry(), 64));
                    visitLog.setProvince(truncate(location.getProvince(), 64));
                    visitLog.setCity(truncate(location.getCity(), 64));
                    visitLog.setIsp(truncate(location.getIsp(), 128));
                }
            }

            visitLogRepository.save(visitLog);

            String visitorKey = resolveVisitorKey(visitorId, ip);
            updateSession(visitorKey, ip, visitLog, now);
            updateDailyStats(now.toLocalDate(), visitorKey);
        } catch (Exception e) {
            // 采集为旁路能力，任何异常都不得影响主流程
            log.warn("记录访问数据失败：{}", e.getMessage());
        }
    }

    private void updateSession(String visitorKey, String ip, VisitLog visitLog, LocalDateTime now) {
        if (visitorKey == null) {
            return;
        }
        VisitSession session = visitSessionRepository
                .findFirstByVisitorIdOrderByLastActiveAtDesc(visitorKey)
                .orElse(null);
        if (session == null || session.getLastActiveAt() == null
                || session.getLastActiveAt().isBefore(now.minusMinutes(SESSION_TIMEOUT_MINUTES))) {
            VisitSession s = new VisitSession();
            s.setVisitorId(visitorKey);
            s.setIp(truncate(ip, 64));
            s.setPageCount(1);
            s.setBounce(true);
            s.setSource(visitLog.getSource());
            s.setDevice(visitLog.getDevice());
            s.setEntryPath(visitLog.getPath());
            s.setExitPath(visitLog.getPath());
            s.setStartTime(now);
            s.setLastActiveAt(now);
            s.setDurationSec(0);
            visitSessionRepository.save(s);
        } else {
            session.setPageCount(session.getPageCount() + 1);
            session.setBounce(false);
            session.setExitPath(visitLog.getPath());
            session.setLastActiveAt(now);
            if (session.getStartTime() != null) {
                long seconds = Duration.between(session.getStartTime(), now).getSeconds();
                session.setDurationSec((int) Math.max(0, Math.min(seconds, Integer.MAX_VALUE)));
            }
            visitSessionRepository.save(session);
        }
    }

    private void updateDailyStats(LocalDate today, String visitorKey) {
        if (!today.equals(currentDate)) {
            synchronized (this) {
                if (!today.equals(currentDate)) {
                    todayVisitors.clear();
                    currentDate = today;
                }
            }
        }
        boolean isNewVisitor = visitorKey != null && todayVisitors.add(visitorKey);

        VisitStats stats = visitStatsRepository.findByVisitDate(today)
                .orElseGet(() -> {
                    VisitStats s = new VisitStats();
                    s.setVisitDate(today);
                    s.setPv(0);
                    s.setUv(0);
                    return s;
                });
        stats.setPv(stats.getPv() + 1);
        if (isNewVisitor) {
            stats.setUv(stats.getUv() + 1);
        }
        visitStatsRepository.save(stats);
    }

    private static String resolveVisitorKey(String visitorId, String ip) {
        if (visitorId != null && !visitorId.isBlank()) {
            return visitorId;
        }
        if (ip != null && !ip.isBlank()) {
            return "ip:" + ip;
        }
        return null;
    }

    private static boolean matchIp(String ip, List<String> patterns) {
        if (ip == null || ip.isBlank() || patterns == null || patterns.isEmpty()) {
            return false;
        }
        String target = ip.trim();
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            String p = pattern.trim();
            if (p.endsWith("*")) {
                if (target.startsWith(p.substring(0, p.length() - 1))) {
                    return true;
                }
            } else if (target.equals(p)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }
}
