#!/bin/bash
# ════════════════════════════════════════════════════════════════
# Nova key 一键更新脚本
# 用法：bash update.sh
# 流程：检查配置 → 拉取代码 → 后端打包 → 前端构建 → PM2 重启 → 验证
# ════════════════════════════════════════════════════════════════
set -e
# 管道中任一命令失败即整体失败（配合 tee 记录日志后判断 mvn/pnpm 真实退出码）
set -o pipefail

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
    _val=$(grep -E "^${_var}=" .env | head -1 | cut -d= -f2- || true)
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
mkdir -p "$PROJECT_DIR/logs"
cd "$PROJECT_DIR/apps/api"
# 完整输出落盘 logs/maven-build.log，失败时自动显示 ERROR 摘要
if ! mvn -DskipTests package 2>&1 | tee "$PROJECT_DIR/logs/maven-build.log"; then
    echo -e "${RED}[X] Maven 打包失败！错误摘要:${NC}"
    grep -E '\[ERROR\]|BUILD FAILURE|error:' "$PROJECT_DIR/logs/maven-build.log" | tail -n 40 || true
    echo -e "${RED}    完整日志: cat logs/maven-build.log${NC}"
    exit 1
fi
echo -e "${GREEN}[OK] 后端打包完成${NC}"

# ═══════ Step 4: 前端依赖 + 构建 ═══════
echo -e "${YELLOW}[4] 前端构建 (pnpm)...${NC}"
cd "$PROJECT_DIR"
# 依赖安装失败时直接恢复 web 进程，避免网页停在不可用状态
if ! pnpm install 2>&1 | tee "$PROJECT_DIR/logs/pnpm-install.log"; then
    echo -e "${RED}[X] pnpm install 失败！错误摘要:${NC}"
    grep -iE 'error|ERR_|Failed|npm error' "$PROJECT_DIR/logs/pnpm-install.log" | tail -n 30 || true
    echo -e "${RED}    完整日志: cat logs/pnpm-install.log${NC}"
    pm2 restart noepay.cn-web 2>/dev/null || true
    exit 1
fi
# 备份旧构建产物，构建失败时可回滚（防止网页长期打不开）
[ -d apps/web/.next ] && { rm -rf apps/web/.next.bak; cp -r apps/web/.next apps/web/.next.bak; }
# 构建前先停前端进程，避免覆盖 .next 目录时写冲突
pm2 stop noepay.cn-web 2>/dev/null || pkill -f 'next start -p 3001' 2>/dev/null || true
cd apps/web
# 完整输出落盘 logs/web-build.log，失败时自动显示错误尾部（避免被刷屏截断看不到真实报错）
if ! env -u NODE_OPTIONS BACKEND_URL=http://127.0.0.1:8083 pnpm build 2>&1 | tee "$PROJECT_DIR/logs/web-build.log"; then
    echo -e "${RED}[X] 前端构建失败！回滚旧版本并恢复 web 进程...${NC}"
    [ -d .next.bak ] && { rm -rf .next; mv .next.bak .next; }
    pm2 restart noepay.cn-web 2>/dev/null || true
    echo -e "${RED}    构建错误摘要（最后 40 行）:${NC}"
    tail -n 40 "$PROJECT_DIR/logs/web-build.log" || true
    echo -e "${RED}    完整日志: cat logs/web-build.log${NC}"
    exit 1
fi
rm -rf .next.bak
echo -e "${GREEN}[OK] 前端构建完成${NC}"

# ═══════ Step 5: PM2 启动/重启 ═══════
echo -e "${YELLOW}[5] PM2 启动/重启进程...${NC}"
cd "$PROJECT_DIR"
if command -v pm2 >/dev/null 2>&1; then
    # 先清理旧的 nohup/裸进程，避免端口冲突
    pkill -f 'nova-key-1.0.0-SNAPSHOT.jar' 2>/dev/null || true
    pkill -f 'next start -p 3001' 2>/dev/null || true
    # 删除旧 PM2 进程后全新启动（startOrRestart 对 stopped 进程行为不可靠，统一重建最稳）
    pm2 delete noepay.cn-api >/dev/null 2>&1 || true
    pm2 delete noepay.cn-web >/dev/null 2>&1 || true
    sleep 1
    pm2 start ecosystem.config.js --update-env
    pm2 save
    echo -e "${GREEN}[OK] PM2 已接管进程${NC}"
