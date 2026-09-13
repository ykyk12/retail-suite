# 云小店（retail-suite）

> 一句话：**给小微零售店用的进销存 + 收银系统**——进货登记、库存与预警、扫码收银、退货、日报与对账，一套跑起来就能用。
> 技术：Spring Boot 3 + MyBatis-Plus + MySQL 8 + Redis + JWT · Vue 3 + TypeScript + Element Plus + ECharts

当前版本 **1.3.0**：后端 70 个自动化测试、前端类型检查与构建、CI 三个 job 全绿（含 docker compose 全栈冒烟 40 项）；`docker compose up -d --build` 一键起全栈（MySQL + Redis + 后端 + Nginx 前端）。

---

## 1. 谁可以用它

- 校园超市 / 便利店 / 水果店：店员用收银台扫码结账、打印小票，店长用后台看报表、管商品、录进货
- 实验室 / 社团的耗材房：进货入库、领用出库、库存预警（把"商品"换成"耗材"即可）
- 单店直接可用；数据模型已按门店隔离（`store_id`），扩到小连锁不需要重构

## 2. 功能清单

| 模块 | 功能 | 说明 |
|---|---|---|
| 认证与权限 | JWT 登录、角色/权限码、门店数据隔离 | 密码 BCrypt；权限码如 `sale:create`、`purchase:write`，接口上直接声明 |
| 商品 | 商品与分类 CRUD、条码检索、上架/停售、低库存标记 | 条码门店内唯一（数据库唯一索引兜底） |
| 库存 | 库存流水（每次变动都有账）、批次台账（进价/生产日/到期日）、盘点调整（必须填原因）、低库存预警 | 库存**只能**通过库存服务变更；出库按 FEFO（先到期先出）扣批次，商品编辑接口不含库存字段 |
| 保质期管家 | 临期批次、已过期批次、临期压了多少钱、批次与库存不符自查 | 到期日缺失时按"入库日 + 商品保质期"推算并留痕；`/api/inventory/expiring` 等接口供前端与 Agent 共用 |
| 采购 | 采购单草稿 → 确认入库、取消 | 录单不动库存，确认入库才增加库存并生成批次（符合门店"先登记、货到再入库"的实际流程） |
| 收银 | 扫码/搜索加购、改价、折扣、多种支付方式、幂等结算、小票打印 | 结算用 `requestId` 幂等键，重复提交/网络重试只产生一笔订单；销售按批次锁成本，毛利含真实进价 |
| 订单与退货 | 订单查询、部分退货、超退拦截 | 退货按明细记 `refunded_quantity`，并发也不会退超 |
| 报表 | 实时经营概览、日报（物化汇总）、TOP 商品与毛利、**对账差异**、Excel 导出 | 对账比对"销售数量 vs 库存出库数量"，发现绕过收银的库存改动 |
| AI 录单 | 一句话录采购单（草稿 + 人工确认） | 大模型解析失败自动回退本地规则解析；**AI 不直接改账** |
| 管家 Agent | 多轮对话 + 工具调用：经营概况、商品排行、单品画像（进价/售价/毛利/批次/多久没卖）、临期与过期、缺货风险、补货建议、滞销、**毛利异常**、对账、**生成采购单草稿** | 10 个声明式工具，工具自带权限码与只读标记；运行时做权限过滤 + 每用户每分钟限流 + 每次调用审计；写操作只出草稿，人工确认才落单；未配模型时同一条工具链走规则兜底（`source=RULE`） |
| 管家巡检日报 | 每天开门前自动巡检每个门店：过期批次、临期批次、断货风险、补货建议、滞销、毛利异常、账实不符、批次与库存不符 → 落库为结构化日报，**补货建议可一键转采购单草稿** | 同门店同一天唯一（重复巡检覆盖）；报告保留"当时的口径"，可回看历史；一键转草稿走 Agent 工具执行器，权限/限流/审计与对话路径完全一致 |
| 审计 | 登录、结算、退货、入库、库存调整、AI 草稿确认全部留痕 | append-only，含 traceId 便于串联排查 |

