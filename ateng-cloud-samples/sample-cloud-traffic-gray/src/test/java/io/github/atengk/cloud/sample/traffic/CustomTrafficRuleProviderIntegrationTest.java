package io.github.atengk.cloud.sample.traffic;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.traffic.model.TrafficTraceReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自定义 TrafficRuleProvider SPI 扩展接管端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ateng.cloud.traffic.sample.custom-rule-provider-enabled=true"
})
@Import(SampleCloudTrafficIntegrationTest.OfflineMockConfig.class)
@DisplayName("自定义 TrafficRuleProvider SPI 扩展接管实测")
class CustomTrafficRuleProviderIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SampleCloudTrafficIntegrationTest.DynamicTestDiscoveryClient discoveryClient;

    @Test
    @DisplayName("验证自定义 SPI 注入的规则生效命中灰度")
    void testCustomTrafficRuleProviderTakesEffect() {
        // 配置测试实例拓扑
        discoveryClient.setInstances("service-a", List.of(
                new DefaultServiceInstance("inst-a-base", "service-a", "127.0.0.1", port, false, Map.of("tag", "default")),
                new DefaultServiceInstance("inst-a-gray", "service-a", "127.0.0.1", port, false, Map.of("tag", "v2"))
        ));
        discoveryClient.setInstances("service-b", List.of(
                new DefaultServiceInstance("inst-b-base", "service-b", "127.0.0.1", port, false, Map.of("tag", "default"))
        ));
        discoveryClient.setInstances("service-c", List.of(
                new DefaultServiceInstance("inst-c-base", "service-c", "127.0.0.1", port, false, Map.of("tag", "default")),
                new DefaultServiceInstance("inst-c-gray", "service-c", "127.0.0.1", port, false, Map.of("tag", "v2"))
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Channel", "vip_wechat");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ParameterizedTypeReference<Result<TrafficTraceReport>> typeRef = new ParameterizedTypeReference<>() {};
        ResponseEntity<Result<TrafficTraceReport>> response = restTemplate.exchange(
                "http://localhost:" + port + "/gateway/dispatch",
                HttpMethod.GET,
                entity,
                typeRef
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Result<TrafficTraceReport> result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        TrafficTraceReport report = result.getData();
        assertThat(report).isNotNull();
        assertThat(report.getTrafficTag()).isEqualTo("v2");
        assertThat(report.getVisitedNodes()).contains(
                "ServiceA[inst=inst-a-gray, tag=v2]",
                "ServiceC[inst=inst-c-gray, tag=v2]"
        );
    }
}
