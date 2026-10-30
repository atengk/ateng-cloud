package io.github.atengk.cloud.feign.decoder;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Response;
import feign.Util;
import feign.codec.Decoder;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.feign.annotation.AdaptiveUnwrap;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Feign 双模自适应契约解码引擎
 * 针对下游微服务统一返回的 Result&lt;T&gt; 物理契约：
 * 1. 若方法声明返回具体业务对象 T，自动校验 code == 200 并剥壳返回纯业务实体；
 * 2. 若方法声明返回原生 Result&lt;T&gt;，原样透传不做剥壳；
 * 3. 若方法声明返回 void，校验 code == 200 正常返回，否则抛出受管 BusinessException；
 * 4. 支持声明式 @AdaptiveUnwrap(false) 与 exclude-clients 白名单排除以兼容非标第三方接口。
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignDecoder implements Decoder {

    private final Decoder delegate;
    private final AtengFeignProperties properties;
    private final ObjectMapper objectMapper;

    public AtengFeignDecoder(Decoder delegate, AtengFeignProperties properties, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Object decode(Response response, Type type) throws IOException {
        if (response.status() == 204 || response.body() == null) {
            return checkPrimitiveNull(type, delegate.decode(response, type));
        }

        // 1. 如果返回类型本身属于流式 / 二进制 / Response，无条件豁免并原样透传
        if (isStreamOrBinaryType(type)) {
            return delegate.decode(response, type);
        }

        // 2. 如果方法声明返回类型本身就是 Result，原样透传不剥壳
        if (isResultType(type)) {
            return delegate.decode(response, type);
        }

        // 3. 检查自适应解包是否被全局、客户端或注解显式禁用
        if (!isUnwrapEnabled(response)) {
            return checkPrimitiveNull(type, delegate.decode(response, type));
        }

        // 4. 硬防爆安全前置防御：校验 Content-Length 标头
        long maxBytes = properties.getPayload() != null && properties.getPayload().getMaxResponseSize() != null
                ? properties.getPayload().getMaxResponseSize().toBytes() : 10 * 1024 * 1024L;

        if (response.headers().containsKey("content-length")) {
            for (String lenStr : response.headers().get("content-length")) {
                try {
                    long len = Long.parseLong(lenStr);
                    if (len > maxBytes) {
                        throw new BusinessException(CommonResultCode.PAYLOAD_TOO_LARGE,
                                String.format("Feign 响应标头 Content-Length [%d 字节] 超出硬防爆限制 [%d 字节]", len, maxBytes));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // 5. 按照硬防爆上限流式读取 body 字节流并保留副本，防流关闭
        byte[] bodyBytes;
        try (InputStream is = response.body().asInputStream()) {
            bodyBytes = readBoundedBody(is, maxBytes);
        }
        if (bodyBytes.length == 0) {
            return checkPrimitiveNull(type, null);
        }

        // 6. 尝试将 body 解析为 JsonNode
        JsonNode jsonNode;
        try {
            jsonNode = objectMapper.readTree(bodyBytes);
        } catch (Exception e) {
            // 非 JSON 格式，回退直接用原生 delegate 解码
            Response copy = response.toBuilder().body(bodyBytes).build();
            return checkPrimitiveNull(type, delegate.decode(copy, type));
        }

        // 7. 判断是否符合 Result 结构 (具备数字类型的 code 字段)
        if (FeignResultSupport.isResultObject(jsonNode)) {
            int code = FeignResultSupport.extractCode(jsonNode);
            String msg = FeignResultSupport.extractMessage(jsonNode);

            // 7.1 业务失败 (code != 200)，直接抛出受管 BusinessException
            if (code != 200) {
                throw new BusinessException(code, msg);
            }

            // 7.2 业务成功，处理 void 返回
            if (type == void.class || type == Void.class) {
                return null;
            }

            // 7.3 业务成功，反序列化 data 字段为业务类型 T
            if (!jsonNode.has("data") || jsonNode.get("data").isNull()) {
                return checkPrimitiveNull(type, null);
            }

            JavaType javaType = objectMapper.getTypeFactory().constructType(type);
            return checkPrimitiveNull(type, objectMapper.readValue(jsonNode.get("data").traverse(objectMapper), javaType));
        }

        // 8. 若返回 JSON 不包含 code 字段（如第三方 API），平滑降级反序列化为声明的 type
        JavaType javaType = objectMapper.getTypeFactory().constructType(type);
        return checkPrimitiveNull(type, objectMapper.readValue(bodyBytes, javaType));
    }

    private boolean isPrimitiveNonVoid(Type type) {
        return type instanceof Class<?> clazz && clazz.isPrimitive() && clazz != void.class;
    }

    private Object checkPrimitiveNull(Type type, Object result) {
        if (result == null && isPrimitiveNonVoid(type)) {
            Class<?> clazz = (Class<?>) type;
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    String.format("Feign 接口方法返回原生基本类型 [%s] 无法接收 null 载荷，请修改方法返回类型为包装类型", clazz.getName()));
        }
        return result;
    }

    private byte[] readBoundedBody(InputStream is, long maxBytes) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int len;
            while ((len = is.read(buffer)) != -1) {
                total += len;
                if (total > maxBytes) {
                    throw new BusinessException(CommonResultCode.PAYLOAD_TOO_LARGE,
                            String.format("Feign 响应体大小超出硬防爆限制 [%d 字节]，已熔断防御客户端内存溢出", maxBytes));
                }
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        }
    }

    private boolean isResultType(Type type) {
        if (type instanceof Class<?> clazz) {
            return Result.class.isAssignableFrom(clazz);
        }
        if (type instanceof ParameterizedType pType) {
            Type rawType = pType.getRawType();
            if (rawType instanceof Class<?> clazz) {
                return Result.class.isAssignableFrom(clazz);
            }
        }
        return false;
    }

    private boolean isStreamOrBinaryType(Type type) {
        if (type instanceof Class<?> clazz) {
            return Response.class.isAssignableFrom(clazz)
                    || byte[].class.isAssignableFrom(clazz)
                    || InputStream.class.isAssignableFrom(clazz)
                    || File.class.isAssignableFrom(clazz)
                    || Resource.class.isAssignableFrom(clazz);
        }
        if (type instanceof ParameterizedType pType) {
            Type rawType = pType.getRawType();
            if (rawType instanceof Class<?> clazz) {
                if (Resource.class.isAssignableFrom(clazz)
                        || HttpEntity.class.isAssignableFrom(clazz)) {
                    Type[] args = pType.getActualTypeArguments();
                    if (args.length > 0) {
                        return isStreamOrBinaryType(args[0]);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isUnwrapEnabled(Response response) {
        // 1. 最高优先级：方法级别 @AdaptiveUnwrap 声明式注解
        Method method = extractMethod(response);
        if (method != null && method.isAnnotationPresent(AdaptiveUnwrap.class)) {
            return method.getAnnotation(AdaptiveUnwrap.class).value();
        }

        // 2. 次高优先级：类/接口级别 @AdaptiveUnwrap 声明式注解
        Class<?> targetType = extractTargetType(response);
        if (targetType != null && targetType.isAnnotationPresent(AdaptiveUnwrap.class)) {
            return targetType.getAnnotation(AdaptiveUnwrap.class).value();
        }

        // 3. 单 Client 独立配置优先级高于全局白名单与全局开关
        String clientName = extractClientName(response);
        if (clientName != null && properties.getClients() != null) {
            var clientProps = properties.getClients().get(clientName);
            if (clientProps != null && clientProps.getAdaptiveUnwrap() != null) {
                return clientProps.getAdaptiveUnwrap();
            }
        }

        // 4. 客户端排除白名单检查
        if (clientName != null && properties.getAdaptiveUnwrap() != null
                && properties.getAdaptiveUnwrap().getExcludeClients().contains(clientName)) {
            return false;
        }

        // 5. 全局开关保底
        return properties.getAdaptiveUnwrap() != null && properties.getAdaptiveUnwrap().isEnabled();
    }

    private feign.RequestTemplate extractRequestTemplate(Response response) {
        if (response != null && response.request() != null) {
            return response.request().requestTemplate();
        }
        return null;
    }

    private String extractClientName(Response response) {
        feign.RequestTemplate template = extractRequestTemplate(response);
        if (template != null && template.feignTarget() != null) {
            return template.feignTarget().name();
        }
        return null;
    }

    private Method extractMethod(Response response) {
        feign.RequestTemplate template = extractRequestTemplate(response);
        if (template != null && template.methodMetadata() != null) {
            return template.methodMetadata().method();
        }
        return null;
    }

    private Class<?> extractTargetType(Response response) {
        feign.RequestTemplate template = extractRequestTemplate(response);
        if (template != null) {
            if (template.methodMetadata() != null && template.methodMetadata().targetType() != null) {
                return template.methodMetadata().targetType();
            }
            if (template.feignTarget() != null) {
                return template.feignTarget().type();
            }
        }
        return null;
    }
}
