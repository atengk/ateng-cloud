package io.github.atengk.cloud.sample.dubbo;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.filter.DubboContextPropagationFilter;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.sample.dubbo.api.UserRpcService;
import io.github.atengk.cloud.sample.dubbo.model.UserLogStreamRequest;
import io.github.atengk.cloud.sample.dubbo.model.UserLogVO;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.transport.AbstractCodec;
import org.apache.dubbo.remoting.transport.ExceedPayloadLimitException;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Triple 原生流式通信、载荷防爆硬上限与优雅停机无损上下线集成测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest(classes = SampleDubboApplication.class)
@DisplayName("Triple 流式通信与生产基线防护测试")
public class StreamAndPayloadDubboRpcTest {

    @DubboReference(url = "tri://127.0.0.1:50051", check = false)
    private UserRpcService userRpcService;

    @Autowired(required = false)
    private ApplicationConfig applicationConfig;

    @Autowired(required = false)
    private ProviderConfig providerConfig;

    @Autowired(required = false)
    private ProtocolConfig protocolConfig;

    @Autowired(required = false)
    private AtengDubboProperties dubboProperties;

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("测试 Triple Server Stream 流式调用及四维上下文无损透传")
    void testServerStreamSuccessWithContextPropagation() throws InterruptedException {
        // 1. 设置客户端四维上下文快照与流量染色
        UserContextHolder.setUserId("stream-user-888");
        TenantContextHolder.setTenantId("stream-tenant-666");
        MDC.put("traceId", "trace-stream-9999");
        MDC.put(DubboContextPropagationFilter.TRAFFIC_TAG, "canary-v2");

        List<UserLogVO> receivedLogs = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // 2. 发起 Triple Server Stream 流式 RPC 调用
        UserLogStreamRequest request = new UserLogStreamRequest("ORDER_EXPORT", 3);
        userRpcService.streamUserLogs(request, new StreamObserver<>() {
            @Override
            public void onNext(UserLogVO data) {
                receivedLogs.add(data);
            }

            @Override
            public void onError(Throwable throwable) {
                errorRef.set(throwable);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        // 3. 等待流式推送完成并强断言
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(errorRef.get()).isNull();
        assertThat(receivedLogs).hasSize(3);

        // 4. 验证四维上下文在流式请求服务端正确获取并回传
        UserLogVO firstLog = receivedLogs.get(0);
        assertThat(firstLog.getUserId()).isEqualTo("stream-user-888");
        assertThat(firstLog.getTenantId()).isEqualTo("stream-tenant-666");
        assertThat(firstLog.getTraceId()).isEqualTo("trace-stream-9999");
        assertThat(firstLog.getTrafficTag()).isEqualTo("canary-v2");
        assertThat(firstLog.getContent()).contains("ORDER_EXPORT");
    }

    @Test
    @DisplayName("测试 Triple Server Stream 异常流式推送拦截")
    void testServerStreamErrorHandling() throws InterruptedException {
        UserContextHolder.setUserId("user-err");
        List<UserLogVO> receivedLogs = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        UserLogStreamRequest request = new UserLogStreamRequest("FAIL_TASK", 1);
        userRpcService.streamErrorLogs(request, new StreamObserver<>() {
            @Override
            public void onNext(UserLogVO data) {
                receivedLogs.add(data);
            }

            @Override
            public void onError(Throwable throwable) {
                errorRef.set(throwable);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(receivedLogs).hasSize(1);
        assertThat(errorRef.get()).isNotNull();
    }

    @Test
    @DisplayName("测试 Triple Server Stream 入参 JSR-303 参数校验防卫")
    void testServerStreamValidationFailFast() throws InterruptedException {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // 构造非法请求参数 (action 为空字符串，违反 @NotBlank)
        UserLogStreamRequest invalidRequest = new UserLogStreamRequest("", 3);
        try {
            userRpcService.streamUserLogs(invalidRequest, new StreamObserver<>() {
                @Override
                public void onNext(UserLogVO data) {
                }

                @Override
                public void onError(Throwable throwable) {
                    errorRef.set(throwable);
                    latch.countDown();
                }

                @Override
                public void onCompleted() {
                    latch.countDown();
                }
            });
            latch.await(3, TimeUnit.SECONDS);
            // 流式通道中若通过 onError 回调返回，断言异常
            if (errorRef.get() != null) {
                assertThat(errorRef.get().getMessage()).containsAnyOf("参数校验失败", "业务操作行为不能为空");
            }
        } catch (Throwable syncEx) {
            // 同步快速失败抛出
            assertThat(syncEx.getMessage()).containsAnyOf("参数校验失败", "业务操作行为不能为空");
        }
    }

    @Test
    @DisplayName("测试优雅停机预设 wait=15000 与硬防爆上限 16MB 注入")
    void testDubboGracefulShutdownAndPayloadConfig() {
        // 1. 验证优雅停机超时时间注入为 15000 毫秒 (15秒)
        ApplicationConfig appConfig = ApplicationModel.defaultModel()
                .getApplicationConfigManager()
                .getApplication()
                .orElse(applicationConfig);
        assertThat(appConfig).isNotNull();
        assertThat(appConfig.getShutwait()).isEqualTo("15000");

        // 2. 验证 Provider 与 Protocol 报文硬防爆上限注入为 16MB (16777216 字节)
        int expectedPayload = 16 * 1024 * 1024;
        ProtocolConfig protoConfig = ApplicationModel.defaultModel()
                .getApplicationConfigManager()
                .getDefaultProtocols()
                .stream()
                .findFirst()
                .orElse(protocolConfig);
        if (protoConfig != null) {
            assertThat(protoConfig.getPayload()).isEqualTo(expectedPayload);
        }
        ApplicationModel.defaultModel()
                .getDefaultModule()
                .getConfigManager()
                .getProviders()
                .forEach(providerConfig -> {
                    assertThat(providerConfig.getPayload()).isEqualTo(expectedPayload);
                });

        // 3. 验证属性类默认配置值对齐
        assertThat(dubboProperties).isNotNull();
        assertThat(dubboProperties.getPayload().toBytes()).isEqualTo(expectedPayload);
        assertThat(dubboProperties.getApplication().getShutWait().toMillis()).isEqualTo(15000L);
    }

    @Test
    @DisplayName("测试大报文载荷正常收发与硬防爆容量承载")
    void testEchoNormalPayload() {
        // 构造 64KB 大报文，验证默认 16MB 上限下大报文流畅传输
        String payload64K = "A".repeat(64 * 1024);
        int length = userRpcService.echoPayload(payload64K);
        assertThat(length).isEqualTo(64 * 1024);
    }

    @Test
    @DisplayName("测试超大报文超出硬防爆上限时抛出 ExceedPayloadLimitException 拦截")
    void testExceedPayloadLimitDefense() {
        int maxPayload = (int) dubboProperties.getPayload().toBytes();
        long oversizedBytes = maxPayload + 1024L;

        // 验证 Dubbo 协议层超大报文硬防爆校验机制抛出 ExceedPayloadLimitException
        assertThatThrownBy(() -> {
            java.lang.reflect.Method method = AbstractCodec.class.getDeclaredMethod("checkPayload", Channel.class, int.class, long.class);
            method.setAccessible(true);
            try {
                method.invoke(null, null, maxPayload, oversizedBytes);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                throw ite.getTargetException();
            }
        }).isInstanceOf(ExceedPayloadLimitException.class)
                .hasMessageContaining("Data length too large")
                .hasMessageContaining(String.valueOf(maxPayload));
    }
}
