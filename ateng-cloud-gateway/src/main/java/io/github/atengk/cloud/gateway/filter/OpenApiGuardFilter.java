package io.github.atengk.cloud.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayOpenApiProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewayOpenApiProperties.GuardMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * 微服务 OpenAPI / Swagger 统一接口文档访问安全守卫响应式 Web 过滤器
 *
 * <p>核心治理语义：
 * 1. 拦截识别微服务契约文档路径（包括 {@code /docs/**}、{@code /v3/api-docs/**}、{@code /swagger-ui/**} 等）；
 * 2. 提供四模生产守卫策略：
 *    - {@link GuardMode#DISABLED}：生产环境硬熔断隐藏，返回 HTTP 404；
 *    - {@link GuardMode#BASIC}：HTTP Basic Auth 弹窗认证挑战与凭据校验；
 *    - {@link GuardMode#SA_TOKEN}：校验 Sa-Token 登录态与管理员角色；
 *    - {@link GuardMode#PUBLIC}：开发与测试环境免密直连放行（入口自动引导至 Swagger UI）。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class OpenApiGuardFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(OpenApiGuardFilter.class);

    private final AtengGatewayOpenApiProperties properties;
    private final ObjectMapper objectMapper;
    private final PathMatcher pathMatcher = new AntPathMatcher();

    private static final List<String> DEFAULT_OPENAPI_PATTERNS = List.of(
            "/docs",
            "/docs/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/swagger-resources/**",
            "/doc.html",
            "/webjars/swagger-ui/**"
    );

    public OpenApiGuardFilter(AtengGatewayOpenApiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (properties == null || !properties.isEnabled()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        if (!isOpenApiPath(path)) {
            return chain.filter(exchange);
        }

        GuardMode mode = properties.getGuardMode() != null ? properties.getGuardMode() : GuardMode.PUBLIC;

        return switch (mode) {
            case DISABLED -> handleDisabled(exchange);
            case BASIC -> handleBasicAuth(exchange, chain);
            case SA_TOKEN -> handleSaToken(exchange, chain);
            case PUBLIC -> handlePublic(exchange, chain);
        };
    }

    /**
     * PUBLIC 模式：免密直访与文档入口统一路由
     */
    private Mono<Void> handlePublic(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String configuredPath = StringUtils.hasText(properties.getPath()) ? properties.getPath() : "/docs";
        if (path.equalsIgnoreCase(configuredPath) || path.equalsIgnoreCase(configuredPath + "/")) {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.FOUND);
            response.getHeaders().setLocation(URI.create("/swagger-ui.html"));
            return response.setComplete();
        }
        return chain.filter(exchange);
    }

    /**
     * 判断当前请求路径是否为 OpenAPI / Swagger 文档相关路径
     */
    public boolean isOpenApiPath(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        if (StringUtils.hasText(properties.getPath())) {
            String configuredPath = properties.getPath();
            if (path.equalsIgnoreCase(configuredPath) || pathMatcher.match(configuredPath + "/**", path)) {
                return true;
            }
        }
        for (String pattern : DEFAULT_OPENAPI_PATTERNS) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * DISABLED 模式：生产硬阻断直接返回 HTTP 404
     */
    private Mono<Void> handleDisabled(ServerWebExchange exchange) {
        log.warn("[GatewayOpenApi] 生产环境已禁用接口文档访问: path={}", exchange.getRequest().getPath().value());
        return writeJsonError(exchange, HttpStatus.NOT_FOUND, 404, "接口文档服务已在生产环境熔断关闭");
    }

    /**
     * BASIC 模式：HTTP Basic Auth 挑战认证
     */
    private Mono<Void> handleBasicAuth(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Basic ")) {
            try {
                String base64Credentials = authHeader.substring(6).trim();
                byte[] decodedBytes = Base64.getDecoder().decode(base64Credentials);
                String credentials = new String(decodedBytes, StandardCharsets.UTF_8);
                String[] parts = credentials.split(":", 2);

                if (parts.length == 2) {
                    String username = parts[0];
                    String password = parts[1];
                    AtengGatewayOpenApiProperties.BasicAuthProperties basicAuth = properties.getBasicAuth();
                    if (basicAuth != null
                            && basicAuth.getUsername().equals(username)
                            && basicAuth.getPassword().equals(password)) {
                        return chain.filter(exchange);
                    }
                }
            } catch (Exception e) {
                log.warn("[GatewayOpenApi] Basic 认证头解析失败: {}", e.getMessage());
            }
        }

        // 认证失败或未提供凭据，触发 401 挑战
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"OpenAPI Protected\"");
        return writeJsonError(exchange, HttpStatus.UNAUTHORIZED, 401, "接口文档访问需通过 HTTP Basic 身份认证");
    }

    /**
     * SA_TOKEN 模式：校验 Sa-Token 登录态
     */
    private Mono<Void> handleSaToken(ServerWebExchange exchange, WebFilterChain chain) {
        try {
            // 通过 cn.dev33.satoken.stp.StpUtil 校验登录状态
            if (cn.dev33.satoken.stp.StpUtil.isLogin()) {
                return chain.filter(exchange);
            }
        } catch (Throwable e) {
            log.debug("[GatewayOpenApi] Sa-Token 检查失败: {}", e.getMessage());
        }

        return writeJsonError(exchange, HttpStatus.UNAUTHORIZED, 401, "接口文档访问需要登录管理员权限");
    }

    /**
     * 输出统一强类型 JSON 异常响应
     */
    private Mono<Void> writeJsonError(ServerWebExchange exchange, HttpStatus httpStatus, int code, String msg) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(httpStatus);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String traceId = (String) exchange.getAttributes().get(GatewayConstants.ATTR_TRACE_ID);
        if (StringUtils.hasText(traceId)) {
            response.getHeaders().set(GatewayConstants.X_TRACE_ID, traceId);
        }

        Result<Void> result = Result.fail(code, msg);
        result.setTraceId(traceId);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":" + code + ",\"msg\":\"" + msg + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_IP_FILTER + 1;
    }
}
