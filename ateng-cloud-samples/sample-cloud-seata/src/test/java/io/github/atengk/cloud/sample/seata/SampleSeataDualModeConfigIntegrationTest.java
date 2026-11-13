package io.github.atengk.cloud.sample.seata;

import io.github.atengk.cloud.seata.properties.SeataProperties;
import io.github.atengk.cloud.seata.tcc.JdbcTccFenceRepository;
import io.github.atengk.cloud.seata.tcc.TccFenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 样例工程配置键名校准与双模联调守卫集成测试
 * <p>
 * 1. 验证 {@code application.yml} 中 {@code ateng.cloud.seata.tx.default-timeout} 等配置正确绑定至 {@link SeataProperties}；
 * 2. 验证配置物理数据源后，{@link TccFenceRepository} 稳定装配为 {@link JdbcTccFenceRepository}；
 * 3. 验证离线测试配置标配 {@link Profile @Profile("!local")}，激活 {@code local} 时自动卸载，让位真实集群配置。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-13
 */
@SpringBootTest(classes = SampleSeataApplication.class)
@Import(SampleSeataDualModeConfigIntegrationTest.OfflineStubConfig.class)
@DisplayName("Seata 配置属性绑定与双模联调 @Profile(\"!local\") 守卫测试")
class SampleSeataDualModeConfigIntegrationTest {

    @Autowired
    private SeataProperties seataProperties;

    @Autowired
    private TccFenceRepository tccFenceRepository;

    @Autowired(required = false)
    private Supplier<String> offlineStubXidSupplier;

    @Autowired
    private Environment environment;

    /**
     * 离线模式测试桩配置：仅在未激活 {@code local} Profile 时提供模拟 XID 生成器
     */
    @TestConfiguration
    @Profile("!local")
    static class OfflineStubConfig {

        @Bean
        public Supplier<String> offlineStubXidSupplier() {
            return () -> "127.0.0.1:8091:offline-stub-xid";
        }
    }

    @Test
    @DisplayName("校准后的 ateng.cloud.seata.tx.default-timeout 应正确绑定且 TCC 仓储为 JDBC 实现")
    void shouldBindCalibratedSeataPropertiesAndJdbcRepository() {
        assertThat(seataProperties.isEnabled()).isTrue();
        assertThat(seataProperties.getTx().getDefaultTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(tccFenceRepository).isInstanceOf(JdbcTccFenceRepository.class);
    }

    @Test
    @DisplayName("离线测试配置应标注 @Profile(\"!local\") 并在激活 local 时自动卸载")
    void shouldLoadOfflineStubOnlyWhenLocalProfileInactive() {
        if (!environment.acceptsProfiles(Profiles.of("local"))) {
            assertThat(offlineStubXidSupplier).isNotNull();
            assertThat(offlineStubXidSupplier.get()).startsWith("127.0.0.1:8091:");
            assertThat(environment.getProperty("seata.enabled", Boolean.class)).isFalse();
        }

        try (AnnotationConfigApplicationContext localContext = new AnnotationConfigApplicationContext()) {
            localContext.getEnvironment().setActiveProfiles("local");
            localContext.register(OfflineStubConfig.class);
            localContext.refresh();
            assertThat(localContext.getBeansOfType(Supplier.class)).isEmpty();
        }
    }
}
