package io.github.atengk.cloud.gateway.sign.util;

import io.github.atengk.boot.core.util.DigestUtil;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 网关 API 加签验签标准化待签串构造工具类
 *
 * <p>严格对齐 {@code ateng-boot-web} {@code SignCanonicalUtil} 6 行入站待签串与 3 行出站响应待签串协议，
 * 保障单体应用向微服务网关平滑迁移时客户端签名实现 100% 字节级互通。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public final class GatewaySignCanonicalUtil {

    private GatewaySignCanonicalUtil() {
    }

    /**
     * 构建统一规范入站请求待签串
     * <p>规范格式：{@code Method\nURI\nTimestamp\nNonce\nSortedQueryString\nBodySha256}</p>
     *
     * @param method      HTTP 请求方法（如 POST、GET）
     * @param uri         请求路径 URI（如 /api/open/orders）
     * @param timestamp   时间戳字符串
     * @param nonce       防重放随机数
     * @param queryParams Query 查询参数映射
     * @param bodySha256  请求体 SHA-256 小写十六进制摘要（空体或无体传 {@code null} 将自动填补空串摘要）
     * @return 6 行规范化待签名字符串
     */
    public static String buildStandardRequestCanonicalString(String method,
                                                             String uri,
                                                             String timestamp,
                                                             String nonce,
                                                             MultiValueMap<String, String> queryParams,
                                                             String bodySha256) {
        String cleanMethod = method != null ? method.toUpperCase(Locale.ROOT) : "GET";
        String cleanUri = StringUtils.hasText(uri) ? uri : "/";
        String cleanTimestamp = timestamp != null ? timestamp : "";
        String cleanNonce = nonce != null ? nonce : "";
        String sortedQuery = buildSortedQueryString(queryParams);
        String cleanBodySha256 = StringUtils.hasText(bodySha256)
                ? bodySha256.toLowerCase(Locale.ROOT)
                : DigestUtil.sha256Hex("");

        return String.join("\n", cleanMethod, cleanUri, cleanTimestamp, cleanNonce, sortedQuery, cleanBodySha256);
    }

    /**
     * 构建出站响应标准化待签串
     * <p>规范格式：{@code Timestamp\nNonce\nResponseBodySha256}</p>
     *
     * @param timestamp          出站响应时间戳
     * @param nonce              出站响应随机 Nonce
     * @param responseBodySha256 响应体 SHA-256 小写十六进制摘要
     * @return 3 行规范化出站待签名字符串
     */
    public static String buildResponseCanonicalString(String timestamp,
                                                      String nonce,
                                                      String responseBodySha256) {
        String cleanTimestamp = timestamp != null ? timestamp : "";
        String cleanNonce = nonce != null ? nonce : "";
        String cleanBodySha256 = StringUtils.hasText(responseBodySha256)
                ? responseBodySha256.toLowerCase(Locale.ROOT)
                : DigestUtil.sha256Hex("");

        return String.join("\n", cleanTimestamp, cleanNonce, cleanBodySha256);
    }

    /**
     * 将 Query 参数映射按 ASCII 字典序双排序（参数名及同键多值均排序）组装为 {@code k1=v1&k2=v2}
     *
     * @param queryParams Query 查询参数映射
     * @return 排序后的 Query 字符串；无参数时返回空字符串
     */
    public static String buildSortedQueryString(MultiValueMap<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return "";
        }

        List<String> sortedKeys = new ArrayList<>(queryParams.keySet());
        Collections.sort(sortedKeys);

        List<String> pairs = new ArrayList<>();
        for (String key : sortedKeys) {
            List<String> values = queryParams.get(key);
            if (values == null || values.isEmpty()) {
                pairs.add(key + "=");
            } else {
                List<String> sortedValues = new ArrayList<>(values.size());
                for (String value : values) {
                    sortedValues.add(value != null ? value : "");
                }
                Collections.sort(sortedValues);
                for (String val : sortedValues) {
                    pairs.add(key + "=" + val);
                }
            }
        }

        return String.join("&", pairs);
    }
}
