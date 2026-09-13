#!/usr/bin/env bash
# 云小店 端到端冒烟脚本（Linux / macOS / Git Bash）
#
# 用途：对**已启动的系统**跑一遍真实 HTTP 链路，验证从建档到报表与 AI 的完整流程。
# 用法：
#   BASE_URL=http://localhost:8080 bash scripts/smoke-e2e.sh          # 直连后端
#   BASE_URL=http://localhost bash scripts/smoke-e2e.sh               # 走 Nginx（含前端代理）
#
# 依赖：curl、jq（GitHub Actions 与主流发行版自带）
# 退出码：0 = 全部通过；1 = 有失败项
# 注意：会真实写入数据（商品/采购单/销售单），请在演示或测试环境执行。

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
USERNAME="${USERNAME:-admin}"
PASSWORD="${PASSWORD:-admin123}"

pass=0
fail=0
failures=()

ok() { pass=$((pass + 1)); printf '\033[32m[PASS]\033[0m %s\n' "$1"; }
ko() { fail=$((fail + 1)); failures+=("$1 :: $2"); printf '\033[31m[FAIL]\033[0m %s :: %s\n' "$1" "$2"; }

# 发请求：api <METHOD> <PATH> [BODY] [TOKEN] -> 设置 STATUS 与 BODY
api() {
  local method="$1" path="$2" body="${3:-}" token="${4:-}" auth=()
  if [ -n "$token" ]; then auth=(-H "Authorization: Bearer $token"); fi
  local raw
  if [ -n "$body" ]; then
    # ${auth[@]+"${auth[@]}"} 是为了兼容 set -u 下空数组展开（bash < 4.4 会报未绑定变量）
    raw=$(curl -sS -X "$method" "$BASE_URL$path" -H "Content-Type: application/json" ${auth[@]+"${auth[@]}"} -d "$body" -w $'\n%{http_code}')
  else
    raw=$(curl -sS -X "$method" "$BASE_URL$path" ${auth[@]+"${auth[@]}"} -w $'\n%{http_code}')
  fi
  STATUS="${raw##*$'\n'}"
  BODY="${raw%$'\n'*}"
}

# expect <描述> <期望状态码> <断言函数/jq 表达式>；断言用 jq -e 表达式，非 true 即失败
check() {
  local name="$1" expect="$2" expr="$3"
  if [ "$STATUS" != "$expect" ]; then
    ko "$name" "期望状态 $expect，实际 $STATUS，响应：$(echo "$BODY" | head -c 300)"
    return 1
  fi
  if [ -n "$expr" ] && ! echo "$BODY" | jq -e "$expr" >/dev/null 2>&1; then
    ko "$name" "断言失败：$expr ，响应：$(echo "$BODY" | head -c 300)"
    return 1
  fi
  ok "$name"
  return 0
}

echo "=== 云小店端到端冒烟 ==="
echo "目标：$BASE_URL"
echo

# ---------- 1. 未登录必须 401 ----------
api GET /api/products
check "未登录访问受保护接口返回 401" 401 '.code == "UNAUTHORIZED"'

# ---------- 2. 登录 ----------
api POST /api/auth/login "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}"
check "登录获取令牌与权限" 200 '.success == true and (.data.token | length > 20) and (.data.user.permissions | length >= 5)'
TOKEN=$(echo "$BODY" | jq -r '.data.token')

api GET /api/auth/me "" "$TOKEN"
check "当前用户信息" 200 ".data.username == \"$USERNAME\""

# ---------- 3. 建档（期初库存 5，阈值 10 → 应进入低库存） ----------
BARCODE="69$(date +%s%N | tail -c 12)"
api POST /api/products "{\"name\":\"冒烟测试商品\",\"barcode\":\"$BARCODE\",\"spec\":\"500ml\",\"unit\":\"瓶\",\"purchasePrice\":2.00,\"salePrice\":3.50,\"initStock\":5,\"lowStockThreshold\":10}" "$TOKEN"
check "新建商品并写入期初库存" 200 '.data.stock == 5 and .data.lowStock == true'
PRODUCT_ID=$(echo "$BODY" | jq -r '.data.id')

api GET "/api/inventory/flows/$PRODUCT_ID?limit=10" "" "$TOKEN"
check "库存流水记录了期初建库" 200 '.data[0].type == "IN" and .data[0].beforeStock == 0'

api GET /api/inventory/low-stock "" "$TOKEN"
check "低库存预警包含该商品" 200 ".data | map(.productId) | index($PRODUCT_ID) != null"

# ---------- 4. 采购：录单不动库存 → 确认入库 ----------
api POST /api/purchases "{\"supplierName\":\"冒烟供应商\",\"remark\":\"冒烟测试\",\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":20,\"unitCost\":2.00}]}" "$TOKEN"
check "采购单保存为草稿" 200 '.data.status == "DRAFT"'
PURCHASE_ID=$(echo "$BODY" | jq -r '.data.id')