前端页面（Vue3，菜单按权限码自动显隐）：

| 页面 | 路径 | 说明 |
|---|---|---|
| 经营看板 / 收银台 / 商品管理 / 库存管理 / 采购进货 / 订单查询 / 报表对账 | `/dashboard` … | 日常进销存与收银 |
| **临期与批次** | `/expiry` | 临期（要促销）、已过期（要下架报损）、批次与库存不符三视角；过期批次可一键下架报损 |
| **管家对话** | `/assistant` | 多轮会话：工具轨迹表（调了什么工具、参数、返回、是否需人工确认）+ 结构化建议卡片；还能看到当前账号可用的工具目录 |
| **管家日报** | `/steward-report` | 严重度排序的发现卡片、明细表格、一键生成采购单草稿，以及历史报告时间线 |
| AI 录单 | `/ai-draft` | 一句话录采购单（草稿 + 人工确认） |

## 3. 技术栈

**后端**：Java 17 · Spring Boot 3.3 · MyBatis-Plus 3.5.7 · MySQL 8 / H2 · Redis · JWT（自研 HS256）· BCrypt · Apache POI（流式导出）· springdoc-openapi · Actuator
**前端**：Vue 3 · TypeScript · Vite · Pinia · Vue Router · Element Plus · ECharts · axios
**工程**：Maven · npm · GitHub Actions（后端 `mvn verify` + 前端 `type-check && build`）· Docker Compose

## 4. 架构与数据流

```
┌──────────── 前端（Vue3 + Element Plus）─────────────┐
│ 收银台 │ 商品/库存/采购 │ 订单/退货 │ 报表对账 │ 管家 Agent 对话 │
└───────────────────────┬────────────────────────────┘
                        │ /api（开发期 Vite 代理；生产 Nginx 反代）
┌───────────────────────▼────────────────────────────┐
│ Nginx → 后端单体（分层：controller / service / mapper）│
│  鉴权拦截器（JWT + 权限码）→ 门店上下文（ThreadLocal）  │
│  ProductService · InventoryService（批次/效期）         │
│  PurchaseService · SaleService（幂等）· ReportService   │
│  Agent 运行时：工具注册表 → 守卫（权限/限流/审计）→ 执行器│
│  AI 录单：NLP / LLM 解析 → 草稿 → 人工确认             │
└───────┬─────────────────────────────────┬────────────┘
        │ 同库事务（库存、订单、流水必须一致）   │
   ┌────▼─────┐                       ┌─────▼─────┐
   │ MySQL 8  │                       │  Redis    │
   │ 业务表+汇总│                       │ 预留：缓存/限流│
   └──────────┘                       └───────────┘
```

**为什么是单体而不是微服务**（面试常问）：库存扣减、订单落库、流水记账必须在同一个本地事务里完成。
拆成微服务会把一个事务变成分布式事务，引入对账与补偿复杂度，而单店/小连锁的数据量与团队规模完全用不上。
真到多店高并发阶段，再按"订单 / 库存 / 报表"拆分也不迟。

## 5. 快速开始

### 5.1 本地零依赖（推荐先跑这个）

后端默认使用 H2 文件库（`MODE=MySQL`），不需要装 MySQL/Redis：

```bash
mvn spring-boot:run
# 接口文档 http://localhost:8080/swagger-ui.html
# H2 控制台 http://localhost:8080/h2-console （JDBC URL: jdbc:h2:file:./data/retailsuite）
```

