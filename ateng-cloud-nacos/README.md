# ateng-cloud-nacos

Nacos 服务注册与动态配置中心基础设施构件，全面支持 Spring Boot 3 原生 `spring.config.import` 规范，集成开箱即用的优雅上下线与零破坏配置热绑。

---

## 维度零：特性与公共 API 全景速查矩阵

| 类 / 接口 / 属性 | 类型 | 核心职责 | 默认值 / 边界 |
| :--- | :--- | :--- | :--- |
| `AtengNacosAutoConfiguration` | 自动装配类 | Nacos 服务注册与配置中心自动化装配入口 | 当 `ateng.cloud.nacos.enabled=true` 时激活 |
| `AtengNacosProperties` | 配置属性类 | Nacos 治理增强配置映射 | 前缀 `ateng.cloud.nacos` |
| `ateng.cloud.nacos.enabled` | 配置项 | 是否激活 Nacos 基础设施增强 | `true` |

---

## 维度一：最小起步体验

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-cloud-nacos</artifactId>
</dependency>
```

### 2. 最小化配置 (`application.yml`)

遵循 Spring Boot 3 标准 `spring.config.import` 规范：

```yaml
spring:
  application:
    name: demo-service
  cloud:
    nacos:
      server-addr: ${NACOS_ADDR:127.0.0.1:8848}
      discovery:
        namespace: ${NACOS_NAMESPACE:public}
        group: ${NACOS_GROUP:DEFAULT_GROUP}
  config:
    import:
      - optional:nacos:${spring.application.name}.yaml
      - optional:nacos:application-common.yaml?group=DEFAULT_GROUP
```

---

## 维度二：生产扩展接管

### 自定义配置属性覆盖

若需关闭框架增强自动装配，可在配置中心或本地声明：

```yaml
ateng:
  cloud:
    nacos:
      enabled: false
```

---

## 维度三：核心生产边界与非功能语义

1. **现代加载与 `@RefreshScope` 禁令**：全域使用 `spring.config.import` 规范，严禁在承载事务与锁的业务单例 Bean 上标注 `@RefreshScope`，配置热更统一收敛至纯 `@ConfigurationProperties` 类。
2. **离线单测自洽**：单测环境下默认声明 `spring.cloud.nacos.discovery.enabled: false` 与 `spring.cloud.nacos.config.enabled: false`，杜绝依赖外部物理 Nacos 服务端。

---

## 维度四：物理排版与代码全真验证

- **单元测试锚定**：对齐 `ateng-cloud-samples/sample-cloud-nacos` 场景自验工程。
- **排版合规**：表格泛型严格反引号包裹，配置项与 `AtengNacosProperties` 100% 逐字对齐。
