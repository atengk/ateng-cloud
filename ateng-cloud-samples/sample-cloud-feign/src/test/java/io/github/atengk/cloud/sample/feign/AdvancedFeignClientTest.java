package io.github.atengk.cloud.sample.feign;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.feign.preheat.AtengFeignPreheatListener;
import io.github.atengk.cloud.sample.feign.client.AdvancedFeignClient;
import io.github.atengk.cloud.sample.feign.model.UserQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生产深水区高级特性端到端集成测试
 * 强断言：二进制流透传未损坏、Multipart 文件上传、GET 复杂 POJO 参数平铺、硬防爆拦截及预热正常
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public class AdvancedFeignClientTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        int port = TestSocketUtils.findAvailableTcpPort();
        registry.add("server.port", () -> port);
        registry.add("test.server.port", () -> port);
        // 配置载荷硬防爆上限为 50KB，用于防御测试
        registry.add("ateng.cloud.feign.payload.max-response-size", () -> "50KB");
    }

    @Autowired
    private AdvancedFeignClient advancedFeignClient;

    @Autowired(required = false)
    private AtengFeignPreheatListener preheatListener;

    @Autowired(required = false)
    private io.micrometer.observation.ObservationRegistry observationRegistry;

    @Autowired
    private org.springframework.cloud.openfeign.FeignClientFactory feignClientFactory;

    @Test
    @DisplayName("1. 强断言：二进制流下载 (byte[]) 纯净透传，无损且未触碰 JSON 解包")
    void testBinaryStreamDownload() {
        byte[] bytes = advancedFeignClient.download();
        assertNotNull(bytes, "下载的二进制字节数组不可为空");
        assertEquals(10, bytes.length, "字节长度必须精准匹配");
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, bytes, "二进制字节内容不可损坏");
    }

    @Test
    @DisplayName("2. 强断言：MultipartFile 文件上传与参数表单混合调用成功")
    void testMultipartFileUpload() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-document.txt",
                "text/plain",
                "hello feign form multipart content".getBytes(StandardCharsets.UTF_8)
        );

        String result = advancedFeignClient.upload(file, "file-desc-demo");
        assertNotNull(result);
        assertTrue(result.contains("filename=test-document.txt"), "文件名必须完整透传");
        assertTrue(result.contains("file-desc-demo"), "文本参数必须正常绑定");
        assertTrue(result.contains("hello feign form multipart content"), "文件内容必须准确送达服务端");
    }

    @Test
    @DisplayName("3. 强断言：SpringQueryMap 复杂 POJO 查询参数自动展开并平铺为 URL 参数")
    void testSpringQueryMapPojoBinding() {
        UserQuery query = new UserQuery("feign-query-keyword", 2, 50);
        UserQuery echoed = advancedFeignClient.query(query);

        assertNotNull(echoed, "反序列化回传的查询对象不可为空");
        assertEquals("feign-query-keyword", echoed.getKeyword());
        assertEquals(2, echoed.getPage());
        assertEquals(50, echoed.getSize());
    }

    @Test
    @DisplayName("4. 强断言：响应体超出 max-response-size (50KB) 时触发硬防爆阻断 (413 Payload Too Large)")
    void testPayloadBombDefense() {
        BusinessException ex = assertThrows(BusinessException.class, () -> advancedFeignClient.largePayload());
        assertEquals(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(), ex.getCode(),
                "响应超限必须精准转译为 PAYLOAD_TOO_LARGE 业务异常");
    }

    @Test
    @DisplayName("5. 强断言：容器启动期饥饿预热监听器已成功装配并运行且客户端预热就绪")
    void testPreheatListenerInitialized() {
        assertNotNull(preheatListener, "AtengFeignPreheatListener 必须受管初始化并完成预热");
        assertTrue(preheatListener.isPreheated(), "预热状态必须为 true");
        assertTrue(preheatListener.getPreheatedCount() > 0, "预热客户端数量必须大于 0");
    }

    @Test
    @DisplayName("6. 强断言：Micrometer Observation 统一监控度量基础设施正常装配")
    void testObservationRegistryConfigured() {
        assertNotNull(observationRegistry, "ObservationRegistry 必须受管初始化并就绪");
        feign.micrometer.MicrometerObservationCapability capability =
                feignClientFactory.getInstance("advanced-client", feign.micrometer.MicrometerObservationCapability.class);
        assertNotNull(capability, "Feign 客户端子容器中必须成功自动装配 MicrometerObservationCapability");
    }
}
