package io.github.atengk.cloud.gateway.route;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.gateway.properties.AtengGatewayDynamicRouteProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Nacos 动态路由仓库与快照守卫容错机制单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("Nacos 动态路由仓库与快照守卫单元测试")
class NacosRouteDefinitionRepositoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("正确解析标准 JSON 数组动态路由 (含常见断言与过滤器)")
    void testParseValidJsonArrayRoutes() throws Exception {
        AtengGatewayDynamicRouteProperties properties = new AtengGatewayDynamicRouteProperties();
        NacosRouteDefinitionRepository repository = new NacosRouteDefinitionRepository(
                null, properties, objectMapper, null);

        String json = """
                [
                  {
                    "id": "order-service",
                    "uri": "lb://order-service",
                    "order": 1,
                    "predicates": [
                      {
                        "name": "Path",
                        "args": {
                          "_genkey_0": "/api/order/**"
                        }
                      },
                      {
                        "name": "Method",
                        "args": {
                          "_genkey_0": "GET,POST"
                        }
                      }
                    ],
                    "filters": [
                      {
                        "name": "StripPrefix",
                        "args": {
                          "_genkey_0": "1"
                        }
                      },
                      {
                        "name": "PrefixPath",
                        "args": {
                          "_genkey_0": "/v1"
                        }
                      }
                    ],
                    "metadata": {
                      "traffic": "canary"
                    }
                  }
                ]
                """;

        List<RouteDefinition> routes = repository.parseRouteDefinitions(json);
        assertEquals(1, routes.size());
        RouteDefinition route = routes.get(0);
        assertEquals("order-service", route.getId());
        assertEquals(URI.create("lb://order-service"), route.getUri());
        assertEquals(1, route.getOrder());
        assertEquals(2, route.getPredicates().size());
        assertEquals("Path", route.getPredicates().get(0).getName());
        assertEquals("Method", route.getPredicates().get(1).getName());
        assertEquals(2, route.getFilters().size());
        assertEquals("StripPrefix", route.getFilters().get(0).getName());
        assertEquals("PrefixPath", route.getFilters().get(1).getName());
        assertEquals("canary", route.getMetadata().get("traffic"));
    }

    @Test
    @DisplayName("支持单 JSON 对象格式平滑解析为单元素列表")
    void testParseSingleJsonObject() throws Exception {
        AtengGatewayDynamicRouteProperties properties = new AtengGatewayDynamicRouteProperties();
        NacosRouteDefinitionRepository repository = new NacosRouteDefinitionRepository(
                null, properties, objectMapper, null);

        String json = """
                {
                  "id": "single-route",
                  "uri": "http://localhost:8080",
                  "order": 10,
                  "predicates": [
                    {
                      "name": "Path",
                      "args": {
                        "_genkey_0": "/single/**"
                      }
                    }
                  ]
                }
                """;

        List<RouteDefinition> routes = repository.parseRouteDefinitions(json);
        assertEquals(1, routes.size());
        assertEquals("single-route", routes.get(0).getId());
        assertEquals(URI.create("http://localhost:8080"), routes.get(0).getUri());
        assertEquals(10, routes.get(0).getOrder());
    }

    @Test
    @DisplayName("快照守卫容错：当推送畸形破损 JSON 时坚决保留上一份有效快照")
    void testSnapshotGuardOnMalformedJson() {
        List<Object> publishedEvents = new ArrayList<>();
        ApplicationEventPublisher publisher = publishedEvents::add;
        AtengGatewayDynamicRouteProperties properties = new AtengGatewayDynamicRouteProperties();
        NacosRouteDefinitionRepository repository = new NacosRouteDefinitionRepository(
                null, properties, objectMapper, publisher);

        // 1. 推送首份有效路由
        String validJson = """
                [
                  {
                    "id": "good-route",
                    "uri": "http://127.0.0.1:8080",
                    "predicates": [
                      {
                        "name": "Path",
                        "args": {
                          "_genkey_0": "/good/**"
                        }
                      }
                    ]
                  }
                ]
                """;
        boolean firstSuccess = repository.updateRoutes(validJson);
        assertTrue(firstSuccess);
        assertEquals(1, repository.getRoutesSnapshot().size());
        assertTrue(repository.getRoutesSnapshot().containsKey("good-route"));
        assertEquals(1, publishedEvents.size());
        assertTrue(publishedEvents.get(0) instanceof RefreshRoutesEvent);

        // 2. 模拟运维推送畸变损坏的 JSON 报文
        String brokenJson = "[ { \"id\": \"broken-route\", \"uri\": ";
        boolean secondSuccess = repository.updateRoutes(brokenJson);
        assertFalse(secondSuccess);

        // 3. 强断言：快照守卫拦截错误，有效快照依然完整维持
        assertEquals(1, repository.getRoutesSnapshot().size());
        assertTrue(repository.getRoutesSnapshot().containsKey("good-route"));
        // 事件发布次数未增加 (防止错误配置刷新)
        assertEquals(1, publishedEvents.size());
    }

    @Test
    @DisplayName("快照守卫容错：当推送空串或纯空白时保留现有快照不清空")
    void testSnapshotGuardOnBlankOrEmptyJson() {
        AtengGatewayDynamicRouteProperties properties = new AtengGatewayDynamicRouteProperties();
        NacosRouteDefinitionRepository repository = new NacosRouteDefinitionRepository(
                null, properties, objectMapper, null);

        String validJson = """
                [
                  {
                    "id": "init-route",
                    "uri": "http://127.0.0.1:8080"
                  }
                ]
                """;
        assertTrue(repository.updateRoutes(validJson));
        assertEquals(1, repository.getRoutesSnapshot().size());

        // 推送空白字符
        assertFalse(repository.updateRoutes("   "));
        assertEquals(1, repository.getRoutesSnapshot().size());
        assertTrue(repository.getRoutesSnapshot().containsKey("init-route"));

        // 推送 null
        assertFalse(repository.updateRoutes(null));
        assertEquals(1, repository.getRoutesSnapshot().size());
    }

    @Test
    @DisplayName("语义防卫：拒绝缺失 ID、缺失 URI 或包含非法 scheme 的路由定义")
    void testValidationRejectsInvalidRoutes() {
        AtengGatewayDynamicRouteProperties properties = new AtengGatewayDynamicRouteProperties();
        NacosRouteDefinitionRepository repository = new NacosRouteDefinitionRepository(
                null, properties, objectMapper, null);

        // 缺少 ID
        String missingId = """
                [
                  {
                    "uri": "http://localhost:8080"
                  }
                ]
                """;
        assertFalse(repository.updateRoutes(missingId));

        // 缺少 URI
        String missingUri = """
                [
                  {
                    "id": "no-uri"
                  }
                ]
                """;
        assertFalse(repository.updateRoutes(missingUri));

        // 包含无 scheme 的非法 URI
        String badScheme = """
                [
                  {
                    "id": "bad-scheme",
                    "uri": "illegal-uri-without-scheme"
                  }
                ]
                """;
        assertFalse(repository.updateRoutes(badScheme));

        // 存在重复的 Route ID
        String duplicateId = """
                [
                  {
                    "id": "dup-route",
                    "uri": "http://host-a"
                  },
                  {
                    "id": "dup-route",
                    "uri": "http://host-b"
                  }
                ]
                """;
        assertFalse(repository.updateRoutes(duplicateId));
    }

    @Test
    @DisplayName("语义防卫：拒绝包含无效断言或过滤器名称的路由定义")
    void testValidationRejectsInvalidPredicatesOrFilters() {
        AtengGatewayDynamicRouteProperties properties = new AtengGatewayDynamicRouteProperties();
        NacosRouteDefinitionRepository repository = new NacosRouteDefinitionRepository(
                null, properties, objectMapper, null);

        // 断言名称为空
        String invalidPredicate = """
                [
                  {
                    "id": "invalid-pred",
                    "uri": "http://localhost:8080",
                    "predicates": [
                      {
                        "name": ""
                      }
                    ]
                  }
                ]
                """;
        assertFalse(repository.updateRoutes(invalidPredicate));

        // 过滤器名称为空
        String invalidFilter = """
                [
                  {
                    "id": "invalid-filter",
                    "uri": "http://localhost:8080",
                    "filters": [
                      {
                        "name": "   "
                      }
                    ]
                  }
                ]
                """;
        assertFalse(repository.updateRoutes(invalidFilter));
    }

    @Test
    @DisplayName("支持原生 RouteDefinitionRepository 的 save 与 delete 操作并响应式流转")
    void testSaveAndDeleteMono() {
        List<Object> publishedEvents = new ArrayList<>();
        ApplicationEventPublisher publisher = publishedEvents::add;
        AtengGatewayDynamicRouteProperties properties = new AtengGatewayDynamicRouteProperties();
        NacosRouteDefinitionRepository repository = new NacosRouteDefinitionRepository(
                null, properties, objectMapper, publisher);

        RouteDefinition route = new RouteDefinition();
        route.setId("dynamic-save-route");
        route.setUri(URI.create("http://127.0.0.1:9090"));

        // 1. save 操作
        repository.save(Mono.just(route)).block();
        assertEquals(1, repository.getRoutesSnapshot().size());
        assertEquals(1, publishedEvents.size());

        // 1.1 校验非法路由 (无 URI 或无协议头) 被拦截抛出异常
        RouteDefinition invalidRoute = new RouteDefinition();
        invalidRoute.setId("invalid-route");
        assertThrows(IllegalArgumentException.class, () -> repository.save(Mono.just(invalidRoute)).block());

        // 2. getRouteDefinitions 查询
        List<RouteDefinition> list = repository.getRouteDefinitions().collectList().block();
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("dynamic-save-route", list.get(0).getId());

        // 3. delete 存在路由
        repository.delete(Mono.just("dynamic-save-route")).block();
        assertEquals(0, repository.getRoutesSnapshot().size());
        assertEquals(2, publishedEvents.size());

        // 4. delete 不存在的路由返回 NotFoundException
        assertThrows(NotFoundException.class, () -> repository.delete(Mono.just("not-exist-route")).block());
    }

    @Test
    @DisplayName("Nacos 监听器注册、初始读取与 receiveConfigInfo 动态变更完整链路测试")
    void testNacosListenerIntegration() throws Exception {
        ConfigService mockConfigService = mock(ConfigService.class);
        NacosConfigManager mockConfigManager = mock(NacosConfigManager.class);
        when(mockConfigManager.getConfigService()).thenReturn(mockConfigService);

        @SuppressWarnings("unchecked")
        ObjectProvider<NacosConfigManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mockConfigManager);

        List<Object> publishedEvents = new ArrayList<>();
        ApplicationEventPublisher publisher = publishedEvents::add;
        AtengGatewayDynamicRouteProperties properties = new AtengGatewayDynamicRouteProperties();
        properties.setDataId("test-routes.json");
        properties.setGroup("TEST_GROUP");

        // 模拟初始配置
        String initialJson = """
                [
                  {
                    "id": "init-route",
                    "uri": "lb://init-service"
                  }
                ]
                """;
        when(mockConfigService.getConfig(eq("test-routes.json"), eq("TEST_GROUP"), anyLong()))
                .thenReturn(initialJson);

        NacosRouteDefinitionRepository repository = new NacosRouteDefinitionRepository(
                provider, properties, objectMapper, publisher);

        // 1. 生命周期初始化
        repository.afterPropertiesSet();

        // 验证初始路由被载入快照
        assertEquals(1, repository.getRoutesSnapshot().size());
        assertTrue(repository.getRoutesSnapshot().containsKey("init-route"));

        // 验证已向 ConfigService 注册 Listener
        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
        verify(mockConfigService).addListener(eq("test-routes.json"), eq("TEST_GROUP"), listenerCaptor.capture());
        Listener registeredListener = listenerCaptor.getValue();
        assertNotNull(registeredListener);

        // 2. 模拟 Nacos 服务端推送新的配置变更
        String newJson = """
                [
                  {
                    "id": "updated-route",
                    "uri": "lb://updated-service"
                  }
                ]
                """;
        registeredListener.receiveConfigInfo(newJson);

        // 验证快照原子更新且触发了 RefreshRoutesEvent
        Map<String, RouteDefinition> snapshot = repository.getRoutesSnapshot();
        assertEquals(1, snapshot.size());
        assertTrue(snapshot.containsKey("updated-route"));
        assertFalse(snapshot.containsKey("init-route"));
        assertEquals(1, publishedEvents.size());
        assertTrue(publishedEvents.get(0) instanceof RefreshRoutesEvent);

        // 3. 容器销毁确定性清理
        repository.destroy();
        verify(mockConfigService).removeListener(eq("test-routes.json"), eq("TEST_GROUP"), eq(registeredListener));
    }

    @Test
    @DisplayName("环境缺失豁免：NacosConfigManager 未注入时平滑退化，不抛异常")
    void testGracefulFallbackWhenNacosMissing() {
        AtengGatewayDynamicRouteProperties properties = new AtengGatewayDynamicRouteProperties();
        NacosRouteDefinitionRepository repository = new NacosRouteDefinitionRepository(
                null, properties, objectMapper, null);

        // 不注入 NacosConfigManager 执行生命周期初始化
        assertDoesNotThrow(repository::afterPropertiesSet);
        assertEquals(0, repository.getRoutesSnapshot().size());

        // 本地依然支持编程式 updateRoutes
        String json = """
                [
                  {
                    "id": "local-route",
                    "uri": "http://localhost:8080"
                  }
                ]
                """;
        assertTrue(repository.updateRoutes(json));
        assertEquals(1, repository.getRoutesSnapshot().size());

        assertDoesNotThrow(repository::destroy);
    }
}
