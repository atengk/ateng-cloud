package io.github.atengk.cloud.sample.feign.controller;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.feign.client.ClassLevelExcludedProductFeignClient;
import io.github.atengk.cloud.sample.feign.client.ExcludedProductFeignClient;
import io.github.atengk.cloud.sample.feign.client.ForcedAdaptiveProductFeignClient;
import io.github.atengk.cloud.sample.feign.client.ProductFeignClient;
import io.github.atengk.cloud.sample.feign.model.OrderCreateDTO;
import io.github.atengk.cloud.sample.feign.model.OrderDetailVO;
import io.github.atengk.cloud.sample.feign.model.ProductDetailVO;
import io.github.atengk.cloud.sample.feign.model.ProductQueryDTO;
import io.github.atengk.cloud.sample.feign.model.ToleranceResultVO;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 电商订单中心消费者控制器
 * 对外暴露 RESTful 体验接口，模拟微服务间声明式 RPC 调用、四维上下文透传、流式通信与自适应解包
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/feign/order")
public class FeignOrderConsumerController {

    private final ProductFeignClient productFeignClient;
    private final ExcludedProductFeignClient excludedProductFeignClient;
    private final ClassLevelExcludedProductFeignClient classLevelExcludedProductFeignClient;
    private final ForcedAdaptiveProductFeignClient forcedAdaptiveProductFeignClient;

    public FeignOrderConsumerController(ProductFeignClient productFeignClient,
                                        ExcludedProductFeignClient excludedProductFeignClient,
                                        ClassLevelExcludedProductFeignClient classLevelExcludedProductFeignClient,
                                        ForcedAdaptiveProductFeignClient forcedAdaptiveProductFeignClient) {
        this.productFeignClient = productFeignClient;
        this.excludedProductFeignClient = excludedProductFeignClient;
        this.classLevelExcludedProductFeignClient = classLevelExcludedProductFeignClient;
        this.forcedAdaptiveProductFeignClient = forcedAdaptiveProductFeignClient;
    }

    /**
     * 1. 声明式查商品详情：演示 @AdaptiveUnwrap 客户端自适应解包并提取 Result 业务数据
     *
     * @param id 商品 ID
     * @return 业务强类型结果包装
     */
    @GetMapping({"/product/{id}", "/product/unwrap/{id}"})
    public Result<ProductDetailVO> getProductById(@PathVariable("id") Long id) {
        ProductDetailVO product = productFeignClient.getProductById(id);
        return Result.success(product);
    }

    /**
     * 2. 声明式查商品原生契约：演示显式声明 Result&lt;ProductDetailVO&gt; 时原样透传
     *
     * @param id 商品 ID
     * @return 原始 Result 包装
     */
    @GetMapping("/product/raw/{id}")
    public Result<ProductDetailVO> getRawProductById(@PathVariable("id") Long id) {
        return productFeignClient.getRawProductById(id);
    }

    /**
     * 3. 复杂条件商品检索：演示 BeanQueryMapEncoder 将 GET POJO 自动平铺为 URL Query 参数
     *
     * @param query 查询对象
     * @return 查询参数回显
     */
    @GetMapping("/product/query")
    public Result<ProductQueryDTO> queryProducts(ProductQueryDTO query) {
        ProductQueryDTO result = productFeignClient.queryProducts(query);
        return Result.success(result);
    }

