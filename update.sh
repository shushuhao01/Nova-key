#!/bin/bash
# Nova-Key 一键部署脚本：拉取代码 → 编译后端 → 重启后端 → 构建前端 → 重启前端 → 验证
set -e
cd /www/wwwroot/nova-key

# ── 颜色与符号 ──
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

OK="${GREEN}✓${NC}"
FAIL="${RED}✗${NC}"
WARN="${YELLOW}⚠${NC}"
ARROW="${CYAN}→${NC}"

step=0
total_steps=10

section() {
    step=$((step + 1))
    echo ""
    echo -e "${BLUE}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}${BOLD}  [$step/$total_steps] $1${NC}"
    echo -e "${BLUE}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

info()  { echo -e "  ${ARROW} $1"; }
ok()    { echo -e "  ${OK} ${GREEN}$1${NC}"; }
fail()  { echo -e "  ${FAIL} ${RED}$1${NC}"; }
warn()  { echo -e "  ${WARN} ${YELLOW}$1${NC}"; }

START_TIME=$(date +%s)

echo -e "${BOLD}${CYAN}"
echo "╔══════════════════════════════════════════════════╗"
echo "║          Nova-Key 一键部署脚本                   ║"
echo "╚══════════════════════════════════════════════════╝"
echo -e "${NC}"
info "开始时间: $(date '+%Y-%m-%d %H:%M:%S')"
info "服务器: $(hostname)"
info "工作目录: $(pwd)"

# ─────────────────────────────────────────
section "拉取最新代码"
# ─────────────────────────────────────────
OLD_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
git pull 2>&1 | while read -r line; do echo -e "  ${ARROW} $line"; done
NEW_COMMIT=$(git rev-parse --short HEAD)
COMMIT_MSG=$(git log --oneline -1 | cut -d' ' -f2-)
if [ "$OLD_COMMIT" = "$NEW_COMMIT" ]; then
    warn "代码无更新（已是最新: $NEW_COMMIT）"
else
    ok "代码已更新: $OLD_COMMIT → $NEW_COMMIT"
    info "提交信息: $COMMIT_MSG"
fi

# ─────────────────────────────────────────
section "检查启动脚本"
# ─────────────────────────────────────────
if grep -q 'APP_BASE_URL= `' start-api.sh 2>/dev/null; then
    sed -i "s|export APP_BASE_URL= \`https://noepay.cn\`|export APP_BASE_URL=https://noepay.cn|" start-api.sh
    ok "已修复 APP_BASE_URL 反引号 bug"
else
    ok "启动脚本正常，无需修复"
fi

# ─────────────────────────────────────────
section "编译后端 (Maven)"
# ─────────────────────────────────────────
cd /www/wwwroot/nova-key/apps/api
info "开始编译..."
if mvn -q package -DskipTests 2>&1; then
    JAR_TIME=$(ls -la target/nova-key-1.0.0-SNAPSHOT.jar | awk '{print $6, $7, $8}')
    JAR_SIZE=$(du -h target/nova-key-1.0.0-SNAPSHOT.jar | awk '{print $1}')
    ok "后端编译成功"
    info "JAR 文件: nova-key-1.0.0-SNAPSHOT.jar ($JAR_SIZE, $JAR_TIME)"
else
    fail "后端编译失败！"
    exit 1
fi

# ─────────────────────────────────────────
section "停止旧后端进程"
# ─────────────────────────────────────────
# 同时按「8083 端口占用」和「进程名」双重清理，避免残留旧进程占用端口
info "清理 8083 端口占用进程..."
for pid in $(ss -ltnp | grep ':8083' | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u); do
    info "强制结束 PID: $pid"
    kill -9 "$pid" 2>/dev/null || true
done
info "清理 nova-key 相关 Java 进程..."
pkill -9 -f 'nova-key.*jar' 2>/dev/null || true
sleep 3
if ss -ltnp | grep -q ':8083'; then
    fail "8083 端口仍被占用！这通常是宝塔面板的守护进程在自动拉起后端。"
    fail "请先在宝塔面板【暂停】该项目的守护进程，再重新运行本脚本。"
    info "当前占用进程:"
    ss -ltnp | grep ':8083' | sed 's/^/    /'
    exit 1
fi
ok "旧进程已停止，8083 端口已释放"

# ─────────────────────────────────────────
section "启动后端服务"
# ─────────────────────────────────────────
cd /www/wwwroot/nova-key
info "执行 start-api.sh..."
bash start-api.sh
info "等待后端启动（检查 8083 端口）..."

# 轮询等待端口就绪，最多等 30 秒
wait_count=0
max_wait=30
while [ $wait_count -lt $max_wait ]; do
    if ss -ltnp | grep -q ':8083'; then
        break
    fi
    sleep 1
    wait_count=$((wait_count + 1))
    printf "\r  ${ARROW} 等待中... ${wait_count}/${max_wait}s"
done
echo ""

# ─────────────────────────────────────────
section "验证后端服务"
# ─────────────────────────────────────────
if ss -ltnp | grep -q ':8083'; then
    PID=$(ss -ltnp | grep ':8083' | grep -o 'pid=[0-9]*' | cut -d= -f2)
    ok "后端 8083 端口已监听 (PID: $PID)"
    info "进程信息:"
    ps -eo pid,lstart,cmd | grep 'nova-key.*jar' | grep -v grep | head -1 | sed 's/^/    /'
    # 健康检查
    HEALTH=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8083/api/health 2>/dev/null || echo "000")
    if [ "$HEALTH" = "200" ] || [ "$HEALTH" = "404" ]; then
        ok "后端 HTTP 响应正常 ($HEALTH)"
    else
        warn "后端 HTTP 响应码: $HEALTH（可能还在启动中）"
    fi
