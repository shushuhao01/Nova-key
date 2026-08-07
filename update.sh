#!/bin/bash
# ════════════════════════════════════════════════════════════════
# Nova key 一键更新脚本
# 用法：bash update.sh
# 流程：检查配置 → 拉取代码 → 后端打包 → 前端构建 → PM2 重启 → 验证
# ════════════════════════════════════════════════════════════════
set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

PROJECT_DIR="/www/wwwroot/nova-key"
cd "$PROJECT_DIR"

echo -e "${CYAN}==========================================${NC}"
echo -e "${CYAN}  Nova key 一键更新脚本${NC}"
echo -e "${CYAN}==========================================${NC}"

# 构建内存上限（Next.js 构建需要，用总内存 60%）
TOTAL_MEM=$(free -m 2>/dev/null | awk '/^Mem:/{print $2}' || echo 4096)
NODE_MEM=$((TOTAL_MEM * 60 / 100))
[ "$NODE_MEM" -lt 1536 ] && NODE_MEM=1536
[ "$NODE_MEM" -gt 6144 ] && NODE_MEM=6144
export NODE_OPTIONS="--max-old-space-size=$NODE_MEM"
echo -e "${YELLOW}[i] 内存 ${TOTAL_MEM}MB，Node 构建上限 ${NODE_MEM}MB${NC}"

# ═══════ Step 1: 配置检查与备份 ═══════
echo -e "${YELLOW}[1] 检查/备份 .env...${NC}"
if [ ! -f ".env" ]; then
    cp .env.example .env
    echo -e "${RED}[X] 已生成 .env 模板，请先填写 DB_PASSWORD 等真实值后重新执行: bash update.sh${NC}"
    exit 1
fi
# 防止 .env 仍是模板占位符（占位符含 < >），否则 PM2 会拿模板启动导致连库失败、无限重启
if grep -q '<[^>]*>' .env; then
    echo -e "${RED}[X] .env 仍含模板占位符（<...>），请先填写真实值（APP_BASE_URL/DB_URL/DB_USERNAME/DB_PASSWORD/JWT_SECRET）后重新执行: bash update.sh${NC}"
    exit 1
fi
# 防止 .env 混入反引号（粘贴模板/文档时常见，如 APP_BASE_URL= `https://...`）。
# 反引号会被 bash 当作命令替换执行，导致配置值变空，PM2 启动后回调地址/连库出错。
# 配置值不允许出现反引号，检测到直接自动清除。
if grep -q '`' .env; then
    echo -e "${YELLOW}[i] .env 含反引号（\`），自动清除（配置值不允许出现反引号）...${NC}"
    sed -i 's/`//g' .env
fi
# 清理复制粘贴混入的垃圾内容：
# 1) 终端提示符（如 root@xxx:/path#）被粘进 .env → source 时报 command not found
# 2) grep 输出的行号前缀（如 "6:APP_BASE_URL=..."）被粘进 → 变量名解析错误
if grep -qE '^(root@|bash:|No such file|command not found)' .env; then
    echo -e "${YELLOW}[i] .env 混入了终端提示符等非法内容，自动清理...${NC}"
    sed -i -E '/^(root@|bash:|No such file|command not found)/d' .env
fi
if grep -qE '^[0-9]+:[A-Za-z_]+=' .env; then
    echo -e "${YELLOW}[i] .env 混入了带行号的配置行（如 "6:APP_BASE_URL=..."），自动去掉行号前缀...${NC}"
    sed -i -E 's/^[0-9]+://' .env
fi
# 语法检查：引号/括号未闭合会在 source .env 时直接失败，导致 PM2 启动崩溃
if ! bash -n .env 2>/dev/null; then
    echo -e "${RED}[X] .env 语法错误（引号/括号未闭合），请修复后重新执行: bash update.sh${NC}"
    exit 1
fi
# 关键配置非空校验（空值会导致后端用默认值连库失败 / 回调地址生成错误）
for _var in APP_BASE_URL DB_URL DB_USERNAME DB_PASSWORD; do
    _val=$(grep -E "^${_var}=" .env | head -1 | cut -d= -f2-)
    if [ -z "$_val" ]; then
        echo -e "${RED}[X] .env 缺少 ${_var} 配置值（为空），请填写后重新执行: bash update.sh${NC}"
        exit 1
    fi
done
# 缺失 JWT_SECRET 时自动生成（固定写入 .env，避免每次重启导致登录态失效）
if ! grep -q '^JWT_SECRET=' .env; then
    echo "JWT_SECRET=$(openssl rand -base64 48)" >> .env
    echo -e "${GREEN}[i] 已生成新的 JWT_SECRET（写入 .env）${NC}"
