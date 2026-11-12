package io.github.atengk.cloud.feign.support;

import cn.dev33.satoken.same.SaSameUtil;
import io.github.atengk.cloud.feign.constant.FeignHeaderConstants;
import io.github.atengk.cloud.feign.spi.SameTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feign 零依赖反射桥接类隔离降级与 SameTokenProvider 契约单元测试
 *
 * @author Ateng
 * @since 2026-11-12
 */
@DisplayName("Feign 零依赖反射桥接与 SameTokenProvider 单元测试")
class FeignBridgeAndSameTokenSupportTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("1. 验证 SameTokenProvider 默认常量时间校验覆盖空值、空白、首尾空格修剪与不匹配拒绝")
    void testSameTokenProviderDefaultValidation() {
        SameTokenProvider activeProvider = () -> "  secret-internal-token  ";
        assertThat(activeProvider.isValidSameToken("secret-internal-token")).isTrue();
        assertThat(activeProvider.isValidSameToken("  secret-internal-token  ")).isTrue();
        assertThat(activeProvider.isValidSameToken("wrong-token")).isFalse();
        assertThat(activeProvider.isValidSameToken(null)).isFalse();
        assertThat(activeProvider.isValidSameToken("   ")).isFalse();

        SameTokenProvider emptyExpectedProvider = () -> "   ";
        assertThat(emptyExpectedProvider.isValidSameToken("secret-internal-token")).isFalse();

        SameTokenProvider nullExpectedProvider = () -> null;
        assertThat(nullExpectedProvider.isValidSameToken("secret-internal-token")).isFalse();
    }

    @Test
    @DisplayName("2. 验证 SaTokenSameTokenProvider 委托 SaSameUtil 校验并安全过滤空白入参")
    void testSaTokenSameTokenProviderValidation() {
        SaTokenSameTokenProvider provider = new SaTokenSameTokenProvider();
        try (MockedStatic<SaSameUtil> mocked = Mockito.mockStatic(SaSameUtil.class)) {
            mocked.when(SaSameUtil::getToken).thenReturn("sa-token-999");
            mocked.when(() -> SaSameUtil.isValid("sa-token-999")).thenReturn(true);
            mocked.when(() -> SaSameUtil.isValid("forged")).thenReturn(false);

            assertThat(provider.getSameToken()).isEqualTo("sa-token-999");
            assertThat(provider.isValidSameToken("  sa-token-999  ")).isTrue();
            assertThat(provider.isValidSameToken("forged")).isFalse();
            assertThat(provider.isValidSameToken(null)).isFalse();
            assertThat(provider.isValidSameToken("   ")).isFalse();
            mocked.verify(() -> SaSameUtil.isValid(Mockito.any()), Mockito.times(2));
        }
    }

    @Test
    @DisplayName("3. 验证类路径缺失 TrafficContextHolder 与 Seata RootContext 时反射桥接零异常静默降级至 MDC")
    void testReflectionBridgesDegradeToMdcWhenOptionalDepsAbsent() throws Exception {
        URL feignCodeSource = FeignTrafficPropagationSupport.class.getProtectionDomain().getCodeSource().getLocation();
        try (MissingOptionalDepsClassLoader isolatedLoader = new MissingOptionalDepsClassLoader(
                feignCodeSource, FeignBridgeAndSameTokenSupportTest.class.getClassLoader())) {

            // 1. Traffic 缺类隔离验证
            Class<?> trafficSupportClass = Class.forName(FeignTrafficPropagationSupport.class.getName(), true, isolatedLoader);
            Method isTrafficAvailable = trafficSupportClass.getMethod("isTrafficAvailable");
            assertThat((Boolean) isTrafficAvailable.invoke(null)).isFalse();

            Method restoreMethod = trafficSupportClass.getMethod("restore", String.class, String.class, String.class);
            Method getSnapshotMethod = trafficSupportClass.getMethod("getSnapshot");
            Method clearMethod = trafficSupportClass.getMethod("clear");

            restoreMethod.invoke(null, "canary-isolated", "lane-isolated", "rule-isolated");
            assertThat(MDC.get(FeignHeaderConstants.TRAFFIC_TAG)).isEqualTo("canary-isolated");
            assertThat(MDC.get(FeignHeaderConstants.TRAFFIC_LANE)).isEqualTo("lane-isolated");
            assertThat(MDC.get(FeignHeaderConstants.TRAFFIC_RULE)).isEqualTo("rule-isolated");

            Object snapshot = getSnapshotMethod.invoke(null);
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.getClass().getMethod("tag").invoke(snapshot)).isEqualTo("canary-isolated");
            assertThat(snapshot.getClass().getMethod("laneName").invoke(snapshot)).isEqualTo("lane-isolated");
            assertThat(snapshot.getClass().getMethod("matchedRule").invoke(snapshot)).isEqualTo("rule-isolated");

            clearMethod.invoke(null);
            assertThat(MDC.get(FeignHeaderConstants.TRAFFIC_TAG)).isNull();

            // 2. Seata 缺类隔离验证
            Class<?> seataSupportClass = Class.forName(FeignSeataPropagationSupport.class.getName(), true, isolatedLoader);
            Method isSeataAvailable = seataSupportClass.getMethod("isSeataAvailable");
            assertThat((Boolean) isSeataAvailable.invoke(null)).isFalse();

            Method getXidMethod = seataSupportClass.getMethod("getXID");
            Method bindMethod = seataSupportClass.getMethod("bind", String.class);
            Method unbindMethod = seataSupportClass.getMethod("unbind",
                    Class.forName(FeignSeataPropagationSupport.BindSnapshot.class.getName(), true, isolatedLoader));

            MDC.put(FeignHeaderConstants.MDC_XID, "mdc-outer-xid");
            Object bindSnapshot = bindMethod.invoke(null, "mdc-inner-xid");
            assertThat(MDC.get(FeignHeaderConstants.MDC_XID)).isEqualTo("mdc-inner-xid");
            assertThat(getXidMethod.invoke(null)).isEqualTo("mdc-inner-xid");

            unbindMethod.invoke(null, bindSnapshot);
            assertThat(MDC.get(FeignHeaderConstants.MDC_XID)).isEqualTo("mdc-outer-xid");
        }
    }

    /**
     * 模拟运行期未引入 ateng-cloud-traffic 与 seata-core 的隔离类加载器
     */
    private static final class MissingOptionalDepsClassLoader extends URLClassLoader {

        private MissingOptionalDepsClassLoader(URL feignLocation, ClassLoader parent) {
            super(new URL[]{feignLocation}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("io.github.atengk.cloud.traffic.")
                    || name.startsWith("org.apache.seata.")
                    || name.startsWith("io.seata.")) {
                throw new ClassNotFoundException("Simulated missing optional dependency: " + name);
            }
            if (name.startsWith("io.github.atengk.cloud.feign.")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }
}