else
    fail "后端 8083 端口未监听！"
    info "最近日志（最后 30 行）:"
    tail -n 30 /www/wwwroot/nova-key/logs/api.log 2>/dev/null | sed 's/^/    /'
    exit 1
fi

# ─────────────────────────────────────────
section "构建前端 (Next.js)"
# ─────────────────────────────────────────
cd /www/wwwroot/nova-key/apps/web
info "清理构建缓存..."
rm -rf .next
info "开始构建..."
if pnpm build 2>&1 | tail -n 8 | sed 's/^/  /'; then
    BUILD_TIME=$(ls -la .next/BUILD_ID | awk '{print $6, $7, $8}')
    ok "前端构建完成 ($BUILD_TIME)"
    # 验证关键字段是否已编译
    if grep -rl "coupon_quantity" .next/static/chunks/ >/dev/null 2>&1; then
        ok "coupon_quantity 字段已编译进 JS"
    else
        warn "未找到 coupon_quantity（可能字段名有变化）"
    fi
else
    fail "前端构建失败！"
    exit 1
fi

# ─────────────────────────────────────────
section "重启前端服务"
# ─────────────────────────────────────────
OLD_WEB_PID=$(ss -ltnp | grep ':3001' | grep -o 'pid=[0-9]*' | cut -d= -f2 || echo "")
if [ -n "$OLD_WEB_PID" ]; then
    info "停止旧前端进程 PID: $OLD_WEB_PID"
    fuser -k 3001/tcp 2>/dev/null || true
else
    info "无旧前端进程"
fi
info "等待宝塔守护进程拉起前端..."
sleep 5

# 如果宝塔没拉起，手动启动
if ! ss -ltnp | grep -q ':3001'; then
    warn "宝塔未自动拉起，手动启动..."
    cd /www/wwwroot/nova-key/apps/web
    nohup env BACKEND_URL=http://127.0.0.1:8083 node node_modules/next/dist/bin/next start -p 3001 >> /www/wwwroot/nova-key/logs/web.log 2>&1 &
    info "等待手动启动完成..."
    sleep 5
