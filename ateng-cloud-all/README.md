# ateng-cloud-all

微服务通用基础设施聚合全家桶，提供一键引入常用微服务核心构件的能力，杜绝业务微服务重复堆砌分散依赖。

---

## 维度零：特性与构件聚合矩阵

| 聚合模块 | 核心职责 | 当前状态 |
| :--- | :--- | :--- |
| `ateng-cloud-nacos` | 服务注册发现与 Nacos 动态配置中心 | 已集成 |
| `ateng-cloud-feign` | 声明式 HTTP RPC 通信底座与防雪崩重试治理 | 规划中 |
| `ateng-cloud-sentinel` | 统一受管降级工厂与流量防卫治理 | 规划中 |
| `ateng-cloud-traffic` | 全链路流量染色与金丝雀灰度路由 | 规划中 |

---

## 维度一：最小起步体验

### 引入依赖

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-cloud-all</artifactId>
</dependency>
```

---

## 维度二：生产扩展接管

通过 Maven `<exclusions>` 可按需排除无需激活的特定中间件依赖，保持轻量。

---

## 维度三：核心生产边界与非功能语义

- 聚合包本身不包含业务代码，专职执行依赖传递与聚合管治。
- 依赖版本由根目录统一管控，消除依赖版本冲突风险。

---

## 维度四：物理排版与代码全真验证

- 对应场景工程为 `ateng-cloud-samples/sample-cloud-smoke-test`。