fi
cp .env .env.backup
echo -e "${GREEN}[OK] 配置就绪${NC}"

# ═══════ Step 2: 拉取代码 ═══════
echo -e "${YELLOW}[2] 拉取最新代码...${NC}"
git stash save "Auto stash before update $(date '+%Y-%m-%d %H:%M:%S')" 2>/dev/null || true
git pull origin main || {
    echo -e "${RED}[X] 代码拉取失败！${NC}"
    git stash pop 2>/dev/null || true
    exit 1
}
cp .env.backup .env && rm -f .env.backup
echo -e "${GREEN}[OK] 代码已更新${NC}"

# ═══════ Step 3: 后端打包 ═══════
echo -e "${YELLOW}[3] 后端打包 (Maven)...${NC}"
cd "$PROJECT_DIR/apps/api"
mvn -DskipTests package
echo -e "${GREEN}[OK] 后端打包完成${NC}"

# ═══════ Step 4: 前端依赖 + 构建 ═══════
echo -e "${YELLOW}[4] 前端构建 (pnpm)...${NC}"
cd "$PROJECT_DIR"
# 依赖安装失败时直接恢复 web 进程，避免网页停在不可用状态
if ! pnpm install; then
    echo -e "${RED}[X] pnpm install 失败！恢复旧前端进程...${NC}"
    pm2 restart noepay.cn-web 2>/dev/null || true
    exit 1
fi
# 备份旧构建产物，构建失败时可回滚（防止网页长期打不开）
[ -d apps/web/.next ] && { rm -rf apps/web/.next.bak; cp -r apps/web/.next apps/web/.next.bak; }
# 构建前先停前端进程，避免覆盖 .next 目录时写冲突
pm2 stop noepay.cn-web 2>/dev/null || pkill -f 'next start -p 3001' 2>/dev/null || true
cd apps/web
if ! env -u NODE_OPTIONS BACKEND_URL=http://127.0.0.1:8083 pnpm build; then
    echo -e "${RED}[X] 前端构建失败！回滚旧版本并恢复 web 进程...${NC}"
    [ -d .next.bak ] && { rm -rf .next; mv .next.bak .next; }
    pm2 restart noepay.cn-web 2>/dev/null || true
    echo -e "${RED}    请查看构建错误: pnpm build 2>&1 | tail -80${NC}"
    exit 1
fi
rm -rf .next.bak
echo -e "${GREEN}[OK] 前端构建完成${NC}"

# ═══════ Step 5: PM2 启动/重启 ═══════
echo -e "${YELLOW}[5] PM2 启动/重启进程...${NC}"
cd "$PROJECT_DIR"
if command -v pm2 >/dev/null 2>&1; then
    if pm2 describe noepay.cn-api >/dev/null 2>&1 || pm2 describe noepay.cn-web >/dev/null 2>&1; then
        # 已有 PM2 进程：直接重启
        pm2 startOrRestart ecosystem.config.js --update-env || pm2 restart ecosystem.config.js
    else
        # 首次接管：清理旧的 nohup 进程后由 PM2 管理
        echo -e "${YELLOW}[i] 首次接管，清理旧进程...${NC}"
        pkill -f 'nova-key-1.0.0-SNAPSHOT.jar' 2>/dev/null || true
        pkill -f 'next start -p 3001' 2>/dev/null || true
        sleep 2
        pm2 start ecosystem.config.js
    fi
    pm2 save
    echo -e "${GREEN}[OK] PM2 已接管进程${NC}"
else
    echo -e "${RED}[X] PM2 未安装，请执行: npm install -g pm2${NC}"
    exit 1
fi

# ═══════ Step 6: 等待服务就绪并验证 ═══════
echo -e "${YELLOW}[6] 等待服务就绪并验证...${NC}"
# Spring Boot 启动 + Next.js 冷启动需要时间，轮询等待（最长 90 秒），
# 避免固定 sleep 后 curl 到启动中的服务误报 000、误以为更新失败
API_OK=0; WEB_OK=0
for i in $(seq 1 30); do
    if [ "$API_OK" != 1 ]; then
        code=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8083/api/products || echo "000")
        { [ "$code" = "200" ] || [ "$code" = "401" ]; } && API_OK=1
    fi
    if [ "$WEB_OK" != 1 ]; then
        code=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:3001 || echo "000")
        [ "$code" != "000" ] && [ "$code" != "502" ] && [ "$code" != "503" ] && WEB_OK=1
    fi
    [ "$API_OK" = 1 ] && [ "$WEB_OK" = 1 ] && break
    echo -e "${YELLOW}[i] 等待服务启动... (${i}/30)  api=$([ "$API_OK" = 1 ] && echo OK || echo 启动中)  web=$([ "$WEB_OK" = 1 ] && echo OK || echo 启动中)${NC}"
    sleep 3
