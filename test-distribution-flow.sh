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

# 服务器上 psql 通常在 /www/server/pgsql/bin/（宝塔），不在 PATH 时自动用全路径
PSQL_BIN=${PSQL_BIN:-$(command -v psql 2>/dev/null || echo /www/server/pgsql/bin/psql)}

TS=$(date +%s)
PREFIX="disttest_${TS}"
C1_EMAIL="c1_${PREFIX}@dist.test"
C2_EMAIL="c2_${PREFIX}@dist.test"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[0;33m'; CYAN='\033[0;36m'; NC='\033[0m'
OK="${GREEN}✓${NC}"; FAIL="${RED}✗${NC}"; WARN="${YELLOW}⚠${NC}"; ARROW="${CYAN}→${NC}"

psql_run() { PGPASSWORD="$DB_PASS" "$PSQL_BIN" -h 127.0.0.1 -U "$DB_USER" -d "$DB_NAME" -t -A -c "$1" 2>/dev/null; }

# JSON 提取：优先 jq，回退 grep；支持 "token" / "data.id" / "order.id // empty" 等写法
# 自动兼容 data 包裹：先按原路径取，取不到再按 .data.<路径> 取
jget() {
  local json="$1" p="$2" v=""
  if command -v jq >/dev/null 2>&1; then
    v=$(echo "$json" | jq -r "$p // empty" 2>/dev/null)
    if [ -z "$v" ] || [ "$v" = "null" ]; then
      v=$(echo "$json" | jq -r ".data.$p // empty" 2>/dev/null)
    fi
  fi
  if [ -z "$v" ] || [ "$v" = "null" ]; then
    local key="${p% // *}"
    key="${key##*.}"
    v=$(echo "$json" | grep -o "\"$key\":\"[^\"]*\"" | head -1 | cut -d'"' -f4)
    [ -z "$v" ] && v=$(echo "$json" | grep -o "\"$key\":[0-9.-]*" | head -1 | cut -d':' -f2)
  fi
  echo "$v"
}

# 需要 jq 的场景（嵌套/数组）直接调 jq，缺失时报错提示
jq_get() { echo "$1" | jq -r "$2" 2>/dev/null; }

# ── --clean: 清理测试数据（按 disttest 特征模式，多次运行残留一并清理）──
if [ "$1" = "--clean" ]; then
  echo -e "${ARROW} 清理测试数据（disttest 模式）..."
  psql_run "DELETE FROM commission_records WHERE order_id IN (SELECT id FROM orders WHERE email LIKE '%@dist.test')" >/dev/null
  psql_run "DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE email LIKE '%@dist.test')" >/dev/null
  psql_run "DELETE FROM withdrawal_records WHERE distributor_id IN (SELECT d.id FROM distributors d JOIN users u ON u.id=d.user_id WHERE u.username LIKE 'tA_%' OR u.username LIKE 'tB_%')" >/dev/null
  psql_run "DELETE FROM promotion_links WHERE distributor_id IN (SELECT d.id FROM distributors d JOIN users u ON u.id=d.user_id WHERE u.username LIKE 'tA_%' OR u.username LIKE 'tB_%')" >/dev/null
  psql_run "DELETE FROM customer_bindings WHERE customer_email LIKE '%@dist.test'" >/dev/null
  psql_run "DELETE FROM orders WHERE email LIKE '%@dist.test'" >/dev/null
  psql_run "DELETE FROM distributors WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'tA_%' OR username LIKE 'tB_%')" >/dev/null
  psql_run "DELETE FROM users WHERE username LIKE 'tA_%' OR username LIKE 'tB_%'" >/dev/null
  echo -e "  ${OK} 清理完成"; exit 0
fi

# ── 运行模式 ──
MODE="full"
[ "$1" = "--settle" ] && MODE="settle"

# ── admin 登录 ──
ADMIN_TOKEN=""
echo -e "${ARROW} 登录 admin..."
LOGIN_RESP=$(curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
  -d '{"account":"admin","password":"admin123"}')
ADMIN_TOKEN=$(jget "$LOGIN_RESP" token)
# 登录限流为每分钟 5 次/IP（RateLimitFilter），被限流时等待 60 秒自动重试一次
if [ -z "$ADMIN_TOKEN" ] && echo "$LOGIN_RESP" | grep -q '10005'; then
  echo -e "  ${WARN} 触发登录限流(10005)，等待 60 秒重试..."
  sleep 60
  LOGIN_RESP=$(curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
    -d '{"account":"admin","password":"admin123"}')
  ADMIN_TOKEN=$(jget "$LOGIN_RESP" token)
fi
if [ -z "$ADMIN_TOKEN" ]; then
  echo -e "  ${FAIL} admin 登录失败: $(echo "$LOGIN_RESP" | head -c 300)"
  exit 1
fi
echo -e "  ${OK} admin 登录成功"

ADMIN_HEADERS=(-H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json")

# ═══════════════════════════════════════════════════════════════
#  --settle 模式：基于已生成的测试数据 结算 + 提现（不重建数据）
#  用法：先跑完整流程生成数据，再执行 bash test-distribution-flow.sh --settle
# ═══════════════════════════════════════════════════════════════
if [ "$MODE" = "settle" ]; then
  echo -e "\n${ARROW} [结算+提现模式] 定位最近一批测试数据..."
  O_IDS=$(psql_run "SELECT string_agg(id::text, ',') FROM (SELECT id FROM orders WHERE email LIKE '%@dist.test' ORDER BY created_at DESC LIMIT 6) t")
  if [ -z "$O_IDS" ]; then
    echo -e "  ${FAIL} 未找到测试订单，请先运行 bash test-distribution-flow.sh 生成测试数据"; exit 1
  fi
  A_DIST_ID=$(psql_run "SELECT d.id::text FROM distributors d JOIN users u ON u.id=d.user_id WHERE u.username LIKE 'tA_%' ORDER BY d.created_at DESC LIMIT 1")
  B_DIST_ID=$(psql_run "SELECT d.id::text FROM distributors d JOIN users u ON u.id=d.user_id WHERE u.username LIKE 'tB_%' ORDER BY d.created_at DESC LIMIT 1")
  if [ -z "$A_DIST_ID" ] || [ -z "$B_DIST_ID" ]; then
    echo -e "  ${FAIL} 未找到测试分销员 A/B（tA_%/tB_%）"; exit 1
  fi
  echo -e "  ${OK} 测试订单: $O_IDS"
  echo -e "  ${OK} 分销员 A=$A_DIST_ID  B=$B_DIST_ID"

  echo -e "\n${ARROW} [结算验证] 测试佣金 backdate 8 天，调用手动结算接口..."
  psql_run "UPDATE commission_records SET created_at=now()-interval '8 days' WHERE order_id IN ($O_IDS)" >/dev/null
  SETTLE_RESP=$(curl -s -X POST "$BASE_URL/admin/distribution/commissions/settle" "${ADMIN_HEADERS[@]}")
  echo -e "  ${OK} 结算接口响应: $(jget "$SETTLE_RESP" code)"
  sleep 1
  echo ""
  echo "  结算后佣金状态:"
  psql_run "SELECT status, count(*), sum(commission_amount) FROM commission_records WHERE order_id IN ($O_IDS) GROUP BY status" | sed 's/^/    /'
  echo "  A 分销员余额:"
  psql_run "SELECT 'available='||available_balance||' frozen='||frozen_balance||' total='||total_commission FROM distributors WHERE id='$A_DIST_ID'" | sed 's/^/    /'
  echo "  B 分销员余额:"
  psql_run "SELECT 'available='||available_balance||' frozen='||frozen_balance||' total='||total_commission FROM distributors WHERE id='$B_DIST_ID'" | sed 's/^/    /'

  # ── 提现验证（A 申请提现 → admin 审批 → 手动结算）──
  MIN_W=$(psql_run "SELECT min_withdraw_amount FROM distribution_rules LIMIT 1")
  A_BAL=$(psql_run "SELECT available_balance FROM distributors WHERE id='$A_DIST_ID'")
  if [ -z "$A_BAL" ] || [ "$A_BAL" = "0.00" ] || [ "$A_BAL" = "0" ]; then
    echo -e "\n${WARN} 结算后 A 可提现余额仍为 $A_BAL，提现验证跳过（请检查结算逻辑）"
  else
    echo -e "\n${ARROW} A 可提现余额: $A_BAL 元，开始提现流程..."
    if [ -n "$MIN_W" ] && [ "$(psql_run "SELECT $A_BAL < $MIN_W")" = "t" ]; then
      echo -e "  ${WARN} 可提现余额 $A_BAL < 最低提现门槛 $MIN_W，临时调低门槛为 0.01 验证提现流程..."
      curl -s -X PUT "$BASE_URL/admin/distribution/rules" "${ADMIN_HEADERS[@]}" \
        -d '{"min_withdraw_amount":0.01}' >/dev/null
    fi
    # 登录最新 A 测试用户（获取提现所需 token；密码均为 admin123）
    echo -e "${ARROW} 登录测试分销员 A..."
    UA_EMAIL=$(psql_run "SELECT email FROM users WHERE id=(SELECT user_id FROM distributors WHERE id='$A_DIST_ID')")
    A_LOGIN=$(curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
      -d "{\"account\":\"$UA_EMAIL\",\"password\":\"admin123\"}")
    A_TOKEN=$(jget "$A_LOGIN" token)
    if [ -z "$A_TOKEN" ]; then
      echo -e "  ${WARN} A 登录失败: $(echo "$A_LOGIN" | head -c 200)（可能触发登录限流，请稍后重试）"
    else
      echo -e "  ${OK} A 登录成功"
      psql_run "UPDATE distributors SET wechat_openid='test_openid_$(date +%s)', wechat_nickname='测试分销员A' WHERE id='$A_DIST_ID'" >/dev/null
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
  fi

  # ── 恢复规则（仅恢复提现门槛，不触碰其它配置）──
  if [ -n "$MIN_W" ]; then
    echo -e "\n${ARROW} 恢复最低提现门槛 $MIN_W ..."
    curl -s -X PUT "$BASE_URL/admin/distribution/rules" "${ADMIN_HEADERS[@]}" \
      -d "{\"min_withdraw_amount\":$MIN_W}" >/dev/null
    echo -e "  ${OK} 提现门槛已恢复 $MIN_W"
  fi
  echo ""
  echo -e "${GREEN}══════════════════════════════════════════════════${NC}"
  echo -e "${GREEN}  结算+提现验证完成${NC}"
  echo -e "${GREEN}  测试订单: $O_IDS${NC}"
  echo -e "${GREEN}  分销员A: $A_DIST_ID  B: $B_DIST_ID${NC}"
  echo -e "${GREEN}══════════════════════════════════════════════════${NC}"
  echo -e "清理测试数据: bash test-distribution-flow.sh --clean"
  exit 0
fi

# ── 检查可分销商品 ──
echo -e "${ARROW} 查找可分销商品..."
# 优先取已开启分销的商品；否则为第一个启用商品开启分销（默认比例）
PID=$(psql_run "SELECT pc.product_id::text FROM product_commissions pc JOIN products p ON p.id=pc.product_id WHERE p.is_deleted=0 AND p.is_enabled=true AND pc.excluded=false ORDER BY p.created_at LIMIT 1")
if [ -z "$PID" ]; then
  echo -e "  ${WARN} 无可分销商品，为第一个启用商品开启分销..."
  PID=$(psql_run "SELECT id::text FROM products WHERE is_deleted=0 AND is_enabled=true ORDER BY created_at LIMIT 1")
  if [ -z "$PID" ]; then
    echo -e "  ${FAIL} 无可用商品，测试终止"; exit 1
  fi
  psql_run "INSERT INTO product_commissions (id, product_id, custom_rate, excluded, created_at, updated_at) VALUES (gen_random_uuid(), '$PID', NULL, false, now(), now()) ON CONFLICT (product_id) DO NOTHING" >/dev/null
  echo -e "  ${OK} 已为商品 $PID 开启分销"
fi
echo -e "  ${OK} 测试商品: $PID"

# 取商品单价（用于佣金预期计算）
PRICE=$(psql_run "SELECT base_price::text FROM products WHERE id='$PID'")
[ -z "$PRICE" ] && PRICE=0
echo -e "  ${ARROW} 商品单价: $PRICE 元"

# 取商品佣金比例（custom_rate，覆盖默认率；用于预期计算）
PROD_RATE=$(psql_run "SELECT COALESCE(custom_rate, (SELECT default_rate FROM distribution_rules LIMIT 1))::text FROM product_commissions WHERE product_id='$PID'")
[ -z "$PROD_RATE" ] && PROD_RATE=0.10
echo -e "  ${ARROW} 商品佣金比例: $PROD_RATE"

# 取一个启用的支付渠道
PAY_METHOD=$(psql_run "SELECT channel_code FROM payment_channels WHERE is_deleted=0 AND is_enabled=true ORDER BY created_at LIMIT 1")
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
  # 可能已存在分销记录 → 直接查库（profile 接口不返回分销员 UUID）
  A_DIST_ID=$(psql_run "SELECT id::text FROM distributors WHERE user_id='$UA_ID' LIMIT 1")
  A_INVITE=$(psql_run "SELECT invite_code FROM distributors WHERE user_id='$UA_ID' LIMIT 1")
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
  B_DIST_ID=$(psql_run "SELECT id::text FROM distributors WHERE user_id='$UB_ID' LIMIT 1")
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
# 接口要求请求体为 {"tiers":[...]}，裸数组会报"阶梯配置不能为空"
TIERS_RESP=$(curl -s -X PUT "$BASE_URL/admin/distribution/tiers" "${ADMIN_HEADERS[@]}" \
  -d '{"tiers":[{"tier_order":1,"rate":1.0000,"enabled":true},{"tier_order":2,"rate":0.5000,"enabled":true}]}')
# 显式校验：配置必须实际落库生效（避免静默失败导致佣金按旧档位计算）
TIER_OK=$(psql_run "SELECT count(*) FROM commission_tiers WHERE enabled=true AND tier_order=2 AND rate=0.5000")
if [ "$TIER_OK" != "1" ]; then
  echo -e "  ${FAIL} 阶梯配置未生效（tier2 应=0.5000）：$(echo "$TIERS_RESP" | head -c 200)"
  exit 1
fi
echo -e "  ${OK} 阶梯佣金已开启（tier1=100%, tier2=50%）"

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

# 校验：订单1 佣金应 = 单价×商品比例×tier1（第1次，tier=100%）
C1_A_COMM=$(psql_run "SELECT commission_amount::text FROM commission_records WHERE order_id='$O1_ID' AND distributor_id='$A_DIST_ID' AND parent_distributor_id IS NULL")
EXP1=$(echo "$PRICE * $PROD_RATE * 1.0" | bc -l 2>/dev/null | xargs printf "%.2f" 2>/dev/null || echo "?")
if [ -n "$C1_A_COMM" ]; then
  echo -e "  ${OK} 订单1 → A 佣金: $C1_A_COMM 元（预期 ≈ $EXP1 = ${PRICE}×${PROD_RATE}×100%）"
else
  echo -e "  ${FAIL} 订单1 未找到 A 的佣金记录！请检查日志"
fi

# 校验：订单2 → B 实得 70%，A 抽成 30%
# 注意：B 的本人佣金记录 parent_distributor_id 指向其上级 A（非 NULL），不能加 IS NULL 条件
B_COMM=$(psql_run "SELECT commission_amount::text FROM commission_records WHERE order_id='$O2_ID' AND distributor_id='$B_DIST_ID'")
A_PARENT_COMM=$(psql_run "SELECT commission_amount::text FROM commission_records WHERE order_id='$O2_ID' AND distributor_id='$A_DIST_ID'")
if [ -n "$B_COMM" ] && [ -n "$A_PARENT_COMM" ]; then
  echo -e "  ${OK} 订单2 → B 佣金: $B_COMM 元（≈70%），A 抽成: $A_PARENT_COMM 元（≈30%）"
else
  echo -e "  ${FAIL} 订单2 佣金记录缺失（B=$B_COMM A抽成=$A_PARENT_COMM）"
fi

# 校验：订单3 → C1 第 2 次购买 → tier=2 → 50% 佣金（应为 单价×商品比例×tier2）
C3_TIER=$(psql_run "SELECT tier_order FROM commission_records WHERE order_id='$O3_ID' AND distributor_id='$A_DIST_ID' AND parent_distributor_id IS NULL LIMIT 1")
C3_COMM=$(psql_run "SELECT commission_amount::text FROM commission_records WHERE order_id='$O3_ID' AND distributor_id='$A_DIST_ID' AND parent_distributor_id IS NULL LIMIT 1")
EXP3=$(echo "$PRICE * $PROD_RATE * 0.5" | bc -l 2>/dev/null | xargs printf "%.2f" 2>/dev/null || echo "?")
if [ "$C3_TIER" = "2" ]; then
  echo -e "  ${OK} 订单3 → A 佣金: $C3_COMM 元（预期 ≈ $EXP3 = ${PRICE}×${PROD_RATE}×50%）"
else
  echo -e "  ${WARN} 订单3 阶梯档位=$C3_TIER（预期 2），佣金=$C3_COMM"
fi

# ── 结算 + 提现（完整流程结束佣金为 PENDING，需先 --settle 结算）──
MIN_W=$(psql_run "SELECT min_withdraw_amount FROM distribution_rules LIMIT 1")
A_BAL=$(psql_run "SELECT available_balance FROM distributors WHERE id='$A_DIST_ID'")
if [ -z "$A_BAL" ] || [ "$A_BAL" = "0.00" ] || [ "$A_BAL" = "0" ]; then
  echo -e "\n${WARN} A 的可提现余额为 $A_BAL，暂未结算。"
  echo -e "  继续执行提现流程前请先完成结算："
  echo -e "    bash test-distribution-flow.sh --settle"
  echo -e "  结算完成后（可提现余额 > 0），再次运行本脚本即可继续验证提现。"
else
  echo -e "\n${ARROW} A 可提现余额: $A_BAL 元，开始提现流程..."
  # 测试金额可能低于最低提现门槛 → 临时调低（结束后恢复）
  if [ -n "$MIN_W" ] && [ "$(psql_run "SELECT $A_BAL < $MIN_W")" = "t" ]; then
    echo -e "  ${WARN} 可提现余额 $A_BAL < 最低提现门槛 $MIN_W，临时调低门槛为 0.01 验证提现流程..."
    curl -s -X PUT "$BASE_URL/admin/distribution/rules" "${ADMIN_HEADERS[@]}" \
      -d '{"min_withdraw_amount":0.01}' >/dev/null
  fi
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

# ── 恢复规则（关闭阶梯 + 恢复最低提现门槛，避免影响生产配置）──
RESTORE_NOTE="tier_enabled=false"
[ -n "$MIN_W" ] && RESTORE_NOTE="$RESTORE_NOTE、min_withdraw_amount=$MIN_W"
echo -e "\n${ARROW} 恢复分销规则（$RESTORE_NOTE）..."
RESTORE_JSON="{\"tier_enabled\":false"
[ -n "$MIN_W" ] && RESTORE_JSON="$RESTORE_JSON,\"min_withdraw_amount\":$MIN_W"
RESTORE_JSON="$RESTORE_JSON}"
curl -s -X PUT "$BASE_URL/admin/distribution/rules" "${ADMIN_HEADERS[@]}" \
  -d "$RESTORE_JSON" >/dev/null
echo -e "  ${OK} 规则已恢复（$RESTORE_NOTE）"

echo ""
echo -e "${GREEN}══════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  测试完成。测试标识: ${PREFIX}${NC}"
echo -e "${GREEN}  测试订单: $O1_ID / $O2_ID / $O3_ID${NC}"
echo -e "${GREEN}  分销员A: $A_DIST_ID  B: $B_DIST_ID${NC}"
echo -e "${GREEN}══════════════════════════════════════════════════${NC}"
echo -e "下一步: bash test-distribution-flow.sh --settle   # 结算+提现验证"
echo -e "清理测试数据: bash test-distribution-flow.sh --clean"
