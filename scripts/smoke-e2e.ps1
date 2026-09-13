# 云小店 端到端冒烟脚本
#
# 用途：一条命令验证"登录 → 建档 → 采购入库 → 收银(幂等) → 退货 → 报表对账 → AI 录单/助手"整条链路。
# 用法（后端已启动，默认 http://localhost:8080）：
#   pwsh -File scripts/smoke-e2e.ps1
#   pwsh -File scripts/smoke-e2e.ps1 -BaseUrl http://localhost:8080 -Username admin -Password admin123
#
# 退出码：0 = 全部通过；1 = 有失败项（会打印失败清单）。
# 注意：脚本会真实写入数据（新建商品、采购单、销售单），请在演示/测试环境跑。

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Username = "admin",
    [string]$Password = "admin123"
)

$ErrorActionPreference = "Stop"
$script:Pass = 0
$script:Fail = 0
$script:Failures = @()

function Step($name, [scriptblock]$body) {
    try {
        $result = & $body
        if ($result -eq $false) { throw "断言失败" }
        $script:Pass++
        Write-Host ("[PASS] " + $name) -ForegroundColor Green
    } catch {
        $script:Fail++
        $script:Failures += "$name :: $($_.Exception.Message)"
        Write-Host ("[FAIL] " + $name + " :: " + $_.Exception.Message) -ForegroundColor Red
    }
}

function Api($method, $path, $body, $token) {
    $headers = @{ "Content-Type" = "application/json" }
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $uri = "$BaseUrl$path"
    if ($body) {
        $json = $body | ConvertTo-Json -Depth 8 -Compress
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        $resp = Invoke-WebRequest -Method $method -Uri $uri -Headers $headers -Body $bytes -UseBasicParsing
    } else {
        $resp = Invoke-WebRequest -Method $method -Uri $uri -Headers $headers -UseBasicParsing
    }
    $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    $obj = $text | ConvertFrom-Json
    if ($obj.success -eq $false) { throw "$($obj.code): $($obj.message)" }
    return $obj.data
}

Write-Host "=== 云小店端到端冒烟 ===" -ForegroundColor Cyan
Write-Host "目标：$BaseUrl"

$token = $null
$productId = $null
$stockAfterPurchase = 0
$orderNo = $null
$requestId = "SMOKE-" + [guid]::NewGuid().ToString()

Step "登录（admin）" {
    $login = Api "POST" "/api/auth/login" @{ username = $Username; password = $Password } $null
    if (-not $login.token) { throw "未返回令牌" }
    $script:token = $login.token
    if ($login.user.permissions.Count -lt 5) { throw "权限码数量异常：$($login.user.permissions.Count)" }
    return $true
}

Step "当前用户与门店上下文" {
    $me = Api "GET" "/api/auth/me" $null $token
    if ($me.username -ne $Username) { throw "用户名不匹配：$($me.username)" }
    return $true
}

Step "新建商品（期初库存 5）" {
    $barcode = "69" + (Get-Random -Minimum 10000000000 -Maximum 99999999999)
    $product = Api "POST" "/api/products" @{
        name = "冒烟测试商品-" + (Get-Random); barcode = $barcode; spec = "500ml"; unit = "瓶"
        purchasePrice = 2.00; salePrice = 3.50; initStock = 5; lowStockThreshold = 10
    } $token
    $script:productId = $product.id
    if ($product.stock -ne 5) { throw "期初库存应为 5，实际 $($product.stock)" }
    return $true
}

Step "库存流水写入了期初建库（库存不是凭空来的）" {
    $flows = Api "GET" "/api/inventory/flows/$productId?limit=10" $null $token
    if ($flows.Count -lt 1) { throw "没有流水记录" }
    return $true
}

Step "库存预警包含新商品（库存 5 ≤ 阈值 10）" {
    $low = Api "GET" "/api/inventory/low-stock" $null $token
    $hit = $low | Where-Object { $_.productId -eq $productId }
    if (-not $hit) { throw "预警列表中没有该商品" }
    return $true
}

Step "采购单：录单不动库存" {
    $order = Api "POST" "/api/purchases" @{
        supplierName = "冒烟供应商"; remark = "冒烟测试"
        items = @(@{ productId = $productId; quantity = 20; unitCost = 2.00 })
    } $token
    $detail = Api "GET" "/api/products/$productId" $null $token
    if ($detail.stock -ne 5) { throw "草稿状态不应改动库存，实际 $($detail.stock)" }
    $script:purchaseOrderId = $order.id
    return $true
}

