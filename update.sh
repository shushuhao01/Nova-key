#!/bin/bash
# Nova key 一键部署脚本：拉取代码 → 编译后端 → 重启后端 → 构建前端 → 重启前端 → 验证
set -e
cd /www/wwwroot/nova-key

echo "=== 1. 拉取最新代码 ==="
git pull
git log --oneline -1

echo ""
echo "=== 2. 修复 start-api.sh 中的 APP_BASE_URL 反引号 bug（如有）==="
if grep -q 'APP_BASE_URL= `' start-api.sh 2>/dev/null; then
    sed -i "s|export APP_BASE_URL= \`https://noepay.cn\`|export APP_BASE_URL=https://noepay.cn|" start-api.sh
    echo "已修复 APP_BASE_URL 反引号 bug"
else
    echo "无需修复"
fi

echo ""
echo "=== 3. 编译后端 ==="
cd /www/wwwroot/nova-key/apps/api
mvn -q package -DskipTests
echo "后端编译完成: $(ls -la target/nova-key-1.0.0-SNAPSHOT.jar | awk '{print $6, $7, $8}')"

echo ""
echo "=== 4. 杀掉旧后端进程 ==="
pkill -f 'nova-key.*jar' 2>/dev/null || true
sleep 3

echo "=== 5. 启动后端 ==="
cd /www/wwwroot/nova-key
bash start-api.sh
echo "等待后端启动..."
sleep 12

echo "=== 6. 验证后端 ==="
if ss -ltnp | grep -q 8083; then
    echo "✓ 后端 8083 端口已监听"
    ps -eo pid,lstart,cmd | grep 'nova-key.*jar' | grep -v grep
else
    echo "✗ 后端 8083 端口未监听！查看日志："
    tail -n 30 /www/wwwroot/nova-key/logs/api.log
    exit 1
fi

echo ""
echo "=== 7. 构建前端 ==="
cd /www/wwwroot/nova-key/apps/web
pnpm build 2>&1 | tail -n 5
echo "前端构建完成: $(ls -la .next/BUILD_ID | awk '{print $6, $7, $8}')"

echo ""
echo "=== 8. 重启前端 ==="
fuser -k 3001/tcp 2>/dev/null || true
sleep 5

echo "=== 9. 验证前端 ==="
if ss -ltnp | grep -q 3001; then
    echo "✓ 前端 3001 端口已监听（宝塔守护已拉起）"
    ss -ltnp | grep 3001
else
    echo "前端未自动拉起，手动启动..."
    cd /www/wwwroot/nova-key/apps/web
    nohup env BACKEND_URL=http://127.0.0.1:8083 node node_modules/next/dist/bin/next start -p 3001 >> /www/wwwroot/nova-key/logs/web.log 2>&1 &
    sleep 5
    ss -ltnp | grep 3001
fi

echo ""
echo "=== 10. 验证营销接口 ==="
TOKEN=$(curl -s -X POST http://127.0.0.1:8083/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"account":"admin","password":"admin123"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
if [ -z "$TOKEN" ]; then
    echo "⚠ 登录失败，尝试看返回内容："
    curl -s -X POST http://127.0.0.1:8083/api/auth/login \
        -H "Content-Type: application/json" \
        -d '{"account":"admin","password":"admin123"}'
else
    echo "✓ 登录成功"
    echo "营销优惠券列表接口测试："
    curl -s http://127.0.0.1:8083/api/admin/marketing/coupons \
        -H "Authorization: Bearer $TOKEN" | head -c 500
    echo ""
fi

echo ""
echo "=== 部署完成 ==="
echo "Git: $(git log --oneline -1)"
echo "后端 JAR: $(ls -la /www/wwwroot/nova-key/apps/api/target/nova-key-1.0.0-SNAPSHOT.jar | awk '{print $6, $7, $8}')"
echo "前端 BUILD_ID: $(ls -la /www/wwwroot/nova-key/apps/web/.next/BUILD_ID | awk '{print $6, $7, $8}')"