api GET "/api/products/$PRODUCT_ID" "" "$TOKEN"
check "草稿状态不改动库存" 200 '.data.stock == 5'

api POST "/api/purchases/$PURCHASE_ID/confirm" "" "$TOKEN"
check "确认入库后库存 +20" 200 '.data.status == "CONFIRMED"'

api GET "/api/products/$PRODUCT_ID" "" "$TOKEN"
check "入库后库存为 25" 200 '.data.stock == 25'

api POST "/api/purchases/$PURCHASE_ID/confirm" "" "$TOKEN"
check "重复确认入库被状态机拒绝" 409 '.code == "CONFLICT"'

# ---------- 5. 收银：幂等 + 库存不足回滚 ----------
REQ_ID="SMOKE-$(date +%s%N)"
CHECKOUT="{\"requestId\":\"$REQ_ID\",\"customerName\":\"冒烟顾客\",\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":3}],\"discountAmount\":0,\"payMethod\":\"WECHAT\"}"
api POST /api/sales/checkout "$CHECKOUT" "$TOKEN"
check "收银结算（应收 10.50）" 200 '.data.payAmount == 10.50'
ORDER_NO=$(echo "$BODY" | jq -r '.data.orderNo')
SALE_ID=$(echo "$BODY" | jq -r '.data.id')
ORDER_ITEM_ID=$(echo "$BODY" | jq -r '.data.items[0].id')

api GET "/api/products/$PRODUCT_ID" "" "$TOKEN"
check "收银后库存扣到 22" 200 '.data.stock == 22'

api POST /api/sales/checkout "$CHECKOUT" "$TOKEN"
check "同一 requestId 重复提交返回首次订单" 200 ".data.orderNo == \"$ORDER_NO\" and .data.duplicated == true"

api GET "/api/products/$PRODUCT_ID" "" "$TOKEN"
check "幂等命中不重复扣库存" 200 '.data.stock == 22'

api POST /api/sales/checkout "{\"requestId\":\"SMOKE-FAIL-$(date +%s%N)\",\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":999}],\"discountAmount\":0,\"payMethod\":\"CASH\"}" "$TOKEN"
check "库存不足返回 422 且错误码可读" 422 '.code == "STOCK_NOT_ENOUGH"'

api GET "/api/products/$PRODUCT_ID" "" "$TOKEN"
check "失败的结算不改动库存" 200 '.data.stock == 22'

# ---------- 6. 退货 ----------
api POST "/api/sales/$SALE_ID/refund" "{\"items\":[{\"orderItemId\":$ORDER_ITEM_ID,\"quantity\":1}],\"remark\":\"冒烟退货\"}" "$TOKEN"
check "部分退货成功（状态 PARTIAL_REFUNDED）" 200 '.data.status == "PARTIAL_REFUNDED"'

api GET "/api/products/$PRODUCT_ID" "" "$TOKEN"
check "退货回补库存到 23" 200 '.data.stock == 23'

api POST "/api/sales/$SALE_ID/refund" "{\"items\":[{\"orderItemId\":$ORDER_ITEM_ID,\"quantity\":99}]}" "$TOKEN"
check "超退被拒绝" 409 '.code == "CONFLICT"'

# ---------- 7. 报表与对账 ----------
api GET /api/reports/overview "" "$TOKEN"
check "经营概览有成交数据" 200 '.data.orderCount >= 1 and (.data.netAmount | tonumber) > 0'

TODAY=$(date +%F)
api POST "/api/reports/daily/$TODAY/rebuild" "" "$TOKEN"
NET1=$(echo "$BODY" | jq -r '.data.netAmount')
api POST "/api/reports/daily/$TODAY/rebuild" "" "$TOKEN"
NET2=$(echo "$BODY" | jq -r '.data.netAmount')
if [ "$NET1" = "$NET2" ]; then ok "日汇总重算幂等（两次结果一致）"; else ko "日汇总重算幂等" "两次结果不一致：$NET1 vs $NET2"; fi

api GET /api/reports/reconcile "" "$TOKEN"
check "对账一致（销售数量 = 库存出库数量）" 200 '.data.consistent == true'

# ---------- 8. AI 录单与助手 ----------
api POST /api/ai/drafts '{"text":"进了 6 瓶冒烟测试商品 单价 2.0","type":"PURCHASE"}' "$TOKEN"
check "自然语言解析为草稿（待确认）" 200 '.data.status == "PENDING" and .data.items[0].resolved == true'
DRAFT_ID=$(echo "$BODY" | jq -r '.data.id')