fi

# ─────────────────────────────────────────
section "验证前端服务"
# ─────────────────────────────────────────
if ss -ltnp | grep -q ':3001'; then
    WEB_PID=$(ss -ltnp | grep ':3001' | grep -o 'pid=[0-9]*' | cut -d= -f2 | head -1)
    ok "前端 3001 端口已监听 (PID: $WEB_PID)"
    # HTTP 检查
    WEB_HEALTH=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:3001/ 2>/dev/null || echo "000")
    if [ "$WEB_HEALTH" = "200" ]; then
        ok "前端 HTTP 响应正常 (200)"
    else
        warn "前端 HTTP 响应码: $WEB_HEALTH"
    fi
else
    fail "前端 3001 端口未监听！"
    info "查看日志: tail -n 30 /www/wwwroot/nova-key/logs/web.log"
    exit 1
fi

# ─────────────────────────────────────────
section "接口连通性测试"
# ─────────────────────────────────────────
info "测试登录接口..."
LOGIN_RESP=$(curl -s -X POST http://127.0.0.1:8083/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"account":"admin","password":"admin123"}')
TOKEN=$(echo "$LOGIN_RESP" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
if [ -n "$TOKEN" ]; then
    ok "登录成功"
    info "测试营销接口..."
    COUPON_RESP=$(curl -s http://127.0.0.1:8083/api/admin/marketing/coupons?page=1\&page_size=5 \
        -H "Authorization: Bearer $TOKEN")
    COUPON_CODE=$(echo "$COUPON_RESP" | grep -o '"code":[0-9]*' | cut -d':' -f2)
    if [ "$COUPON_CODE" = "0" ]; then
        ok "营销接口正常 (code=0)"
    else
        warn "营销接口返回: $COUPON_RESP"
    fi
else
    warn "登录失败: $LOGIN_RESP"
fi

# ─────────────────────────────────────────
# 部署总结
# ─────────────────────────────────────────
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
MINUTES=$((DURATION / 60))
SECONDS=$((DURATION % 60))

echo ""
echo -e "${BOLD}${GREEN}╔══════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}${GREEN}║              ✓ 部署完成                          ║${NC}"
echo -e "${BOLD}${GREEN}╚══════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  ${BOLD}部署摘要${NC}"
echo -e "  ─────────────────────────────────────"
echo -e "  Git 提交:    ${CYAN}$NEW_COMMIT${NC} ($COMMIT_MSG)"
echo -e "  后端 JAR:    $(ls -la /www/wwwroot/nova-key/apps/api/target/nova-key-1.0.0-SNAPSHOT.jar | awk '{print $6, $7, $8}')"
echo -e "  前端构建:    $(ls -la /www/wwwroot/nova-key/apps/web/.next/BUILD_ID | awk '{print $6, $7, $8}')"
echo -e "  后端端口:    ${GREEN}8083 ✓${NC}  (PID: $(ss -ltnp | grep ':8083' | grep -o 'pid=[0-9]*' | cut -d= -f2 | head -1))"
echo -e "  前端端口:    ${GREEN}3001 ✓${NC}  (PID: $(ss -ltnp | grep ':3001' | grep -o 'pid=[0-9]*' | cut -d= -f2 | head -1))"
echo -e "  耗时:        ${YELLOW}${MINUTES}分${SECONDS}秒${NC}"
echo -e "  完成时间:    $(date '+%Y-%m-%d %H:%M:%S')"
echo ""
echo -e "  ${BOLD}访问地址${NC}"
echo -e "  ─────────────────────────────────────"
echo -e "  前台:  ${CYAN}https://noepay.cn${NC}"
echo -e "  后台:  ${CYAN}https://noepay.cn/admin${NC}"
echo -e "  API:   ${CYAN}https://noepay.cn/api${NC}"
echo ""