前端：

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173 ，/api 自动代理到 8080
```

**一键验证整条链路**（后端起来之后跑，会真实写入演示数据）：

```bash
pwsh -File scripts/smoke-e2e.ps1
# 覆盖：登录权限 → 建档 → 采购入库（含重复确认被拒）→ 收银幂等 → 库存不足回滚
#       → 部分退货与超退拦截 → 报表对账 → 日汇总幂等 → AI 录单（含未识别拦截、销售草稿被拒）→ 助手问答 → 未登录 401
```

演示账号（后端首次启动时自动创建）：

| 账号 | 密码 | 权限 |
|---|---|---|
| `admin` | `admin123` | 全部（含进货、库存调整、用户管理） |
| `cashier` | `cashier123` | 收银、退货、查库存与报表（**没有**进货与库存调整权限） |

可以拿 cashier 登录点一下"采购进货"，会被后端 403 拒绝——这是权限体系真的在生效，而不是前端藏了按钮。

### 5.2 docker compose 一键全栈

```bash
cp .env.example .env      # 按需改数据库密码与 JWT 密钥
docker compose up -d --build
# 浏览器打开 http://localhost ，用 admin/admin123 登录
```

- MySQL 首次启动执行 `deploy/mysql/init/01-schema.sql`（建表）与 `02-seed.sql`（演示门店/分类/8 个商品/期初流水），只在容器首次初始化时跑一次
- 账号与权限由后端启动时**幂等**创建（`DemoDataInitializer`：存在即跳过，不含任何 DELETE）
- 数据存在具名 volume，`docker compose down` 不删数据；要清空加 `-v`
- 想接大模型：在 `.env` 填 `AI_API_KEY`（不填也能用，AI 自动走本地规则解析）

## 6. 关键设计（也是面试可深挖的点）

### 6.1 库存防超卖：条件更新，而不是先查再改

```sql
UPDATE product SET stock = stock - #{qty}, version = version + 1
 WHERE id = #{id} AND store_id = #{storeId} AND deleted = 0 AND stock >= #{qty}
```

一条语句里同时完成"判断够不够"和"扣减"，靠数据库行锁保证原子性，返回影响行数 0 就是库存不足。
`ProductInventoryTest` 用一个真实并发用例证明它有效：**库存 10、30 个线程并发各扣 1，恰好 10 次成功、20 次被明确拒绝、最终库存为 0**。

为什么不用另外几种：
- 悲观锁 `select ... for update`：把同一商品的并发全部排队，收银高峰容易把连接池占满；只有"必须跨多表持锁"时才值得
- 乐观锁 `@Version`：适合"读-改-写"式更新，冲突要重试，高并发下重试成本高（本项目在商品整体更新上保留 `@Version`）
- Redis / 分布式锁：单库单表不需要，多引入一个失败点（真实的分布式扣减才需要）

### 6.2 收银幂等：唯一索引是最终兜底

收银员手抖点两下、扫码枪连发、网络重试都会造成重复单。做法：

1. 先按 `requestId` 查一次，命中直接返回首次结果（快路径，无副作用）
2. 没命中才真正下单，最终由 `sale_order.request_id` 的**唯一索引**兜底
3. 捕获唯一键冲突后**在事务外**回查首次结果返回

第 3 点的原因很具体：如果把"查重 + 插入"放在同一个事务里，唯一键冲突会把事务标记为 rollback-only，
之后回查会抛 `UnexpectedRollbackException`。所以幂等入口用 `TransactionTemplate` 只包住真正的下单动作。
`SaleOrderFlowTest` 验了两条路：同一 `requestId` 串行重复提交、以及 **10 个线程并发提交同一 `requestId`**——都只产生一笔订单、只扣一次库存。

### 6.3 退货防超退：同样用条件更新

```sql
UPDATE sale_order_item SET refunded_quantity = refunded_quantity + #{qty}
 WHERE id = #{itemId} AND order_id = #{orderId} AND quantity - refunded_quantity >= #{qty}
