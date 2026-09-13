# 部署与运维

## 0. 前置说明

`docker-compose.yml`、两个 `Dockerfile`、MySQL 初始化 SQL 都已就绪，并且**已由 CI 的 `e2e` job 真机验证过**：
在 GitHub runner（自带 Docker）上构建镜像 → `docker compose up -d` → 等待 `/actuator/health` 通过 →
通过 Nginx（80 端口）执行 `scripts/smoke-e2e.sh` 的 31 项端到端断言 → 无论成败都 `docker compose down -v` 清理。

也就是说"能起来"这件事有 CI 记录可查；但**你机器上的端口占用、防火墙、密码策略仍需自行确认**，见下面第 7 节。

## 1. 准备

- Docker 20.10+ 与 Compose v2（`docker compose version` 能跑）
- 一台能跑 Docker 的机器（店内收银机/一台闲置笔记本/云主机均可），内存建议 ≥ 2G
- 端口：`80`（前端）、`8080`（后端，仅本机）、`3306`/`6379`（仅本机）

## 2. 一键部署

```bash
git clone <你的仓库地址> retail-suite && cd retail-suite
cp .env.example .env
vi .env                 # 必改：MYSQL_PASSWORD、JWT_SECRET（≥32 字节随机串）

docker compose up -d --build
docker compose ps       # 四个容器都应是 healthy / running
```

浏览器打开 `http://<服务器IP>`，用 `admin / admin123` 登录；**登录后第一件事是改密码或新建自己的账号**。

## 3. 初始化发生了什么

1. MySQL 首次启动执行 `deploy/mysql/init/` 下的脚本（按文件名字母序）：
   - `01-schema.sql`：建表（MySQL 方言）
   - `02-seed.sql`：演示门店 + 分类 + 8 个商品 + 期初库存流水
   注意：这两个脚本**只在数据目录为空时执行一次**；已经有 `mysql-data` volume 时改脚本不会生效（见第 6 节升级说明）。
2. 后端启动时 `DemoDataInitializer` 幂等补齐（**只增不改、不含 DELETE**）：
   - 门店（按 code 判断）、角色与权限码、分类、演示商品（仅当门店没有任何商品时）
   - `admin` / `cashier` 账号（BCrypt 哈希在运行时生成，不写死在 SQL 里）
3. 后端定时任务：
   - 每天 `00:10` 重算日汇总（`app.report.daily-summary-cron`）
   - 每天 `07:30` 管家巡检，为每个在营门店生成当天日报（`app.steward.inspect-cron`，门店开门前）
4. 老库升级（已有数据）按 `deploy/mysql/migration/` 下的脚本顺序执行：
   - `v1.1.0-batch-expiry.sql`：批次与保质期（`product_batch` 表 + 商品保质期 + 采购明细生产日期）
   - `v1.2.0-steward-report.sql`：管家巡检日报（`steward_report` 表，纯增量、可回滚）

> 为什么演示数据有一部分在 Java 里：SQL 种子带 DELETE 时，Spring 每次新建上下文都会重跑它，
> 在测试环境会把已有商品与库存流水删掉（只留下订单），造成"有销售、无流水"的假对账差异。
> 这个坑在 CI 上真实出现过，所以改为 Java 幂等初始化，代码里也留了注释。

> 升级到 v1.1.0 之后请做一次批次补齐：老库存没有批次（历史数据没有生产日期），
> 走一次盘点或确认一张采购单让库存落到批次上，然后用 `GET /api/inventory/batch-mismatch`
> 复核到返回空数组为止——否则临期预警会看不到旧库存的效期。

## 4. 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD` | `retail_suite` / `retail` / `retail123456` | 生产必须改密码 |
| `MYSQL_ROOT_PASSWORD` | `root123456` | 仅用于容器内健康检查与运维 |
| `JWT_SECRET` | 占位串 | **必须改**：`openssl rand -base64 32` |
| `AI_ENABLED` | `true` | 关掉则不复用模型（AI 走规则解析） |
| `AI_BASE_URL` / `AI_MODEL` / `AI_API_KEY` | DeepSeek 默认 | 任何 OpenAI 兼容服务均可；不填 Key 时 AI 自动退化为本地规则解析 |
| `EXPIRY_ALERT_DAYS` | `30` | 临期预警阈值（天） |
| `STEWARD_INSPECT_CRON` | `0 30 7 * * ?` | 管家巡检时间（cron，默认每天 07:30） |
| `SPRING_PROFILES_ACTIVE` | `prod` | `prod` 用 MySQL + Redis；不带 profile 时用 H2 文件库 |

## 5. 数据备份与恢复

```bash
# 备份（建议每天定时）
docker exec retail-mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
  --single-transaction --routines retail_suite | gzip > backup-$(date +%F).sql.gz

# 恢复
gunzip -c backup-2026-09-13.sql.gz | docker exec -i retail-mysql \
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" retail_suite
```

注意：`--single-transaction` 保证备份期间不锁表；库存与订单是同一库同一事务写的，因此备份天然一致。

## 6. 升级流程

```bash
git pull
docker compose build backend frontend
docker compose up -d
docker compose logs -f backend | grep -E "Started|ERROR"
```