Step "采购单：确认入库后库存 +20" {
    $confirmed = Api "POST" "/api/purchases/$purchaseOrderId/confirm" $null $token
    if ($confirmed.status -ne "CONFIRMED") { throw "状态应为 CONFIRMED，实际 $($confirmed.status)" }
    $detail = Api "GET" "/api/products/$productId" $null $token
    if ($detail.stock -ne 25) { throw "库存应为 25，实际 $($detail.stock)" }
    $script:stockAfterPurchase = $detail.stock
    return $true
}

Step "重复确认入库被状态机拒绝" {
    try {
        Api "POST" "/api/purchases/$purchaseOrderId/confirm" $null $token | Out-Null
        throw "竟然允许重复确认"
    } catch {
        if ($_.Exception.Message -notmatch "CONFLICT|状态") { throw "拒绝原因不可读：$($_.Exception.Message)" }
    }
    return $true
}

Step "收银结算（应收 10.50，库存 25 → 22）" {
    $sale = Api "POST" "/api/sales/checkout" @{
        requestId = $requestId; customerName = "冒烟顾客"
        items = @(@{ productId = $productId; quantity = 3 })
        discountAmount = 0; payMethod = "WECHAT"
    } $token
    if ([decimal]$sale.payAmount -ne 10.50) { throw "实收应为 10.50，实际 $($sale.payAmount)" }
    $script:orderNo = $sale.orderNo
    $script:saleId = $sale.id
    $script:orderItemId = $sale.items[0].id
    $detail = Api "GET" "/api/products/$productId" $null $token
    if ($detail.stock -ne 22) { throw "库存应为 22，实际 $($detail.stock)" }
    return $true
}

Step "幂等：同一 requestId 重复提交返回同一单号且不重复扣库存" {
    $again = Api "POST" "/api/sales/checkout" @{
        requestId = $requestId; customerName = "冒烟顾客"
        items = @(@{ productId = $productId; quantity = 3 })
        discountAmount = 0; payMethod = "WECHAT"
    } $token
    if ($again.orderNo -ne $orderNo) { throw "单号不一致：$($again.orderNo) vs $orderNo" }
    if ($again.duplicated -ne $true) { throw "未标记为幂等命中" }
    $detail = Api "GET" "/api/products/$productId" $null $token
    if ($detail.stock -ne 22) { throw "库存被重复扣减，实际 $($detail.stock)" }
    return $true
}

Step "库存不足时整笔回滚（不会留下订单）" {
    $failId = "SMOKE-FAIL-" + [guid]::NewGuid().ToString()
    try {
        Api "POST" "/api/sales/checkout" @{
            requestId = $failId; items = @(@{ productId = $productId; quantity = 999 })
            discountAmount = 0; payMethod = "CASH"
        } $token | Out-Null
        throw "库存不足竟然结算成功"
    } catch {
        if ($_.Exception.Message -notmatch "库存") { throw "错误信息不可读：$($_.Exception.Message)" }
    }
    $detail = Api "GET" "/api/products/$productId" $null $token
    if ($detail.stock -ne 22) { throw "失败的结算改动了库存，实际 $($detail.stock)" }
    return $true
}

Step "部分退货：退 1 件回补库存，状态为部分退货" {
    $refunded = Api "POST" "/api/sales/$saleId/refund" @{
        items = @(@{ orderItemId = $orderItemId; quantity = 1 }); remark = "冒烟测试退货"
    } $token
    if ($refunded.status -ne "PARTIAL_REFUNDED") { throw "状态应为 PARTIAL_REFUNDED，实际 $($refunded.status)" }
    $detail = Api "GET" "/api/products/$productId" $null $token
    if ($detail.stock -ne 23) { throw "退货后库存应为 23，实际 $($detail.stock)" }
    return $true
}

Step "超退被拒绝" {
    try {
        Api "POST" "/api/sales/$saleId/refund" @{ items = @(@{ orderItemId = $orderItemId; quantity = 99 }) } $token | Out-Null
        throw "竟然允许超退"
    } catch {
        if ($_.Exception.Message -notmatch "超过|CONFLICT") { throw "拒绝原因不可读：$($_.Exception.Message)" }
    }
    return $true
}

