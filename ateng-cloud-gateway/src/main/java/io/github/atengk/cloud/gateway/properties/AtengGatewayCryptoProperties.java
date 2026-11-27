package io.github.atengk.cloud.gateway.properties;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoAlgorithm;
import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoResponseKeyMode;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关边缘 API 报文混合加解密防窃听配置属性
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class AtengGatewayCryptoProperties {

    /**
     * 是否启用网关边缘 API 混合加解密过滤器（默认 false 保持向后零侵入）
     */
    private boolean enabled = false;

    /**
     * 默认混合加密算法套件（默认 RSA_AES_GCM，可选 SM2_SM4_GCM）
     */
    private GatewayCryptoAlgorithm algorithm = GatewayCryptoAlgorithm.RSA_AES_GCM;

    /**
     * 出站响应密钥协商模式（默认 CLIENT_PUBLIC_KEY，可选 SPA 无私钥场景 REQUEST_SESSION_KEY）
     */
    private GatewayCryptoResponseKeyMode responseKeyMode = GatewayCryptoResponseKeyMode.CLIENT_PUBLIC_KEY;

    /**
     * 缺省应用标识（当客户端未传 {@code X-App-Id} 时用于构造上下文与密钥查询，默认 default）
     */
    private String appId = "default";

    /**
     * 当前激活的密钥版本号标识（默认 v1，出站响应未指定时回退使用）
     */
    private String activeKeyId = "v1";

    /**
     * 单次加解密请求体与响应体最大允许内存容量阈值（默认 2MB 防堆外 OOM）
     */
    private DataSize maxBodySize = DataSize.ofMegabytes(2);

    /**
     * 是否将 {@code appId|keyId|algorithm} 绑定为 AEAD 关联认证数据（AAD）防标头替换攻击（默认 false）
     */
    private boolean bindAad = false;

    /**
     * 是否启用入站请求体自动解密（默认 true）
     */
    private boolean decryptEnabled = true;

    /**
     * 是否启用出站响应体自动加密（默认 true）
     */
    private boolean encryptEnabled = true;

    /**
     * 是否强制要求入站请求必须携带有效加密载荷与信封标头（默认 true 严格拦截未加密明文；false 兼容双模灰度）
     */
    private boolean required = true;

    /**
     * 是否对 HTTP 状态码 {@code >= 400} 的错误响应执行加密（默认 false 直接明文透出便于故障排障）
     */
    private boolean encryptError = false;

    /**
     * 入站解密完成后是否物理剥离传输加密标头再转发下游微服务（保留 {@code X-App-Id}，默认 true）
     */
    private boolean stripCryptoHeaders = true;

    /**
     * 需执行边缘加解密的目标路径列表（AntPath；为空时配合方向子路径或对非排除路径全局生效）
     */
    private List<String> includePaths = new ArrayList<>();

    /**
     * 加解密放行排除路径列表（AntPath，优先级高于 includePaths）
     */
    private List<String> excludePaths = new ArrayList<>();

    /**
     * 仅需执行入站解密的细分路径列表（AntPath；非空时替代 includePaths 判定入站解密）
     */
    private List<String> decryptIncludePaths = new ArrayList<>();

    /**
     * 仅需执行出站加密的细分路径列表（AntPath；非空时替代 includePaths 判定出站加密）
     */
    private List<String> encryptIncludePaths = new ArrayList<>();

    /**
     * 通用服务端解密私钥（Base64/PEM；算法专属字段未配置时回退读取）
     */
    private String serverPrivateKey;

    /**
     * 通用客户端加密公钥（Base64/PEM；算法专属字段未配置时回退读取）
     */
    private String clientPublicKey;

    /**
     * RSA 专属服务端解密私钥（PKCS#8 Base64/PEM）
     */
    private String rsaServerPrivateKey;

    /**
     * RSA 专属客户端加密公钥（X.509 Base64/PEM）
     */
    private String rsaClientPublicKey;

    /**
     * 国密 SM2 专属服务端解密私钥（Hex/Base64/PEM）
     */
    private String sm2ServerPrivateKey;

    /**
     * 国密 SM2 专属客户端加密公钥（Hex/Base64/PEM）
     */
    private String sm2ClientPublicKey;

    /**
     * 多版本密钥轮转配置映射表（Key 为 {@code keyId}，支持新旧版本并行灰度解密与启用停用状态控制）
     */
    private Map<String, CryptoKeyVersionProperties> keys = new LinkedHashMap<>();

    /**
     * 应用标识请求头名称（默认 X-App-Id）
     */
    private String headerAppId = GatewayConstants.X_APP_ID;

    /**
     * 对称会话密钥信封请求头/响应头名称（默认 X-Encrypt-Key）
     */
    private String headerEncryptKey = GatewayConstants.X_ENCRYPT_KEY;

    /**
     * AES/SM4-GCM 初始化向量请求头/响应头名称（默认 X-Encrypt-Iv）
     */
    private String headerEncryptIv = GatewayConstants.X_ENCRYPT_IV;

    /**
     * 密钥版本号请求头/响应头名称（默认 X-Key-Id）
     */
    private String headerKeyId = GatewayConstants.X_KEY_ID;

    /**
     * 混合加密套件算法标识请求头/响应头名称（默认 X-Encrypt-Alg）
     */
    private String headerEncryptAlg = GatewayConstants.X_ENCRYPT_ALG;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public GatewayCryptoAlgorithm getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(GatewayCryptoAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public GatewayCryptoResponseKeyMode getResponseKeyMode() {
        return responseKeyMode;
    }

    public void setResponseKeyMode(GatewayCryptoResponseKeyMode responseKeyMode) {
        this.responseKeyMode = responseKeyMode;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getActiveKeyId() {
        return activeKeyId;
    }

    public void setActiveKeyId(String activeKeyId) {
        this.activeKeyId = activeKeyId;
    }

    public DataSize getMaxBodySize() {
        return maxBodySize;
    }

    public void setMaxBodySize(DataSize maxBodySize) {
        this.maxBodySize = maxBodySize;
    }

    public boolean isBindAad() {
        return bindAad;
    }

    public void setBindAad(boolean bindAad) {
        this.bindAad = bindAad;
    }

    public boolean isDecryptEnabled() {
        return decryptEnabled;
    }

    public void setDecryptEnabled(boolean decryptEnabled) {
        this.decryptEnabled = decryptEnabled;
    }

    public boolean isEncryptEnabled() {
        return encryptEnabled;
    }

    public void setEncryptEnabled(boolean encryptEnabled) {
        this.encryptEnabled = encryptEnabled;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public boolean isEncryptError() {
        return encryptError;
    }

    public void setEncryptError(boolean encryptError) {
        this.encryptError = encryptError;
    }

    public boolean isStripCryptoHeaders() {
        return stripCryptoHeaders;
    }

    public void setStripCryptoHeaders(boolean stripCryptoHeaders) {
        this.stripCryptoHeaders = stripCryptoHeaders;
    }

    public List<String> getIncludePaths() {
        return includePaths;
    }

    public void setIncludePaths(List<String> includePaths) {
        this.includePaths = includePaths;
    }

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
    }

    public List<String> getDecryptIncludePaths() {
        return decryptIncludePaths;
    }

    public void setDecryptIncludePaths(List<String> decryptIncludePaths) {
        this.decryptIncludePaths = decryptIncludePaths;
    }

    public List<String> getEncryptIncludePaths() {
        return encryptIncludePaths;
    }

    public void setEncryptIncludePaths(List<String> encryptIncludePaths) {
        this.encryptIncludePaths = encryptIncludePaths;
    }

    public String getServerPrivateKey() {
        return serverPrivateKey;
    }

    public void setServerPrivateKey(String serverPrivateKey) {
        this.serverPrivateKey = serverPrivateKey;
    }

    public String getClientPublicKey() {
        return clientPublicKey;
    }

    public void setClientPublicKey(String clientPublicKey) {
        this.clientPublicKey = clientPublicKey;
    }

    public String getRsaServerPrivateKey() {
        return rsaServerPrivateKey;
    }

    public void setRsaServerPrivateKey(String rsaServerPrivateKey) {
        this.rsaServerPrivateKey = rsaServerPrivateKey;
    }

    public String getRsaClientPublicKey() {
        return rsaClientPublicKey;
    }

    public void setRsaClientPublicKey(String rsaClientPublicKey) {
        this.rsaClientPublicKey = rsaClientPublicKey;
    }

    public String getSm2ServerPrivateKey() {
        return sm2ServerPrivateKey;
    }

    public void setSm2ServerPrivateKey(String sm2ServerPrivateKey) {
        this.sm2ServerPrivateKey = sm2ServerPrivateKey;
    }

    public String getSm2ClientPublicKey() {
        return sm2ClientPublicKey;
    }

    public void setSm2ClientPublicKey(String sm2ClientPublicKey) {
        this.sm2ClientPublicKey = sm2ClientPublicKey;
    }

    public Map<String, CryptoKeyVersionProperties> getKeys() {
        return keys;
    }

    public void setKeys(Map<String, CryptoKeyVersionProperties> keys) {
        this.keys = keys;
    }

    public String getHeaderAppId() {
        return headerAppId;
    }

    public void setHeaderAppId(String headerAppId) {
        this.headerAppId = headerAppId;
    }

    public String getHeaderEncryptKey() {
        return headerEncryptKey;
    }

    public void setHeaderEncryptKey(String headerEncryptKey) {
        this.headerEncryptKey = headerEncryptKey;
    }

    public String getHeaderEncryptIv() {
        return headerEncryptIv;
    }

    public void setHeaderEncryptIv(String headerEncryptIv) {
        this.headerEncryptIv = headerEncryptIv;
    }

    public String getHeaderKeyId() {
        return headerKeyId;
    }

    public void setHeaderKeyId(String headerKeyId) {
        this.headerKeyId = headerKeyId;
    }

    public String getHeaderEncryptAlg() {
        return headerEncryptAlg;
    }

    public void setHeaderEncryptAlg(String headerEncryptAlg) {
        this.headerEncryptAlg = headerEncryptAlg;
    }

    /**
     * 单版本加解密密钥对配置项（用于无缝版本轮转与受损密钥下线停用）
     *
     * @author Ateng
     * @since 2026-11-27
     */
    public static class CryptoKeyVersionProperties {

        /**
         * 当前密钥版本是否启用（设为 false 时使用该版本的请求将被 400 快速阻断）
         */
        private boolean enabled = true;

        /**
         * 通用服务端解密私钥
         */
        private String serverPrivateKey;

        /**
         * 通用客户端加密公钥
         */
        private String clientPublicKey;

        /**
         * RSA 专属服务端解密私钥
         */
        private String rsaServerPrivateKey;

        /**
         * RSA 专属客户端加密公钥
         */
        private String rsaClientPublicKey;

        /**
         * 国密 SM2 专属服务端解密私钥
         */
        private String sm2ServerPrivateKey;

        /**
         * 国密 SM2 专属客户端加密公钥
         */
        private String sm2ClientPublicKey;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getServerPrivateKey() {
            return serverPrivateKey;
        }

        public void setServerPrivateKey(String serverPrivateKey) {
            this.serverPrivateKey = serverPrivateKey;
        }

        public String getClientPublicKey() {
            return clientPublicKey;
        }

        public void setClientPublicKey(String clientPublicKey) {
            this.clientPublicKey = clientPublicKey;
        }

        public String getRsaServerPrivateKey() {
            return rsaServerPrivateKey;
        }

        public void setRsaServerPrivateKey(String rsaServerPrivateKey) {
            this.rsaServerPrivateKey = rsaServerPrivateKey;
        }

        public String getRsaClientPublicKey() {
            return rsaClientPublicKey;
        }

        public void setRsaClientPublicKey(String rsaClientPublicKey) {
            this.rsaClientPublicKey = rsaClientPublicKey;
        }

        public String getSm2ServerPrivateKey() {
            return sm2ServerPrivateKey;
        }

        public void setSm2ServerPrivateKey(String sm2ServerPrivateKey) {
            this.sm2ServerPrivateKey = sm2ServerPrivateKey;
        }

        public String getSm2ClientPublicKey() {
            return sm2ClientPublicKey;
        }

        public void setSm2ClientPublicKey(String sm2ClientPublicKey) {
            this.sm2ClientPublicKey = sm2ClientPublicKey;
        }
    }
}
