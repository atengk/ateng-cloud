package io.github.atengk.cloud.gateway.filter;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.same.SaSameUtil;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySecurityProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTenantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sa-Token 响应式微服务网关集中安全鉴权与零信任同源防御全局过滤器
 *
 * <p>置于网关鉴权关键防线（{@link GatewayConstants#ORDER_SA_TOKEN}）：
 * <ul>
 *     <li>自动合并系统默认白名单与配置属性中的排除路径（{@code exclude-paths}），对白名单请求执行免密直通；</li>
 *     <li>对白名单与鉴权通过的流量，出站向下游统一注入网关动态签发的 {@code Same-Token} 内部同源受信凭据；</li>
 *     <li>对受保护接口未登录或 Token 非法/已过期请求，Fail-Fast 熔断并返回 HTTP 401 统一契约；</li>
 *     <li>鉴权通过后动态从登录态中解出 {@code userId} 与 {@code tenantId}，权威注入下游微服务请求头。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class SaTokenGatewayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SaTokenGatewayFilter.class);

    private static final String LOGIN_USER_KEY = "login_user";
    private static final String SWITCH_TARGET_USER_ID_KEY = "switch_target_user_id";
    private static final String TENANT_DELIMITER = "_";

    private static final ReentrantLock STP_LOGIC_LOCK = new ReentrantLock();

    private static final List<String> DEFAULT_SYSTEM_WHITELIST = Arrays.asList(
            "/actuator/**",
            "/docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/favicon.ico"
    );

    private final AtengGatewaySecurityProperties securityProperties;
    private final AtengGatewayTenantProperties tenantProperties;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public SaTokenGatewayFilter(AtengGatewaySecurityProperties securityProperties) {
        this(securityProperties, null);
    }

    public SaTokenGatewayFilter(AtengGatewaySecurityProperties securityProperties,
                                AtengGatewayTenantProperties tenantProperties) {
        this.securityProperties = securityProperties;
        this.tenantProperties = tenantProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();
        if (!StringUtils.hasText(path)) {
            path = "/";
        }

        String loginType = resolveLoginType(path);
        StpAccessor stp = resolveStpAccessor(loginType);

        // 1. 检查是否命中系统默认或配置白名单
        if (isWhitelisted(path)) {
            log.debug("网关白名单放行: URI=[{}], loginType=[{}]", path, loginType);
            SaReactorSyncHolder.setContext(exchange);
            try {
                ServerHttpRequest.Builder reqBuilder = exchange.getRequest().mutate();
                populateAuthHeaders(exchange, reqBuilder, stp, loginType);
                return chain.filter(exchange.mutate().request(reqBuilder.build()).build());
            } finally {
                SaReactorSyncHolder.clearContext();
            }
        }

        // 2. 集中鉴权与同源签名注入
        SaReactorSyncHolder.setContext(exchange);
        try {
            if (!stp.isLogin()) {
                log.warn("网关集中鉴权：未携带合法 Token 或已过期，阻断访问: URI=[{}], loginType=[{}]", path, loginType);
                return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录状态已过期，请重新登录"));
            }

            ServerHttpRequest.Builder reqBuilder = exchange.getRequest().mutate();
            populateAuthHeaders(exchange, reqBuilder, stp, loginType);
            return chain.filter(exchange.mutate().request(reqBuilder.build()).build());
        } catch (NotLoginException e) {
            log.warn("网关集中鉴权：未登录异常: URI=[{}], loginType=[{}], type=[{}]", path, loginType, e.getType());
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录状态已过期，请重新登录"));
        } finally {
            SaReactorSyncHolder.clearContext();
        }
    }

    /**
     * 提取当前登录会话中的用户与租户身份，注入上下文 Attributes 与出站请求头，并挂载内部同源 Same-Token
     */
    private void populateAuthHeaders(ServerWebExchange exchange,
                                     ServerHttpRequest.Builder reqBuilder,
                                     StpAccessor stp,
                                     String loginType) {
        if (stp.isLogin()) {
            exchange.getAttributes().put(GatewayConstants.ATTR_AUTHENTICATED, Boolean.TRUE);
            exchange.getAttributes().put(GatewayConstants.ATTR_LOGIN_TYPE, loginType);
            Object rawLoginId = stp.getLoginIdDefaultNull();
            String loginIdStr = rawLoginId != null ? String.valueOf(rawLoginId).trim() : null;
            SaSession accountSession = getAccountSessionSafely(stp);
            SaSession tokenSession = getTokenSessionSafely(stp);
            Map<String, Object> loginUserMap = extractLoginUserMap(accountSession, tokenSession);

            String userId = resolveUserId(accountSession, tokenSession, loginUserMap, loginIdStr);
            String tenantId = resolveTenantId(stp, accountSession, tokenSession, loginUserMap, loginIdStr);

            if (StringUtils.hasText(userId)) {
                exchange.getAttributes().put(GatewayConstants.ATTR_USER_ID, userId);
                reqBuilder.header(GatewayConstants.X_USER_ID, userId);
            }
            if (StringUtils.hasText(tenantId)) {
                exchange.getAttributes().put(GatewayConstants.ATTR_TENANT_ID, tenantId);
                reqBuilder.header(GatewayConstants.X_TENANT_ID, tenantId);
            }
            if (tenantProperties != null && tenantProperties.isEnabled() && tenantProperties.isAllowSuperAdminSwitch()) {
                exchange.getAttributes().put(GatewayConstants.ATTR_SUPER_ADMIN, resolveSuperAdmin(stp, loginUserMap));
            }
        }
        if (securityProperties == null || securityProperties.isSameTokenEnabled()) {
            injectSameTokenHeaders(reqBuilder);
        }
    }

    /**
     * 根据请求路径解析目标账号体系标识（最具体 AntPath 模式优先，未命中回退默认体系）
     */
    private String resolveLoginType(String path) {
        String defaultType = (securityProperties != null && StringUtils.hasText(securityProperties.getDefaultLoginType()))
                ? securityProperties.getDefaultLoginType().trim()
                : StpUtil.TYPE;
        Map<String, List<String>> mappings = securityProperties != null ? securityProperties.getLoginTypeMappings() : null;
        if (mappings == null || mappings.isEmpty()) {
            return defaultType;
        }

        List<Map.Entry<String, String>> matched = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : mappings.entrySet()) {
            String targetType = entry.getKey();
            List<String> patterns = entry.getValue();
            if (!StringUtils.hasText(targetType) || patterns == null || patterns.isEmpty()) {
                continue;
            }
            for (String pattern : patterns) {
                if (StringUtils.hasText(pattern) && antPathMatcher.match(pattern.trim(), path)) {
                    matched.add(Map.entry(pattern.trim(), targetType.trim()));
                }
            }
        }
        if (matched.isEmpty()) {
            return defaultType;
        }
        if (matched.size() == 1) {
            return matched.get(0).getValue();
        }

        Comparator<String> comparator = antPathMatcher.getPatternComparator(path);
        Map.Entry<String, String> best = matched.get(0);
        for (int i = 1; i < matched.size(); i++) {
            Map.Entry<String, String> candidate = matched.get(i);
            if (comparator.compare(candidate.getKey(), best.getKey()) < 0) {
                best = candidate;
            }
        }
        return best.getValue();
    }

    private static boolean isDefaultLoginType(String loginType) {
        return !StringUtils.hasText(loginType) || StpUtil.TYPE.equals(loginType.trim());
    }

    /**
     * 获取或懒加载注册指定账号体系对应的 {@link StpLogic}（与 {@code ateng-boot-satoken} 同构约定）
     *
     * <p>优先复用 {@link SaManager} 中已预注册的实例；若未注册，则在 {@link ReentrantLock} 守护下
     * 创建并注册以 {@code <tokenName>-<loginType>} 为令牌名的独立 {@link StpLogic}。</p>
     *
     * @param loginType 账号体系类型标识（如 {@code admin}、{@code member}；为空或 {@code login} 返回默认 {@link StpUtil#stpLogic}）
     * @return 对应的 {@link StpLogic} 实例
     */
    public static StpLogic getOrCreateStpLogic(String loginType) {
        if (isDefaultLoginType(loginType)) {
            return StpUtil.stpLogic;
        }
        String normalizedType = loginType.trim();
        StpLogic logic = findExistingStpLogic(normalizedType);
        if (logic != null) {
            return logic;
        }

        STP_LOGIC_LOCK.lock();
        try {
            logic = findExistingStpLogic(normalizedType);
            if (logic == null) {
                logic = new StpLogic(normalizedType) {
                    @Override
                    public String splicingKeyTokenName() {
                        return super.splicingKeyTokenName() + "-" + normalizedType;
                    }

                    @Override
                    public String splicingKeyJustCreatedSave() {
                        return super.splicingKeyJustCreatedSave() + normalizedType;
                    }
                };
                SaManager.putStpLogic(logic);
                log.info("网关动态懒加载注册 Sa-Token 多账号 StpLogic [loginType={}, tokenName={}]",
                        normalizedType, logic.getTokenName());
            }
            return logic;
        } finally {
            if (STP_LOGIC_LOCK.isHeldByCurrentThread()) {
                STP_LOGIC_LOCK.unlock();
            }
        }
    }

    private static StpLogic findExistingStpLogic(String loginType) {
        if (SaManager.stpLogicMap != null) {
            StpLogic existing = SaManager.stpLogicMap.get(loginType);
            if (existing != null) {
                return existing;
            }
        }
        try {
            return SaManager.getStpLogic(loginType, false);
        } catch (SaTokenException e) {
            log.debug("SaManager 尚未预注册 StpLogic [loginType={}]: {}", loginType, e.getMessage());
            return null;
        }
    }

    private StpAccessor resolveStpAccessor(String loginType) {
        if (isDefaultLoginType(loginType)) {
            return DEFAULT_STP_ACCESSOR;
        }
        StpLogic logic = getOrCreateStpLogic(loginType);
        return new StpAccessor() {
            @Override
            public boolean isLogin() {
                return logic.isLogin();
            }

            @Override
            public Object getLoginIdDefaultNull() {
                return logic.getLoginIdDefaultNull();
            }

            @Override
            public SaSession getAccountSession() {
                return logic.getSession(false);
            }

            @Override
            public SaSession getTokenSession() {
                return logic.getTokenSession(false);
            }

            @Override
            public Object getExtra(String key) {
                return logic.getExtra(key);
            }

            @Override
            public List<String> getRoleList() {
                return logic.getRoleList();
            }

            @Override
            public List<String> getPermissionList() {
                return logic.getPermissionList();
            }
        };
    }

    private SaSession getAccountSessionSafely(StpAccessor stp) {
        try {
            return stp.getAccountSession();
        } catch (Exception e) {
            log.debug("读取 Account Session 跳过: [{}]", e.getMessage());
            return null;
        }
    }

    private SaSession getTokenSessionSafely(StpAccessor stp) {
        try {
            return stp.getTokenSession();
        } catch (Exception e) {
            log.debug("读取 TokenSession 跳过: [{}]", e.getMessage());
            return null;
        }
    }

    private Object getExtraSafely(StpAccessor stp, String key) {
        try {
            return stp.getExtra(key);
        } catch (Exception e) {
            log.debug("读取 JWT Extra 字段 [{}] 跳过: [{}]", key, e.getMessage());
            return null;
        }
    }

    private interface StpAccessor {
        boolean isLogin();

        Object getLoginIdDefaultNull();

        SaSession getAccountSession();

        SaSession getTokenSession();

        Object getExtra(String key);

        List<String> getRoleList();

        List<String> getPermissionList();
    }

    private static final StpAccessor DEFAULT_STP_ACCESSOR = new StpAccessor() {
        @Override
        public boolean isLogin() {
            return StpUtil.isLogin();
        }

        @Override
        public Object getLoginIdDefaultNull() {
            return StpUtil.getLoginIdDefaultNull();
        }

        @Override
        public SaSession getAccountSession() {
            return StpUtil.getSession(false);
        }

        @Override
        public SaSession getTokenSession() {
            // 优先使用非创建式读取防止无 TokenSession 时创建空缓存条目；静态 Mock 场景仅通过 StpUtil.getTokenSession() 响应
            if (StringUtils.hasText(StpUtil.stpLogic.getTokenValue())) {
                return StpUtil.stpLogic.getTokenSession(false);
            }
            return StpUtil.getTokenSession();
        }

        @Override
        public Object getExtra(String key) {
            return StpUtil.getExtra(key);
        }

        @Override
        public List<String> getRoleList() {
            return StpUtil.getRoleList();
        }

        @Override
        public List<String> getPermissionList() {
            return StpUtil.getPermissionList();
        }
    };

    /**
     * 判断当前路径是否命中系统默认白名单或业务自定义白名单
     */
    private boolean isWhitelisted(String path) {
        // 匹配系统预设白名单
        for (String pattern : DEFAULT_SYSTEM_WHITELIST) {
            if (antPathMatcher.match(pattern, path)) {
                return true;
            }
        }

        // 匹配业务自定义排除路径
        if (securityProperties != null && securityProperties.getExcludePaths() != null) {
            for (String pattern : securityProperties.getExcludePaths()) {
                if (StringUtils.hasText(pattern) && antPathMatcher.match(pattern.trim(), path)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 自适应提取当前登录会话中的用户 ID (兼容 RunAs 模拟态、login_user 载荷与复合 loginId 解包)
     */
    private String resolveUserId(SaSession accountSession,
                                 SaSession tokenSession,
                                 Map<String, Object> loginUserMap,
                                 String loginIdStr) {
        if (accountSession != null) {
            String targetUserId = asNonBlankString(accountSession.get(SWITCH_TARGET_USER_ID_KEY));
            if (targetUserId != null) {
                return targetUserId;
            }
        }
        if (tokenSession != null) {
            String targetUserId = asNonBlankString(tokenSession.get(SWITCH_TARGET_USER_ID_KEY));
            if (targetUserId != null) {
                return targetUserId;
            }
        }
        if (loginUserMap != null) {
            String payloadUserId = asNonBlankString(loginUserMap.get("userId"));
            if (payloadUserId != null) {
                return payloadUserId;
            }
        }
        if (!StringUtils.hasText(loginIdStr)) {
            return null;
        }
        if (loginUserMap == null && isCompositeLoginIdEnabled()) {
            int idx = loginIdStr.indexOf(TENANT_DELIMITER);
            if (idx > 0 && idx + 1 < loginIdStr.length()) {
                return loginIdStr.substring(idx + 1).trim();
            }
        }
        return loginIdStr;
    }

    /**
     * 自适应提取当前登录会话中的租户 ID (兼容 JWT extra、login_user 载荷、SaSession 键值与复合 loginId 解包)
     */
    private String resolveTenantId(StpAccessor stp,
                                   SaSession accountSession,
                                   SaSession tokenSession,
                                   Map<String, Object> loginUserMap,
                                   String loginIdStr) {
        String extraTenant = asNonBlankString(getExtraSafely(stp, "tenantId"));
        if (extraTenant != null) {
            return extraTenant;
        }
        if (loginUserMap != null) {
            String payloadTenant = asNonBlankString(loginUserMap.get("tenantId"));
            if (payloadTenant != null) {
                return payloadTenant;
            }
        }
        if (accountSession != null) {
            String sessionTenant = asNonBlankString(accountSession.get("tenantId"));
            if (sessionTenant != null) {
                return sessionTenant;
            }
        }
        if (tokenSession != null) {
            String tokenTenant = asNonBlankString(tokenSession.get("tenantId"));
            if (tokenTenant != null) {
                return tokenTenant;
            }
        }
        if (loginUserMap == null && isCompositeLoginIdEnabled() && StringUtils.hasText(loginIdStr)) {
            int idx = loginIdStr.indexOf(TENANT_DELIMITER);
            if (idx > 0 && idx + 1 < loginIdStr.length()) {
                return loginIdStr.substring(0, idx).trim();
            }
        }
        return null;
    }

    private Map<String, Object> extractLoginUserMap(SaSession accountSession, SaSession tokenSession) {
        Map<String, Object> parsed = parseLoginUserPayload(accountSession != null ? accountSession.get(LOGIN_USER_KEY) : null);
        if (parsed != null) {
            return parsed;
        }
        return parseLoginUserPayload(tokenSession != null ? tokenSession.get(LOGIN_USER_KEY) : null);
    }

    private Map<String, Object> parseLoginUserPayload(Object rawPayload) {
        if (rawPayload == null) {
            return null;
        }
        if (rawPayload instanceof Map<?, ?> rawMap) {
            Map<String, Object> result = new java.util.HashMap<>(rawMap.size());
            rawMap.forEach((k, v) -> {
                if (k != null) {
                    result.put(String.valueOf(k), v);
                }
            });
            return result;
        }
        if (rawPayload instanceof String jsonStr) {
            if (!StringUtils.hasText(jsonStr)) {
                return null;
            }
            try {
                return JsonUtil.parseMap(jsonStr);
            } catch (Exception e) {
                log.warn("网关解析会话 login_user JSON 失败，将安全降级至兼容提取链: [{}]", e.getMessage());
                return null;
            }
        }
        try {
            return JsonUtil.convertValue(rawPayload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("网关转换会话 login_user 对象失败，将安全降级至兼容提取链: [{}]", e.getMessage());
            return null;
        }
    }

    private boolean isCompositeLoginIdEnabled() {
        return securityProperties == null || securityProperties.isCompositeLoginIdEnabled();
    }

    private String asNonBlankString(Object value) {
        if (value == null) {
            return null;
        }
        String str = String.valueOf(value).trim();
        return StringUtils.hasText(str) ? str : null;
    }

    private boolean resolveSuperAdmin(StpAccessor stp, Map<String, Object> loginUserMap) {
        Set<String> configuredRoles = normalizeTargetSet(tenantProperties != null ? tenantProperties.getSuperAdminRoles() : null);
        Set<String> configuredPerms = normalizeTargetSet(tenantProperties != null ? tenantProperties.getSuperAdminPermissions() : null);
        if (configuredRoles.isEmpty() && configuredPerms.isEmpty()) {
            return false;
        }
        if (loginUserMap != null) {
            if (!configuredRoles.isEmpty() && containsAnyMatch(configuredRoles,
                    loginUserMap.get("roles"), loginUserMap.get("roleKeys"))) {
                return true;
            }
            if (!configuredPerms.isEmpty() && containsAnyMatch(configuredPerms,
                    loginUserMap.get("permissions"), loginUserMap.get("perms"))) {
                return true;
            }
        }
        if (!configuredRoles.isEmpty()) {
            List<String> stpRoles = getRoleListSafely(stp);
            for (String role : stpRoles) {
                if (StringUtils.hasText(role) && configuredRoles.contains(role.trim())) {
                    return true;
                }
            }
        }
        if (!configuredPerms.isEmpty()) {
            List<String> stpPerms = getPermissionListSafely(stp);
            for (String perm : stpPerms) {
                if (StringUtils.hasText(perm) && configuredPerms.contains(perm.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> getRoleListSafely(StpAccessor stp) {
        try {
            List<String> roles = stp.getRoleList();
            return roles != null ? roles : Collections.emptyList();
        } catch (Exception e) {
            log.debug("读取 Sa-Token 角色列表跳过: [{}]", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<String> getPermissionListSafely(StpAccessor stp) {
        try {
            List<String> perms = stp.getPermissionList();
            return perms != null ? perms : Collections.emptyList();
        } catch (Exception e) {
            log.debug("读取 Sa-Token 权限列表跳过: [{}]", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static Set<String> normalizeTargetSet(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String item : raw) {
            if (StringUtils.hasText(item)) {
                normalized.add(item.trim());
            }
        }
        return normalized;
    }

    private static boolean containsAnyMatch(Set<String> expected, Object... sources) {
        if (expected.isEmpty() || sources == null) {
            return false;
        }
        for (Object src : sources) {
            if (src instanceof Collection<?> col) {
                for (Object item : col) {
                    if (item != null && expected.contains(String.valueOf(item).trim())) {
                        return true;
                    }
                }
            } else if (src instanceof String str && StringUtils.hasText(str)) {
                for (String part : str.split(",")) {
                    if (StringUtils.hasText(part) && expected.contains(part.trim())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void injectSameTokenHeaders(ServerHttpRequest.Builder builder) {
        try {
            String sameToken = SaSameUtil.getToken();
            if (StringUtils.hasText(sameToken)) {
                builder.header(GatewayConstants.SAME_TOKEN, sameToken);
                builder.header(GatewayConstants.SAME_TOKEN_UPPER, sameToken);
                builder.header(GatewayConstants.SA_SAME_TOKEN, sameToken);
            }
        } catch (Exception e) {
            log.debug("网关签发 Same-Token 跳过: [{}]", e.getMessage());
        }
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_SA_TOKEN;
    }
}
