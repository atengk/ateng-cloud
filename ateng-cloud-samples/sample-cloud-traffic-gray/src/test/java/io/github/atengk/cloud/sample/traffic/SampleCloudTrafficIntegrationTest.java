package io.github.atengk.cloud.sample.traffic;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import io.github.atengk.cloud.sample.traffic.model.TrafficTraceReport;
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
import org.springframework.context.annotation.Profile;
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
 * <p>微服务调用拓扑：
 * Gateway ➔ Service A ➔ Service B ➔ Service C
 *
 * 验证核心能力：
 * 1. 静态 Header 与 Query 参数染色及全链路透传
 * 2. 东西向 LoadBalancer 精准匹配实例灰度标签
 * 3. 用户 ID / 租户 ID 规则匹配与灰度分流
 * 4. 一致性哈希百分比切流与单用户会话粘性
 * 5. 跨跳泳道穿透 (Lane Continuity): A(v2) ➔ B(fallback base) ➔ C(re-enter v2)
 * 6. 灰度节点全下线平滑降级 (Resilient Fallback 零 503 报警)
 * 7. 金融级严格隔离阻断 (Strict Lane 503 Fail-Fast 异常拦截)
 * 8. Nacos 动态规则秒级热更新切流
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

    private static final ParameterizedTypeReference<Result<TrafficTraceReport>> RESULT_TYPE_REF =
            new ParameterizedTypeReference<>() {};

    private TrafficTraceReport extractReport(ResponseEntity<Result<TrafficTraceReport>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Result<TrafficTraceReport> result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        TrafficTraceReport report = result.getData();
        assertThat(report).isNotNull();
        return report;
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

        // 初始化微服务沙箱拓扑
        // Service A: 同时拥有基线与 v2 灰度节点
        discoveryClient.setInstances("service-a", List.of(
                new DefaultServiceInstance("inst-a-base", "service-a", "127.0.0.1", port, false, Map.of("tag", "default")),
                new DefaultServiceInstance("inst-a-gray", "service-a", "127.0.0.1", port, false, Map.of("tag", "v2"))
        ));

        // Service B: 仅拥有基线节点 (模拟中间服务未发版)
        discoveryClient.setInstances("service-b", List.of(
                new DefaultServiceInstance("inst-b-base", "service-b", "127.0.0.1", port, false, Map.of("tag", "default"))
        ));

        // Service C: 同时拥有基线与 v2 灰度节点 (下游发版)
        discoveryClient.setInstances("service-c", List.of(
                new DefaultServiceInstance("inst-c-base", "service-c", "127.0.0.1", port, false, Map.of("tag", "default")),
                new DefaultServiceInstance("inst-c-gray", "service-c", "127.0.0.1", port, false, Map.of("tag", "v2"))
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
    @DisplayName("场景 1: 未染色普通请求全链路调度至基线实例")
    void testBaselineRouting() {
        ResponseEntity<Result<TrafficTraceReport>> response = restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                null,
                RESULT_TYPE_REF
        );

        TrafficTraceReport report = extractReport(response);
        assertThat(report.getTrafficTag()).isNull();

        // 验证全链路均路由到 default 基线节点
        assertThat(report.getVisitedNodes()).containsExactly(
                "Gateway[tag=none]",
                "ServiceA[inst=inst-a-base, tag=default]",
                "ServiceB[inst=inst-b-base, tag=default]",
                "ServiceC[inst=inst-c-base, tag=default]"
        );
    }

    @Test
    @DisplayName("场景 2: 静态 Header 染色与跨跳泳道穿透 (Lane Continuity)")
    void testHeaderStainingAndCrossHopLaneContinuity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TrafficConstants.HEADER_TRAFFIC_TAG, "v2");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Result<TrafficTraceReport>> response = restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                entity,
                RESULT_TYPE_REF
        );

        TrafficTraceReport report = extractReport(response);
        assertThat(report.getTrafficTag()).isEqualTo("v2");

        // 核心断言：
        // 1. Service A 命中 v2 灰度节点 (inst-a-gray)
        // 2. Service B 无 v2 节点，平滑降级至基线 (inst-b-base)
        // 3. Service C 泳道回升成功，重新命中 v2 灰度节点 (inst-c-gray)
        assertThat(report.getVisitedNodes()).containsExactly(
                "Gateway[tag=v2]",
                "ServiceA[inst=inst-a-gray, tag=v2]",
                "ServiceB[inst=inst-b-base, tag=default]",
                "ServiceC[inst=inst-c-gray, tag=v2]"
        );
    }

    @Test
    @DisplayName("场景 3: URL Query 参数 traffic_tag 染色透传")
    void testQueryParamStaining() {
        ResponseEntity<Result<TrafficTraceReport>> response = restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch?traffic_tag=v2",
                HttpMethod.GET,
                null,
                RESULT_TYPE_REF
        );

        TrafficTraceReport report = extractReport(response);
        assertThat(report.getTrafficTag()).isEqualTo("v2");

        assertThat(report.getVisitedNodes()).containsExactly(
                "Gateway[tag=v2]",
                "ServiceA[inst=inst-a-gray, tag=v2]",
                "ServiceB[inst=inst-b-base, tag=default]",
                "ServiceC[inst=inst-c-gray, tag=v2]"
        );
    }

    @Test
    @DisplayName("场景 4: 用户 ID 谓词匹配动态分流至灰度泳道")
    void testUserIdPredicateMatching() {
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
        ResponseEntity<Result<TrafficTraceReport>> vipResponse = restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                new HttpEntity<>(vipHeaders),
                RESULT_TYPE_REF
        );

        TrafficTraceReport vipReport = extractReport(vipResponse);
        assertThat(vipReport.getTrafficTag()).isEqualTo("v2");
        assertThat(vipReport.getVisitedNodes()).contains(
                "ServiceA[inst=inst-a-gray, tag=v2]",
                "ServiceC[inst=inst-c-gray, tag=v2]"
        );

        // 2. 普通用户发起请求
        HttpHeaders normalHeaders = new HttpHeaders();
        normalHeaders.set("X-User-Id", "normal_user_888");
        ResponseEntity<Result<TrafficTraceReport>> normalResponse = restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                new HttpEntity<>(normalHeaders),
                RESULT_TYPE_REF
        );

        TrafficTraceReport normalReport = extractReport(normalResponse);
        assertThat(normalReport.getTrafficTag()).isNull();
        assertThat(normalReport.getVisitedNodes()).contains(
                "ServiceA[inst=inst-a-base, tag=default]",
                "ServiceC[inst=inst-c-base, tag=default]"
        );
    }

    @Test
    @DisplayName("场景 5: 一致性哈希百分比切流与单用户会话粘性保障")
    void testConsistentHashCanaryAndSessionStickiness() {
        // 配置 50% 百分比放量，以 Header X-Client-Uid 为哈希源
        TrafficRule stickyRule = TrafficRule.builder()
                .id("rule-sticky-50")
                .targetTag("v2")
                .percentage(50)
                .hashKeySource(TrafficFieldSource.HEADER)
                .hashKeyName("X-Client-Uid")
                .build();

        if (nacosRuleListener != null) {
            nacosRuleListener.receiveAppConfig(JsonUtil.toJsonString(List.of(stickyRule)));
        }

        // 同一客户端 UID 多次请求
        String clientUid = "client_session_sticky_user_9999";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Client-Uid", clientUid);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String firstObservedTag = null;
        for (int i = 0; i < 5; i++) {
            ResponseEntity<Result<TrafficTraceReport>> response = restTemplate.exchange(
                    "http://localhost:" + port + "/gateway/dispatch",
                    HttpMethod.GET,
                    entity,
                    RESULT_TYPE_REF
            );
            TrafficTraceReport report = extractReport(response);

            if (firstObservedTag == null) {
                firstObservedTag = report.getTrafficTag() != null ? report.getTrafficTag() : "baseline";
            } else {
                String currentTag = report.getTrafficTag() != null ? report.getTrafficTag() : "baseline";
                // 严密断言：多次请求的染色结果必须 100% 绝对稳定一致，杜绝会话横跳！
                assertThat(currentTag).isEqualTo(firstObservedTag);
            }
        }
    }

    @Test
    @DisplayName("场景 6: 灰度节点全下线平滑降级 (Resilient Fallback 零 503 报警)")
    void testGrayNodeOfflineResilientFallback() {
        // 将 Service A 的灰度节点下线 (仅保留基线实例)
        discoveryClient.removeInstance("service-a", "inst-a-gray");

        HttpHeaders headers = new HttpHeaders();
        headers.set(TrafficConstants.HEADER_TRAFFIC_TAG, "v2");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Result<TrafficTraceReport>> response = restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                entity,
                RESULT_TYPE_REF
        );

        // 强断言：Service A 灰度实例全下线时，必须平滑回退至基线，返回 200 OK，绝不抛出 503 报警
        TrafficTraceReport report = extractReport(response);
        assertThat(report.getVisitedNodes()).contains(
                "ServiceA[inst=inst-a-base, tag=default]"
        );
    }

    @Test
    @DisplayName("场景 7: 金融级严格泳道强隔离阻断 (Strict Lane 503 Fail-Fast)")
    void testStrictLaneIsolationFailFast() {
        // 开启严格隔离模式
        trafficProperties.setStrictLane(true);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TrafficConstants.HEADER_TRAFFIC_TAG, "v3-not-exist");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
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
    @DisplayName("场景 8: Nacos 流量规则秒级热更新切流")
    void testNacosRuleHotReload() {
        if (nacosRuleListener == null) {
            return;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Hot-Channel", "wechat");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // 1. 未配置热更规则前，请求走基线
        ResponseEntity<Result<TrafficTraceReport>> resp1 = restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                entity,
                RESULT_TYPE_REF
        );
        TrafficTraceReport report1 = extractReport(resp1);
        assertThat(report1.getTrafficTag()).isNull();

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
        ResponseEntity<Result<TrafficTraceReport>> resp2 = restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                entity,
                RESULT_TYPE_REF
        );
        TrafficTraceReport report2 = extractReport(resp2);
        assertThat(report2.getTrafficTag()).isEqualTo("v2");
        assertThat(report2.getVisitedNodes()).contains(
                "ServiceA[inst=inst-a-gray, tag=v2]",
                "ServiceC[inst=inst-c-gray, tag=v2]"
        );

        // 4. 清除规则，秒级回退
        nacosRuleListener.receiveAppConfig("[]");
        ResponseEntity<Result<TrafficTraceReport>> resp3 = restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                entity,
                RESULT_TYPE_REF
        );
        TrafficTraceReport report3 = extractReport(resp3);
        assertThat(report3.getTrafficTag()).isNull();
    }

    @Test
    @DisplayName("场景 9: 声明式 @TrafficTag 注解自动绑定金丝雀泳道并触发跨跳穿透")
    void testDeclarativeTrafficTagAspectRouting() {
        ResponseEntity<Result<TrafficTraceReport>> response = restTemplate.exchange(
                "http://localhost:" + port + "/declarative/dispatch",
                HttpMethod.GET,
                null,
                RESULT_TYPE_REF
        );

        TrafficTraceReport report = extractReport(response);
        assertThat(report.getTrafficTag()).isEqualTo("v2");

        // 强断言：声明式绑定的 v2 标签成功引导全链路调度，并在出口还原
        assertThat(report.getVisitedNodes()).containsExactly(
                "DeclarativeEntry[tag=v2]",
                "ServiceA[inst=inst-a-gray, tag=v2]",
                "ServiceB[inst=inst-b-base, tag=default]",
                "ServiceC[inst=inst-c-gray, tag=v2]"
        );

        // 断言测试线程上下文零污染
        assertThat(TrafficContextHolder.getTag()).isNull();
    }

    @Test
    @DisplayName("场景 10: 多金丝雀分支 (v2、v3 与基线) percentageRange 互斥区间放量与会话粘性端到端集成测试")
    void testMultiCanaryPercentageRangeMutualExclusion() {
        // 1. 在拓扑中扩展注入 v3 灰度实例节点
        discoveryClient.setInstances("service-a", List.of(
                new DefaultServiceInstance("inst-a-base", "service-a", "127.0.0.1", port, false, Map.of("tag", "default")),
                new DefaultServiceInstance("inst-a-gray", "service-a", "127.0.0.1", port, false, Map.of("tag", "v2")),
                new DefaultServiceInstance("inst-a-v3", "service-a", "127.0.0.1", port, false, Map.of("tag", "v3"))
        ));
        discoveryClient.setInstances("service-c", List.of(
                new DefaultServiceInstance("inst-c-base", "service-c", "127.0.0.1", port, false, Map.of("tag", "default")),
                new DefaultServiceInstance("inst-c-gray", "service-c", "127.0.0.1", port, false, Map.of("tag", "v2")),
                new DefaultServiceInstance("inst-c-v3", "service-c", "127.0.0.1", port, false, Map.of("tag", "v3"))
        ));

        // 2. 配置两条互斥百分比区间规则：
        // 规则 1: v2 灰度分支命中 [0, 20)
        // 规则 2: v3 灰度分支命中 [20, 50)
        // 基线分支: 占据剩余 [50, 100)
        TrafficRule ruleV2 = TrafficRule.builder()
                .id("rule-canary-v2-range")
                .name("Canary V2 Range [0, 20)")
                .priority(1)
                .targetTag("v2")
                .percentageRange(0, 20)
                .hashKeySource(TrafficFieldSource.HEADER)
                .hashKeyName("X-Client-Uid")
                .build();

        TrafficRule ruleV3 = TrafficRule.builder()
                .id("rule-canary-v3-range")
                .name("Canary V3 Range [20, 50)")
                .priority(2)
                .targetTag("v3")
                .percentageRange(20, 50)
                .hashKeySource(TrafficFieldSource.HEADER)
                .hashKeyName("X-Client-Uid")
                .build();

        if (nacosRuleListener != null) {
            nacosRuleListener.receiveAppConfig(JsonUtil.toJsonString(List.of(ruleV2, ruleV3)));
        }

        StickyHashPartitioner partitioner = new StickyHashPartitioner();

        // 3. 寻找测试用户种子
        String userV2 = null;
        String userV3 = null;
        String userBase = null;

        for (int i = 0; i < 500; i++) {
            String uid = "test_user_bucket_" + i;
            if (userV2 == null && partitioner.isHitRange(uid, 0, 20)) {
                userV2 = uid;
            } else if (userV3 == null && partitioner.isHitRange(uid, 20, 50)) {
                userV3 = uid;
            } else if (userBase == null && partitioner.isHitRange(uid, 50, 100)) {
                userBase = uid;
            }
            if (userV2 != null && userV3 != null && userBase != null) {
                break;
            }
        }

        assertThat(userV2).isNotNull();
        assertThat(userV3).isNotNull();
        assertThat(userBase).isNotNull();

        // 4. 验证 userV2 命中 v2 泳道
        HttpHeaders headersV2 = new HttpHeaders();
        headersV2.set("X-Client-Uid", userV2);
        TrafficTraceReport reportV2 = extractReport(restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                new HttpEntity<>(headersV2),
                RESULT_TYPE_REF
        ));
        assertThat(reportV2.getTrafficTag()).isEqualTo("v2");
        assertThat(reportV2.getVisitedNodes()).contains(
                "ServiceA[inst=inst-a-gray, tag=v2]",
                "ServiceB[inst=inst-b-base, tag=default]",
                "ServiceC[inst=inst-c-gray, tag=v2]"
        );

        // 5. 验证 userV3 命中 v3 泳道
        HttpHeaders headersV3 = new HttpHeaders();
        headersV3.set("X-Client-Uid", userV3);
        TrafficTraceReport reportV3 = extractReport(restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                new HttpEntity<>(headersV3),
                RESULT_TYPE_REF
        ));
        assertThat(reportV3.getTrafficTag()).isEqualTo("v3");
        assertThat(reportV3.getVisitedNodes()).contains(
                "ServiceA[inst=inst-a-v3, tag=v3]",
                "ServiceB[inst=inst-b-base, tag=default]",
                "ServiceC[inst=inst-c-v3, tag=v3]"
        );

        // 6. 验证 userBase 命中基线泳道
        HttpHeaders headersBase = new HttpHeaders();
        headersBase.set("X-Client-Uid", userBase);
        TrafficTraceReport reportBase = extractReport(restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                new HttpEntity<>(headersBase),
                RESULT_TYPE_REF
        ));
        assertThat(reportBase.getTrafficTag()).isNull();
        assertThat(reportBase.getVisitedNodes()).contains(
                "ServiceA[inst=inst-a-base, tag=default]",
                "ServiceB[inst=inst-b-base, tag=default]",
                "ServiceC[inst=inst-c-base, tag=default]"
        );

        // 7. 验证会话粘性：同一客户端再次调用结果保持绝对稳定
        TrafficTraceReport reportV2Again = extractReport(restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                new HttpEntity<>(headersV2),
                RESULT_TYPE_REF
        ));
        assertThat(reportV2Again.getTrafficTag()).isEqualTo("v2");

        TrafficTraceReport reportV3Again = extractReport(restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                new HttpEntity<>(headersV3),
                RESULT_TYPE_REF
        ));
        assertThat(reportV3Again.getTrafficTag()).isEqualTo("v3");
    }

    @Test
    @DisplayName("场景 11: SUFFIX 与 CONTAINS 谓词规则动态匹配灰度泳道")
    void testSuffixAndContainsPredicateRouting() {
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
        TrafficTraceReport hitReport = extractReport(restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                new HttpEntity<>(hitHeaders),
                RESULT_TYPE_REF
        ));
        assertThat(hitReport.getTrafficTag()).isEqualTo("v2");
        assertThat(hitReport.getVisitedNodes()).contains(
                "ServiceA[inst=inst-a-gray, tag=v2]",
                "ServiceC[inst=inst-c-gray, tag=v2]"
        );

        // 2. 不满足 SUFFIX (域名后缀不符)
        HttpHeaders missHeaders = new HttpHeaders();
        missHeaders.set("X-Tenant-Domain", "app.corp.external");
        missHeaders.set("X-Channel-Code", "PRE_CANARY_ONLINE");
        TrafficTraceReport missReport = extractReport(restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                new HttpEntity<>(missHeaders),
                RESULT_TYPE_REF
        ));
        assertThat(missReport.getTrafficTag()).isNull();
    }

    @Test
    @DisplayName("场景 12: 应用层外部标签白名单防线 (STRIP 剥离清洗与 REJECT 4211 阻断)")
    void testAllowedTagsWhitelistDefense() {
        // 配置白名单仅允许 v2
        trafficProperties.setAllowedTags(Set.of("v2"));

        // 1. STRIP 模式：携带未授权标签 'forged-tag'，应被安全剥离降级至基线
        trafficProperties.setWhitelistAction(TrafficWhitelistAction.STRIP);
        HttpHeaders stripHeaders = new HttpHeaders();
        stripHeaders.set(TrafficConstants.HEADER_TRAFFIC_TAG, "forged-tag");
        TrafficTraceReport stripReport = extractReport(restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                new HttpEntity<>(stripHeaders),
                RESULT_TYPE_REF
        ));
        assertThat(stripReport.getTrafficTag()).isNull();
        assertThat(stripReport.getVisitedNodes()).contains(
                "ServiceA[inst=inst-a-base, tag=default]",
                "ServiceC[inst=inst-c-base, tag=default]"
        );

        // 2. REJECT 模式：携带未授权标签 'forged-tag'，必须直接 4211 阻断
        trafficProperties.setWhitelistAction(TrafficWhitelistAction.REJECT);
        HttpHeaders rejectHeaders = new HttpHeaders();
        rejectHeaders.set(TrafficConstants.HEADER_TRAFFIC_TAG, "forged-tag");
        ResponseEntity<String> rejectResponse = restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                new HttpEntity<>(rejectHeaders),
                String.class
        );
        assertThat(rejectResponse.getBody()).isNotNull();
        assertThat(rejectResponse.getBody()).contains("4211");

        // 3. 携带已授权标签 'v2'，不受阻断正常放行
        HttpHeaders allowedHeaders = new HttpHeaders();
        allowedHeaders.set(TrafficConstants.HEADER_TRAFFIC_TAG, "v2");
        TrafficTraceReport allowedReport = extractReport(restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                new HttpEntity<>(allowedHeaders),
                RESULT_TYPE_REF
        ));
        assertThat(allowedReport.getTrafficTag()).isEqualTo("v2");
    }

    @Test
    @DisplayName("场景 13: 验证 /actuator/traffic 端点全景只读查询与 POST /actuator/traffic/simulate 沙箱演练")
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
                "path", "/gateway/dispatch"
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
        extractReport(restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                new HttpEntity<>(canaryHeaders),
                RESULT_TYPE_REF
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
    }

    /**
     * 离线 Mock 与沙箱拓扑配置 (优先缺省守卫)
     */
    @TestConfiguration
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
