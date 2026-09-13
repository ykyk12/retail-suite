# 云小店（retail-suite）

> 一句话：**给小微零售店用的进销存 + 收银系统**——进货登记、库存与预警、扫码收银、退货、日报与对账，一套跑起来就能用。
> 技术：Spring Boot 3 + MyBatis-Plus + MySQL 8 + Redis + JWT · Vue 3 + TypeScript + Element Plus + ECharts

当前版本 **1.0.0**：后端 46 个自动化测试、前端类型检查与构建、CI 两个 job 全绿；`docker compose up -d --build` 一键起全栈（MySQL + Redis + 后端 + Nginx 前端）。

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
| 库存 | 库存流水（每次变动都有账）、盘点调整（必须填原因）、低库存预警 | 库存**只能**通过库存服务变更，商品编辑接口不含库存字段 |
| 采购 | 采购单草稿 → 确认入库、取消 | 录单不动库存，确认入库才增加库存（符合门店"先登记、货到再入库"的实际流程） |
| 收银 | 扫码/搜索加购、改价、折扣、多种支付方式、幂等结算、小票打印 | 结算用 `requestId` 幂等键，重复提交/网络重试只产生一笔订单 |
| 订单与退货 | 订单查询、部分退货、超退拦截 | 退货按明细记 `refunded_quantity`，并发也不会退超 |
| 报表 | 实时经营概览、日报（物化汇总）、TOP 商品与毛利、**对账差异**、Excel 导出 | 对账比对"销售数量 vs 库存出库数量"，发现绕过收银的库存改动 |
| AI 录单 | 一句话录采购单（草稿 + 人工确认） | 大模型解析失败自动回退本地规则解析；**AI 不直接改账** |
| 经营助手 | 只读问答：营业额、畅销商品、库存预警、单品库存、对账 | 只挂只读工具；未配模型时走规则意图识别 |
| 审计 | 登录、结算、退货、入库、库存调整、AI 草稿确认全部留痕 | append-only，含 traceId 便于串联排查 |

## 3. 技术栈

**后端**：Java 17 · Spring Boot 3.3 · MyBatis-Plus 3.5.7 · MySQL 8 / H2 · Redis · JWT（自研 HS256）· BCrypt · Apache POI（流式导出）· springdoc-openapi · Actuator
**前端**：Vue 3 · TypeScript · Vite · Pinia · Vue Router · Element Plus · ECharts · axios
**工程**：Maven · npm · GitHub Actions（后端 `mvn verify` + 前端 `type-check && build`）· Docker Compose

## 4. 架构与数据流

```
┌──────────── 前端（Vue3 + Element Plus）─────────────┐
│ 收银台  │ 商品/库存/采购  │ 订单/退货 │ 报表对账 │ AI 录单/助手 │
└───────────────────────┬────────────────────────────┘
                        │ /api（开发期 Vite 代理；生产 Nginx 反代）
┌───────────────────────▼────────────────────────────┐
│ Nginx → 后端单体（分层：controller / service / mapper）│
│  鉴权拦截器（JWT + 权限码）→ 门店上下文（ThreadLocal）  │
│  ProductService · InventoryService · PurchaseService   │
│  SaleService（幂等）· ReportService · AI（NLP/LLM/只读工具）│
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

- MySQL 首次启动执行 `deploy/mysql/init/01-schema.sql` 建表 + 挂载的演示数据脚本灌 8 个商品
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

经营助手只挂只读工具（营业额/畅销/库存预警/单品库存/对账），模型只负责"选工具、传参数"，
真正的数字由 Java 查库返回；没配模型时走规则意图识别，功能照样可用。
CI 里跑的正是这条离线分支（10 个用例，含"答不了就说清能力边界"）。

## 7. 接口速览

| 分类 | 接口 | 权限码 |
|---|---|---|
| 认证 | `POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/logout` | 公开 / 登录 |
| 商品 | `GET/POST /api/products`、`PUT /api/products/{id}`、`GET /api/products/barcode/{barcode}`、`PATCH /{id}/status` | `product:read` / `product:write` |
| 分类 | `GET/POST /api/categories`、`PUT/DELETE /api/categories/{id}` | `product:read` / `category:write` |
| 库存 | `GET /api/inventory/low-stock`、`GET /api/inventory/flows/{productId}`、`POST /api/inventory/adjust` | `inventory:read` / `inventory:adjust` |
| 采购 | `GET/POST /api/purchases`、`POST /{id}/confirm`、`POST /{id}/cancel` | `purchase:read` / `purchase:write` |
| 收银 | `POST /api/sales/checkout`、`POST /api/sales/{id}/refund`、`GET /api/sales` | `sale:create` / `refund:create` / `sale:read` |
| 报表 | `GET /api/reports/overview`、`/daily`、`/top-products`、`/reconcile`、`POST /daily/{date}/rebuild`、`GET /export/daily` | `report:read` |
| AI | `POST /api/ai/drafts`、`POST /{id}/confirm`、`GET /api/ai/drafts`、`POST /api/ai/assistant/ask` | `ai:use` |

统一响应体：`{ success, code, message, data, traceId }`；错误码见 `ErrorCode`。

## 8. 测试与 CI

最近一次 CI（两个 job 全绿）：

- **后端** `mvn verify`：**46 个测试**
  - `AuthFlowTest`(8)：登录、错误密码不泄漏用户名是否存在、篡改签名被拒、未登录 401、收银员越权 403、参数校验
  - `ProductInventoryTest`(9)：**30 线程并发扣 10 件库存不超卖**、流水 before/after 自洽、库存不足不改数据、盘点需原因、跨门店不可见
  - `PurchaseFlowTest`(5)：录单不动库存、同商品合并、重复确认被状态机拒绝、已入库不可取消
  - `SaleOrderFlowTest`(7)：金额计算、幂等（串行 + **并发同 requestId 只落一笔**）、库存不足整笔回滚、部分退货与超退拦截
  - `ReportFlowTest`(6)：毛利口径（含退款成本）、汇总幂等且与实时口径一致、TOP 商品、对账发现人为差异、Excel 真实字节流
  - `AiModuleTest`(10)：规则解析（含条码与多行）、未识别不瞎猜、确认生成采购单但不动库存、销售草稿被拒、助手只读问答与能力边界
- **前端**：`npm run type-check`（vue-tsc 0 错误）+ `npm run build`（产出 dist 并上传 artifact）

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
| docker compose | 配置已就绪，但**开发机上没有 Docker，未做过真机验证** | 首次部署请按 `docs/DEPLOY.md` 逐项检查 |

## 10. 路线图

- **多店与连锁**：总部视角汇总、跨店调拨
- **支付与对账闭环**：微信/支付宝回调、日终自动对账
- **更强的 AI**：小票照片识别入库、按销售预测补货建议（仍然保持"草稿 + 人工确认"）
- **性能**：商品与库存的热点缓存、报表汇总的增量更新（只重算变动天）
- **交付**：一键部署脚本、备份恢复演练、Grafana 面板

## 11. 版本记录

| 版本 | 说明 |
|---|---|
| 1.0.0 | 首个完整版本：认证与权限（JWT + 权限码 + 门店隔离 + 审计）、商品与分类、库存条件更新防超卖与流水、采购入库、收银幂等结算、退货回补、日报与 TOP 商品与对账、Excel 流式导出、AI 录单与经营助手、Vue3 前端（收银台 + 管理后台）、双 job CI、Docker Compose 全栈部署 |

## 12. License

MIT
