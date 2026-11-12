package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.cloud.dubbo.overload.DubboOverloadFilter;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dubbo SPI 过滤器责任链激活顺序契约测试
 *
 * @author Ateng
 * @since 2026-11-12
 */
class DubboFilterChainOrderTest {

    @Test
    @DisplayName("Provider 端入站过滤器链顺序: Context -> SameToken -> Overload -> Validation")
    void testProviderFilterChainOrder() {
        List<Class<?>> activeFilterClasses = getActiveFilterClasses(CommonConstants.PROVIDER);

        assertThat(activeFilterClasses).containsSubsequence(
                DubboContextPropagationFilter.class,
                DubboSameTokenFilter.class,
                DubboOverloadFilter.class,
                DubboValidationFilter.class
        );
    }

    @Test
    @DisplayName("Consumer 端出站过滤器链顺序: Context -> SameToken -> Overload -> TrafficTag -> ContractAssert")
    void testConsumerFilterChainOrder() {
        List<Class<?>> activeFilterClasses = getActiveFilterClasses(CommonConstants.CONSUMER);

        assertThat(activeFilterClasses).containsSubsequence(
                DubboContextPropagationFilter.class,
                DubboSameTokenFilter.class,
                DubboOverloadFilter.class,
                DubboTrafficTagFilter.class,
                DubboContractAssertFilter.class
        );
    }

    private static List<Class<?>> getActiveFilterClasses(String group) {
        ExtensionLoader<Filter> loader = ApplicationModel.defaultModel()
                .getDefaultModule()
                .getExtensionLoader(Filter.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.DemoService?side=" + group);
        return loader.getActivateExtension(url, new String[0], group)
                .stream()
                .<Class<?>>map(Filter::getClass)
                .toList();
    }
}
