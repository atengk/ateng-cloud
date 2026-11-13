package io.github.atengk.cloud.sample.traffic;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import io.github.atengk.cloud.sample.traffic.model.OrderFulfillmentVO;
import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.nacos.NacosTrafficRuleListener;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.properties.TrafficWhitelistAction;
import io.github.atengk.cloud.traffic.rule.hash.StickyHashPartitioner;
import io.github.atengk.cloud.traffic.rule.model.TrafficCondition;
import io.github.atengk.cloud.traffic.rule.model.TrafficFieldSource;
import io.github.atengk.cloud.traffic.rule.model.TrafficMatchOperator;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全链路微服务多跳灰度沙箱全真端到端集成测试 (100% 离线自洽)
 *
 * <p>电商履约分布式微服务拓扑：
 * Gateway ➔ Order Service ➔ Inventory Service ➔ Logistics Service
 *
 * 验证核心能力：
 * 1. 静态 Header 与 Query 参数染色及全链路透传
 * 2. 东西向 LoadBalancer 精准匹配实例灰度标签
 * 3. OpenFeign 跨微服务调用出站标头透明传递与泳道穿透
 * 4. 用户 ID / 租户 ID 规则匹配与灰度分流
 * 5. 一致性哈希百分比切流与单用户会话粘性保障
 * 6. 多金丝雀互斥百分比区间切流 (percentageRange)
 * 7. 跨跳泳道穿透 (Lane Continuity): Order(v2) ➔ Inventory(fallback base) ➔ Logistics(re-enter v2)
 * 8. 声明式 @TrafficTag 注解自动绑定金丝雀泳道与上下文恢复
 * 9. 白名单安全防线 (STRIP 剥离清洗 vs REJECT 4212 拦截)
 * 10. 金融级严格隔离阻断 (Strict Lane Fail-Fast 4210 异常拦截)
 * 11. 灰度节点全下线平滑降级 (Resilient Fallback 零 503 报警)
 * 12. Nacos 动态规则秒级热更新切流
 * 13. SUFFIX 与 CONTAINS 复杂谓词匹配
 * 14. /actuator/traffic 运维端点全景快照与 /simulate 沙箱演练
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SampleCloudTrafficIntegrationTest.OfflineMockConfig.class)
@DisplayName("全链路微服务流量染色与金丝雀灰度端到端集成实测")
class SampleCloudTrafficIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DynamicTestDiscoveryClient discoveryClient;

    @Autowired
    private AtengTrafficProperties trafficProperties;

    @Autowired(required = false)
    private NacosTrafficRuleListener nacosRuleListener;

    private static final ParameterizedTypeReference<Result<OrderFulfillmentVO>> ORDER_VO_TYPE_REF =
            new ParameterizedTypeReference<>() {};

    private OrderFulfillmentVO extractOrderFulfillment(ResponseEntity<Result<OrderFulfillmentVO>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Result<OrderFulfillmentVO> result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        OrderFulfillmentVO vo = result.getData();
        assertThat(vo).isNotNull();
        return vo;
    }

    @BeforeEach
    void setUp() {
        TrafficContextHolder.clear();
        UserContextHolder.clear();
        trafficProperties.setStrictLane(false);
        trafficProperties.setFallbackToDefault(true);
        trafficProperties.setLaneContinuity(true);
        trafficProperties.setAllowedTags(Collections.emptySet());
        trafficProperties.setWhitelistAction(TrafficWhitelistAction.STRIP);

        // 初始化电商履约微服务沙箱拓扑 (Order Service -> Inventory Service -> Logistics Service)
        // Order Service: 同时拥有基线与 v2 灰度节点
        discoveryClient.setInstances("order-service", List.of(
                new DefaultServiceInstance("inst-order-base", "order-service", "127.0.0.1", port, false, Map.of("tag", "default")),
                new DefaultServiceInstance("inst-order-gray", "order-service", "127.0.0.1", port, false, Map.of("tag", "v2"))
        ));

        // Inventory Service: 仅拥有基线节点 (模拟中间服务未发版)
        discoveryClient.setInstances("inventory-service", List.of(
                new DefaultServiceInstance("inst-inv-base", "inventory-service", "127.0.0.1", port, false, Map.of("tag", "default"))
        ));

        // Logistics Service: 同时拥有基线与 v2 灰度节点 (下游发版)
        discoveryClient.setInstances("logistics-service", List.of(
                new DefaultServiceInstance("inst-logistics-base", "logistics-service", "127.0.0.1", port, false, Map.of("tag", "default")),
                new DefaultServiceInstance("inst-logistics-gray", "logistics-service", "127.0.0.1", port, false, Map.of("tag", "v2"))
        ));

        // 重置动态规则
        if (nacosRuleListener != null) {
            nacosRuleListener.receiveAppConfig("[]");
            nacosRuleListener.receiveGlobalConfig("[]");
        }
    }

    @AfterEach
    void tearDown() {
        TrafficContextHolder.clear();
        UserContextHolder.clear();
        trafficProperties.setStrictLane(false);
        trafficProperties.setFallbackToDefault(true);
        trafficProperties.setAllowedTags(Collections.emptySet());
        trafficProperties.setWhitelistAction(TrafficWhitelistAction.STRIP);
    }

    @Test
    @DisplayName("电商履约切片: 未染色普通订单调度全链路调度至基线实例")
    void testOrderDispatchBaselineRouting() {
        ResponseEntity<Result<OrderFulfillmentVO>> response = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/dispatch?orderNo=ORD-20261103-1001",
                HttpMethod.GET,
                null,
                ORDER_VO_TYPE_REF
        );

        OrderFulfillmentVO vo = extractOrderFulfillment(response);
        assertThat(vo.getTrafficTag()).isNull();
        assertThat(vo.getOrderNo()).isEqualTo("ORD-20261103-1001");
        assertThat(vo.getDeliveryStatus()).isEqualTo("FULFILLED");

        // 验证全链路均路由到 default 基线节点 (order-service -> inventory-service -> logistics-service)
        assertThat(vo.getVisitedNodes()).containsExactly(
                "Gateway[tag=none]",
                "OrderService[inst=inst-order-base, tag=default]",
                "InventoryService[inst=inst-inv-base, tag=default]",
                "LogisticsService[inst=inst-logistics-base, tag=default]"
        );
    }

    @Test
    @DisplayName("电商履约切片: 静态 Header 染色与跨跳泳道穿透 (Lane Continuity)")
    void testOrderDispatchHeaderStainingAndCrossHopLaneContinuity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TrafficConstants.HEADER_TRAFFIC_TAG, "v2");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Result<OrderFulfillmentVO>> response = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/dispatch?orderNo=ORD-20261103-1002",
                HttpMethod.GET,
                entity,
                ORDER_VO_TYPE_REF
        );

        OrderFulfillmentVO vo = extractOrderFulfillment(response);
        assertThat(vo.getTrafficTag()).isEqualTo("v2");
        assertThat(vo.getOrderNo()).isEqualTo("ORD-20261103-1002");
        assertThat(vo.getDeliveryStatus()).isEqualTo("FULFILLED");

        // 核心断言跨跳泳道穿透 (Lane Continuity)：
        // 1. Order Service 命中 v2 灰度节点 (inst-order-gray)
        // 2. Inventory Service 无 v2 节点，平滑降级至基线 (inst-inv-base)
        // 3. Logistics Service 泳道回升成功，重新命中 v2 灰度节点 (inst-logistics-gray)
        assertThat(vo.getVisitedNodes()).containsExactly(
                "Gateway[tag=v2]",
                "OrderService[inst=inst-order-gray, tag=v2]",
                "InventoryService[inst=inst-inv-base, tag=default]",
                "LogisticsService[inst=inst-logistics-gray, tag=v2]"
        );
    }

    @Test
    @DisplayName("电商履约切片: URL Query 参数 traffic_tag 染色透传")
    void testOrderDispatchQueryParamStaining() {
        ResponseEntity<Result<OrderFulfillmentVO>> response = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/dispatch?traffic_tag=v2&orderNo=ORD-20261103-1003",
                HttpMethod.GET,
                null,
                ORDER_VO_TYPE_REF
        );

        OrderFulfillmentVO vo = extractOrderFulfillment(response);
        assertThat(vo.getTrafficTag()).isEqualTo("v2");
        assertThat(vo.getOrderNo()).isEqualTo("ORD-20261103-1003");

        assertThat(vo.getVisitedNodes()).containsExactly(
                "Gateway[tag=v2]",
                "OrderService[inst=inst-order-gray, tag=v2]",
                "InventoryService[inst=inst-inv-base, tag=default]",
                "LogisticsService[inst=inst-logistics-gray, tag=v2]"
        );
    }

    @Test
    @DisplayName("电商履约切片: OpenFeign 声明式跨微服务调用出站标头透明透传与泳道穿透")
    void testOrderDispatchFeignRouting() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TrafficConstants.HEADER_TRAFFIC_TAG, "v2");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Result<OrderFulfillmentVO>> response = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/feign-dispatch?orderNo=ORD-FEIGN-001",
                HttpMethod.GET,
                entity,
                ORDER_VO_TYPE_REF
        );

        OrderFulfillmentVO vo = extractOrderFulfillment(response);
        assertThat(vo.getTrafficTag()).isEqualTo("v2");
        assertThat(vo.getOrderNo()).isEqualTo("ORD-FEIGN-001");

        // 验证 Feign 出站链路透传与下游 LogisticsService 泳道恢复
        assertThat(vo.getVisitedNodes()).contains(
                "FeignGateway[tag=v2]",
                "InventoryService[feign-direct]",
                "LogisticsService[inst=inst-logistics-gray, tag=v2]"
        );
    }

    @Test
    @DisplayName("电商履约切片: 一致性哈希百分比切流与单用户会话粘性保障")
    void testOrderDispatchCanaryHashAndSessionStickiness() {
        if (nacosRuleListener == null) {
            return;
        }

        // 配置 50% 放量切流规则，哈希源取 query 的 userId
        TrafficRule stickyRule = TrafficRule.builder()
                .id("rule-order-hash-50")
                .targetTag("v2")
                .percentage(50)
                .hashKeySource(TrafficFieldSource.QUERY)
                .hashKeyName("userId")
                .build();
        nacosRuleListener.receiveAppConfig(JsonUtil.toJsonString(List.of(stickyRule)));

        String userId = "user_sticky_9999";
        String firstObservedTag = null;
        for (int i = 0; i < 5; i++) {
            ResponseEntity<Result<OrderFulfillmentVO>> response = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/canary-hash?userId=" + userId + "&orderNo=ORD-STICKY-001",
                    HttpMethod.GET,
                    null,
                    ORDER_VO_TYPE_REF
            );
            OrderFulfillmentVO vo = extractOrderFulfillment(response);
            if (firstObservedTag == null) {
                firstObservedTag = vo.getTrafficTag() != null ? vo.getTrafficTag() : "baseline";
            } else {
                String currentTag = vo.getTrafficTag() != null ? vo.getTrafficTag() : "baseline";
                assertThat(currentTag).isEqualTo(firstObservedTag);
            }
        }
    }

    @Test
    @DisplayName("电商履约切片: 多金丝雀互斥分桶放量切流 (percentageRange)")
    void testOrderDispatchMultiCanaryPercentageRangeMutualExclusion() {
        if (nacosRuleListener == null) {
            return;
        }

        // 为拓扑补充 v3 节点
        discoveryClient.setInstances("order-service", List.of(
                new DefaultServiceInstance("inst-order-base", "order-service", "127.0.0.1", port, false, Map.of("tag", "default")),
                new DefaultServiceInstance("inst-order-gray", "order-service", "127.0.0.1", port, false, Map.of("tag", "v2")),
                new DefaultServiceInstance("inst-order-v3", "order-service", "127.0.0.1", port, false, Map.of("tag", "v3"))
        ));
        discoveryClient.setInstances("logistics-service", List.of(
                new DefaultServiceInstance("inst-logistics-base", "logistics-service", "127.0.0.1", port, false, Map.of("tag", "default")),
                new DefaultServiceInstance("inst-logistics-gray", "logistics-service", "127.0.0.1", port, false, Map.of("tag", "v2")),
                new DefaultServiceInstance("inst-logistics-v3", "logistics-service", "127.0.0.1", port, false, Map.of("tag", "v3"))
        ));

        TrafficRule ruleV2 = TrafficRule.builder()
                .id("rule-order-v2-range")
                .priority(1)
                .targetTag("v2")
                .percentageRange(0, 50)
                .hashKeySource(TrafficFieldSource.QUERY)
                .hashKeyName("userId")
                .build();

        TrafficRule ruleV3 = TrafficRule.builder()
                .id("rule-order-v3-range")
                .priority(2)
                .targetTag("v3")
                .percentageRange(50, 100)
                .hashKeySource(TrafficFieldSource.QUERY)
                .hashKeyName("userId")
                .build();

        nacosRuleListener.receiveAppConfig(JsonUtil.toJsonString(List.of(ruleV2, ruleV3)));

        StickyHashPartitioner partitioner = new StickyHashPartitioner();
        String userV2 = null;
        String userV3 = null;
        for (int i = 0; i < 500; i++) {
            String uid = "user_range_test_" + i;
            if (userV2 == null && partitioner.isHitRange(uid, 0, 50)) {
                userV2 = uid;
            } else if (userV3 == null && partitioner.isHitRange(uid, 50, 100)) {
                userV3 = uid;
            }
            if (userV2 != null && userV3 != null) {
                break;
            }
        }
        assertThat(userV2).isNotNull();
        assertThat(userV3).isNotNull();

        // 验证 userV2 命中 v2 泳道
        ResponseEntity<Result<OrderFulfillmentVO>> respV2 = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/multi-canary?userId=" + userV2,
                HttpMethod.GET,
                null,
                ORDER_VO_TYPE_REF
        );
        OrderFulfillmentVO voV2 = extractOrderFulfillment(respV2);
        assertThat(voV2.getTrafficTag()).isEqualTo("v2");
        assertThat(voV2.getVisitedNodes()).contains(
                "OrderService[inst=inst-order-gray, tag=v2]",
                "LogisticsService[inst=inst-logistics-gray, tag=v2]"
        );

        // 验证 userV3 命中 v3 泳道
        ResponseEntity<Result<OrderFulfillmentVO>> respV3 = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/multi-canary?userId=" + userV3,
                HttpMethod.GET,
                null,
                ORDER_VO_TYPE_REF
        );
        OrderFulfillmentVO voV3 = extractOrderFulfillment(respV3);
        assertThat(voV3.getTrafficTag()).isEqualTo("v3");
        assertThat(voV3.getVisitedNodes()).contains(
                "OrderService[inst=inst-order-v3, tag=v3]",
                "LogisticsService[inst=inst-logistics-v3, tag=v3]"
        );
    }

    @Test
    @DisplayName("策略切片: 声明式 @TrafficTag 注解自动绑定金丝雀泳道并触发跨跳穿透与出口还原")
    void testPolicyDeclarativeTagAspect() {
        ResponseEntity<Result<OrderFulfillmentVO>> response = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/policy/declarative-tag?orderNo=ORD-DECL-1001",
                HttpMethod.GET,
                null,
                ORDER_VO_TYPE_REF
        );

        OrderFulfillmentVO vo = extractOrderFulfillment(response);
        assertThat(vo.getTrafficTag()).isEqualTo("v2");
        assertThat(vo.getOrderNo()).isEqualTo("ORD-DECL-1001");

        // 强断言：声明式绑定的 v2 标签成功引导全链路调度并在出口还原
        // 链路：DeclarativeEntry[tag=v2] ➔ OrderService[inst=inst-order-gray, tag=v2] ➔ InventoryService[inst=inst-inv-base, tag=default] ➔ LogisticsService[inst=inst-logistics-gray, tag=v2]
        assertThat(vo.getVisitedNodes()).containsExactly(
                "DeclarativeEntry[tag=v2]",
                "OrderService[inst=inst-order-gray, tag=v2]",
                "InventoryService[inst=inst-inv-base, tag=default]",
                "LogisticsService[inst=inst-logistics-gray, tag=v2]"
        );

        // 断言测试线程上下文零污染
        assertThat(TrafficContextHolder.getTag()).isNull();
    }

    @Test
    @DisplayName("策略切片: 应用层外部标签白名单防线 (STRIP 剥离清洗与 REJECT 4212 阻断)")
    void testPolicyWhitelistDefense() {
        // 配置白名单仅允许 v2
        trafficProperties.setAllowedTags(Set.of("v2"));

        // 1. STRIP 模式：携带未授权标签 'forged-tag'，应被安全剥离降级至基线
        trafficProperties.setWhitelistAction(TrafficWhitelistAction.STRIP);
        HttpHeaders stripHeaders = new HttpHeaders();
        stripHeaders.set(TrafficConstants.HEADER_TRAFFIC_TAG, "forged-tag");
        ResponseEntity<Result<OrderFulfillmentVO>> stripResp = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/policy/whitelist-check?orderNo=ORD-WL-STRIP",
                HttpMethod.GET,
                new HttpEntity<>(stripHeaders),
                ORDER_VO_TYPE_REF
        );
        OrderFulfillmentVO stripVo = extractOrderFulfillment(stripResp);
        assertThat(stripVo.getTrafficTag()).isNull();
        assertThat(stripVo.getVisitedNodes()).contains(
                "OrderService[inst=inst-order-base, tag=default]",
                "LogisticsService[inst=inst-logistics-base, tag=default]"
        );

        // 2. REJECT 模式：携带未授权标签 'forged-tag'，必须直接 4212 阻断
        trafficProperties.setWhitelistAction(TrafficWhitelistAction.REJECT);
        HttpHeaders rejectHeaders = new HttpHeaders();
        rejectHeaders.set(TrafficConstants.HEADER_TRAFFIC_TAG, "forged-tag");
        ResponseEntity<String> rejectResponse = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/policy/whitelist-check?orderNo=ORD-WL-REJECT",
                HttpMethod.GET,
                new HttpEntity<>(rejectHeaders),
                String.class
        );
        assertThat(rejectResponse.getBody()).isNotNull();
        assertThat(rejectResponse.getBody()).contains("4212");

        // 3. 携带已授权标签 'v2'，不受阻断正常放行
        HttpHeaders allowedHeaders = new HttpHeaders();
        allowedHeaders.set(TrafficConstants.HEADER_TRAFFIC_TAG, "v2");
        ResponseEntity<Result<OrderFulfillmentVO>> allowedResp = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/policy/whitelist-check?orderNo=ORD-WL-OK",
                HttpMethod.GET,
                new HttpEntity<>(allowedHeaders),
                ORDER_VO_TYPE_REF
        );
        OrderFulfillmentVO allowedVo = extractOrderFulfillment(allowedResp);
        assertThat(allowedVo.getTrafficTag()).isEqualTo("v2");
        assertThat(allowedVo.getVisitedNodes()).contains(
                "OrderService[inst=inst-order-gray, tag=v2]",
                "LogisticsService[inst=inst-logistics-gray, tag=v2]"
        );
    }

    @Test
    @DisplayName("策略切片: 金融级严格泳道强隔离阻断 (Strict Lane 503 Fail-Fast 异常拦截)")
    void testPolicyStrictLaneIsolationFailFast() {
        // 开启严格隔离模式
        trafficProperties.setStrictLane(true);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TrafficConstants.HEADER_TRAFFIC_TAG, "v3-not-exist");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/policy/strict-lane?orderNo=ORD-STRICT-001",
                HttpMethod.GET,
                entity,
                String.class
        );

        // 强断言：严格隔离模式下未命中灰度实例，直接触发 Fail-Fast (阻断调度并由全局异常拦截返回 STRICT_LANE_BLOCK 4210 业务错误)，严禁脏写基线
        assertThat(response.getStatusCode().value()).isIn(200, 503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("4210");
        assertThat(response.getBody()).contains("严格泳道隔离阻断");
    }

    @Test
    @DisplayName("策略切片: 灰度节点全下线弹性平滑降级 (Resilient Fallback 零 503 报警)")
    void testPolicyResilientFallback() {
        // 将 Order Service 的灰度节点下线 (仅保留基线实例)
        discoveryClient.removeInstance("order-service", "inst-order-gray");

        HttpHeaders headers = new HttpHeaders();
        headers.set(TrafficConstants.HEADER_TRAFFIC_TAG, "v2");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Result<OrderFulfillmentVO>> response = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/policy/resilient-fallback?orderNo=ORD-FALLBACK-001",
                HttpMethod.GET,
                entity,
                ORDER_VO_TYPE_REF
        );

        // 强断言：Order Service 灰度实例全下线时，必须平滑回退至基线，返回 200 OK，绝不抛出 503 报警
        OrderFulfillmentVO vo = extractOrderFulfillment(response);
        assertThat(vo.getVisitedNodes()).contains(
                "OrderService[inst=inst-order-base, tag=default]"
        );
        // 且由于泳道穿透 (Lane Continuity) 保持，下游 logistics-service 仍能重新进入其已发版的 v2 灰度实例
        assertThat(vo.getVisitedNodes()).contains(
                "LogisticsService[inst=inst-logistics-gray, tag=v2]"
        );
    }

    @Test
    @DisplayName("电商履约切片: 用户 ID 谓词匹配动态分流至灰度泳道")
    void testOrderDispatchUserIdPredicateMatching() {
        // 配置动态规则：当 Header X-User-Id 为 vip_canary_001 时赋予标签 v2
        TrafficRule rule = TrafficRule.builder()
                .id("rule-vip-user")
                .targetTag("v2")
                .conditions(List.of(
                        TrafficCondition.builder()
                                .fieldSource(TrafficFieldSource.HEADER)
                                .fieldName("X-User-Id")
                                .operator(TrafficMatchOperator.EXACT)
                                .expectedValue("vip_canary_001")
                                .build()
                ))
                .build();

        if (nacosRuleListener != null) {
            nacosRuleListener.receiveAppConfig(JsonUtil.toJsonString(List.of(rule)));
        }

        // 1. VIP 用户发起请求 (无 X-Traffic-Tag 标头)
        HttpHeaders vipHeaders = new HttpHeaders();
        vipHeaders.set("X-User-Id", "vip_canary_001");
        ResponseEntity<Result<OrderFulfillmentVO>> vipResponse = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/dispatch?orderNo=ORD-PRED-VIP",
                HttpMethod.GET,
                new HttpEntity<>(vipHeaders),
                ORDER_VO_TYPE_REF
        );

        OrderFulfillmentVO vipVo = extractOrderFulfillment(vipResponse);
        assertThat(vipVo.getTrafficTag()).isEqualTo("v2");
        assertThat(vipVo.getVisitedNodes()).contains(
                "OrderService[inst=inst-order-gray, tag=v2]",
                "LogisticsService[inst=inst-logistics-gray, tag=v2]"
        );

        // 2. 普通用户发起请求
        HttpHeaders normalHeaders = new HttpHeaders();
        normalHeaders.set("X-User-Id", "normal_user_888");
        ResponseEntity<Result<OrderFulfillmentVO>> normalResponse = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/dispatch?orderNo=ORD-PRED-NORM",
                HttpMethod.GET,
                new HttpEntity<>(normalHeaders),
                ORDER_VO_TYPE_REF
        );

        OrderFulfillmentVO normalVo = extractOrderFulfillment(normalResponse);
        assertThat(normalVo.getTrafficTag()).isNull();
        assertThat(normalVo.getVisitedNodes()).contains(
                "OrderService[inst=inst-order-base, tag=default]",
                "LogisticsService[inst=inst-logistics-base, tag=default]"
        );
    }

    @Test
    @DisplayName("电商履约切片: Nacos 流量规则秒级热更新切流")
    void testOrderDispatchNacosRuleHotReload() {
        if (nacosRuleListener == null) {
            return;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Hot-Channel", "wechat");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // 1. 未配置热更规则前，请求走基线
        ResponseEntity<Result<OrderFulfillmentVO>> resp1 = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/dispatch?orderNo=ORD-HOT-001",
                HttpMethod.GET,
                entity,
                ORDER_VO_TYPE_REF
        );
        OrderFulfillmentVO vo1 = extractOrderFulfillment(resp1);
        assertThat(vo1.getTrafficTag()).isNull();

        // 2. 模拟 Nacos 推送新规则：X-Hot-Channel == wechat ➔ 染色为 v2
        TrafficRule hotRule = TrafficRule.builder()
                .id("rule-wechat-hot")
                .targetTag("v2")
                .conditions(List.of(
                        TrafficCondition.builder()
                                .fieldSource(TrafficFieldSource.HEADER)
                                .fieldName("X-Hot-Channel")
                                .expectedValue("wechat")
                                .build()
                ))
                .build();
        nacosRuleListener.receiveAppConfig(JsonUtil.toJsonString(List.of(hotRule)));

        // 3. 再次发起相同请求，无需重启秒级即时生效命中 v2
        ResponseEntity<Result<OrderFulfillmentVO>> resp2 = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/dispatch?orderNo=ORD-HOT-002",
                HttpMethod.GET,
                entity,
                ORDER_VO_TYPE_REF
        );
        OrderFulfillmentVO vo2 = extractOrderFulfillment(resp2);
        assertThat(vo2.getTrafficTag()).isEqualTo("v2");
        assertThat(vo2.getVisitedNodes()).contains(
                "OrderService[inst=inst-order-gray, tag=v2]",
                "LogisticsService[inst=inst-logistics-gray, tag=v2]"
        );

        // 4. 清除规则，秒级回退
        nacosRuleListener.receiveAppConfig("[]");
        ResponseEntity<Result<OrderFulfillmentVO>> resp3 = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/dispatch?orderNo=ORD-HOT-003",
                HttpMethod.GET,
                entity,
                ORDER_VO_TYPE_REF
        );
        OrderFulfillmentVO vo3 = extractOrderFulfillment(resp3);
        assertThat(vo3.getTrafficTag()).isNull();
    }

    @Test
    @DisplayName("电商履约切片: SUFFIX 与 CONTAINS 谓词规则动态匹配灰度泳道")
    void testOrderDispatchSuffixAndContainsPredicateRouting() {
        TrafficRule rule = TrafficRule.builder()
                .id("rule-suffix-contains")
                .name("Suffix and Contains Demo")
                .targetTag("v2")
                .conditions(List.of(
                        TrafficCondition.builder()
                                .fieldSource(TrafficFieldSource.HEADER)
                                .fieldName("X-Tenant-Domain")
                                .operator(TrafficMatchOperator.SUFFIX)
                                .expectedValue(".internal")
                                .build(),
                        TrafficCondition.builder()
                                .fieldSource(TrafficFieldSource.HEADER)
                                .fieldName("X-Channel-Code")
                                .operator(TrafficMatchOperator.CONTAINS)
                                .expectedValue("CANARY")
                                .build()
                ))
                .build();

        if (nacosRuleListener != null) {
            nacosRuleListener.receiveAppConfig(JsonUtil.toJsonString(List.of(rule)));
        }

        // 1. 同时满足 SUFFIX 与 CONTAINS
        HttpHeaders hitHeaders = new HttpHeaders();
        hitHeaders.set("X-Tenant-Domain", "app.corp.internal");
        hitHeaders.set("X-Channel-Code", "PRE_CANARY_ONLINE");
        ResponseEntity<Result<OrderFulfillmentVO>> hitResp = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/dispatch?orderNo=ORD-PRED-HIT",
                HttpMethod.GET,
                new HttpEntity<>(hitHeaders),
                ORDER_VO_TYPE_REF
        );
        OrderFulfillmentVO hitVo = extractOrderFulfillment(hitResp);
        assertThat(hitVo.getTrafficTag()).isEqualTo("v2");
        assertThat(hitVo.getVisitedNodes()).contains(
                "OrderService[inst=inst-order-gray, tag=v2]",
                "LogisticsService[inst=inst-logistics-gray, tag=v2]"
        );

        // 2. 不满足 SUFFIX (域名后缀不符)
        HttpHeaders missHeaders = new HttpHeaders();
        missHeaders.set("X-Tenant-Domain", "app.corp.external");
        missHeaders.set("X-Channel-Code", "PRE_CANARY_ONLINE");
        ResponseEntity<Result<OrderFulfillmentVO>> missResp = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/dispatch?orderNo=ORD-PRED-MISS",
                HttpMethod.GET,
                new HttpEntity<>(missHeaders),
                ORDER_VO_TYPE_REF
        );
        OrderFulfillmentVO missVo = extractOrderFulfillment(missResp);
        assertThat(missVo.getTrafficTag()).isNull();
    }

    @Test
    @DisplayName("运维端点切片: 验证 /actuator/traffic 端点全景只读查询与 POST /actuator/traffic/simulate 沙箱演练")
    void testActuatorTrafficEndpointAndSimulationSandbox() {
        // 1. GET /actuator/traffic 只读查询
        ResponseEntity<Map<String, Object>> overviewResp = restTemplate.exchange(
                "http://localhost:" + port + "/actuator/traffic",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );
        assertThat(overviewResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> overview = overviewResp.getBody();
        assertThat(overview).isNotNull();
        assertThat(overview.get("status")).isEqualTo("UP");
        assertThat(overview.get("properties")).isNotNull();
        assertThat(overview.get("rules")).isNotNull();
        assertThat(overview.get("metrics")).isNotNull();

        // 2. POST /actuator/traffic/simulate 模拟匹配沙箱 (通过 JSON 字符串或属性快照传递)
        Map<String, Object> simulatePayload = Map.of(
                "headers", "{\"X-Device-Type\":\"iOS\"}",
                "queryParams", "{\"channel\":\"appstore\"}",
                "userId", "user-vip-999",
                "path", "/sample/cloud/traffic/order/dispatch"
        );
        ResponseEntity<Map<String, Object>> simResp = restTemplate.exchange(
                "http://localhost:" + port + "/actuator/traffic/simulate",
                HttpMethod.POST,
                new HttpEntity<>(simulatePayload),
                new ParameterizedTypeReference<>() {}
        );
        assertThat(simResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> simResult = simResp.getBody();
        assertThat(simResult).isNotNull();
        assertThat(simResult.get("success")).isEqualTo(true);
        assertThat(Long.parseLong(simResult.get("durationNanos").toString())).isGreaterThan(0L);

        // 3. 验证触发请求后度量统计累计
        HttpHeaders canaryHeaders = new HttpHeaders();
        canaryHeaders.set(TrafficConstants.HEADER_TRAFFIC_TAG, "v2");
        extractOrderFulfillment(restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/dispatch?orderNo=ORD-ACTUATOR-001",
                HttpMethod.GET,
                new HttpEntity<>(canaryHeaders),
                ORDER_VO_TYPE_REF
        ));

        // 再次查看 metrics 快照
        ResponseEntity<Map<String, Object>> metricsOverview = restTemplate.exchange(
                "http://localhost:" + port + "/actuator/traffic",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> metricsMap = (Map<String, Object>) metricsOverview.getBody().get("metrics");
        assertThat(Long.parseLong(metricsMap.get("totalRequests").toString())).isGreaterThanOrEqualTo(1L);

        // 断言测试线程上下文零污染
        assertThat(TrafficContextHolder.getTag()).isNull();
    }

    /**
     * 离线 Mock 与沙箱拓扑配置 (优先缺省守卫，仅非 local Profile 激活)
     */
    @TestConfiguration
    @org.springframework.context.annotation.Profile("!local")
    static class OfflineMockConfig {

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
        public DynamicTestDiscoveryClient dynamicTestDiscoveryClient() {
            return new DynamicTestDiscoveryClient();
        }

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
        public NacosTemplate mockNacosTemplate() {
            NacosTemplate mock = mock(NacosTemplate.class);
            when(mock.isConfigAvailable()).thenReturn(true);
            when(mock.findConfig(anyString(), anyString())).thenReturn(Optional.empty());
            return mock;
        }
    }

    /**
     * 可动态注册与下线实例的内存服务发现客户端
     */
    public static class DynamicTestDiscoveryClient implements DiscoveryClient {

        private final Map<String, List<ServiceInstance>> instancesMap = new ConcurrentHashMap<>();

        public void setInstances(String serviceId, List<ServiceInstance> instances) {
            instancesMap.put(serviceId, new CopyOnWriteArrayList<>(instances));
        }

        public void removeInstance(String serviceId, String instanceId) {
            List<ServiceInstance> list = instancesMap.get(serviceId);
            if (list != null) {
                list.removeIf(inst -> inst.getInstanceId().equals(instanceId));
            }
        }

        @Override
        public String description() {
            return "DynamicTestDiscoveryClient";
        }

        @Override
        public List<ServiceInstance> getInstances(String serviceId) {
            List<ServiceInstance> list = instancesMap.get(serviceId);
            return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
        }

        @Override
        public List<String> getServices() {
            return new ArrayList<>(instancesMap.keySet());
        }
    }
}