done
echo -e "${GREEN}[i] 后端(8083): $([ "$API_OK" = 1 ] && echo 正常 || echo 未就绪)  前端(3001): $([ "$WEB_OK" = 1 ] && echo 正常 || echo 未就绪)${NC}"
if [ "$API_OK" != 1 ] || [ "$WEB_OK" != 1 ]; then
    echo -e "${RED}[X] 服务未在 90 秒内就绪！请查看日志定位问题:${NC}"
    echo -e "${RED}    后端: pm2 logs noepay.cn-api --lines 50 --nostream${NC}"
    echo -e "${RED}    前端: pm2 logs noepay.cn-web --lines 50 --nostream${NC}"
    exit 1
fi

# ═══════ Step 7: Nginx 自愈与公网验证 ═══════
echo -e "${YELLOW}[7] Nginx 配置自愈与公网验证...${NC}"
NGINX_CONF="/www/server/panel/vhost/nginx/noepay.cn.conf"
if [ -f "$NGINX_CONF" ]; then
    # 备份当前配置（时间戳后缀，不覆盖旧备份）
    cp "$NGINX_CONF" "$NGINX_CONF.bak.$(date +%s)" 2>/dev/null || true
    # 1) 清理粘贴混入的反引号（会导致 80→443 跳转地址变成坏 URL，浏览器报错/000）
    if grep -q '`' "$NGINX_CONF"; then
        echo -e "${YELLOW}[i] 清理 Nginx 配置中的反引号...${NC}"
        sed -i 's/`//g' "$NGINX_CONF"
    else
        echo -e "${GREEN}[i] Nginx 配置无反引号${NC}"
    fi
    # 2) 检查反代配置完整性（location / → 3001, location /api/ → 8083）
    if ! grep -q 'proxy_pass http://127.0.0.1:3001' "$NGINX_CONF" || ! grep -q 'location /api/' "$NGINX_CONF"; then
        echo -e "${RED}[X] Nginx 反代配置缺失！请到宝塔面板检查站点 noepay.cn 的配置文件，需要包含:${NC}"
        echo -e "${RED}    location /     { proxy_pass http://127.0.0.1:3001; }${NC}"
        echo -e "${RED}    location /api/  { proxy_pass http://127.0.0.1:8083; }${NC}"
    else
        echo -e "${GREEN}[i] Nginx 反代配置完整${NC}"
    fi
    # 3) 配置测试通过则重载，失败提示（不破坏现有配置）
    if nginx -t >/dev/null 2>&1; then
        /etc/init.d/nginx reload >/dev/null 2>&1 || systemctl reload nginx >/dev/null 2>&1 || true
        echo -e "${GREEN}[i] Nginx 已重载${NC}"
    else
        echo -e "${RED}[X] Nginx 配置语法错误，未重载！请查看: nginx -t${NC}"
    fi
else
    echo -e "${YELLOW}[i] 未找到 Nginx 站点配置 ${NGINX_CONF}，跳过自愈（请确认 Nginx 站点名是否为 noepay.cn）${NC}"
fi
# 4) 公网验证（从服务器访问域名，200/301/302 视为正常）
sleep 2
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" https://noepay.cn/ || echo "000")
echo -e "${YELLOW}[i] 公网首页: ${HTTP_CODE}${NC}"
if [ "$HTTP_CODE" != "200" ] && [ "$HTTP_CODE" != "301" ] && [ "$HTTP_CODE" != "302" ]; then
    echo -e "${RED}[X] 公网访问异常（HTTP ${HTTP_CODE}），请检查:${NC}"
    echo -e "${RED}    nginx -t;  pm2 logs noepay.cn-web --lines 30 --nostream${NC}"
else
    echo -e "${GREEN}[OK] 公网访问正常${NC}"
fi

cd "$PROJECT_DIR"
echo ""
echo -e "${CYAN}==========================================${NC}"
echo -e "${GREEN}  更新完成！${NC}"
echo -e "${CYAN}==========================================${NC}"
echo -e "${YELLOW}更新日志:${NC}"
git log --oneline -5
echo ""
echo -e "${YELLOW}服务状态:${NC}"
pm2 list
echo ""
echo -e "${YELLOW}常用命令:${NC}"
echo "  - 查看日志:        pm2 logs noepay.cn-api / noepay.cn-web"
echo "  - 重启前端:        pm2 restart noepay.cn-web"
echo "  - 重启后端:        pm2 restart noepay.cn-api"
echo "  - 服务器重启后:    pm2 start ecosystem.config.js"
echo ""