else
    echo -e "${RED}[X] PM2 未安装，请执行: npm install -g pm2${NC}"
    exit 1
fi

# Step 5.1: 清理 Nginx 页面缓存（宝塔默认 proxy_cache 会缓存页面 HTML 一年，
#           更新代码后用户仍命中旧缓存 → 页面/按钮"时有时无"。必须每次构建后清空。）
echo -e "${YELLOW}[5.1] 清理 Nginx 页面缓存...${NC}"
if [ -d /www/server/nginx/proxy_cache_dir ]; then
    rm -rf /www/server/nginx/proxy_cache_dir/* 2>/dev/null || true
    echo -e "${GREEN}[i] 已清空 /www/server/nginx/proxy_cache_dir${NC}"
else
    echo -e "${YELLOW}[i] 未发现 proxy_cache_dir，跳过${NC}"
fi
# 强制关闭本站点的 proxy_cache（http 层全局开启，location 未显式关闭就继承）
# 在站点配置首个 server 块内注入 proxy_cache off，防止页面被共享缓存
NGINX_CONF_FOR_CACHE="/www/server/panel/vhost/nginx/noepay.cn.conf"
if [ -f "$NGINX_CONF_FOR_CACHE" ] && ! grep -q 'proxy_cache off' "$NGINX_CONF_FOR_CACHE"; then
    sed -i '0,/listen[[:space:]]*443/s//proxy_cache off;\n    listen 443/' "$NGINX_CONF_FOR_CACHE" 2>/dev/null || true
    if grep -q 'proxy_cache off' "$NGINX_CONF_FOR_CACHE" && nginx -t >/dev/null 2>&1; then
        /etc/init.d/nginx reload >/dev/null 2>&1 || systemctl reload nginx >/dev/null 2>&1 || true
        echo -e "${GREEN}[i] 已在站点配置注入 proxy_cache off（页面不再被缓存）${NC}"
    fi
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
        # 严格校验：首页必须返回 200（404 说明 .next 产物损坏，绝不能误判为正常）
        [ "$code" = "200" ] && WEB_OK=1
    fi
    [ "$API_OK" = 1 ] && [ "$WEB_OK" = 1 ] && break
    echo -e "${YELLOW}[i] 等待服务启动... (${i}/30)  api=$([ "$API_OK" = 1 ] && echo OK || echo 启动中)  web=$([ "$WEB_OK" = 1 ] && echo OK || echo 启动中)${NC}"
    sleep 3
done
echo -e "${GREEN}[i] 后端(8083): $([ "$API_OK" = 1 ] && echo 正常 || echo 未就绪)  前端(3001): $([ "$WEB_OK" = 1 ] && echo 正常 || echo 未就绪)${NC}"
if [ "$API_OK" != 1 ] || [ "$WEB_OK" != 1 ]; then
    echo -e "${RED}[X] 服务未在 90 秒内就绪！自动打印最近日志定位问题:${NC}"
    echo -e "${CYAN}────── 后端日志(noepay.cn-api) ──────${NC}"
    pm2 logs noepay.cn-api --lines 100 --nostream 2>/dev/null || true
    echo -e "${CYAN}────── 前端日志(noepay.cn-web) ──────${NC}"
    pm2 logs noepay.cn-web --lines 30 --nostream 2>/dev/null || true
    echo -e "${RED}    后端日志已打印在上方，请根据报错定位（种子数据由本脚本 psql 步骤执行，不阻塞后端启动）${NC}"
    exit 1
fi

# ═══════ Step 6.5: 初始化种子数据 (data.sql，幂等) ═══════
# 表结构由后端 ddl-auto:update 启动时自动创建/补字段；data.sql 只插入种子行，
# 全部 WHERE NOT EXISTS 可重复执行。放在后端就绪后执行，保证表已存在。
echo -e "${YELLOW}[6.5] 初始化种子数据 (data.sql)...${NC}"
DB_URL_VAL=$(grep -E '^DB_URL=' .env | head -1 | cut -d= -f2- || true)
DB_USER_VAL=$(grep -E '^DB_USERNAME=' .env | head -1 | cut -d= -f2- || true)
DB_PASS_VAL=$(grep -E '^DB_PASSWORD=' .env | head -1 | cut -d= -f2- || true)
# 解析 JDBC URL: jdbc:postgresql://HOST:PORT/DBNAME
DB_HOST=$(echo "$DB_URL_VAL" | sed -nE 's|jdbc:postgresql://([^:/]+)(:[0-9]+)?/.*|\1|p')
DB_PORT=$(echo "$DB_URL_VAL" | sed -nE 's|jdbc:postgresql://[^:]+:([0-9]+)/.*|\1|p')
[ -z "$DB_PORT" ] && DB_PORT=5432
DB_NAME=$(echo "$DB_URL_VAL" | sed -nE 's|jdbc:postgresql://[^/]+/([^?]+).*|\1|p')
if [ -z "$DB_HOST" ] || [ -z "$DB_NAME" ]; then
    echo -e "${RED}[X] 无法解析 DB_URL（$DB_URL_VAL），请检查 .env 中 DB_URL 格式: jdbc:postgresql://host:port/dbname${NC}"
    exit 1
fi
# 定位 psql（宝塔安装时完整路径优先）
PSQL_BIN=""
for p in psql /www/server/pgsql/bin/psql /usr/local/pgsql/bin/psql /usr/bin/psql; do
    if command -v "$p" >/dev/null 2>&1; then PSQL_BIN=$(command -v "$p"); break; fi
    if [ -x "$p" ]; then PSQL_BIN="$p"; break; fi
done
if [ -z "$PSQL_BIN" ]; then
    echo -e "${RED}[X] 未找到 psql，请安装 postgresql-client 或修正 PATH 后重新执行${NC}"
    exit 1
fi
export PGPASSWORD="$DB_PASS_VAL"
"$PSQL_BIN" -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER_VAL" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$PROJECT_DIR/apps/api/src/main/resources/data.sql" || {
    echo -e "${RED}[X] 种子数据执行失败！请检查上方 psql 报错（缺依赖/权限/表结构不一致等）${NC}"
    exit 1
}
unset PGPASSWORD
echo -e "${GREEN}[OK] 种子数据已就绪${NC}"

# ═══════ Step 7: Nginx 自愈与公网/SSL 验证 ═══════
echo -e "${YELLOW}[7] Nginx 配置自愈与公网/SSL 验证...${NC}"
NGINX_CONF="/www/server/panel/vhost/nginx/noepay.cn.conf"
NGINX_FAIL=0

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
        NGINX_FAIL=1
    else
        echo -e "${GREEN}[i] Nginx 反代配置完整${NC}"
    fi
    # 2.1) 修复宝塔默认 js/css 静态缓存 location（会导致 /_next/static 404 → 首页白屏）
    #      宝塔面板创建站点时会注入 `location ~ .*\.(js|css|gif|jpg|...)$` 正则缓存规则，
    #      正则 location 优先级高于普通前缀 location，会把 Next.js 的 /_next/static 全部
    #      拦截成 404。修复方式：注入 `location ^~ /_next/`（^~ 前缀匹配优先于正则 location），
    #      让静态资源始终反代到 Next.js。不删除任何原规则，注入后 nginx -t 验证，失败自动回滚备份。
    if ! grep -q 'location ^~ /_next/' "$NGINX_CONF"; then
        if grep -qE 'location[[:space:]]+~[[:space:]]*\.\*\\\.(js|css|gif|jpg|jpeg|png|bmp|swf|ico)' "$NGINX_CONF"; then
            echo -e "${YELLOW}[i] 检测到宝塔默认静态缓存 location（会拦截 /_next/static 导致 404 白屏），注入修复规则...${NC}"
            # 注入到包含 listen 443 ssl 的 server 块（80 块只做跳转，注入无效）；找不到 443 块时回退到第一个 server 块。
            # 必须用【花括号深度】定位 server 块的结束行：直接匹配行首 } 会把 location 块的 } 当成 server 块结束，
            # 导致 location 被注入到 location 内部（location 不能嵌套）→ nginx -t 语法错误 → 回滚 → 404 依旧复现。
            awk 'BEGIN{injected=0; depth=0; in443=0; srv=0}
                {
                    if (!injected) {
                        if (srv==1 && $0 ~ /listen[[:space:]]+443/) in443=1
                        n_open=gsub(/\{/, "{", $0)
                        n_close=gsub(/\}/, "}", $0)
                        depth += n_open - n_close
                        if ($0 ~ /^[[:space:]]*server[[:space:]]*\{/) { srv=1; in443=0 }
                        if (srv==1 && depth==0 && in443==1) {
                            print "    location ^~ /_next/ { proxy_pass http://127.0.0.1:3001; }"
                            injected=1
                            srv=0
                        }
                    }
                    print
                }' "$NGINX_CONF" > "$NGINX_CONF.tmp" && mv "$NGINX_CONF.tmp" "$NGINX_CONF"
            if grep -q 'location ^~ /_next/' "$NGINX_CONF"; then
                echo -e "${GREEN}[i] /_next/ 静态资源修复规则已注入（443 server 块）${NC}"
            else
                # 未匹配到 443 块（如证书未启用/配置结构不同），回退注入到第一个 server 块
                awk 'BEGIN{inserted=0} {print} /^[[:space:]]*server[[:space:]]*\{/ && !inserted {inserted=1; print "    location ^~ /_next/ { proxy_pass http://127.0.0.1:3001; }"}' "$NGINX_CONF" > "$NGINX_CONF.tmp" && mv "$NGINX_CONF.tmp" "$NGINX_CONF"
                echo -e "${YELLOW}[i] 未检测到 443 server 块，已回退注入到第一个 server 块${NC}"
            fi
            if nginx -t >/dev/null 2>&1; then
                echo -e "${GREEN}[i] /_next/ 静态资源修复规则已生效${NC}"
            else
                echo -e "${RED}[X] 注入修复规则后 nginx -t 失败，自动回滚最新备份${NC}"
                mv "$NGINX_CONF" "$NGINX_CONF.tmp.broken"
                LATEST_BAK=$(ls -t "$NGINX_CONF".bak.* 2>/dev/null | head -1 || true)
                [ -n "$LATEST_BAK" ] && cp "$LATEST_BAK" "$NGINX_CONF"
                NGINX_FAIL=1
            fi
        fi
    fi
    # 3) SSL 证书配置检查（"连接不是专用连接"= 证书无效/过期/域名不匹配，必须前置拦截）
    SSL_CERT=$(grep -oE 'ssl_certificate[[:space:]]+[^;]+;' "$NGINX_CONF" | head -1 | awk '{print $2}' | tr -d ';' || true)
    SSL_CERT_KEY=$(grep -oE 'ssl_certificate_key[[:space:]]+[^;]+;' "$NGINX_CONF" | head -1 | awk '{print $2}' | tr -d ';' || true)
    # 展开可能的变量（如 $server_root）
    [ -n "$SSL_CERT" ] && SSL_CERT=$(eval echo "$SSL_CERT")
    [ -n "$SSL_CERT_KEY" ] && SSL_CERT_KEY=$(eval echo "$SSL_CERT_KEY")
    if [ -n "$SSL_CERT" ] && [ -f "$SSL_CERT" ]; then
        # 证书过期时间检查（取结束时间与当前时间比较，剩余 <30 天告警、已过期则失败）
        CERT_END=$(openssl x509 -enddate -noout -in "$SSL_CERT" 2>/dev/null | cut -d= -f2 || true)
        if [ -n "$CERT_END" ]; then
            END_EPOCH=$(date -d "$CERT_END" +%s 2>/dev/null)
            NOW_EPOCH=$(date +%s)
            DAYS_LEFT=$(( (END_EPOCH - NOW_EPOCH) / 86400 ))
            if [ "$END_EPOCH" -lt "$NOW_EPOCH" ]; then
                echo -e "${RED}[X] SSL 证书已过期（${CERT_END}）！请在宝塔面板 → 网站 → SSL 中续签证书，否则浏览器报「连接不是专用连接」${NC}"
                NGINX_FAIL=1
            elif [ "$DAYS_LEFT" -lt 30 ]; then
                echo -e "${YELLOW}[i] SSL 证书剩余 ${DAYS_LEFT} 天（${CERT_END}），请在宝塔面板续签${NC}"
            else
                echo -e "${GREEN}[i] SSL 证书有效期正常（剩余 ${DAYS_LEFT} 天）${NC}"
            fi
        fi
        # 证书域名匹配检查：SAN 必须精确包含裸域 noepay.cn。
        # 只含 www.noepay.cn 时（子串匹配会误判通过），浏览器访问 https://noepay.cn 仍报「连接不是专用连接」。
        if openssl x509 -noout -ext subjectAltName -in "$SSL_CERT" 2>/dev/null | grep -qE 'DNS:noepay\.cn([,]|$)'; then
            echo -e "${GREEN}[i] SSL 证书域名匹配（SAN 含裸域 noepay.cn）${NC}"
        else
            CERT_SUBJECT=$(openssl x509 -noout -subject -in "$SSL_CERT" 2>/dev/null || true)
            echo -e "${RED}[X] SSL 证书 SAN 未覆盖裸域 noepay.cn（当前证书: ${CERT_SUBJECT:-未知}）！${NC}"
            echo -e "${RED}    浏览器访问 https://noepay.cn 会报「你的连接不是专用连接」。${NC}"
            echo -e "${RED}    修复: 宝塔面板 → 网站 → noepay.cn → SSL，重新申请/部署同时覆盖 noepay.cn 与 www.noepay.cn 的证书${NC}"
            NGINX_FAIL=1
        fi
    else
        echo -e "${RED}[X] 未找到 SSL 证书文件（${SSL_CERT:-未配置}）！请到宝塔面板 → 网站 → SSL 中配置/部署证书${NC}"
        NGINX_FAIL=1
    fi
    # 4) 配置测试通过则重载，失败提示（不破坏现有配置）
    if nginx -t >/dev/null 2>&1; then
        /etc/init.d/nginx reload >/dev/null 2>&1 || systemctl reload nginx >/dev/null 2>&1 || true
        echo -e "${GREEN}[i] Nginx 已重载${NC}"
    else
        echo -e "${RED}[X] Nginx 配置语法错误，未重载！请查看: nginx -t${NC}"
        NGINX_FAIL=1
    fi
else
    echo -e "${RED}[X] 未找到 Nginx 站点配置 ${NGINX_CONF}（请确认宝塔站点名是否为 noepay.cn）${NC}"
    NGINX_FAIL=1
fi

# 4.5) 多 A 记录检测：域名解析出多个 IP 时，非本机的节点若证书异常（自签名/过期/不匹配），
#      DNS 轮询会让浏览器随机连到坏节点 → 随机报「你的连接不是专用连接」/ 时好时坏。
#      这是"网站久不久出问题"的最常见根因（本机证书正常但公网随机失败）。
LOCAL_IP=$(curl -s --connect-timeout 5 ifconfig.me 2>/dev/null || echo "")
if [ -n "$LOCAL_IP" ]; then
    for _ip in $(dig +short noepay.cn A 2>/dev/null | sort -u); do
        [ "$_ip" = "$LOCAL_IP" ] && continue
        _verify=$(echo | timeout 5 openssl s_client -connect "$_ip":443 -servername noepay.cn 2>/dev/null | grep 'Verify return code' | head -1)
        case "$_verify" in
            *"0 (ok)"*)
                echo -e "${GREEN}[i] 额外 A 记录 $_ip 证书正常${NC}" ;;
            *)
                echo -e "${RED}[X] 额外 A 记录 $_ip 证书异常（${_verify:-无法连接}）！DNS 轮询会随机让浏览器报「连接不是专用连接」${NC}"
                echo -e "${RED}    修复: 到域名 DNS 服务商删除 $_ip 这条 A 记录，只保留本机 IP $LOCAL_IP${NC}"
                NGINX_FAIL=1 ;;
        esac
    done
fi

# 5) 公网 HTTPS 验证（证书错误时 curl 会失败，需先区分证书问题还是连接问题）
#    重试 3 次排除瞬态（nginx reload 后短暂不可用 / 网络抖动导致误报）
sleep 2
HTTPS_OK=0
for _t in 1 2 3; do
    if curl -s -o /dev/null -w "" --connect-timeout 8 https://noepay.cn/ >/dev/null 2>&1; then
        HTTPS_OK=1
        break
    fi
    sleep 3
done
if [ "$HTTPS_OK" = "1" ]; then
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 8 https://noepay.cn/ || echo "000")
    echo -e "${YELLOW}[i] 公网首页(https): ${HTTP_CODE}${NC}"
    if [ "$HTTP_CODE" != "200" ] && [ "$HTTP_CODE" != "301" ] && [ "$HTTP_CODE" != "302" ]; then
        echo -e "${RED}[X] 公网 HTTPS 返回 HTTP ${HTTP_CODE}，请检查: nginx -t; pm2 logs noepay.cn-web --lines 30 --nostream${NC}"
        NGINX_FAIL=1
    else
        echo -e "${GREEN}[OK] 公网 HTTPS 访问正常${NC}"
    fi
else
    # 证书校验失败：先忽略证书测试连通性，区分「证书错误」与「443 端口不通」
    if curl -sk -o /dev/null -w "" --connect-timeout 8 https://noepay.cn/ >/dev/null 2>&1; then
        CERT_SUBJECT=$(echo | timeout 5 openssl s_client -connect noepay.cn:443 -servername noepay.cn 2>/dev/null | openssl x509 -noout -subject 2>/dev/null || true)
        echo -e "${RED}[X] HTTPS 端口连通但证书校验失败（浏览器报「你的连接不是专用连接」）！${NC}"
        echo -e "${RED}    当前证书: ${CERT_SUBJECT:-未知}${NC}"
        echo -e "${RED}    原因与修复: 证书未覆盖裸域 noepay.cn（如只签给 www.noepay.cn）。请到宝塔面板 → 网站 → noepay.cn → SSL，重新申请/部署同时覆盖 noepay.cn 与 www.noepay.cn 的证书${NC}"
        NGINX_FAIL=1
    else
        echo -e "${RED}[X] 公网 HTTPS 443 端口不通（连接失败）！请检查阿里云安全组是否放行 443，或 nginx 是否监听 443${NC}"
        NGINX_FAIL=1
    fi
fi

# 5.1) 静态资源检查：从首页 HTML 提取真实引用的 /_next/static 资源并验证可访问。
#      注意：直接 curl /_next/static/（目录本身）Next.js 返回 404，会误报失败；
#      必须拿页面真实引用的资源文件验证。资源 404 → 宝塔静态缓存 location 拦截或构建产物损坏 → 首页白屏。
STATIC_HTML=$(curl -s --connect-timeout 8 https://noepay.cn/ || echo "")
STATIC_ASSET=$(echo "$STATIC_HTML" | grep -oE '/_next/static/[A-Za-z0-9_./-]+' | head -1 || true)
if [ -n "$STATIC_ASSET" ]; then
    ASSET_CODE=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 8 "https://noepay.cn${STATIC_ASSET}" || echo "000")
    echo -e "${YELLOW}[i] 静态资源(${STATIC_ASSET}): ${ASSET_CODE}${NC}"
    if [ "$ASSET_CODE" != "200" ] && [ "$ASSET_CODE" != "301" ] && [ "$ASSET_CODE" != "302" ] && [ "$ASSET_CODE" != "304" ]; then
        echo -e "${RED}[X] 静态资源不可访问（HTTP ${ASSET_CODE}）→ 首页会白屏！${NC}"
        echo -e "${RED}    原因通常是宝塔面板默认的 js/css 静态缓存 location 拦截（本脚本已尝试自动修复），${NC}"
        echo -e "${RED}    或前端构建产物损坏。请检查 Nginx 配置与 pnpm build 日志${NC}"
        NGINX_FAIL=1
    else
        echo -e "${GREEN}[OK] 静态资源可访问${NC}"
    fi
else
    echo -e "${RED}[X] 首页 HTML 中未提取到任何 /_next/static 资源引用（页面已白屏或无法访问）${NC}"
    NGINX_FAIL=1
fi

# 6) 公网 HTTP 验证（HTTP 应能跳转到 HTTPS；跳转异常同样导致浏览器报错）
HTTP_CODE_HTTP=$(curl -s -o /dev/null -w "%{http_code}" http://noepay.cn/ || echo "000")
echo -e "${YELLOW}[i] 公网首页(http): ${HTTP_CODE_HTTP}${NC}"
if [ "$HTTP_CODE_HTTP" != "301" ] && [ "$HTTP_CODE_HTTP" != "302" ] && [ "$HTTP_CODE_HTTP" != "200" ]; then
    echo -e "${RED}[X] 公网 HTTP 访问异常（HTTP ${HTTP_CODE_HTTP}），请检查 Nginx 80 端口配置${NC}"
    NGINX_FAIL=1
fi

if [ "$NGINX_FAIL" = "1" ]; then
    echo -e "${RED}[X] 公网/SSL 验证未通过！本次更新将被标记为失败，请按上方提示修复后重新执行 bash update.sh${NC}"
    exit 1
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
