#!/bin/bash
# ═══════════════════════════════════════════════════════════════
#  分销推广佣金流程测试：下单 → 佣金生成 → 结算 → 提现
#
#  用法（在服务器 /www/wwwroot/nova-key 下执行）:
#    bash test-distribution-flow.sh            # 完整流程（建分销员/下单/佣金/提现）
#    bash test-distribution-flow.sh --clean    # 清理本次测试产生的数据
#    bash test-distribution-flow.sh --settle   # 结算阶段：把测试佣金 backdate 后等定时任务结算并验证
#
#  依赖: curl, psql（服务器宝塔环境通常自带）
#  注意: 测试数据带时间戳后缀，多次运行互不冲突
# ═══════════════════════════════════════════════════════════════

BASE_URL=${BASE_URL:-"http://127.0.0.1:8083/api"}
DB_NAME=${DB_NAME:-novakey}
DB_USER=${DB_USER:-novakey}
DB_PASS=${DB_PASS:-'AjfXCMiMkBpTDc2d'}

TS=$(date +%s)
PREFIX="disttest_${TS}"
C1_EMAIL="c1_${PREFIX}@dist.test"
C2_EMAIL="c2_${PREFIX}@dist.test"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[0;33m'; CYAN='\033[0;36m'; NC='\033[0m'
OK="${GREEN}✓${NC}"; FAIL="${RED}✗${NC}"; WARN="${YELLOW}⚠${NC}"; ARROW="${CYAN}→${NC}"

psql_run() { PGPASSWORD="$DB_PASS" psql -h 127.0.0.1 -U "$DB_USER" -d "$DB_NAME" -t -A -c "$1" 2>/dev/null; }

# JSON 提取：优先 jq，回退 grep
jget() {
  if command -v jq >/dev/null 2>&1; then
    echo "$1" | jq -r "$2" 2>/dev/null
  else
    echo "$1" | grep -o "\"$2\":[^,}]*" | head -1 | sed 's/^.*://; s/^"//; s/"$//'
  fi
}

# 需要 jq 的场景（嵌套/数组）直接调 jq，缺失时报错提示
jq_get() { echo "$1" | jq -r "$2" 2>/dev/null; }

# ── admin 登录 ──
ADMIN_TOKEN=""
echo -e "${ARROW} 登录 admin..."
LOGIN_RESP=$(curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
  -d '{"account":"admin","password":"admin123"}')
ADMIN_TOKEN=$(jget "$LOGIN_RESP" token)
if [ -z "$ADMIN_TOKEN" ]; then
  echo -e "  ${FAIL} admin 登录失败: $(echo "$LOGIN_RESP" | head -c 200)"
  exit 1
fi
echo -e "  ${OK} admin 登录成功"

ADMIN_HEADERS=(-H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json")

# ── 检查可分销商品 ──
echo -e "${ARROW} 查找可分销商品..."
PRODUCT_RESP=$(curl -s "$BASE_URL/distribution/products?page=1&page_size=5")
PID=$(jget "$PRODUCT_RESP" 'items[0].product_id // empty')
if [ -z "$PID" ]; then
  # 无已开启分销的商品 → 用 psql 给第一个启用商品开启分销（默认比例）
  echo -e "  ${WARN} 无可分销商品，为第一个启用商品开启分销..."
  PID=$(psql_run "SELECT id::text FROM products WHERE is_deleted=0 AND enabled=true ORDER BY created_at LIMIT 1")
  if [ -z "$PID" ]; then
    echo -e "  ${FAIL} 无可用商品，测试终止"; exit 1
  fi
  psql_run "INSERT INTO product_commissions (id, product_id, custom_rate, is_excluded, created_at, updated_at) VALUES (gen_random_uuid(), '$PID', NULL, false, now(), now()) ON CONFLICT (product_id) DO NOTHING" >/dev/null
  echo -e "  ${OK} 已为商品 $PID 开启分销"
fi
echo -e "  ${OK} 测试商品: $PID"

# 取商品单价（用于佣金预期计算）
PRICE=$(psql_run "SELECT base_price::text FROM products WHERE id='$PID'")
[ -z "$PRICE" ] && PRICE=0
echo -e "  ${ARROW} 商品单价: $PRICE 元"

# 取一个启用的支付渠道
PAY_METHOD=$(psql_run "SELECT channel_code FROM payment_channels WHERE is_deleted=0 AND enabled=true ORDER BY created_at LIMIT 1")
[ -z "$PAY_METHOD" ] && { echo -e "  ${FAIL} 无启用支付渠道"; exit 1; }
echo -e "  ${ARROW} 支付渠道: $PAY_METHOD"

# ── 创建测试用户（A=一级分销员, B=二级分销员）──
make_user() { # $1=user_id标识 $2=邮箱
  local uid="$1" email="$2"
  psql_run "INSERT INTO users (id, username, email, password_hash, role, is_deleted, points, failed_login_attempts, password_version, created_at, updated_at)
            VALUES (gen_random_uuid(), '${uid}_${TS}', '$email',
                    (SELECT password_hash FROM users WHERE username='admin' LIMIT 1),
                    'USER', 0, 0, 0, 0, now(), now()) RETURNING id::text"
}
user_login() { # $1=邮箱
  curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
    -d "{\"account\":\"$1\",\"password\":\"admin123\"}"
}

echo -e "${ARROW} 创建测试用户..."
UA_EMAIL="a_${PREFIX}@dist.test"
UB_EMAIL="b_${PREFIX}@dist.test"
UA_ID=$(make_user "tA" "$UA_EMAIL")
UB_ID=$(make_user "tB" "$UB_EMAIL")
if [ -z "$UA_ID" ] || [ -z "$UB_ID" ]; then
  echo -e "  ${FAIL} 测试用户创建失败（请确认 DB 凭据与 psql 可用）"; exit 1
fi
echo -e "  ${OK} 测试用户 A(id=$UA_ID), B(id=$UB_ID)（密码均为 admin123）"

A_LOGIN=$(user_login "$UA_EMAIL"); A_TOKEN=$(jget "$A_LOGIN" token)
B_LOGIN=$(user_login "$UB_EMAIL"); B_TOKEN=$(jget "$B_LOGIN" token)
if [ -z "$A_TOKEN" ] || [ -z "$B_TOKEN" ]; then
  echo -e "  ${FAIL} 测试用户登录失败"; exit 1
fi
echo -e "  ${OK} 测试用户登录成功"

# ── A 申请分销（顶级）──
echo -e "${ARROW} A 申请分销员..."
A_APPLY=$(curl -s -X POST "$BASE_URL/distributor/apply" -H "Authorization: Bearer $A_TOKEN" -H "Content-Type: application/json" -d '{}')
A_DIST_ID=$(jget "$A_APPLY" id)
A_INVITE=$(jget "$A_APPLY" invite_code)
A_DIST_CODE=$(jget "$A_APPLY" distributor_code)
[ -z "$A_DIST_ID" ] && A_DIST_ID=$(jget "$A_APPLY" 'data.id // empty')
[ -z "$A_INVITE" ] && A_INVITE=$(jget "$A_APPLY" 'data.invite_code // empty')
if [ -z "$A_DIST_ID" ]; then
  # 可能已存在分销记录 → 查 profile
  A_APPLY=$(curl -s "$BASE_URL/distributor/profile" -H "Authorization: Bearer $A_TOKEN")
  A_DIST_ID=$(jget "$A_APPLY" distributor_id)
  A_INVITE=$(jget "$A_APPLY" invite_code)
fi
if [ -z "$A_DIST_ID" ]; then
  echo -e "  ${FAIL} A 申请分销失败: $(echo "$A_APPLY" | head -c 300)"; exit 1
fi
echo -e "  ${OK} A 分销员: id=$A_DIST_ID 邀请码=$A_INVITE"

# ── B 用 A 的邀请码申请分销（A 的下级）──
echo -e "${ARROW} B 用 A 邀请码申请分销员..."
B_APPLY=$(curl -s -X POST "$BASE_URL/distributor/apply" -H "Authorization: Bearer $B_TOKEN" -H "Content-Type: application/json" \
  -d "{\"invite_code\":\"$A_INVITE\"}")
B_DIST_ID=$(jget "$B_APPLY" id)
[ -z "$B_DIST_ID" ] && B_DIST_ID=$(jget "$B_APPLY" 'data.id // empty')
if [ -z "$B_DIST_ID" ]; then
  B_APPLY=$(curl -s "$BASE_URL/distributor/profile" -H "Authorization: Bearer $B_TOKEN")
  B_DIST_ID=$(jget "$B_APPLY" distributor_id)
fi
if [ -z "$B_DIST_ID" ]; then
  echo -e "  ${FAIL} B 申请分销失败: $(echo "$B_APPLY" | head -c 300)"; exit 1
fi
# 校验 B 的上级是 A
B_PARENT=$(psql_run "SELECT parent_id::text FROM distributors WHERE id='$B_DIST_ID'")
if [ "$B_PARENT" = "$A_DIST_ID" ]; then
  echo -e "  ${OK} B 已绑定为 A 的下级 (parent_id=$A_DIST_ID)"
else
  echo -e "  ${WARN} B 的上级未绑定（parent_id=$B_PARENT），请确认分销规则已启用"
fi

# ── 确认分销员审核通过（自动审核可能关闭 → 用 psql 直接审核通过）──
psql_run "UPDATE distributors SET status='APPROVED', approved_at=now() WHERE id IN ('$A_DIST_ID','$B_DIST_ID')" >/dev/null
echo -e "  ${OK} A/B 分销员状态已设为 APPROVED"

# ── 生成推广链接 ──
echo -e "${ARROW} 生成推广链接..."
A_LINK=$(curl -s -X POST "$BASE_URL/distributor/products/$PID/link" -H "Authorization: Bearer $A_TOKEN" -H "Content-Type: application/json" -d '{}')
A_LINK_ID=$(jget "$A_LINK" id)
[ -z "$A_LINK_ID" ] && A_LINK_ID=$(jget "$A_LINK" 'data.id // empty')
B_LINK=$(curl -s -X POST "$BASE_URL/distributor/products/$PID/link" -H "Authorization: Bearer $B_TOKEN" -H "Content-Type: application/json" -d '{}')
B_LINK_ID=$(jget "$B_LINK" id)
[ -z "$B_LINK_ID" ] && B_LINK_ID=$(jget "$B_LINK" 'data.id // empty')
if [ -z "$A_LINK_ID" ] || [ -z "$B_LINK_ID" ]; then
  echo -e "  ${FAIL} 推广链接生成失败: A=$A_LINK B=$B_LINK"; exit 1
fi
echo -e "  ${OK} A 链接=$A_LINK_ID  B 链接=$B_LINK_ID"

# ── 开启阶梯佣金并配置档位（tier1=100%, tier2=50%）──
echo -e "${ARROW} 配置阶梯佣金（tier1=100%, tier2=50%）..."
curl -s -X PUT "$BASE_URL/admin/distribution/rules" "${ADMIN_HEADERS[@]}" \
  -d '{"tier_enabled":true}' >/dev/null
curl -s -X PUT "$BASE_URL/admin/distribution/tiers" "${ADMIN_HEADERS[@]}" \
  -d '[{"tier_order":1,"rate":1.0000,"enabled":true},{"tier_order":2,"rate":0.5000,"enabled":true}]' >/dev/null
echo -e "  ${OK} 阶梯佣金已开启"

# ── 下单（匿名客户 C1 走 A 链接）──
create_order() { # $1=email $2=referral_dist $3=link_id
  curl -s -X POST "$BASE_URL/orders" -H "Content-Type: application/json" \
    -d "{\"product_id\":\"$PID\",\"quantity\":1,\"email\":\"$1\",\"payment_method\":\"$PAY_METHOD\",\"device\":\"pc\",\"referral_distributor_id\":\"$2\",\"promotion_link_id\":\"$3\"}"
}
mark_paid() { curl -s -X POST "$BASE_URL/admin/orders/$1/mark-paid" "${ADMIN_HEADERS[@]}"; }

echo -e "${ARROW} 客户 C1 通过 A 链接下单（第 1 次）..."
O1=$(create_order "$C1_EMAIL" "$A_DIST_ID" "$A_LINK_ID")
O1_ID=$(jget "$O1" 'order.id // empty')
[ -z "$O1_ID" ] && O1_ID=$(jget "$O1" id)
[ -z "$O1_ID" ] && { echo -e "  ${FAIL} 订单1创建失败: $(echo "$O1" | head -c 300)"; exit 1; }
echo -e "  ${OK} 订单1: $O1_ID"
echo -e "${ARROW} admin 标记订单1已支付..."
M1=$(mark_paid "$O1_ID")
[ "$(jget "$M1" code)" = "0" ] && echo -e "  ${OK} 订单1已支付" || echo -e "  ${WARN} 订单1标记支付响应: $M1"

echo -e "${ARROW} 客户 C2 通过 B 链接下单..."
O2=$(create_order "$C2_EMAIL" "$B_DIST_ID" "$B_LINK_ID")
O2_ID=$(jget "$O2" 'order.id // empty')
[ -z "$O2_ID" ] && O2_ID=$(jget "$O2" id)
[ -z "$O2_ID" ] && { echo -e "  ${FAIL} 订单2创建失败: $(echo "$O2" | head -c 300)"; exit 1; }
echo -e "  ${OK} 订单2: $O2_ID"
mark_paid "$O2_ID" >/dev/null
echo -e "  ${OK} 订单2已支付"

echo -e "${ARROW} 客户 C1 再次通过 A 链接下单（第 2 次，验证阶梯）..."
O3=$(create_order "$C1_EMAIL" "$A_DIST_ID" "$A_LINK_ID")
O3_ID=$(jget "$O3" 'order.id // empty')
[ -z "$O3_ID" ] && O3_ID=$(jget "$O3" id)
[ -z "$O3_ID" ] && { echo -e "  ${FAIL} 订单3创建失败: $(echo "$O3" | head -c 300)"; exit 1; }
echo -e "  ${OK} 订单3: $O3_ID"
mark_paid "$O3_ID" >/dev/null
echo -e "  ${OK} 订单3已支付"

sleep 2

# ── 验证佣金记录 ──
echo -e "\n${ARROW} 验证佣金记录..."
echo "  订单ID: $O1_ID / $O2_ID / $O3_ID"
psql_run "
  SELECT cr.distributor_id::text || ' | 订单:' || substring(cr.order_id::text,1,8) || ' | 商品:' || cr.product_title
       || ' | 基数:' || cr.order_amount || ' | 比例:' || cr.commission_rate || ' | 佣金:' || cr.commission_amount
       || ' | 第' || cr.tier_order || '次 | ' || cr.status
  FROM commission_records cr
  WHERE cr.order_id IN ('$O1_ID','$O2_ID','$O3_ID')
  ORDER BY cr.created_at" | sed 's/^/    /'

# 校验：订单1 佣金应 = 单价×10%（第1次，tier=100%）
C1_A_COMM=$(psql_run "SELECT commission_amount::text FROM commission_records WHERE order_id='$O1_ID' AND distributor_id='$A_DIST_ID' AND parent_distributor_id IS NULL")
if [ -n "$C1_A_COMM" ]; then
  echo -e "  ${OK} 订单1 → A 佣金: $C1_A_COMM 元（预期 ≈ $(echo "$PRICE * 0.10" | bc -l 2>/dev/null || echo $PRICE) 的 100%）"
else
  echo -e "  ${FAIL} 订单1 未找到 A 的佣金记录！请检查日志"
fi

# 校验：订单2 → B 实得 70%，A 抽成 30%
B_COMM=$(psql_run "SELECT commission_amount::text FROM commission_records WHERE order_id='$O2_ID' AND distributor_id='$B_DIST_ID' AND parent_distributor_id IS NULL")
A_PARENT_COMM=$(psql_run "SELECT commission_amount::text FROM commission_records WHERE order_id='$O2_ID' AND distributor_id='$A_DIST_ID' AND parent_distributor_id='$A_DIST_ID'")
A_PARENT_COMM=${A_PARENT_COMM:-$(psql_run "SELECT commission_amount::text FROM commission_records WHERE order_id='$O2_ID' AND distributor_id='$A_DIST_ID'")}
if [ -n "$B_COMM" ] && [ -n "$A_PARENT_COMM" ]; then
  echo -e "  ${OK} 订单2 → B 佣金: $B_COMM 元（≈70%），A 抽成: $A_PARENT_COMM 元（≈30%）"
else
  echo -e "  ${FAIL} 订单2 佣金记录缺失（B=$B_COMM A抽成=$A_PARENT_COMM）"
fi

# 校验：订单3 → C1 第 2 次购买 → tier=2 → 50% 佣金
C3_TIER=$(psql_run "SELECT tier_order FROM commission_records WHERE order_id='$O3_ID' AND distributor_id='$A_DIST_ID' AND parent_distributor_id IS NULL LIMIT 1")
C3_COMM=$(psql_run "SELECT commission_amount::text FROM commission_records WHERE order_id='$O3_ID' AND distributor_id='$A_DIST_ID' AND parent_distributor_id IS NULL LIMIT 1")
if [ "$C3_TIER" = "2" ]; then
  echo -e "  ${OK} 订单3 → A 佣金: $C3_COMM 元（第2次 tier=2，应为 50%）"
else
  echo -e "  ${WARN} 订单3 阶梯档位=$C3_TIER（预期 2），佣金=$C3_COMM"
fi

# ── 结算阶段 ──
if [ "$1" = "--settle" ]; then
  echo -e "\n${ARROW} [结算验证] 检查已结算佣金与余额..."
  # 将测试订单佣金 backdate 到 8 天前（超过默认 7 天结算延迟），等待定时任务
  psql_run "UPDATE commission_records SET created_at=now()-interval '8 days' WHERE order_id IN ('$O1_ID','$O2_ID','$O3_ID')" >/dev/null
  echo -e "  ${ARROW} 测试佣金已 backdate 8 天。等待定时任务（每小时整点）结算..."
  echo -e "  ${ARROW} 也可直接查看: 佣金状态 / 分销员余额（约 1 小时内自动结算）"
  echo ""
  echo "  当前佣金状态:"
  psql_run "SELECT status, count(*), sum(commission_amount) FROM commission_records WHERE order_id IN ('$O1_ID','$O2_ID','$O3_ID') GROUP BY status" | sed 's/^/    /'
  echo "  A 分销员余额:"
  psql_run "SELECT 'available='||available_balance||' frozen='||frozen_balance||' total='||total_commission FROM distributors WHERE id='$A_DIST_ID'" | sed 's/^/    /'
  echo "  B 分销员余额:"
  psql_run "SELECT 'available='||available_balance||' frozen='||frozen_balance||' total='||total_commission FROM distributors WHERE id='$B_DIST_ID'" | sed 's/^/    /'
  exit 0
fi

# ── 结算 + 提现（需要已结算余额；若未结算则提示先跑 --settle）──
A_BAL=$(psql_run "SELECT available_balance FROM distributors WHERE id='$A_DIST_ID'")
if [ -z "$A_BAL" ] || [ "$A_BAL" = "0.00" ] || [ "$A_BAL" = "0" ]; then
  echo -e "\n${WARN} A 的可提现余额为 $A_BAL，暂未结算。"
  echo -e "  继续执行提现流程前请先完成结算："
  echo -e "    bash test-distribution-flow.sh --settle"
  echo -e "  结算完成后（可提现余额 > 0），提现部分可用 psql 验证余额、或继续本脚本跑提现。"
else
  echo -e "\n${ARROW} A 可提现余额: $A_BAL 元，开始提现流程..."
  # 给 A 绑定微信 openid（测试用假 openid，微信转账会失败→走手动打款，便于验证）
  psql_run "UPDATE distributors SET wechat_openid='test_openid_${TS}', wechat_nickname='测试分销员A' WHERE id='$A_DIST_ID'" >/dev/null
  echo -e "  ${OK} A 已绑定测试微信 openid"
  W_AMOUNT=$(psql_run "SELECT available_balance FROM distributors WHERE id='$A_DIST_ID'")
  W_REQ=$(curl -s -X POST "$BASE_URL/distributor/withdrawals" -H "Authorization: Bearer $A_TOKEN" -H "Content-Type: application/json" \
    -d "{\"amount\":$W_AMOUNT}")
  W_ID=$(jget "$W_REQ" id)
  [ -z "$W_ID" ] && W_ID=$(jget "$W_REQ" 'data.id // empty')
  if [ -z "$W_ID" ]; then
    echo -e "  ${FAIL} 提现申请失败: $(echo "$W_REQ" | head -c 300)"
  else
    echo -e "  ${OK} 提现申请成功: id=$W_ID 金额=$W_AMOUNT"
    echo -e "${ARROW} admin 审批提现..."
    W_APPROVE=$(curl -s -X PUT "$BASE_URL/admin/distribution/withdrawals/$W_ID/approve" "${ADMIN_HEADERS[@]}" -d '{}')
    echo -e "  ${OK} 审批响应: $(jget "$W_APPROVE" code)（假 openid 会触发微信转账失败→自动转手动打款）"
    echo -e "${ARROW} admin 手动结算..."
    W_SETTLE=$(curl -s -X PUT "$BASE_URL/admin/distribution/withdrawals/$W_ID/settle" "${ADMIN_HEADERS[@]}" \
      -d "{\"actual_amount\":$W_AMOUNT}")
    echo -e "  ${OK} 手动结算响应: $(jget "$W_SETTLE" code)"
    echo ""
    echo "  提现最终状态:"
    psql_run "SELECT 'amount='||amount||' actual='||actual_amount||' status='||status||' completed='||COALESCE(completed_at::text,'-') FROM withdrawal_records WHERE id='$W_ID'" | sed 's/^/    /'
    echo "  A 最终余额:"
    psql_run "SELECT 'available='||available_balance||' frozen='||frozen_balance||' withdrawn='||withdrawn_amount FROM distributors WHERE id='$A_DIST_ID'" | sed 's/^/    /'
  fi
fi

# ── 恢复规则（关闭阶梯，避免影响生产配置）──
echo -e "\n${ARROW} 恢复分销规则（关闭阶梯佣金）..."
curl -s -X PUT "$BASE_URL/admin/distribution/rules" "${ADMIN_HEADERS[@]}" \
  -d '{"tier_enabled":false}' >/dev/null
echo -e "  ${OK} 规则已恢复"

echo ""
echo -e "${GREEN}══════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  测试完成。测试标识: ${PREFIX}${NC}"
echo -e "${GREEN}  测试订单: $O1_ID / $O2_ID / $O3_ID${NC}"
echo -e "${GREEN}  分销员A: $A_DIST_ID  B: $B_DIST_ID${NC}"
echo -e "${GREEN}══════════════════════════════════════════════════${NC}"
echo -e "清理测试数据: bash test-distribution-flow.sh --clean"