```

并发两笔退货也不会把同一个商品退超（退超等于凭空多出货、库存虚增）。

### 6.4 采购两阶段：录单不动库存，确认入库才动

门店真实流程是"先登记进货，货到了再确认入库"。因此采购单有状态机 `DRAFT → CONFIRMED`，
确认用 `where status='DRAFT'` 的条件更新，天然防止并发重复确认导致库存被加两次。

### 6.5 报表口径与毛利

- **实时口径**：直接聚合当天订单与明细（首页看板用，永远最新）
- **汇总口径**：日汇总物化表 `daily_sales_summary`（趋势图与导出用，数据量大也不慢）；`(store_id, summary_date)` 唯一索引保证重复汇总不产生两行
- **毛利**：销售明细在下单时**冻结成本价**（`sale_order_item.cost_price`），公式为
  `毛利 =（销售额 − 已售成本）−（退款额 − 已退成本）`。
  用商品"今天的进价"去算历史毛利是常见错误，进价一波动历史报表就失真。

### 6.6 对账：销售数量 vs 库存出库数量

两侧都由系统自动写入，正常必须完全一致；不一致就说明有人绕过收银动了库存。
`ReportFlowTest` 里刻意"人为制造差异"（直接扣库存但不落订单），验证对账真的能报出来——
只会说"一切正常"的对账等于没有对账。

### 6.7 AI 模块的三条安全边界

1. **AI 不直接改账**：解析结果只落 `ai_draft`，人工确认后才生成采购单（且是草稿态，入库仍需采购页确认）
2. **销售单不允许 AI 生成**：销售涉及收款与库存扣减，必须由收银员在收银台逐步确认（接口层面直接拒绝）
3. **解析来源可追溯**：草稿记录 `source=LLM|RULE`、原始文本与解析结果；解析不出的行**标注出来让人改，绝不瞎猜商品**

经营助手（管家 Agent）只挂只读工具 + 一个"只出草稿"的写工具，模型只负责"选工具、传参数"，
真正的数字由 Java 查库返回；没配模型时走规则意图识别，**同一条工具链**照样可用。
CI 里跑的正是这条离线分支（`AgentRuntimeTest` 9 + `AiModuleTest` 10，含"答不了就说清能力边界"）。

### 6.8 管家 Agent：把"会不会乱来"变成架构问题

Agent 的危险不在于答错，而在于**它有权改你的账**。所以约束写在接口上，而不是写在提示词里：

| 约束 | 实现 | 面试可深挖 |
|---|---|---|
| 工具自己的能力 | `AgentTool` 接口声明 `name/description/parameters/permission/readOnly` | 权限是代码属性，不是提示词里的一句"请不要…" |
| 模型只看得见有权限的工具 | `AgentToolRegistry.catalogFor(user)` 按权限过滤后写进系统提示词 | 收银员根本看不到进货类工具，谈不上误调 |
| 每次都过守卫 | `AgentGuard`：权限码校验 + 每用户每分钟限流（30 次）+ 审计 `AGENT_TOOL_CALL/DENIED/RATE_LIMITED` | 越权与刷接口都留痕 |
| 参数不信任模型 | 注册表按 `parameters()` 校验必填，缺失直接拒绝 | 宁可让模型重问，也不瞎猜商品 |
| 写操作只出草稿 | `draft_purchase_order` 只创建草稿采购单，库存不变 | 真正的库存变更仍需人工在采购页确认 |
| 门店隔离 | `storeId` 由登录态注入，**不接受模型传参** | 从根上避免"帮我查隔壁店" |
| 会话与降级 | `AgentSessionStore` 按用户隔离、TTL 2h、轮次裁剪；模型不可用 → 规则兜底 `source=RULE` | 断网时管家照样能查库存 |

## 7. 接口速览

| 分类 | 接口 | 权限码 |
|---|---|---|
| 认证 | `POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/logout` | 公开 / 登录 |
| 商品 | `GET/POST /api/products`、`PUT /api/products/{id}`、`GET /api/products/barcode/{barcode}`、`PATCH /{id}/status` | `product:read` / `product:write` |
| 分类 | `GET/POST /api/categories`、`PUT/DELETE /api/categories/{id}` | `product:read` / `category:write` |
| 库存 | `GET /api/inventory/low-stock`、`GET /api/inventory/flows/{productId}`、`POST /api/inventory/adjust`、`POST /api/inventory/loss` | `inventory:read` / `inventory:adjust` / `inventory:loss` |
| 保质期 | `GET /api/inventory/batches/{productId}`、`/expiring`、`/expired`、`/expiry-summary`、`/batch-mismatch` | `inventory:read` |
| 采购 | `GET/POST /api/purchases`、`POST /{id}/confirm`、`POST /{id}/cancel` | `purchase:read` / `purchase:write` |
| 收银 | `POST /api/sales/checkout`、`POST /api/sales/{id}/refund`、`GET /api/sales` | `sale:create` / `refund:create` / `sale:read` |
| 报表 | `GET /api/reports/overview`、`/daily`、`/top-products`、`/reconcile`、`POST /daily/{date}/rebuild`、`GET /export/daily` | `report:read` |
| AI 录单 | `POST /api/ai/drafts`、`POST /{id}/confirm`、`GET /api/ai/drafts` | `ai:use` |
| 管家 Agent | `POST /api/agent/chat`（多轮 + 工具轨迹 + 建议卡片）、`GET /api/agent/tools`（当前账号可用工具）；旧路径 `POST /api/ai/assistant/ask` 保留兼容 | `ai:use` |
| 管家巡检 | `POST /api/steward/inspect`（立即巡检）、`GET /api/steward/reports/latest`、`GET /api/steward/reports`、`GET /api/steward/reports/{id}` | `report:read` |
| 巡检动作 | `POST /api/steward/reports/{id}/findings/{code}/actions`（一键生成采购单草稿） | `ai:use` + 工具级 `purchase:write` |

统一响应体：`{ success, code, message, data, traceId }`；错误码见 `ErrorCode`。

## 8. 测试与 CI

最近一次 CI（三个 job 全绿：后端单测 + 前端构建 + docker compose 全栈冒烟）：

- **后端** `mvn verify`：**70 个测试**
  - `AuthFlowTest`(8)：登录、错误密码不泄漏用户名是否存在、篡改签名被拒、未登录 401、收银员越权 403、参数校验
  - `ProductInventoryTest`(9)：**30 线程并发扣 10 件库存不超卖**、流水 before/after 自洽、库存不足不改数据、盘点需原因、跨门店不可见
  - `BatchExpiryTest`(6)：批次入库、FEFO 先到期先出、临期/过期查询、到期日缺失按保质期推算并留痕、批次与库存对不上能被自查出来
  - `PurchaseFlowTest`(5)：录单不动库存、同商品合并、重复确认被状态机拒绝、已入库不可取消
  - `SaleOrderFlowTest`(7)：金额计算、幂等（串行 + **并发同 requestId 只落一笔**）、库存不足整笔回滚、部分退货与超退拦截
  - `ReportFlowTest`(6)：毛利口径（含退款成本）、汇总幂等且与实时口径一致、TOP 商品、对账发现人为差异、Excel 真实字节流
  - `AgentRuntimeTest`(9)：工具注册与只读标记、收银员看不到进货工具、越权直接拒绝、**采购草稿不动库存**、会话记忆与裁剪、规则兜底与能力边界
  - `StewardInspectionTest`(6)：发现过期/临期批次与压货金额、补货建议一键转草稿**且库存不变**、无进货权限账号点不动、同一天重复巡检是覆盖不是新增、滞销与负毛利识别、报告跨门店不可见
  - `AiModuleTest`(10)：规则解析（含条码与多行）、未识别不瞎猜、确认生成采购单但不动库存、销售草稿被拒、助手问答与能力边界
  - `AcceptanceSmokeTest`(3)：HTTP 层全链路（登录 → 建商品 → 进货 → 收银 → 退货 → 对账 → 管家问答）
- **前端**：`npm run type-check`（vue-tsc 0 错误）+ `npm run build`（产出 dist 并上传 artifact）
- **e2e**：`docker compose` 起 MySQL/Redis/后端/前端 → 等健康检查 → 通过 Nginx 跑 40 项端到端冒烟（覆盖批次台账、临期汇总、管家对话、巡检日报）

## 9. 已知边界（诚实清单）

| 边界 | 现状 | 说明 / 计划 |
|---|---|---|
| 多门店汇总 | 数据按 `store_id` 隔离，但只有单店视角 | 小连锁需加"总部看分店"的汇总视图 |
| 支付 | 只记录支付方式，不接真实支付通道 | 接微信/支付宝需处理回调幂等与对账，属于下一步 |
| 库存并发 | 单库条件更新，未上 Redis 预扣 | 单库足够；若做超卖敏感的高并发电商场景再加预扣 |
| AI 解析 | 规则解析覆盖常见句式，复杂口语会落到"未识别让人改" | 接真实模型后可提升；离线仍保底可用 |
| Redis | 已在依赖与 compose 里，但业务只用内存实现替代 | 预留做商品缓存与接口限流 |
| 前端 | 只做 PC 端；收银台未做小键盘/触屏优化 | 移动端与扫码枪硬件适配待补 |
| 打印 | 用浏览器打印（80mm CSS） | 直连小票打印机需本地打印服务 |
| docker compose | **已由 CI 的 e2e job 真机验证**：构建镜像 → 起 MySQL/Redis/后端/前端 → 等健康检查 → 通过 Nginx（80 端口）跑 31 项端到端冒烟 | 首次在你自己的机器上部署请按 `docs/DEPLOY.md` 检查端口与密码 |

## 10. 路线图

- **多店与连锁**：总部视角汇总、跨店调拨
- **支付与对账闭环**：微信/支付宝回调、日终自动对账
- **更强的 AI**：小票照片识别入库、把巡检日报推送到微信/钉钉（现在已生成报告，还差"主动推送"这一步）
- **性能**：商品与库存的热点缓存、报表汇总的增量更新（只重算变动天）
- **交付**：一键部署脚本、备份恢复演练、Grafana 面板

## 11. 版本记录

| 版本 | 说明 |
|---|---|
| 1.3.0 | 前端补齐管家能力：管家对话页（多轮会话 + 工具轨迹表 + 结构化建议卡片）、临期与批次页（临期/过期/批次不符三个视角 + 下架报损）、管家日报页（严重度排序 + 明细卡片 + 一键转采购草稿）、商品与采购单的保质期/生产日期字段；架构与部署文档同步（批次模型、Agent 与巡检架构、v1.2.0 迁移与故障排查） |
| 1.2.0 | 管家主动巡检：每天开门前自动巡检（过期/临期/断货/补货/滞销/毛利异常/账实不符/批次不符）→ 结构化日报落库 → 补货建议一键转采购草稿；新增毛利异常工具（第 10 个工具）；补货与滞销口径抽成单一口径服务（对话与日报结论必然一致）；e2e 冒烟扩到 40 项 |
| 1.1.0 | 批次与保质期：批次台账（进价/生产日/到期日）、FEFO 先到期先出、过期报损、临期汇总；管家 Agent：9 个声明式工具 + 权限过滤 + 限流 + 审计 + 多轮会话 + 规则兜底，写操作只出草稿；旧助手接口统一由 Agent 运行时接管；e2e 冒烟扩到 31 项 |
| 1.0.0 | 首个完整版本：认证与权限（JWT + 权限码 + 门店隔离 + 审计）、商品与分类、库存条件更新防超卖与流水、采购入库、收银幂等结算、退货回补、日报与 TOP 商品与对账、Excel 流式导出、AI 录单与经营助手、Vue3 前端（收银台 + 管理后台）、双 job CI、Docker Compose 全栈部署 |

## 12. License

MIT
