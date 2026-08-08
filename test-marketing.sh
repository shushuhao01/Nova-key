#!/bin/bash
# 测试营销接口并输出异常堆栈
cd /www/wwwroot/nova-key

# 修复 start-api.sh 的反引号 bug 和日志覆盖问题
sed -i 's|APP_BASE_URL= `https://noepay.cn`|APP_BASE_URL=https://noepay.cn|g' start-api.sh 2>/dev/null
sed -i 's|> /www/wwwroot/nova-key/logs/api.log|>> /www/wwwroot/nova-key/logs/api.log|g' start-api.sh 2>/dev/null

# 杀掉所有后端进程
pkill -f 'nova-key.*jar' 2>/dev/null
sleep 3

# 清空旧日志，启动后端
> /www/wwwroot/nova-key/logs/api.log
bash start-api.sh
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
    exit 1
fi

# 测试营销接口
echo ""
echo "=== 营销接口响应 ==="
curl -s "http://127.0.0.1:8083/api/admin/marketing/coupons?page=1&page_size=10" \
    -H "Authorization: Bearer $TOKEN"
echo ""

# 看异常堆栈
echo ""
echo "=== 异常堆栈 ==="
grep -A 30 "Unexpected error" /www/wwwroot/nova-key/logs/api.log

# 如果没找到 Unexpected error，看最后 50 行
echo ""
echo "=== 日志最后 50 行 ==="
tail -n 50 /www/wwwroot/nova-key/logs/api.log
