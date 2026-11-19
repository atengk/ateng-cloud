package io.github.atengk.cloud.feign.decoder;

import com.fasterxml.jackson.databind.JsonNode;
import feign.Response;
import io.github.atengk.cloud.feign.support.FeignContextHeaderSupport;
import io.github.atengk.cloud.feign.support.FeignErrorSupport;
import org.springframework.util.StringUtils;

/**
 * Feign Result 统一物理契约解析辅助类
 * 消除 Decoder 与 ErrorDecoder 间的代码异味与契约解析逻辑重复
 *
 * @author Ateng
 * @since 2026-10-28
 */
final class FeignResultSupport {

    private FeignResultSupport() {
    }

    /**
     * 判断 JsonNode 是否符合规范的 Result 物理结构（包含数字类型 code 字段的对象）
     *
     * @param jsonNode JSON 节点
     * @return true 表示符合 Result 物理契约
     */
    static boolean isResultObject(JsonNode jsonNode) {
        return jsonNode != null && jsonNode.isObject() && jsonNode.has("code") && jsonNode.get("code").isNumber();
    }

    /**
     * 从 Result JSON 节点中提取状态码
     *
     * @param jsonNode JSON 节点
     * @return 业务状态码
     */
    static int extractCode(JsonNode jsonNode) {
        return jsonNode.get("code").asInt();
    }

    /**
     * 从 Result JSON 节点中提取提示消息，兼容 msg 与 message 字段
     *
     * @param jsonNode JSON 节点
     * @return 错误提示消息
     */
    static String extractMessage(JsonNode jsonNode) {
        if (jsonNode == null) {
            return "";
        }
        if (jsonNode.has("msg") && !jsonNode.get("msg").isNull()) {
            return jsonNode.get("msg").asText();
        }
        if (jsonNode.has("message") && !jsonNode.get("message").isNull()) {
            return jsonNode.get("message").asText();
        }
        return "";
    }

    /**
     * 从 Result JSON 节点中提取可选 {@code traceId} 字段
     *
     * @param jsonNode JSON 节点
     * @return 链路追踪标识，缺失或空白返回 {@code null}
     */
    static String extractTraceId(JsonNode jsonNode) {
        if (jsonNode == null || !jsonNode.has("traceId") || jsonNode.get("traceId").isNull()) {
            return null;
        }
        return FeignContextHeaderSupport.normalize(jsonNode.get("traceId").asText(null));
    }

    /**
     * 解析远端响应关联的 {@code traceId}（优先提取 JSON {@code Result.traceId}，缺失时回退响应头 {@code X-Trace-Id}）
     *
     * @param jsonNode JSON 节点（可为 {@code null}）
     * @param response Feign 原始响应对象
     * @return 链路追踪标识或 {@code null}
     */
    static String resolveResponseTraceId(JsonNode jsonNode, Response response) {
        String bodyTraceId = extractTraceId(jsonNode);
        if (StringUtils.hasText(bodyTraceId)) {
            return bodyTraceId;
        }
        return response != null ? FeignErrorSupport.extractTraceIdFromHeaders(response.headers()) : null;
    }
}
