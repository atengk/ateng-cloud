# sample-cloud-seata 分布式事务增强体验工程

> 专注于演示与自验 `ateng-cloud-seata` 分布式事务增强套件（AT 模式事务编排、TCC 模式刚性防卫屏障与分级清理、全局排他锁、事务感知降级熔断、受管异步线程池 XID 穿透与声明式 `@DS` 假切源防卫）在微服务生产场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8088` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleSeataApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-seata
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置 H2 纯内存数据库（MySQL 兼容模式，自动加载 `schema.sql`）与离线自洽配置，无需依赖外部 Nacos 注册中心与物理 Seata TC 服务端即可极速启动与跑通自动化测试；
- **Local 真实模式**：若需连接真实物理 Nacos 注册中心、MySQL 数据库与 Seata TC 事务协调器，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实连接串与地址，启动自动生效（该文件已被 Git 忽略，CI 零风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或 HTTP 客户端发起调用体验（请求字段与 `PurchaseOrderDTO`、`TccDebitDTO` 等契约 100% 对齐）：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **电商采购提单 (AT 提交)** | `SeataPurchaseOrderController` | `POST /sample/cloud/seata/purchase/submit` | `curl -X POST -H "Content-Type: application/json" -d '{"userId":"U1001","commodityCode":"C2001","count":2}' "http://localhost:8088/sample/cloud/seata/purchase/submit"` |
| **电商采购提单 (AT 回滚)** | `SeataPurchaseOrderController` | `POST /sample/cloud/seata/purchase/submit-rollback` | `curl -X POST -H "Content-Type: application/json" -d '{"userId":"U1001","commodityCode":"C2001","count":2}' "http://localhost:8088/sample/cloud/seata/purchase/submit-rollback"` |
| **账户余额与库存余量查询** | `SeataPurchaseOrderController` | `GET /sample/cloud/seata/purchase/stock-and-balance?userId={uid}&commodityCode={code}` | `curl "http://localhost:8088/sample/cloud/seata/purchase/stock-and-balance?userId=U1001&commodityCode=C2001"` |
| **TCC 一阶段资源预留 (Try)** | `SeataTccFenceController` | `POST /sample/cloud/seata/tcc/prepare` | `curl -X POST -H "Content-Type: application/json" -d '{"xid":"127.0.0.1:8091:202611138888","branchId":3001,"userId":"U1001","money":50}' "http://localhost:8088/sample/cloud/seata/tcc/prepare"` |
| **TCC 二阶段确认提交 (Confirm)** | `SeataTccFenceController` | `POST /sample/cloud/seata/tcc/commit` | `curl -X POST -H "Content-Type: application/json" -d '{"xid":"127.0.0.1:8091:202611138888","branchId":3001,"userId":"U1001","money":50}' "http://localhost:8088/sample/cloud/seata/tcc/commit"` |
| **TCC 二阶段释放回滚 (Cancel)** | `SeataTccFenceController` | `POST /sample/cloud/seata/tcc/rollback` | `curl -X POST -H "Content-Type: application/json" -d '{"xid":"127.0.0.1:8091:202611138888","branchId":3002,"userId":"U1001","money":50}' "http://localhost:8088/sample/cloud/seata/tcc/rollback"` |
| **TCC 防空回滚屏障演示** | `SeataTccFenceController` | `POST /sample/cloud/seata/tcc/empty-rollback` | `curl -X POST -H "Content-Type: application/json" -d '{"xid":"127.0.0.1:8091:202611138888","branchId":3003,"userId":"U1001","money":50}' "http://localhost:8088/sample/cloud/seata/tcc/empty-rollback"` |
| **TCC 防业务悬挂屏障演示 (5110)** | `SeataTccFenceController` | `POST /sample/cloud/seata/tcc/hanging-defense` | `curl -X POST -H "Content-Type: application/json" -d '{"xid":"127.0.0.1:8091:202611138888","branchId":3004,"userId":"U1001","money":50}' "http://localhost:8088/sample/cloud/seata/tcc/hanging-defense"` |
| **TCC 幂等重试防重复提交** | `SeataTccFenceController` | `POST /sample/cloud/seata/tcc/duplicate-commit` | `curl -X POST -H "Content-Type: application/json" -d '{"xid":"127.0.0.1:8091:202611138888","branchId":3005,"userId":"U1001","money":50}' "http://localhost:8088/sample/cloud/seata/tcc/duplicate-commit"` |
| **TCC 过期防卫日志分级清理** | `SeataTccFenceController` | `POST /sample/cloud/seata/tcc/clean` | `curl -X POST "http://localhost:8088/sample/cloud/seata/tcc/clean"` |
| **声明式 `@GlobalLock` 校验** | `SeataGlobalLockController` | `GET /sample/cloud/seata/lock/declarative-check?commodityCode={code}&count={count}` | `curl "http://localhost:8088/sample/cloud/seata/lock/declarative-check?commodityCode=C2001&count=5"` |
| **编程式全局锁退避重试更新** | `SeataGlobalLockController` | `POST /sample/cloud/seata/lock/programmatic-update?commodityCode={code}&count={count}` | `curl -X POST "http://localhost:8088/sample/cloud/seata/lock/programmatic-update?commodityCode=C2001&count=5"` |
| **活跃事务下熔断防卫阻断 (5104)** | `SeataFallbackDefenseController` | `POST /sample/cloud/seata/fallback/with-active-tx` | `curl -X POST "http://localhost:8088/sample/cloud/seata/fallback/with-active-tx"` |
| **无事务上下文平滑降级** | `SeataFallbackDefenseController` | `POST /sample/cloud/seata/fallback/without-tx` | `curl -X POST "http://localhost:8088/sample/cloud/seata/fallback/without-tx"` |
| **受管异步线程池 XID 与 MDC 穿透** | `SeataAsyncPropagationController` | `POST /sample/cloud/seata/async/propagate?xid={xid}` | `curl -X POST "http://localhost:8088/sample/cloud/seata/async/propagate?xid=127.0.0.1:8091:202611130001"` |
| **活跃全局事务 `@DS` 切源放行** | `SeataDatasourceGuardController` | `POST /sample/cloud/seata/datasource-guard/allow-in-global-tx?xid={xid}` | `curl -X POST "http://localhost:8088/sample/cloud/seata/datasource-guard/allow-in-global-tx?xid=127.0.0.1:8091:202611130002"` |
| **单连接本地事务 `@DS` 假切源阻断 (5105)** | `SeataDatasourceGuardController` | `POST /sample/cloud/seata/datasource-guard/block-false-switch` | `curl -X POST "http://localhost:8088/sample/cloud/seata/datasource-guard/block-false-switch"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-cloud-seata,ateng-cloud-samples/sample-cloud-seata,ateng-cloud-samples/sample-cloud-smoke-test -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-cloud-seata README](../../ateng-cloud-seata/README.md)。
