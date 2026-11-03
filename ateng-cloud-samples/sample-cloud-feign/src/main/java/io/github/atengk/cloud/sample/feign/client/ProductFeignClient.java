package io.github.atengk.cloud.sample.feign.client;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.feign.annotation.AdaptiveUnwrap;
import io.github.atengk.cloud.sample.feign.model.OrderCreateDTO;
import io.github.atengk.cloud.sample.feign.model.OrderDetailVO;
import io.github.atengk.cloud.sample.feign.model.ProductDetailVO;
import io.github.atengk.cloud.sample.feign.model.ProductQueryDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 商品中心远程调用 Feign 客户端
 * 演示双模自适应契约解包、原生报文透传、GET POJO 参数平铺、文件流式无损传输及上下文穿透
 *
 * @author Ateng
 * @since 2026-11-03
 */
@FeignClient(name = "product-client", url = "http://localhost:${test.server.port:8080}")
public interface ProductFeignClient {

    /**
     * 1. 自动剥壳：方法声明返回具体业务对象 ProductDetailVO，底层自动剥壳
     */
    @GetMapping("/sample/cloud/feign/remote/product/{id}")
    ProductDetailVO getProductById(@PathVariable("id") Long id);

    /**
     * 2. 原生透传：方法显式声明返回 Result&lt;ProductDetailVO&gt;，原样透传
     */
    @GetMapping("/sample/cloud/feign/remote/product/raw/{id}")
    Result<ProductDetailVO> getRawProductById(@PathVariable("id") Long id);

    /**
     * 3. 规避解包：标注 @AdaptiveUnwrap(false) 禁用解包，直接反序列化为实体
     */
    @GetMapping("/sample/cloud/feign/remote/product/third-party/{id}")
    @AdaptiveUnwrap(false)
    ProductDetailVO getThirdPartyProduct(@PathVariable("id") Long id);

    /**
     * 4. GET POJO 查询：通过 SpringQueryMap 自动展开为 URL Query 参数
     */
    @GetMapping("/sample/cloud/feign/remote/product/query")
    ProductQueryDTO queryProducts(@SpringQueryMap ProductQueryDTO query);

    /**
     * 5. 二进制流下载：声明 byte[] 触发流式透传白名单，无损零解析
     */
    @GetMapping(value = "/sample/cloud/feign/remote/product/invoice/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    byte[] downloadInvoice();

    /**
     * 6. Multipart 附件上传：表单与文件混合传输
     */
    @PostMapping(value = "/sample/cloud/feign/remote/product/attachment/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Result<String> uploadAttachment(@RequestPart("file") MultipartFile file,
                                    @RequestParam("description") String description);

    /**
     * 7. 提交创建订单：POST 写请求，出站自动透明透传四维上下文与 Same-Token
     */
    @PostMapping("/sample/cloud/feign/remote/product/create")
    Result<OrderDetailVO> createOrder(@RequestBody OrderCreateDTO dto);

    /**
     * 8. 业务报错：对端 HTTP 200 但 code=5001，自动转译为 BusinessException
     */
    @GetMapping("/sample/cloud/feign/remote/product/error/business")
    ProductDetailVO getBusinessError();

    /**
     * 9. void 正常返回：对端返回 code=200 正常执行返回
     */
    @PostMapping("/sample/cloud/feign/remote/product/void/success")
    void voidSuccess();

    /**
     * 10. void 报错拦截：对端返回 code=5002 触发 BusinessException 阻断
     */
    @PostMapping("/sample/cloud/feign/remote/product/void/error")
    void voidError();

    /**
     * 11. HTTP 500 异常：底层网络与状态码异常转译为 BusinessException 且保留 cause
     */
    @GetMapping("/sample/cloud/feign/remote/product/error/http-500")
    ProductDetailVO getHttp500();

    /**
     * 12. 原生基本类型 null 安全：方法返回原生基本类型 long，对端返回 null 触发 BusinessException
     */
    @GetMapping("/sample/cloud/feign/remote/product/null-data")
    long getPrimitiveNullData();

    /**
     * 13. 包装类型 null 接收：方法返回包装类型 Long，对端返回 null 正常返回 null
     */
    @GetMapping("/sample/cloud/feign/remote/product/null-data")
    Long getWrapperNullData();

    /**
     * 14. HTTP 204 基本类型防卫：方法返回原生基本类型 boolean，对端返回 204 触发 BusinessException
     */
    @GetMapping("/sample/cloud/feign/remote/product/http-204")
    boolean getPrimitive204();
}
