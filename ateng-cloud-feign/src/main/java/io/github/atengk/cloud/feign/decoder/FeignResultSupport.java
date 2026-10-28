package io.github.atengk.cloud.feign.decoder;

import com.fasterxml.jackson.databind.JsonNode;

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
}
