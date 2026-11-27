package io.github.atengk.cloud.gateway.properties;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.sign.enums.GatewaySignAlgorithm;
import io.github.atengk.cloud.gateway.sign.enums.GatewaySignCanonicalMode;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开放接口数字签名与防重放配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengGatewaySignProperties {

    /**
     * 是否启用开放接口签名防篡改与 Nonce 防重放
     */
    private boolean enabled = false;

    /**
     * 待签串规范协议模式（默认 LEGACY 保持既有接入兼容；可选 STANDARD 对齐 ateng-boot-web 6 行规范串）
     */
    private GatewaySignCanonicalMode canonicalMode = GatewaySignCanonicalMode.LEGACY;

    /**
     * 数字签名与验签算法（默认 HMAC_SHA256，可选 RSA、SM2）
     */
    private GatewaySignAlgorithm algorithm = GatewaySignAlgorithm.HMAC_SHA256;

    /**
     * 是否对出站正常响应计算并回写签名响应头（默认 false 保持向后兼容）
     */
    private boolean signResponse = false;

    /**
     * 当开启出站响应加签时，是否同时对 HTTP 状态码 {@code >= 400} 的错误响应加签（默认 false 跳过）
     */
    private boolean signErrorResponse = false;

    /**
     * 单应用缺省应用标识（默认 null，不配置时缺 X-App-Id 头直接返回 401；配置后缺头将回退至该值）
     */
    private String appId;

    /**
     * 是否启用开放接口请求体 (Request Body) SHA-256 摘要加签防篡改（LEGACY 模式下生效；STANDARD 模式默认自动计算写请求摘要）
     */
    private boolean bodyEnabled = false;

    /**
     * 允许加签的最大请求/响应体容量 (默认 2MB 载荷硬防爆)
     */
    private DataSize maxBodySize = DataSize.ofMegabytes(2);

    /**
     * 时间戳允许的最大时钟偏差 (默认 5 分钟)
     */
    private Duration timestampThreshold = Duration.ofMinutes(5);

    /**
     * 内存 Nonce 防重放校验器最大缓存容量上限 (默认 100,000，防止高频随机 Nonce 洪泛攻击引发 OOM)
     */
    private int nonceMaxCapacity = 100_000;

    /**
     * 分布式 Redis Nonce 防重放缓存键前缀（按 {@code <prefix><appId>:<nonce>} 隔离存储）
     */
    private String noncePrefix = "ateng:gateway:sign:nonce:";

    /**
     * Redis Nonce 防重放存储异常时的降级开关（默认 false 严格 Fail-Closed 阻断；设为 true 时降级至有界内存校验）
     */
    private boolean nonceFailOpen = false;

    /**
     * 通用对称签名密钥（HMAC-SHA256 默认密钥）
     */
    private String secretKey;

    /**
     * 通用验签公钥（RSA X.509 或 SM2 Hex/Base64/PEM）
     */
    private String publicKey;

    /**
     * 通用出站加签私钥（RSA PKCS#8 或 SM2 Hex/Base64/PEM）
     */
    private String privateKey;

    /**
     * RSA 专属入站验签公钥（多算法并存时优先使用）
     */
    private String rsaPublicKey;

    /**
     * RSA 专属出站加签私钥（多算法并存时优先使用）
     */
    private String rsaPrivateKey;

    /**
     * 国密 SM2 专属入站验签公钥（多算法并存时优先使用）
     */
    private String sm2PublicKey;

    /**
     * 国密 SM2 专属出站加签私钥（多算法并存时优先使用）
     */
    private String sm2PrivateKey;

    /**
     * 多应用签名密钥映射表（Key 为 {@code appId}，支持为不同开放平台应用独立配置对称/非对称密钥）
     */
    private Map<String, SignAppProperties> apps = new LinkedHashMap<>();

    /**
     * 应用标识请求头名称（默认 X-App-Id）
     */
    private String headerAppId = GatewayConstants.X_APP_ID;

    /**
     * 时间戳请求头/响应头名称（默认 X-Timestamp）
     */
    private String headerTimestamp = GatewayConstants.X_TIMESTAMP;

    /**
     * 随机防重放串请求头/响应头名称（默认 X-Nonce）
     */
    private String headerNonce = GatewayConstants.X_NONCE;

    /**
     * 签名摘要请求头/响应头名称（默认 X-Signature）
     */
    private String headerSignature = GatewayConstants.X_SIGNATURE;

    /**
     * 需进行签名校验的目标路径列表 (支持 AntPath，若为空且未配置则默认对全部请求生效)
     */
    private List<String> includePaths = new ArrayList<>();

    /**
     * 签名校验排除放行路径列表 (支持 AntPath)
     */
    private List<String> excludePaths = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public GatewaySignCanonicalMode getCanonicalMode() {
        return canonicalMode;
    }

    public void setCanonicalMode(GatewaySignCanonicalMode canonicalMode) {
        this.canonicalMode = canonicalMode;
    }

    public GatewaySignAlgorithm getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(GatewaySignAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public boolean isSignResponse() {
        return signResponse;
    }

    public void setSignResponse(boolean signResponse) {
        this.signResponse = signResponse;
    }

    public boolean isSignErrorResponse() {
        return signErrorResponse;
    }

    public void setSignErrorResponse(boolean signErrorResponse) {
        this.signErrorResponse = signErrorResponse;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public boolean isBodyEnabled() {
        return bodyEnabled;
    }

    public void setBodyEnabled(boolean bodyEnabled) {
        this.bodyEnabled = bodyEnabled;
    }

    public DataSize getMaxBodySize() {
        return maxBodySize;
    }

    public void setMaxBodySize(DataSize maxBodySize) {
        this.maxBodySize = maxBodySize;
    }

    public Duration getTimestampThreshold() {
        return timestampThreshold;
    }

    public void setTimestampThreshold(Duration timestampThreshold) {
        this.timestampThreshold = timestampThreshold;
    }

    public int getNonceMaxCapacity() {
        return nonceMaxCapacity;
    }

    public void setNonceMaxCapacity(int nonceMaxCapacity) {
        this.nonceMaxCapacity = nonceMaxCapacity;
    }

    public String getNoncePrefix() {
        return noncePrefix;
    }

    public void setNoncePrefix(String noncePrefix) {
        this.noncePrefix = noncePrefix;
    }

    public boolean isNonceFailOpen() {
        return nonceFailOpen;
    }

    public void setNonceFailOpen(boolean nonceFailOpen) {
        this.nonceFailOpen = nonceFailOpen;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getRsaPublicKey() {
        return rsaPublicKey;
    }

    public void setRsaPublicKey(String rsaPublicKey) {
        this.rsaPublicKey = rsaPublicKey;
    }

    public String getRsaPrivateKey() {
        return rsaPrivateKey;
    }

    public void setRsaPrivateKey(String rsaPrivateKey) {
        this.rsaPrivateKey = rsaPrivateKey;
    }

    public String getSm2PublicKey() {
        return sm2PublicKey;
    }

    public void setSm2PublicKey(String sm2PublicKey) {
        this.sm2PublicKey = sm2PublicKey;
    }

    public String getSm2PrivateKey() {
        return sm2PrivateKey;
    }

    public void setSm2PrivateKey(String sm2PrivateKey) {
        this.sm2PrivateKey = sm2PrivateKey;
    }

    public Map<String, SignAppProperties> getApps() {
        return apps;
    }

    public void setApps(Map<String, SignAppProperties> apps) {
        this.apps = apps;
    }

    public String getHeaderAppId() {
        return headerAppId;
    }

    public void setHeaderAppId(String headerAppId) {
        this.headerAppId = headerAppId;
    }

    public String getHeaderTimestamp() {
        return headerTimestamp;
    }

    public void setHeaderTimestamp(String headerTimestamp) {
        this.headerTimestamp = headerTimestamp;
    }

    public String getHeaderNonce() {
        return headerNonce;
    }

    public void setHeaderNonce(String headerNonce) {
        this.headerNonce = headerNonce;
    }

    public String getHeaderSignature() {
        return headerSignature;
    }

    public void setHeaderSignature(String headerSignature) {
        this.headerSignature = headerSignature;
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

    /**
     * 单开放应用多算法签名密钥条目配置
     */
    public static class SignAppProperties {

        /**
         * 应用专属对称签名密钥（HMAC-SHA256）
         */
        private String secretKey;

        /**
         * 应用专属通用验签公钥
         */
        private String publicKey;

        /**
         * 应用专属通用加签私钥
         */
        private String privateKey;

        /**
         * 应用专属 RSA 验签公钥
         */
        private String rsaPublicKey;

        /**
         * 应用专属 RSA 加签私钥
         */
        private String rsaPrivateKey;

        /**
         * 应用专属 SM2 验签公钥
         */
        private String sm2PublicKey;

        /**
         * 应用专属 SM2 加签私钥
         */
        private String sm2PrivateKey;

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }

        public String getRsaPublicKey() {
            return rsaPublicKey;
        }

        public void setRsaPublicKey(String rsaPublicKey) {
            this.rsaPublicKey = rsaPublicKey;
        }

        public String getRsaPrivateKey() {
            return rsaPrivateKey;
        }

        public void setRsaPrivateKey(String rsaPrivateKey) {
            this.rsaPrivateKey = rsaPrivateKey;
        }

        public String getSm2PublicKey() {
            return sm2PublicKey;
        }

        public void setSm2PublicKey(String sm2PublicKey) {
            this.sm2PublicKey = sm2PublicKey;
        }

        public String getSm2PrivateKey() {
            return sm2PrivateKey;
        }

        public void setSm2PrivateKey(String sm2PrivateKey) {
            this.sm2PrivateKey = sm2PrivateKey;
        }
    }
}