Step "经营概览能查到今天的成交" {
    $overview = Api "GET" "/api/reports/overview" $null $token
    if ([decimal]$overview.salesAmount -le 0) { throw "销售额应大于 0" }
    return $true
}

Step "对账：销售数量与库存出库一致" {
    $reconcile = Api "GET" "/api/reports/reconcile" $null $token
    if ($reconcile.consistent -ne $true) {
        throw "存在对账差异：$($reconcile.diffs | ConvertTo-Json -Compress)"
    }
    return $true
}

Step "日汇总重算并落库（幂等）" {
    $today = (Get-Date).ToString("yyyy-MM-dd")
    $first = Api "POST" "/api/reports/daily/$today/rebuild" $null $token
    $second = Api "POST" "/api/reports/daily/$today/rebuild" $null $token
    if ([decimal]$first.netAmount -ne [decimal]$second.netAmount) { throw "两次重算结果不一致" }
    return $true
}

Step "AI 录单：自然语言解析为草稿（不产生单据）" {
    $draft = Api "POST" "/api/ai/drafts" @{ text = "进了 6 瓶冒烟测试商品 单价 2.0"; type = "PURCHASE" } $token
    if ($draft.status -ne "PENDING") { throw "草稿状态应为 PENDING" }
    $script:draftId = $draft.id
    return $true
}

Step "AI 草稿：识别不出商品的行不会被瞎猜（确认被拒）" {
    $bad = Api "POST" "/api/ai/drafts" @{ text = "进了 3 瓶根本不存在的饮料"; type = "PURCHASE" } $token
    if ($bad.hasUnresolved -ne $true) { throw "未标记未识别行" }
    try {
        Api "POST" "/api/ai/drafts/$($bad.id)/confirm" @{} $token | Out-Null
        throw "未识别的草稿竟然确认成功"
    } catch {
        if ($_.Exception.Message -notmatch "未识别|BAD_REQUEST") { throw "拒绝原因不可读：$($_.Exception.Message)" }
    }
    return $true
}

Step "AI 草稿：人工确认后生成采购单（草稿态）" {
    $confirmed = Api "POST" "/api/ai/drafts/$draftId/confirm" @{ supplierName = "冒烟供应商" } $token
    if ($confirmed.status -ne "CONFIRMED") { throw "草稿状态应为 CONFIRMED，实际 $($confirmed.status)" }
    if (-not $confirmed.createdRefNo) { throw "未返回生成的采购单号" }
    return $true
}

Step "AI 草稿：销售类单据被拒绝（必须走收银台）" {
    $saleDraft = Api "POST" "/api/ai/drafts" @{ text = "卖了 2 瓶冒烟测试商品"; type = "SALE" } $token
    try {
        Api "POST" "/api/ai/drafts/$($saleDraft.id)/confirm" @{} $token | Out-Null
        throw "销售草稿竟然能生成单据"
    } catch {
        if ($_.Exception.Message -notmatch "收银台|BAD_REQUEST") { throw "拒绝原因不可读：$($_.Exception.Message)" }
    }
    return $true
}

Step "经营助手：营业额问答（只读工具）" {
    $answer = Api "POST" "/api/ai/assistant/ask" @{ question = "今天卖了多少" } $token
    if ($answer.answer -notmatch "营业额") { throw "回答未包含营业额：$($answer.answer)" }
    return $true
}

Step "经营助手：答不了的问题会说清能力边界" {
    $answer = Api "POST" "/api/ai/assistant/ask" @{ question = "帮我预测下个月的销量" } $token
    if ($answer.answer -notmatch "我可以回答") { throw "未给出能力边界说明：$($answer.answer)" }
    return $true
}

Step "越权校验：未登录访问受保护接口返回 401" {
    try {
        Invoke-WebRequest -Method GET -Uri "$BaseUrl/api/products" -UseBasicParsing | Out-Null
        throw "未登录竟然访问成功"
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -ne 401) { throw "应返回 401，实际 $status" }
    }
    return $true
}

Write-Host ""
Write-Host "=== 结果 ===" -ForegroundColor Cyan
Write-Host ("通过：" + $script:Pass + "  失败：" + $script:Fail)
if ($script:Fail -gt 0) {
    Write-Host "失败明细：" -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host (" - " + $_) -ForegroundColor Red }
    exit 1
}
Write-Host "全部通过：登录/权限、建档、采购入库、收银幂等、退货回补、报表对账、AI 录单与助手均正常" -ForegroundColor Green
exit 0
