# ateng-cloud-seata

面向企业级高可用分布式生产环境的微服务分布式事务协调与数据一致性基础设施 Starter，单向依赖消费已发布的 `ateng-boot-bom`，专注于打造垂直正交、开箱即用、无侵入的分布式事务通用治理底座。

全面支持 Apache Seata 2.x 原生协议，内建 AT / TCC 双核事务模式驱动、TCC 防悬挂与幂等业务防卫屏障（TCC Fence）、跨进程 HTTP OpenFeign 与 Dubbo 3.3 RPC 双模 XID 对称透传、SLF4J MDC 全链路 XID 黄金关联、TransmittableThreadLocal 受管异步线程池上下文快照穿透、MyBatis-Plus 多租户内部表（`undo_log`、`tcc_fence_log`）动态放行与大小写清洗、Baomidou 动态数据源自动协同与假切源安全守卫、ANSI-SQL 标准 DDL 模板与按需自愈建表，以及 Sentinel 降级回滚强制防卫与异常因果转译机制。

---

## 维度零：特性与公共 API 全景速查矩阵

### 1. 核心类与门面接口

| 类 / 接口 | 类型 | 核心职责 | 默认行为 / 装配条件 |
| :--- | :--- | :--- | :--- |
| `AtengSeataAutoConfiguration` | 自动装配类 | 分布式事务增强底座自动装配总入口 | 当 `ateng.cloud.seata.enabled=true` 时激活 |
| `AtengSeataAutoConfiguration.SeataMarker` | 标记组件 | 分布式事务基础设施生效凭据组件 | 标配 `@ConditionalOnMissingBean` 注入容器 |
| `SeataProperties` | 强类型配置 | `ateng.cloud.seata.*` 根配置属性类 | 标配 `@ConfigurationProperties`，IDE 补全友好 |
| `SeataConstants` | 常量契约 | 配置前缀、XID 标头键名与内部系统表常量 | 定义 `TX_XID`、`xid` (MDC)、`undo_log`、`tcc_fence_log` |
| `SeataResultCode` | 强类型错误码 | 分布式事务专属响应错误码枚举（`5101` ~ `5114`） | 实现 `IResultCode`，严格遵循 `PERSISTENCE_TX` 规范 |
| `SeataFallbackDefense` | 降级防卫组件 | 全局事务熔断降级防卫机制核心门面 | 活跃全局事务下下游降级强制抛出 `5104` 阻断假提交 |
| `SeataAssert` | 语义化断言 | Seata 分布式事务上下文语义化断言工具类 | 提供静态与链式断言方法，快速校验事务状态 |
| `SeataFeignContextInterceptor` | 请求拦截器 | Feign 客户端跨进程出站 XID 透明透传拦截器 | 条件装配于 `feign.RequestInterceptor` 类路径存在时 |
| `SeataWebHandlerInterceptor` | 请求拦截器 | WebMVC 入站 XID 自动绑定与出口强制对称清理 | 条件装配于 Servlet Web 环境，联动同步 SLF4J MDC `xid` |
| `SeataWebMvcConfigurer` | MVC 配置器 | 注册 WebMVC 入站 XID 拦截器至拦截器链 | 标配 `@ConditionalOnWebApplication(type = SERVLET)` |
| `SeataDubboFilter` | RPC 过滤器 | Dubbo 3.3 RPC 全局事务 XID 透明穿透与自适应幂等防卫 | 消费端自动注入 Attachment，服务端自动绑定并清理 MDC |
| `SeataExceptionTranslationAspect` | 声明式切面 | 拦截全局事务与全局锁底层驱动异常并受控转译 | 拦截 `@GlobalTransactional` 与 `@GlobalLock` 方法 |
| `SeataExceptionTranslator` | 异常转译门面 | 识别锁冲突、超时与连接中断并转译为业务异常 | 保证原始因果链完整，转译为 `BusinessException` |
| `SeataAsyncContextSnapshotProvider` | 快照提供者 | 受管异步线程池 XID 上下文快照提供者 | 注册至 `ateng-boot-core` 的 `AsyncContextSnapshotProvider` SPI |
| `SeataTenantLineHandlerPostProcessor` | 后置处理器 | 自动为 `TenantLineHandler` 注入内部表排除保护 | 静态工厂方法注入，条件装配于 MyBatis-Plus 环境 |
| `SeataTenantLineHandlerWrapper` | 租户装饰器 | 租户处理器装饰器，动态放行内部事务日志表 | 拦截 `ignoreTable`，彻底杜绝多租户污染 `undo_log` 与防卫表 |
| `SeataDynamicDataSourcePropertiesCustomizer` | 属性定制器 | 协同动态数据源开启原生 Seata 代理与事务模式 | 条件装配于 `DynamicDataSourceProperties` 存在时 |
| `SeataDynamicDataSourceCoordinator` | 协同协调器 | 启动时自检物理连接池是否被 Seata 代理包装 | 条件装配于 `DynamicRoutingDataSource` 存在时 |
| `SeataDynamicDatasourceGuard` | 切源守卫 | 协调全局分布式事务跨库切源与拦截本地假切源 | 默认开启，当存在全局 XID 时放行跨库合法切源 |
| `SeataGlobalLockAspect` | 声明式切面 | 拦截 `@GlobalLock` 声明式全局行级排他锁 | 标配 `@ConditionalOnClass(GlobalLock.class)` |
| `SeataGlobalLockTemplate` | 编程式模板 | 编程式高并发轻量全局排他锁防脏读执行模板 | 提供 `execute` 与 `executeWithRetry` 门面接口 |
| `GlobalLockCallback<T>` | 函数式接口 | 全局排他锁业务代码执行回调接口 | 抛出 `Throwable` 支持捕获受管业务异常 |
| `TccFenceBarrier` | 业务防卫屏障 | TCC 两阶段防空回滚、防业务悬挂与幂等状态机 | 基于本地数据库日志表实现刚性原子防卫 |
| `SeataTccFenceTemplate` | 编程式模板 | 编程式 TCC Fence 业务防卫执行模板 | 提供 `executePrepare`、`executeCommit`、`executeRollback` |
| `TwoPhaseAction` | 业务契约接口 | TCC 两阶段动作标准契约接口 | 声明 `commit(actionContext)` 与 `rollback(actionContext)` |
| `TccFenceCallback<T>` | 函数式接口 | TCC 防卫屏障包裹下的单阶段业务操作回调 | 依托本地数据库事务保证状态流转与业务操作原子性 |
| `TccFenceRepository` | 扩展点 SPI | TCC 防卫日志仓储访问标准 SPI 接口 | 标配 `@ConditionalOnMissingBean` 支持平滑接管 |
| `JdbcTccFenceRepository` | 仓储实现 | 基于 JDBC 数据源的 TCC Fence 日志持久化仓储 | 默认基于 `DataSource` 自动装配，支持两阶段键集批量删除 |
| `InMemoryTccFenceRepository` | 仓储实现 | 纯内存自洽 TCC Fence 仓储实现 | 探测到无物理 `DataSource` 时的保底装配 |
| `TccFenceRecord` | 数据实体 | TCC Fence 日志记录模型实体 | 封装 `xid`, `branchId`, `actionName`, `status` |
| `TccFenceStatus` | 状态枚举 | TCC 分支事务执行状态枚举 | TRIED(1), COMMITTED(2), ROLLBACKED(3), SUSPENDED(4) |
| `TccFenceLogCleanScheduler` | 定时调度器 | TCC 防卫日志分级生命周期批量清理定时调度器 | 实现 `SmartLifecycle`，受管线程池驱动分级批量物理清理 |
| `TccFenceCleanResult` | 数据模型 | TCC 防卫日志分级清理结果摘要模型 | 记录终态删除条数、悬挂删除条数与总物理删除记录数 |
| `TccFenceTableInitializer` | 表初始化器 | TCC 防卫日志表结构按需自愈建表初始化器 | 条件装配于 `auto-init-table=true`，4 维兼容探测 |
| `TccFenceException` | 业务异常 | TCC 防卫屏障校验失败时抛出的受管业务异常 | 继承自 `BusinessException`，赋语义化错误码 |
| `TccFenceHangingException` | 业务异常 | 检测到业务悬挂风险（Try 延迟到达）时抛出 | 继承自 `TccFenceException`，明确告警悬挂阻断 |