    /**
     * 4. 电子发票无损流式下载：演示二进制 byte[] 触发 StreamPassthrough 白名单
     *
     * @return 二进制文件流
     */
    @GetMapping(value = "/product/invoice/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> downloadInvoice() {
        byte[] bytes = productFeignClient.downloadInvoice();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoice.pdf\"")
                .body(bytes);
    }

    /**
     * 5. 订单附件上传：演示 MultipartFile 与表单参数混合透传
     *
     * @param file        文件对象
     * @param description 描述信息
     * @return 上传结果摘要
     */
    @PostMapping(value = "/product/attachment/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<String> uploadAttachment(@RequestPart("file") MultipartFile file,
                                           @RequestParam("description") String description) {
        return productFeignClient.uploadAttachment(file, description);
    }

    /**
     * 6. 提交创建订单：演示出站请求透明透传四维上下文 (traceId/userId/tenantId/ignoreTenant)、灰度标签与 Same-Token
     *
     * @param dto 订单创建入参
     * @return 订单详情
     */
    @PostMapping("/create")
    public Result<OrderDetailVO> createOrder(@Valid @RequestBody OrderCreateDTO dto) {
        return productFeignClient.createOrder(dto);
    }

    /**
     * 7. 全局配置排除剥壳：演示命中 exclude-clients 白名单时跳过剥壳直接反序列化实体
     *
     * @param id 商品 ID
     * @return 第三方未封装商品实体包装
     */
    @GetMapping("/product/excluded-unwrap/{id}")
    public Result<ProductDetailVO> getExcludedClientProduct(@PathVariable("id") Long id) {
        return Result.success(excludedProductFeignClient.getThirdPartyProduct(id));
    }

    /**
     * 8. 类注解 {@code @AdaptiveUnwrap(false)} 豁免剥壳：整接口跳过 Result 外壳校验
     *
     * @param id 商品 ID
     * @return 第三方未封装商品实体包装
     */
    @GetMapping("/product/class-excluded/{id}")
    public Result<ProductDetailVO> getClassLevelExcludedProduct(@PathVariable("id") Long id) {
        return Result.success(classLevelExcludedProductFeignClient.getThirdPartyProduct(id));
    }

    /**
     * 9. 方法注解 {@code @AdaptiveUnwrap(false)} 精确豁免剥壳：单个非标接口跳过解包
     *
     * @param id 商品 ID
     * @return 第三方未封装商品实体包装
     */
    @GetMapping("/product/method-excluded/{id}")
    public Result<ProductDetailVO> getMethodExcludedProduct(@PathVariable("id") Long id) {
        return Result.success(productFeignClient.getThirdPartyProduct(id));
    }

    /**
     * 10. 类注解 {@code @AdaptiveUnwrap(true)} 强制覆盖 exclude-clients 白名单执行剥壳
     *
     * @param id 商品 ID
     * @return 解包后的商品实体包装
     */
    @GetMapping("/product/forced-unwrap/{id}")
    public Result<ProductDetailVO> getForcedUnwrapProduct(@PathVariable("id") Long id) {
        return Result.success(forcedAdaptiveProductFeignClient.getProductById(id));
    }

    /**
     * 11. 原生基本类型 Null Fail-Fast 防卫：演示接口声明返回 long 但服务端返回 null 时前置拦截防裸拆箱 NPE
     *
     * @return 防御结果摘要
     */
    @GetMapping("/product/primitive-null-guard")
    public Result<ToleranceResultVO> testPrimitiveNullGuard() {
        long start = System.currentTimeMillis();
        try {
            productFeignClient.getPrimitiveNullData();
            return Result.success(new ToleranceResultVO(
                    "Primitive-Return-Null-FailFast", 1, System.currentTimeMillis() - start,
                    200, "未触发基本类型 null 防卫拦截", null));
        } catch (BusinessException ex) {
            return Result.success(new ToleranceResultVO(
                    "Primitive-Return-Null-FailFast", 1, System.currentTimeMillis() - start,
                    ex.getCode(), "已前置 Fail-Fast 阻断原生基本类型 null 拆箱 NPE: " + ex.getMsg(),
                    ex.getClass().getSimpleName()));
        }
    }

    /**
     * 12. 包装类型 Null 安全接收：演示接口声明返回 Long 且对端 data 为 null 时平滑返回 null
     *
     * @return 商品库存包装值
     */
    @GetMapping("/product/wrapper-null")
    public Result<Long> getWrapperNullData() {
        return Result.success(productFeignClient.getWrapperNullData());
    }

    /**
     * 13. void 返回体契约状态码校验：对端返回 code=200 正常放行
     *
     * @return 校验成功状态
     */
    @PostMapping("/product/void-success")
    public Result<String> testVoidSuccess() {
        productFeignClient.voidSuccess();
        return Result.success("void 接口业务状态码校验通过");
    }
}
