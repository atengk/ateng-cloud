package io.github.atengk.cloud.sample.feign.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.feign.client.ProductFeignClient;
import io.github.atengk.cloud.sample.feign.model.OrderCreateDTO;
import io.github.atengk.cloud.sample.feign.model.OrderDetailVO;
import io.github.atengk.cloud.sample.feign.model.ProductDetailVO;
import io.github.atengk.cloud.sample.feign.model.ProductQueryDTO;
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

    public FeignOrderConsumerController(ProductFeignClient productFeignClient) {
        this.productFeignClient = productFeignClient;
    }

    /**
     * 1. 声明式查商品详情：演示 @AdaptiveUnwrap 客户端自动脱壳剥离 Result 外壳
     *
     * @param id 商品 ID
     * @return 业务强类型结果包装
     */
    @GetMapping("/product/{id}")
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
}