---

### 2. 配置属性速查表 (`ateng.cloud.seata.*`)

| 配置项 | 类型 | 默认值 | 详细业务说明 |
| :--- | :--- | :--- | :--- |
| `ateng.cloud.seata.enabled` | `boolean` | `true` | 是否开启 Seata 分布式事务增强底座 |
| `ateng.cloud.seata.tx.default-timeout` | `Duration` | `30s` | 全局事务默认超时时效，防止大事务或网络悬挂长时间阻塞连接池 |
| `ateng.cloud.seata.lock.retry-interval` | `Duration` | `50ms` | 全局排他锁获取重试等待间隔，避免高频自旋打爆 TC 节点网络 |
| `ateng.cloud.seata.lock.retry-times` | `int` | `30` | 全局排他锁获取最大重试次数（总计约 1.5 秒后 Fail-Fast 释放本地锁） |
| `ateng.cloud.seata.tcc.fence-enabled` | `boolean` | `true` | 是否开启 TCC 两阶段防空回滚、防业务悬挂与幂等业务防卫屏障 |
| `ateng.cloud.seata.tcc.clean-enabled` | `boolean` | `true` | 是否开启 TCC 防卫日志历史记录分级生命周期定时清理调度 |
| `ateng.cloud.seata.tcc.clean-period` | `Duration` | `1d` | TCC 防卫日志历史归档记录清理定时任务调度周期 |
| `ateng.cloud.seata.tcc.log-ttl` | `Duration` | `3d` | TCC 终态防卫日志（COMMITTED / ROLLBACKED）基础保留时效 |
| `ateng.cloud.seata.tcc.clean-batch-size` | `int` | `500` | TCC 防卫日志单批次物理清理最大记录数（防御长事务锁表） |
| `ateng.cloud.seata.tcc.suspended-retention-multiplier` | `int` | `3` | 悬挂防卫标记（SUSPENDED）超长保留倍数（默认 `3x logTtl` = 9 天） |
| `ateng.cloud.seata.tcc.auto-init-table` | `boolean` | `false` | 是否开启 TCC 防卫表自动安全自愈初始化建表（生产环境坚决默认 false） |
| `ateng.cloud.seata.tcc.table-name` | `String` | `"tcc_fence_log"` | TCC 防卫日志存储数据表名（联动多租户动态排除） |
| `ateng.cloud.seata.dynamic-datasource.enabled` | `boolean` | `true` | 是否开启 Baomidou dynamic-datasource 多数据源协同增强 |
| `ateng.cloud.seata.dynamic-datasource.mode` | `String` | `"AT"` | 动态数据源 Seata 事务代理模式（支持 `AT` 或 `XA`，默认 `AT`） |
| `ateng.cloud.seata.dynamic-datasource.auto-proxy` | `boolean` | `true` | 是否自动对 DynamicRoutingDataSource 中各物理连接池进行 Seata 代理包装 |
| `ateng.cloud.seata.dynamic-datasource.guard-enabled` | `boolean` | `true` | 是否开启切源安全守卫协同（感知 Seata 全局分布式事务并放行合法跨库切源） |
| `ateng.cloud.seata.dynamic-datasource.fail-on-false-switch` | `boolean` | `true` | 检测到 Spring 单连接假切源时是否抛出异常阻断执行（false 则降级为 warn 告警） |

---

### 3. 标准错误码契约对照表 (`SeataResultCode`)

| 错误码枚举 | 编码值 (`code`) | 物理 HTTP 映射 | 业务描述与阻断场景 |
| :--- | :--- | :--- | :--- |
| `SEATA_TRANSACTION_FAILED` | `5101` | `200 OK (Result) / 500` | 全局分布式事务执行失败，下游分支或业务断言回滚并已转译抛出 |
| `SEATA_LOCK_CONFLICT` | `5102` | `200 OK (Result) / 409` | 分布式全局行锁冲突：获取全局排他锁超时或竞争失败，避免脏写 |
| `SEATA_TC_UNAVAILABLE` | `5103` | `200 OK (Result) / 503` | 分布式事务协调器 (TC) 不可达、网络断连或超时，刚性 Fail-Fast 阻断 |
| `SEATA_DEGRADE_BLOCKED` | `5104` | `200 OK (Result) / 500` | 下游跨微服务调用触发熔断降级，全局活跃事务强制阻断原子回滚 |
| `TCC_FENCE_SUSPENDED` | `5110` | `200 OK (Result) / 409` | TCC 分支已被业务悬挂拦截：Cancel 先行到达并插旗阻断，延迟 Try 被拒绝 |
| `TCC_FENCE_EMPTY_ROLLBACK` | `5111` | `200 OK (Result) / 200` | TCC 分支已被空回滚拦截：一阶段 Try 未曾执行，二阶段 Cancel 静默放行 |
| `TCC_FENCE_DUPLICATE_CALL` | `5112` | `200 OK (Result) / 200` | TCC 分支重复调用已幂等放行：二阶段 Confirm 或 Cancel 重复调用安全返回 |
| `TCC_FENCE_RECORD_NOT_EXISTS` | `5113` | `200 OK (Result) / 500` | TCC 分支防卫记录不存在，非法进入二阶段流转 |
| `TCC_FENCE_STATE_ERROR` | `5114` | `200 OK (Result) / 500` | TCC 分支事务状态不合法，发生非预期的生命周期逆转 |