api POST "/api/ai/drafts/$DRAFT_ID/confirm" '{"supplierName":"冒烟供应商"}' "$TOKEN"
check "人工确认生成采购单（不改库存）" 200 '.data.status == "CONFIRMED" and (.data.createdRefNo | length > 4)'

api GET "/api/products/$PRODUCT_ID" "" "$TOKEN"
check "AI 草稿确认不改动库存" 200 '.data.stock == 23'

api POST /api/ai/drafts '{"text":"进了 3 瓶根本不存在的饮料","type":"PURCHASE"}' "$TOKEN"
check "识别不出的商品被标注而非瞎猜" 200 '.data.hasUnresolved == true'
BAD_DRAFT_ID=$(echo "$BODY" | jq -r '.data.id')

api POST "/api/ai/drafts/$BAD_DRAFT_ID/confirm" '{}' "$TOKEN"
check "未识别草稿确认被拒绝" 400 '.code == "BAD_REQUEST"'

api POST /api/ai/drafts '{"text":"卖了 2 瓶冒烟测试商品","type":"SALE"}' "$TOKEN"
SALE_DRAFT_ID=$(echo "$BODY" | jq -r '.data.id')
api POST "/api/ai/drafts/$SALE_DRAFT_ID/confirm" '{}' "$TOKEN"
check "销售类草稿必须走收银台" 400 '.message | contains("收银台")'

api POST /api/ai/assistant/ask '{"question":"今天卖了多少"}' "$TOKEN"
# 注意 jq 的管道优先级：写成 `.data.answer | contains(...) and (.data.toolsUsed ...)` 时，
# and 右侧的 `.` 已经被管道改成了字符串，断言会恒为假——必须给两个条件各加括号
check "经营助手回答营业额（只读工具）" 200 '(.data.answer | contains("营业额")) and (.data.toolsUsed | length >= 1)'

api POST /api/ai/assistant/ask '{"question":"帮我预测下个月的销量"}' "$TOKEN"
check "助手答不了时说清能力边界" 200 '.data.answer | contains("我可以回答")'

# ---------- 9. 批次与保质期（M1） ----------
api GET "/api/inventory/batches/$PRODUCT_ID" "" "$TOKEN"
check "商品批次台账（含到期日与批次成本）" 200 '.success == true and (.data | type == "array")'

api GET /api/inventory/expiry-summary "" "$TOKEN"
check "临期/过期汇总（按批次成本算压货金额）" 200 '.data.alertDays >= 1 and (.data.expiringBatchCount >= 0) and (.data.expiredBatchCount >= 0)'

api GET /api/inventory/batch-mismatch "" "$TOKEN"
check "批次数量与库存总数一致（不一致会被列出来）" 200 '.success == true and (.data | type == "array")'

# ---------- 10. 管家 Agent 与巡检日报（M2/M3） ----------
api GET /api/agent/tools "" "$TOKEN"
check "管家工具目录（按账号权限过滤）" 200 '(.data | length >= 9)'

api POST /api/agent/chat '{"question":"哪些商品快过期了"}' "$TOKEN"
check "管家对话：调用工具并给出保质期结论" 200 '(.data.toolsUsed | length >= 1) and (.data.answer | length > 10)'

api POST /api/agent/chat '{"question":"帮我看看库存够不够卖"}' "$TOKEN"
check "管家对话：多轮会话返回 sessionId" 200 '.data.sessionId | length > 0'

api POST /api/agent/session/reset "" "$TOKEN"
check "管家：开新会话清上下文" 200 '.data.sessionId | length > 0'

api POST /api/agent/chat '{"question":"帮我预测下个月的销量"}' "$TOKEN"
check "管家：答不了时说清能力边界且不乱调工具" 200 '(.data.answer | contains("我可以回答")) and (.data.toolsUsed | length == 0)'

api POST /api/steward/inspect "" "$TOKEN"
check "手动巡检生成日报（一句话总结 + 结构化发现）" 200 '(.data.headline | length > 10) and (.data.findings | type == "array")'

api GET /api/steward/reports/latest "" "$TOKEN"
check "巡检日报可回看" 200 '(.data.headline | length > 10) and (.data.reportDate | length == 10)'

api GET "/api/steward/reports?limit=3" "" "$TOKEN"
check "巡检日报历史列表" 200 '(.data | length >= 1)'

echo
echo "=== 结果 ==="
echo "通过：$pass  失败：$fail"
if [ "$fail" -gt 0 ]; then
  printf '失败明细：\n'
  for item in "${failures[@]}"; do printf ' - %s\n' "$item"; done
  exit 1
fi
echo "全部通过：登录权限、建档、采购入库、收银幂等、退货回补、报表对账、AI 录单、批次与保质期、管家 Agent 与巡检日报均正常"
exit 0
