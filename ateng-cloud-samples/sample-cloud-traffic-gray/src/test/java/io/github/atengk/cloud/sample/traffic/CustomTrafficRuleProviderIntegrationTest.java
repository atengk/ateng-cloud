package io.github.atengk.cloud.sample.traffic;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.traffic.model.OrderFulfillmentVO;
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
        // 配置测试实例拓扑 (电商履约服务拓扑)
        discoveryClient.setInstances("order-service", List.of(
                new DefaultServiceInstance("inst-order-base", "order-service", "127.0.0.1", port, false, Map.of("tag", "default")),
                new DefaultServiceInstance("inst-order-gray", "order-service", "127.0.0.1", port, false, Map.of("tag", "v2"))
        ));
        discoveryClient.setInstances("inventory-service", List.of(
                new DefaultServiceInstance("inst-inv-base", "inventory-service", "127.0.0.1", port, false, Map.of("tag", "default"))
        ));
        discoveryClient.setInstances("logistics-service", List.of(
                new DefaultServiceInstance("inst-logistics-base", "logistics-service", "127.0.0.1", port, false, Map.of("tag", "default")),
                new DefaultServiceInstance("inst-logistics-gray", "logistics-service", "127.0.0.1", port, false, Map.of("tag", "v2"))
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Channel", "vip_wechat");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ParameterizedTypeReference<Result<OrderFulfillmentVO>> typeRef = new ParameterizedTypeReference<>() {};
        ResponseEntity<Result<OrderFulfillmentVO>> response = restTemplate.exchange(
                "http://localhost:" + port + "/sample/cloud/traffic/order/dispatch?orderNo=ORD-SPI-001",
                HttpMethod.GET,
                entity,
                typeRef
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Result<OrderFulfillmentVO> result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        OrderFulfillmentVO vo = result.getData();
        assertThat(vo).isNotNull();
        assertThat(vo.getTrafficTag()).isEqualTo("v2");
        assertThat(vo.getVisitedNodes()).contains(
                "OrderService[inst=inst-order-gray, tag=v2]",
                "LogisticsService[inst=inst-logistics-gray, tag=v2]"
        );
    }
}