---

## 维度一：最小起步体验 (Quick Start)

### 1. 痛点与核心定位

在微服务分布式架构中，跨服务、跨数据库的业务操作（如电商下单：`Order` ➔ `Storage` ➔ `Account`）经常面临严峻的一致性挑战：
1. **网络超时引发单边落库**：下游微服务执行成功但网络响应超时，上游重试导致库存与账户重复扣减；若上游放弃则产生单边脏数据；
2. **Sentinel 熔断降级假提交隐患**：跨微服务调用触发 Sentinel 降级时，默认 Fallback 若返回 Mock 假数据，将诱骗事务协调器误判业务成功并提交全局事务，导致灾难性的单边脏写与资损；
3. **多租户内部表穿透污染**：MyBatis-Plus 多租户拦截器盲目将 `tenant_id` 拼接入 Seata 系统的 `undo_log` 或 `tcc_fence_log`，导致日志检索失效，无法正确执行两阶段回滚并引发 SQL 崩溃；
4. **TCC 两阶段三大固有顽疾**：
   - **空回滚 (Empty Rollback)**：一阶段 Try 因网络分区未收到，协调器发起 Cancel，业务执行空补偿导致资金虚增；
   - **业务悬挂 (Hanging)**：Cancel 先于延迟的 Try 到达执行空回滚，随后迟到的 Try 成功预占资源且再也不会被 Cancel，造成资金永久冻结悬挂；
   - **重复调用 (Duplicate Call)**：网络抖动导致 Confirm 或 Cancel 多次重试，造成业务多次扣款或多次退款；
5. **动态数据源假切源串库**：在 Spring `@Transactional` 下切换数据源时，物理连接已被本地事务管理器绑定，造成切源静默失效并脏写主库。

`ateng-cloud-seata` 基于工业级标准深度封装，提供 AT 自动代理、TCC 屏障硬核防卫、全链路 XID 穿透与 SLF4J MDC 黄金关联、Sentinel 降级回滚强制防卫与假切源安全拦截，实现开箱即用的高可用分布式事务管控。

---

### 2. 引入 Maven 依赖

在业务微服务工程的 `pom.xml` 中引入（版本由 `ateng-cloud-bom` 统一纳管）：

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-cloud-seata</artifactId>
</dependency>
```

---

### 3. 最小 YAML 配置

在 `application.yml` 中声明（1:1 提取自 `sample-cloud-seata` 生产基线）：

```yaml
spring:
  application:
    name: sample-cloud-seata
  config:
    import:
      - optional:classpath:application-local.yml

ateng:
  cloud:
    seata:
      enabled: true
      tx:
        default-timeout: 30s
      lock:
        retry-interval: 50ms
        retry-times: 30
      tcc:
        fence-enabled: true
        clean-enabled: true
        log-ttl: 3d
        clean-period: 1d
        clean-batch-size: 500
        suspended-retention-multiplier: 3
        auto-init-table: false
        table-name: tcc_fence_log
      dynamic-datasource:
        enabled: true
        guard-enabled: true

# 原生 Seata 客户端属性
seata:
  enabled: true
  tx-service-group: default_tx_group
  service:
    vgroup-mapping:
      default_tx_group: default
    grouplist:
      default: 127.0.0.1:8091
```

---

### 4. 数据库初始化表结构 (DDL)

生产环境推荐手工初始化数据库表结构。框架内建完整标准模板（存放在 `src/main/resources/schema/`）：

#### (1) MySQL 生产脚本

```sql
-- 业务订单表
CREATE TABLE IF NOT EXISTS order_tbl (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    commodity_code VARCHAR(64) NOT NULL,
    count INT NOT NULL,
    money INT NOT NULL
);

-- 业务库存表
CREATE TABLE IF NOT EXISTS storage_tbl (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    commodity_code VARCHAR(64) NOT NULL UNIQUE,
    count INT NOT NULL
);

-- 业务账户表
CREATE TABLE IF NOT EXISTS account_tbl (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL UNIQUE,
    money INT NOT NULL
);