- 表结构变更：把新的 DDL 追加成 `deploy/mysql/init/01-schema.sql` 里的 `CREATE TABLE IF NOT EXISTS` **只对全新库生效**；
  已有库请按 `deploy/mysql/migration/vX.Y.Z-*.sql` 顺序手工执行迁移（当前版本没有引入 Flyway，升级前务必先备份）
- 迁移脚本约定：一个版本一个文件、只做增量、文件头写清"适用版本 / 执行命令 / 回滚方式"；
  `v1.2.0-steward-report.sql` 只新增表与索引，回滚就是 `DROP TABLE steward_report;` + 退回旧镜像
- 回滚：镜像换成上一个 tag/commit 重新 build，数据库回滚用备份恢复

## 7. 常见故障

| 现象 | 原因与处理 |
|---|---|
| 后端容器反复重启，日志 `Communications link failure` | MySQL 还没就绪：compose 已配 `depends_on: service_healthy`，若仍出现请查 `docker compose logs mysql`（常见是密码不匹配或磁盘满） |
| 页面能开、接口 401 | 令牌过期或 `JWT_SECRET` 变了（改了密钥所有旧令牌失效），重新登录即可 |
| 接口 403 且提示"缺少权限" | 当前账号角色没有该权限码：用 admin 给角色加权限（`sys_role_permission`），或换有权限的账号 |
| 收银提示"库存不足" | 这是对的：先采购入库或盘点调整；可到「库存管理 → 库存流水」看这个商品的完整变动记录 |
| 报表当天数据为空 | 汇总表是物化的：点「报表对账 → 重算该区间汇总」，或等定时任务 |
| 「管家日报」页提示还没有报告 | 定时巡检每天 07:30 才跑；点页面上的「立即巡检」即可生成今天这一份（或 `POST /api/steward/inspect`） |
| 临期页看到"批次与库存不符" | 老库存没有批次（升级前的历史数据）：走一次盘点、或确认一张采购单让新批次承接、或重启一次后端（初始化流程会补「期初建账」批次）；用 `GET /api/inventory/batch-mismatch` 复核到空数组为止 |
| 一键转草稿提示没有权限 | 该动作走的是 Agent 写工具，需要 `purchase:write`；给角色加权限后重新登录（权限码写在令牌里） |
| 导出 Excel 无响应 | 浏览器可能拦了下载；接口是带 Authorization 的 blob 下载，检查是否被代理去掉请求头 |
| 端口 80 被占用 | 改 `docker-compose.yml` 里 frontend 的端口映射（如 `8081:80`） |
| 启动报 `ports are not available ... 3306` | 本机已装 MySQL 占了 3306：把 compose 里 mysql 的映射改成 `127.0.0.1:13306:3306`（后端容器内仍连 `mysql:3306`，不受影响），或先停掉本机 MySQL |
| `docker pull` 卡住 / 超时（国内网络） | 到 Docker Hub 的连接常被重置：在 `~/.docker/daemon.json` 加 `registry-mirrors`（如 `https://docker.m.daocloud.io`），重启引擎后看 `docker info` 的 Registry Mirrors 是否出现 |
| Git Bash（Windows）跑冒烟脚本失败 | 脚本已按 Git Bash 适配：正文走 stdin、变量名避开 Windows 的 `USERNAME`；若仍失败，先把 `jq` 与 `curl` 放进取 PATH（`SMOKE_USERNAME/SMOKE_PASSWORD` 可覆盖默认账号） |
| 页面/接口里的中文变 `å†œå¤«å±±æ³‰` | 种子数据被**双重编码**：MySQL 8 容器的 mysql 客户端默认 `character_set_client=latin1`，而 `deploy/mysql/init/*.sql` 是 UTF-8，加载时中文先按 latin1 解析再转 utf8mb4 存库。已在两个初始化脚本开头加 `SET NAMES utf8mb4`（只对全新库生效）；**已有库**的这批旧数据建议重跑演示数据（`docker compose down -v` 再起）或按正确中文重录。应用自身经 JDBC 写入的数据一直是正确的，不受影响 |

## 8. 不进 Docker 的部署方式

后端：`mvn -DskipTests package` 得到 `target/retail-suite-1.5.0.jar`，
`SPRING_PROFILES_ACTIVE=prod MYSQL_HOST=... JWT_SECRET=... java -jar retail-suite-1.5.0.jar`

前端：`cd frontend && npm ci && npm run build`，把 `dist/` 交给任意 Nginx/Apache，
并把 `/api` 反代到后端（配置参考 `frontend/nginx.conf`）。

## 9. 监控与巡检建议

- 健康检查：`GET /actuator/health`（compose 的 HEALTHCHECK 用的就是它）
- 指标：`GET /actuator/metrics`（JVM、HTTP、连接池）
- 每天开店前看一眼「报表对账」页：**对账一致**说明账实相符；出现差异要当天查清（通常是有人绕过收银改了库存）
- 审计日志表 `audit_log` 记录了登录、结算、退货、入库、库存调整、AI 草稿确认，出问题按时间或单号检索
