# 部署与运维

## 0. 前置说明（先看这条）

`docker-compose.yml`、两个 `Dockerfile`、MySQL 初始化 SQL 都已就绪，但**开发机上没有安装 Docker**，
因此这套编排**没有做过真机启动验证**——首次部署请按下面步骤逐项确认，特别是端口、密码与初始化脚本执行情况。

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

1. MySQL 首次启动执行：
   - `deploy/mysql/init/01-schema.sql`（建表，MySQL 方言）
   - 挂载进来的 `src/main/resources/db/data-demo.sql`（门店 + 分类 + 8 个演示商品 + 期初流水）
2. 后端启动时 `DemoDataInitializer` 用 BCrypt 创建 `admin` / `cashier` 并绑定角色
   （密码哈希不写进 SQL：不同机器同一密码的哈希不同，写死会导致"灌了数据却登录不上"）
3. 后端定时任务默认每天 `00:10` 重算日汇总（`app.report.daily-summary-cron`）

## 4. 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD` | `retail_suite` / `retail` / `retail123456` | 生产必须改密码 |
| `MYSQL_ROOT_PASSWORD` | `root123456` | 仅用于容器内健康检查与运维 |
| `JWT_SECRET` | 占位串 | **必须改**：`openssl rand -base64 32` |
| `AI_ENABLED` | `true` | 关掉则不复用模型（AI 走规则解析） |
| `AI_BASE_URL` / `AI_MODEL` / `AI_API_KEY` | DeepSeek 默认 | 任何 OpenAI 兼容服务均可；不填 Key 时 AI 自动退化为本地规则解析 |
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

- 表结构变更：把新的 DDL 追加成 `deploy/mysql/init/02-*.sql` **只对全新库生效**；已有库请手工执行迁移（当前版本没有引入 Flyway，升级前务必先备份）
- 回滚：镜像换成上一个 tag/commit 重新 build，数据库回滚用备份恢复

## 7. 常见故障

| 现象 | 原因与处理 |
|---|---|
| 后端容器反复重启，日志 `Communications link failure` | MySQL 还没就绪：compose 已配 `depends_on: service_healthy`，若仍出现请查 `docker compose logs mysql`（常见是密码不匹配或磁盘满） |
| 页面能开、接口 401 | 令牌过期或 `JWT_SECRET` 变了（改了密钥所有旧令牌失效），重新登录即可 |
| 接口 403 且提示"缺少权限" | 当前账号角色没有该权限码：用 admin 给角色加权限（`sys_role_permission`），或换有权限的账号 |
| 收银提示"库存不足" | 这是对的：先采购入库或盘点调整；可到「库存管理 → 库存流水」看这个商品的完整变动记录 |
| 报表当天数据为空 | 汇总表是物化的：点「报表对账 → 重算该区间汇总」，或等定时任务 |
| 导出 Excel 无响应 | 浏览器可能拦了下载；接口是带 Authorization 的 blob 下载，检查是否被代理去掉请求头 |
| 端口 80 被占用 | 改 `docker-compose.yml` 里 frontend 的端口映射（如 `8081:80`） |

## 8. 不进 Docker 的部署方式

后端：`mvn -DskipTests package` 得到 `target/retail-suite-1.0.0.jar`，
`SPRING_PROFILES_ACTIVE=prod MYSQL_HOST=... JWT_SECRET=... java -jar retail-suite-1.0.0.jar`

前端：`cd frontend && npm ci && npm run build`，把 `dist/` 交给任意 Nginx/Apache，
并把 `/api` 反代到后端（配置参考 `frontend/nginx.conf`）。

## 9. 监控与巡检建议

- 健康检查：`GET /actuator/health`（compose 的 HEALTHCHECK 用的就是它）
- 指标：`GET /actuator/metrics`（JVM、HTTP、连接池）
- 每天开店前看一眼「报表对账」页：**对账一致**说明账实相符；出现差异要当天查清（通常是有人绕过收银改了库存）
- 审计日志表 `audit_log` 记录了登录、结算、退货、入库、库存调整、AI 草稿确认，出问题按时间或单号检索