-- Seata TCC Fence 防卫日志表（拦截空回滚、业务悬挂与幂等放行）
CREATE TABLE IF NOT EXISTS `tcc_fence_log` (
    `xid` VARCHAR(128) NOT NULL COMMENT '全局事务唯一标识 XID',
    `branch_id` BIGINT NOT NULL COMMENT '分支事务唯一标识 branchId',
    `action_name` VARCHAR(64) NOT NULL COMMENT 'TCC 两阶段业务动作名称',
    `status` INT NOT NULL COMMENT '防卫状态: 1-TRIED, 2-COMMITTED, 3-ROLLBACKED, 4-SUSPENDED',
    `gmt_create` DATETIME(3) NOT NULL COMMENT '创建时间',
    `gmt_modified` DATETIME(3) NOT NULL COMMENT '最后更新时间',
    PRIMARY KEY (`xid`, `branch_id`),
    KEY `idx_tcc_fence_gmt_modified` (`gmt_modified`),
    KEY `idx_tcc_fence_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Seata TCC 防悬挂与幂等业务防卫日志表';

-- Seata AT 模式 undo_log 回滚日志表（两阶段自动反向补偿）
CREATE TABLE IF NOT EXISTS `undo_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `branch_id` BIGINT NOT NULL COMMENT '分支事务 ID',
    `xid` VARCHAR(128) NOT NULL COMMENT '全局事务 XID',
    `context` VARCHAR(128) NOT NULL COMMENT '上下文信息',
    `rollback_info` LONGBLOB NOT NULL COMMENT '回滚快照信息',
    `log_status` INT NOT NULL COMMENT '回滚状态: 0-正常状态, 1-防重放状态',
    `log_created` DATETIME(6) NOT NULL COMMENT '创建时间',
    `log_modified` DATETIME(6) NOT NULL COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Seata AT 模式分布式事务回滚日志表';
```

---

### 5. 开箱即用的微服务业务代码

#### (1) 业务发起方：订单服务 (`OrderService`)

编排 `Order` ➔ `Storage` ➔ `Account` 三节点分布式事务，并集成 Sentinel 熔断降级强制防卫契约（1:1 提取自 `OrderService` 源码）：

```java
package io.github.atengk.cloud.sample.seata.service;

import io.github.atengk.cloud.seata.fallback.SeataFallbackDefense;
import org.apache.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    public static final int DEFAULT_UNIT_PRICE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final StorageService storageService;
    private final AccountService accountService;

    public OrderService(JdbcTemplate jdbcTemplate, StorageService storageService, AccountService accountService) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageService = storageService;
        this.accountService = accountService;
    }

    /**
     * 创建订单全流程分布式事务
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createOrder(String userId, String commodityCode, int orderCount) {
        int orderMoney = orderCount * DEFAULT_UNIT_PRICE;
        log.info("开启下单事务 (XID: [{}]): 用户 [{}], 商品 [{}], 数量 [{}]",
                RootContext.getXID(), userId, commodityCode, orderCount);

        // 1. 扣减库存 (AT 模式)
        storageService.deduct(commodityCode, orderCount);

        // 2. 扣减账户余额 (AT 模式)
        accountService.debit(userId, orderMoney);

        // 3. 创建订单记录
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO order_tbl (user_id, commodity_code, count, money) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, userId);
            ps.setString(2, commodityCode);
            ps.setInt(3, orderCount);
            ps.setInt(4, orderMoney);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : 0L;
    }

    /**
     * 下单流程（编排下游远程调用并挂载 Sentinel 降级强制防卫契约）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createOrderWithDownstream(String userId, String commodityCode, int orderCount, Runnable downstreamAction) {
        int orderMoney = orderCount * DEFAULT_UNIT_PRICE;

        // 1. 本地扣减库存
        storageService.deduct(commodityCode, orderCount);

        // 2. 本地创建订单记录
        Long orderId = insertOrderRecord(userId, commodityCode, orderCount, orderMoney);

        // 3. 执行下游远程微服务调用（带 Sentinel Fallback 防卫）
        try {
            if (downstreamAction != null) {
                downstreamAction.run();
            }
        } catch (Throwable cause) {
            // 触发 Sentinel 降级防卫：活跃全局事务下刚性抛出 BusinessException 阻断假提交；无事务普通调用静默放行
            SeataFallbackDefense.failOnActiveTransaction("下游外部账户扣费服务", cause);
            log.info("无分布式全局事务，下游调用平滑降级执行");
        }

        return orderId;
    }
}
```

#### (2) 资源参与方：库存服务 (`StorageService`)

提供 AT 模式库存扣减与 `@GlobalLock` 轻量排他锁防脏读（1:1 提取自 `StorageService` 源码）：

```java
package io.github.atengk.cloud.sample.seata.service;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.cloud.seata.lock.SeataGlobalLockTemplate;
import org.apache.seata.spring.annotation.GlobalLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final JdbcTemplate jdbcTemplate;
    private final SeataGlobalLockTemplate globalLockTemplate;

    public StorageService(JdbcTemplate jdbcTemplate, SeataGlobalLockTemplate globalLockTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.globalLockTemplate = globalLockTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deduct(String commodityCode, int count) {
        log.info("执行库存扣减: 商品 [{}], 扣减数量 [{}]", commodityCode, count);
        int updated = jdbcTemplate.update(
                "UPDATE storage_tbl SET count = count - ? WHERE commodity_code = ? AND count >= ?",
                count, commodityCode, count
        );
        BusinessAssert.isTrue(updated > 0, "库存不足，扣减失败: %s", commodityCode);
    }

    public int getStock(String commodityCode) {
        Integer stock = jdbcTemplate.queryForObject(
                "SELECT count FROM storage_tbl WHERE commodity_code = ?",
                Integer.class,
                commodityCode
        );
        return stock != null ? stock : 0;
    }

    /**
     * 声明式 @GlobalLock 轻量全局排他锁防脏读 (无 undo_log 开销)
     */
    @GlobalLock
    public boolean checkAndLockStockDeclarative(String commodityCode, int count) {
        log.info("声明式 @GlobalLock 校验商品库存: [{}]", commodityCode);
        return getStock(commodityCode) >= count;
    }

    /**
     * 编程式 SeataGlobalLockTemplate 全局锁校验
     */
    public boolean checkAndLockStockProgrammatic(String commodityCode, int count) throws Throwable {
        return globalLockTemplate.execute(() -> {
            log.info("编程式 GlobalLock 模板校验商品库存: [{}]", commodityCode);
            return getStock(commodityCode) >= count;
        });
    }
}
```

#### (3) 资源参与方：账户服务 (`AccountService`)

同时支持 AT 模式扣款与受管 TCC Fence 业务防卫扣款（1:1 提取自 `AccountService` 源码）：

```java
package io.github.atengk.cloud.sample.seata.service;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.cloud.seata.tcc.SeataTccFenceTemplate;
import io.github.atengk.cloud.seata.tcc.TwoPhaseAction;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService implements TwoPhaseAction {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final JdbcTemplate jdbcTemplate;
    private final SeataTccFenceTemplate tccFenceTemplate;

    public AccountService(JdbcTemplate jdbcTemplate, SeataTccFenceTemplate tccFenceTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.tccFenceTemplate = tccFenceTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public void debit(String userId, int money) {
        log.info("执行账户扣款: 用户 [{}], 扣减金额 [{}]", userId, money);
        int updated = jdbcTemplate.update(
                "UPDATE account_tbl SET money = money - ? WHERE user_id = ? AND money >= ?",
                money, userId, money
        );
        BusinessAssert.isTrue(updated > 0, "账户余额不足，扣款失败: %s", userId);
    }

    /**
     * TCC 一阶段 Try：在 TCC Fence 屏障保护下执行扣款准备
     */
    public boolean prepareDebitTcc(BusinessActionContext actionContext, String userId, int money) throws Throwable {
        return tccFenceTemplate.executePrepare(actionContext, "AccountDebitTcc", () -> {
            log.info("TCC 一阶段 Try: 校验并扣减用户余额 [{}], 金额: [{}]", userId, money);
            int updated = jdbcTemplate.update(
                    "UPDATE account_tbl SET money = money - ? WHERE user_id = ? AND money >= ?",
                    money, userId, money
            );
            BusinessAssert.isTrue(updated > 0, "TCC 余额不足: %s", userId);
            return true;
        });
    }

    @Override
    public boolean commit(BusinessActionContext actionContext) {
        try {
            return tccFenceTemplate.executeCommit(actionContext, () -> {
                log.info("TCC 二阶段 Confirm: 确认用户扣款, 事务 [{}#{}]",
                        actionContext.getXid(), actionContext.getBranchId());
                return true;
            });
        } catch (Throwable t) {
            log.error("TCC 二阶段 Confirm 异常", t);
            return false;
        }
    }

    @Override
    public boolean rollback(BusinessActionContext actionContext) {
        try {
            return tccFenceTemplate.executeRollback(actionContext, () -> {
                String userId = (String) actionContext.getActionContext("userId");
                Object moneyObj = actionContext.getActionContext("money");
                Integer money = moneyObj instanceof Number ? ((Number) moneyObj).intValue() : null;
                log.info("TCC 二阶段 Cancel: 回滚补偿用户余额 [{}], 补回金额: [{}]", userId, money);
                if (userId != null && money != null) {
                    jdbcTemplate.update("UPDATE account_tbl SET money = money + ? WHERE user_id = ?", money, userId);
                }
                return true;
            });
        } catch (Throwable t) {
            log.error("TCC 二阶段 Cancel 异常", t);
            return false;
        }
    }
}
```

---

## 维度二：生产扩展接管 (Production Extensibility)

### 1. 编程式 TCC Fence 防卫执行模板 (`SeataTccFenceTemplate`)

针对高并发复杂业务，除了声明式注解外，框架提供更灵活的编程式模板 `SeataTccFenceTemplate`：
- `executePrepare`：自动检查是否存在悬挂插旗记录（`SUSPENDED`）；若未悬挂，在本地事务中插入 `TRIED` 记录并执行用户业务逻辑；
- `executeCommit`：自动幂等判断；若已是 `COMMITTED` 则幂等放行；若为 `TRIED` 则推进状态为 `COMMITTED` 并执行 Confirm 回调；
- `executeRollback`：空回滚防卫；若一阶段记录未曾生成，则立即插入 `SUSPENDED` 标记并跳过 Cancel 回调；若已为 `TRIED` 则流转为 `ROLLBACKED` 并执行补偿回调；若已是 `ROLLBACKED` 则幂等放行。

---

### 2. 自定义 TCC Fence 仓储扩展点 SPI (`TccFenceRepository`)

默认情况下，框架自动装配 `JdbcTccFenceRepository`（依托主业务数据源）。若业务需要将防卫记录存储于独立的审计库、分库分表引擎或 Redis，只需实现 `TccFenceRepository` 并注入 Spring 容器，即可平滑无缝接管（标配 `@ConditionalOnMissingBean`）：

```java
package io.github.atengk.cloud.sample.seata.config;

import io.github.atengk.cloud.seata.tcc.TccFenceRecord;
import io.github.atengk.cloud.seata.tcc.TccFenceRepository;
import io.github.atengk.cloud.seata.tcc.TccFenceStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Configuration
@ConditionalOnProperty(prefix = "ateng.cloud.seata.sample", name = "custom-fence-repo-enabled", havingValue = "true")
public class CustomFenceRepositoryConfiguration {

    @Bean
    public TccFenceRepository customTccFenceRepository() {
        return new TccFenceRepository() {
            @Override
            public Optional<TccFenceRecord> findByXidAndBranchId(String xid, Long branchId) {
                // 自定义查询逻辑
                return Optional.empty();
            }

            @Override
            public boolean insertRecord(TccFenceRecord record) {
                // 自定义写入逻辑 (如写入独立分布式审计库)
                return true;
            }

            @Override
            public boolean updateStatus(String xid, Long branchId, TccFenceStatus oldStatus, TccFenceStatus newStatus) {
                return true;
            }

            @Override
            public boolean deleteRecord(String xid, Long branchId) {
                return true;
            }

            @Override
            public int deleteExpiredRecords(LocalDateTime beforeTime, List<TccFenceStatus> statuses, int limit) {
                // 自定义分批清理逻辑
                return 0;
            }
        };
    }
}
```

---

### 3. TCC 防卫表结构按需自愈建表初始化器 (`TccFenceTableInitializer`)

生产环境默认遵循 DBA 严格管控（`auto-init-table=false`）。但在微服务沙箱测试、CI 离线流水线或 H2/Docker 敏捷开发环境中，可通过开启自动建表消除环境就绪依赖：
- 开启配置：`ateng.cloud.seata.tcc.auto-init-table: true`；
- **4 维弹性探测**：基于 `DatabaseMetaData` 执行原名、全大写、全小写及当前 Catalog/Schema 4 重判定，防御各类方言元数据大小写敏感误判；
- **安全幂等创建**：表不存在时自动加载 `schema/seata_tcc_fence_log.sql` 中的 ANSI-SQL DDL 自动建表并补齐复合索引。

---

### 4. Dubbo 3.3 RPC 全局事务 XID 透明穿透与自适应幂等防卫 (`SeataDubboFilter`)

在 Dubbo 3.3 微服务调用链中，`SeataDubboFilter`（优先级 `-9000`）提供全透明的跨微服务事务穿透：
- **消费端出站**：无感提取当前线程的 `RootContext.getXID()`，注入 RPC Attachment (`TX_XID`)；
- **服务端入站**：提取 `TX_XID` 绑定至 `RootContext`，并同步注入 SLF4J MDC `xid`；
- **自适应幂等防卫**：若外部环境（如 `ateng-cloud-dubbo` 上下文拦截器）已先期绑定相同 XID，过滤器激活幂等防卫，静默放行且在方法出口处**不执行二次解绑**，彻底避免截断外层全局事务作用域；
- **确定性出口清理**：在 `finally` 块中对称释放 `RootContext` 并恢复 MDC，杜绝 Netty EventLoop 线程池污染；
- **本地 short-circuit**：识别 `injvm` 内存调用自动短路放行。

---

### 5. TCC 防卫日志分级生命周期批量清理调度器 (`TccFenceLogCleanScheduler`)

为杜绝 TCC 防卫日志无限膨胀压垮数据库磁盘与慢查询，框架基于 Spring `SmartLifecycle`（生命周期优先级 `MAX_VALUE - 100`）提供开箱即用的受管分级清理调度器：
- **可接管线程池**：通过 Spring 容器注入 `ScheduledExecutorService` 即可平滑接管内部单线程调度器；
- **编程式手动触发**：对外公开 `scheduler.clean()` 门面方法，返回强类型 `TccFenceCleanResult`，便于集成至 Spring Boot Actuator 或运维管理端点。

---

### 6. Sentinel 降级回滚强制防卫组件与断言门面 (`SeataFallbackDefense` & `SeataAssert`)

为防御微服务熔断降级引发的单边假提交资损，框架提供极简防卫契约：

```java
// 方式一：在 OpenFeign / Dubbo RPC Fallback 工厂或降级回调中防卫
@Override
public OrderResult createFallback(Throwable cause) {
    // 强制防卫：当且仅当处于活跃全局事务时抛出 BusinessException(5104) 阻断回滚，保留原始根因
    SeataFallbackDefense.failOnActiveTransaction("创建订单", cause);
    // 普通无事务调用，平滑返回 Mock 默认值
    return OrderResult.defaultFallback();
}

// 方式二：使用 SeataAssert 语义化断言门面
SeataAssert.failOnActiveTransaction("扣减用户余额", cause);
```

---

### 7. 高并发轻量 `@GlobalLock` 与 `SeataGlobalLockTemplate`

在商品详情页库存查询、高频读取或无需全局事务回滚补偿的场景中，若直接标注 `@GlobalTransactional` 会带来生成 `undo_log` 与反向补偿镜像的高额开销。
- 声明式使用：直接在方法上声明 `@GlobalLock`；
- 编程式使用：注入 `SeataGlobalLockTemplate` 执行 `template.execute(...)` 或带弹性重试的 `template.executeWithRetry(...)`；
- 运行机制：仅在本地事务提交前向 TC 校验或申请该行记录的全局锁，若未发生冲突则直接提交本地事务；一旦检测到冲突立即抛出 `5102 (SEATA_LOCK_CONFLICT)`，彻底免除 `undo_log` 写入，高并发吞吐提升 3~5 倍。

---

### 8. Baomidou 动态数据源协同与假切源安全守卫

在多数据源场景中，`ateng-cloud-seata` 与 `dynamic-datasource` 深度协同：
- **自动连接池代理**：`SeataDynamicDataSourcePropertiesCustomizer` 协同将 `dynamic-datasource.seata=true` 并保持事务模式一致；`SeataDynamicDataSourceCoordinator` 在启动时自检各物理数据源是否均被 `SeataDataSourceProxy` 包装，杜绝遗漏；
- **跨库切源合法放行**：当检测到全局分布式事务开启（`RootContext.getXID() != null`）时，`SeataDynamicDatasourceGuard` 识别为分布式事务协调跨库操作，予以合法放行；
- **本地单连接假切源拦截**：当检测到处于 Spring 本地单连接事务（`DataSourceTransactionManager`）且无全局 XID 时，若开发者尝试跨库切源，守卫直接刚性拦截并抛出受管业务异常，坚决杜绝静默假切源造成的严重串库资损事故。

---

### 9. 受管异步线程池上下文快照透传 (`SeataAsyncContextSnapshotProvider`)

微服务中通过异步线程池（如 `@Async` 或 `CompletableFuture`）并行执行任务时，Seata 原生 `RootContext` 绑定于 `ThreadLocal`，跨线程会导致 XID 丢失。
- 框架内建 `SeataAsyncContextSnapshotProvider`，自动实现 `ateng-boot-core` 的 `AsyncContextSnapshotProvider` SPI；
- 在主线程提交异步任务前自动捕获当前 XID 快照；
- 子线程执行前自动安全绑定 `RootContext.bind(xid)` 并联动 SLF4J MDC `xid`；
- 任务执行完毕后在 `finally` 块中强制 `RootContext.unbind()` 与 MDC 还原，彻底杜绝多线程环境下的 XID 污染与跨请求串行。

---

## 维度三：核心生产边界与非功能语义 (Production Boundaries)

### 1. TCC Fence 业务防卫屏障三大防线工作原理与状态机

`TccFenceBarrier` 构筑了严密的状态机防卫模型，彻底终结 TCC 模式的固有顽疾：

```mermaid
flowchart TD
    subgraph TryPhase["一阶段 Try 防线"]
        TryReq["收到一阶段 Try 请求"] --> CheckSuspended{"防线二：检查是否存在\nSUSPENDED 悬挂插旗记录?"}
        CheckSuspended -- 是 --> ThrowHanging["刚性拦截：抛出 TccFenceHangingException (5110)\n拒绝延迟到达的 Try 操作，防业务悬挂！"]
        CheckSuspended -- 否 --> InsertTried["本地原子写入 status=TRIED(1)"]
        InsertTried --> ExecTryBiz["执行业务 Try 逻辑 (如预冻结余额)"]
    end

    subgraph CancelPhase["二阶段 Cancel 防线"]
        CancelReq["收到二阶段 Cancel 请求"] --> CheckRecordExist{"防线一：检查记录是否存在?"}
        CheckRecordExist -- 记录不存在 --> InsertSuspended["空回滚防卫：插入 status=SUSPENDED(4)\n静默放行，不执行业务补偿！"]
        CheckRecordExist -- 记录已存在 --> CheckStatusCancel{"防线三：检查状态"}
        CheckStatusCancel -- status=TRIED --> UpdateRollbacked["状态流转为 ROLLBACKED(3)\n执行业务补偿逻辑 (如解冻还款)"]
        CheckStatusCancel -- status=ROLLBACKED --> IdempotentCancel["幂等防卫：已回滚过\n静默直接放行 (5112)"]
    end

    subgraph ConfirmPhase["二阶段 Confirm 防线"]
        ConfirmReq["收到二阶段 Confirm 请求"] --> CheckStatusConfirm{"防线三：检查状态"}
        CheckStatusConfirm -- status=TRIED --> UpdateCommitted["状态流转为 COMMITTED(2)\n执行业务确认逻辑 (如完成划扣)"]
        CheckStatusConfirm -- status=COMMITTED --> IdempotentConfirm["幂等防卫：已确认过\n静默直接放行 (5112)"]
    end
```

- **防线一：空回滚防卫 (Anti-Empty-Rollback)**：一阶段 Try 未曾执行时收到 Cancel，屏障在本地事务插入 `SUSPENDED` 标记并跳过补偿业务，杜绝资金虚增；
- **防线二：防业务悬挂 (Anti-Hanging)**：若网络滞后的 Try 在 Cancel 执行之后才到达，屏障检测到先期插入的 `SUSPENDED` 标记，立即阻断并抛出 `TccFenceHangingException`，杜绝资金永久悬挂；
- **防线三：二阶段幂等放行 (Idempotent Execution)**：网络抖动导致 Confirm 或 Cancel 多次重传时，状态校验直接命中 `COMMITTED` 或 `ROLLBACKED`，静默幂等放行，杜绝重复划扣。

---

### 2. 微服务跨进程 XID 对称透传生命周期

在分布式微服务调用拓扑中，XID 通过双向拦截器与 RPC 过滤器完成闭环传递：

```mermaid
sequenceDiagram
    autonumber
    actor Client as 客户端
    participant OrderSvc as OrderService (发起方)
    participant FeignInter as SeataFeignContextInterceptor
    participant WebInter as SeataWebHandlerInterceptor
    participant StorageSvc as StorageService (提供方)
    participant TC as Seata TC 协调器

    Client->>OrderSvc: POST /order/create (创建订单)
    Note over OrderSvc: 开启 @Transactional 全局事务
    OrderSvc->>TC: 注册全局事务，生成 XID
    TC-->>OrderSvc: 返回 XID=127.0.0.1:8091:11223344
    OrderSvc->>FeignInter: 发起 Feign 扣减库存调用
    Note over FeignInter: 从 RootContext 提取 XID，写入 Header: TX_XID
    FeignInter->>WebInter: HTTP POST /storage/deduct (携带 TX_XID)
    Note over WebInter: preHandle: RootContext.bind(xid) 且 MDC.put("xid", xid)
    WebInter->>StorageSvc: 执行库存扣减
    StorageSvc->>TC: 注册分支事务 (BranchId)
    StorageSvc-->>WebInter: 响应 200 OK
    Note over WebInter: afterCompletion: 对称释放 RootContext 且恢复 MDC
    WebInter-->>OrderSvc: 返回成功响应
    OrderSvc-->>Client: 订单创建成功
```

- **严密对称性**：入站 `preHandle` 绑定、出站 `afterCompletion` 确定性解绑；出站 Feign 请求自动注入，彻底杜绝跨租户与线程脏读污染。

---

### 3. TCC 防卫日志分级生命周期批量清理机制 (Hierarchical Retention)

针对防卫日志表的长期物理维护，框架确立了严格的分级生命周期策略：
1. **终态业务记录（`COMMITTED` 与 `ROLLBACKED`）**：
   - 保留期：`logTtl`（默认 3 天）；
   - 处理逻辑：超过保留期的记录已被全局协调器最终裁决且业务两阶段完全收敛，准时分批物理删除以释放空间；
2. **悬挂防卫标记（`SUSPENDED`）**：
   - 保留期：`logTtl * suspendedRetentionMultiplier`（默认 `3 x 3d` = 9 天）；
   - 核心防线：悬挂标记是一阶段迟到 Try 的唯一防护盾。极端网络抖动或下游单机假死恢复后，迟到的 Try 可能在数天后重试到达；通过 3 倍超长窗口长效留存，坚决杜绝因日志过早被删导致迟到 Try 重新穿透执行的业务悬挂事故；
3. **活跃状态（`TRIED`）**：
   - 坚决**永不执行物理删除**，保障未完成二阶段裁决的在途事务处于绝对保护中；
4. **ANSI-SQL 双阶段键集分批清理**：
   - 每次物理删除严格遵守 `cleanBatchSize`（默认 500）限制，采用两阶段主键定位删除，避免长事务造成物理行锁长时间挂起。

---

### 4. Sentinel 熔断降级强制回滚防卫机制

在微服务声明式 RPC 调用中，Sentinel 降级逻辑与分布式事务有着本质冲突：
- **冲突根因**：Sentinel 熔断降级通常旨在“服务降级、放行兜底”。但若发生在声明式分布式事务（AT/TCC）调用链中，Fallback 默认返回 Mock 假数据或 null，上游事务切面将误判下游已正常响应，进而向 TC 上报成功决议并提交全局事务，导致上游成功落库而下游实际未执行的严重单边脏写；
- **防卫契约**：`SeataFallbackDefense` 感知当前执行线程的 `RootContext.getXID()`：
  - 若存在活跃全局事务，强制抛出受管业务异常 `BusinessException(SEATA_DEGRADE_BLOCKED, 5104)` 并完整传递原始异常根因；
  - 触发 Spring `@Transactional` 或 Seata 切面感知异常，驱动 TC 实施全链路两阶段原子回滚；
  - 若当前为无事务普通调用，则静默放行，保留原生 Sentinel 降级体验。

---

### 5. MyBatis-Plus 多租户内部表动态放行机制

MyBatis-Plus 行级多租户插件（`TenantLineInnerInterceptor`）会无差别拦截所有 SQL 并强制拼接 `AND tenant_id = ?` 条件：
- **深水区隐患**：Seata 官方的 `undo_log` 与 `tcc_fence_log` 属于中间件物理系统表，表中通常不包含 `tenant_id` 业务列。一旦被追加租户条件，将直接引发 SQL 执行异常或事务回滚无法检索日志；
- **动态放行与大小写清洗**：`SeataTenantLineHandlerWrapper` 自动放行 `undo_log`、`tcc_fence_log` 以及用户通过 `ateng.cloud.seata.tcc.table-name` 配置的自定义表名；
- **方言弹性**：自动清洗表名中的反引号（`）、双引号（"）并统一转小写比对，支持大写方言（如 Oracle `TCC_FENCE_LOG`），实现彻底免疫。

---

### 6. 因果异常体系与微服务主动回滚防雪崩

`SeataExceptionTranslationAspect` 严守工业级因果异常纪律：
- 严禁吞咽异常，完整保留原始异常因果链（`cause`）；
- 将底层不可控的 Seata 原始异常（如 `LockConflictException`、`TransactionException`）在模块边界拦截转译为受管业务异常 `BusinessException`，分别赋予语义化错误码 `5102 (SEATA_LOCK_CONFLICT)` 与 `5103 (SEATA_TC_UNAVAILABLE)`；
- 在全局事务边界（TM）自动感知业务异常并向 TC 上报回滚决议，杜绝单边提交。

---

## 维度四：物理排版与代码全真验证

本模块所有 Java 示例代码、YAML 配置片段与数据库 DDL，**100% 逆向提取自跑通的场景样例工程 [sample-cloud-seata](../ateng-cloud-samples/sample-cloud-seata) 真实源码与实测用例**，并受以下 8 大端到端全真集成测试套件 100% 离线自洽守护：

### 1. 核心集成测试套件全景矩阵

| 场景测试套件类 | 关键验证用例 | 覆盖技术防线与核心强断言 |
| :--- | :--- | :--- |
| **[SampleSeataDistributedTransactionIntegrationTest](../ateng-cloud-samples/sample-cloud-seata/src/test/java/io/github/atengk/cloud/sample/seata/SampleSeataDistributedTransactionIntegrationTest.java)** | `shouldCommitDistributedTransactionSuccessfully`<br>`shouldRollbackDistributedTransactionOnBusinessException`<br>`shouldRollbackDistributedTransactionOnStockInsufficient` | AT 模式订单、库存、账户三节点正向原子提交；余额不足与库存不足时原子回滚，数据 0 污染 |
| **[SampleSeataGlobalLockIntegrationTest](../ateng-cloud-samples/sample-cloud-seata/src/test/java/io/github/atengk/cloud/sample/seata/SampleSeataGlobalLockIntegrationTest.java)** | `shouldExecuteDeclarativeGlobalLock`<br>`shouldExecuteProgrammaticGlobalLock`<br>`shouldExecuteConcurrentlyWithoutLeak` | `@GlobalLock` 声明式与编程式排他防脏读实测；高并发竞争下无 `undo_log` 开销的高性能排他校验 |
| **[SampleSeataTccFenceIntegrationTest](../ateng-cloud-samples/sample-cloud-seata/src/test/java/io/github/atengk/cloud/sample/seata/SampleSeataTccFenceIntegrationTest.java)** | `shouldExecuteNormalTccCommit`<br>`shouldExecuteNormalTccRollback`<br>`shouldHandleEmptyRollback`<br>`shouldPreventBusinessHanging`<br>`shouldEnsureIdempotentExecution` | TCC 两阶段流转；空回滚防卫（插入 SUSPENDED）；业务悬挂拦截（抛出 `TccFenceHangingException`）；重复提交/回滚幂等放行 |
| **[SampleSeataFeignMdcIntegrationTest](../ateng-cloud-samples/sample-cloud-seata/src/test/java/io/github/atengk/cloud/sample/seata/SampleSeataFeignMdcIntegrationTest.java)** | `shouldInjectTxXidHeaderOnFeignRequest`<br>`shouldPropagateXidAndMdcInServerPipeline`<br>`shouldHandleNonTransactionalRequestNormally` | Feign 客户端透明注入 `TX_XID` 请求头；Web 入站自动提取绑定 XID 并同步 MDC `xid`；出口对称释放防污染 |
| **[SampleSeataDubboRpcIntegrationTest](../ateng-cloud-samples/sample-cloud-seata/src/test/java/io/github/atengk/cloud/sample/seata/SampleSeataDubboRpcIntegrationTest.java)** | `shouldPropagateXidOnConsumerSide`<br>`shouldBindAndRestoreOnProviderSide`<br>`shouldTriggerAdaptiveIdempotencyWhenAlreadyBound`<br>`shouldBypassInjvmProtocol` | Dubbo 消费端注入 Attachment；服务端绑定 XID 与 MDC；自适应幂等防卫（已绑定相同 XID 时静默放行并不二次解绑）；`injvm` 协议短路放行 |
| **[SampleSeataTccFenceCleanIntegrationTest](../ateng-cloud-samples/sample-cloud-seata/src/test/java/io/github/atengk/cloud/sample/seata/SampleSeataTccFenceCleanIntegrationTest.java)** | `shouldExecuteHierarchicalLogClean`<br>`shouldRespectBatchSizeLimit` | 分级生命周期批量清理：终态超期准时清理、悬挂长效留存超期清理、活跃状态永不清理；单批次数量严格限制 |
| **[SampleSeataTenantIntegrationTest](../ateng-cloud-samples/sample-cloud-seata/src/test/java/io/github/atengk/cloud/sample/seata/SampleSeataTenantIntegrationTest.java)** | `shouldIgnoreSeataInternalAndCustomTables`<br>`shouldRewriteSqlSelectAccurately` | MyBatis-Plus 多租户放行判定：系统表与动态自定义表免疫租户过滤；真实 SQL 重写校验（业务表拼接租户条件，系统表保持原样） |
| **[SampleSeataSentinelFallbackIntegrationTest](../ateng-cloud-samples/sample-cloud-seata/src/test/java/io/github/atengk/cloud/sample/seata/SampleSeataSentinelFallbackIntegrationTest.java)** | `shouldRollbackTransactionWhenDownstreamFallbackTriggeredInActiveTx`<br>`shouldAllowFallbackPeacefullyWhenNoActiveTx`<br>`shouldCommitNormallyWhenDownstreamSucceedsInActiveTx` | 对抗性用例：活跃全局事务下，下游触发熔断降级强制抛出 `5104` 阻断并原子回滚本地数据；普通无事务调用降级平滑放行并安全提交 |

---

### 2. 离线冒烟门禁与构建准出命令

本地日常与提交准出验证命令（100% 离线自洽沙箱，多线程并行加速）：

```bash
# 阶梯一：ateng-cloud-seata 模块单元测试 (119 个单测 100% PASS)
mvn test -pl ateng-cloud-seata -am -T 1C -nsu -B

# 阶梯二：sample-cloud-seata 场景工程端到端全真集成测试 (25 个集成测试 100% PASS)
mvn test -pl ateng-cloud-samples/sample-cloud-seata -am -T 1C -nsu -B

# 阶梯三：模块与场景工程联动准出验证 (144 个用例 100% 全绿)
mvn test -pl ateng-cloud-seata,ateng-cloud-samples/sample-cloud-seata -am -T 1C -nsu -B
```
