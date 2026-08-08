#!/bin/bash
# 测试营销接口并输出异常堆栈（使用唯一日志文件避免被宝塔守护进程覆盖）
cd /www/wwwroot/nova-key

# 杀掉所有后端进程
pkill -f 'nova-key.*jar' 2>/dev/null
sleep 5

# 用唯一的日志文件名启动后端（避免被宝塔守护进程的日志覆盖）
LOGFILE="/www/wwwroot/nova-key/logs/api-test-$$.log"
cd /www/wwwroot/nova-key/apps/api
export DB_URL=jdbc:postgresql://127.0.0.1:5432/novakey
export DB_USERNAME=novakey
export DB_PASSWORD='AjfXCMiMkBpTDc2d'
export APP_BASE_URL=https://noepay.cn
export JWT_SECRET=$(openssl rand -base64 48)
export TZ=Asia/Shanghai
nohup java -Duser.timezone=Asia/Shanghai -jar target/nova-key-1.0.0-SNAPSHOT.jar --server.port=8083 > "$LOGFILE" 2>&1 &
API_PID=$!
echo "后端 PID: $API_PID"
echo "日志文件: $LOGFILE"
echo "等待后端启动..."
sleep 15

# 确认进程
echo "=== 后端进程 ==="
ps -eo pid,lstart,cmd | grep 'nova-key.*jar' | grep -v grep

# 登录
echo "=== 登录 ==="
LOGIN_RESP=$(curl -s -X POST http://127.0.0.1:8083/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"account":"admin","password":"admin123"}')
echo "$LOGIN_RESP" | head -c 200
echo ""
TOKEN=$(echo "$LOGIN_RESP" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
    echo "登录失败！"
    echo "=== 日志最后 30 行 ==="
    tail -n 30 "$LOGFILE"
    exit 1
fi

# 测试营销接口
echo ""
echo "=== 营销接口响应 ==="
curl -s "http://127.0.0.1:8083/api/admin/marketing/coupons?page=1&page_size=10" \
    -H "Authorization: Bearer $TOKEN"
echo ""

# 看异常堆栈（从唯一日志文件中查找）
echo ""
echo "=== 异常堆栈 ==="
grep -A 30 "Unexpected error" "$LOGFILE"

# 如果没找到 Unexpected error，看日志最后 50 行
echo ""
echo "=== 日志最后 50 行 ==="
tail -n 50 "$LOGFILE"
