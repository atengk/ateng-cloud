package io.github.atengk.cloud.sample.feign.controller;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.cloud.feign.constant.FeignHeaderConstants;
import io.github.atengk.cloud.feign.support.FeignTrafficPropagationSupport;
import org.slf4j.MDC;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.feign.model.OrderCreateDTO;
import io.github.atengk.cloud.sample.feign.model.OrderDetailVO;
import io.github.atengk.cloud.sample.feign.model.ProductDetailVO;
import io.github.atengk.cloud.sample.feign.model.ProductQueryDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 远程商品中心微服务提供者（对端模拟服务）
 * 供 ProductFeignClient 进行声明式远程调用与全特性验证，实现 100% 离线自洽闭环
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/feign/remote/product")
public class RemoteProductProviderController {

    @GetMapping("/{id}")
    public Result<ProductDetailVO> getProductById(@PathVariable("id") Long id) {
        ProductDetailVO product = new ProductDetailVO(
                id,
                "SKU-" + id,
                "微服务演示商品-" + id,
                new BigDecimal("999.00"),
                100,
                1
        );
        return Result.success(product);
    }

    @GetMapping("/raw/{id}")
    public Result<ProductDetailVO> getRawProductById(@PathVariable("id") Long id) {
        ProductDetailVO product = new ProductDetailVO(
                id,
                "RAW-SKU-" + id,
                "原生报文商品-" + id,
                new BigDecimal("1999.00"),
                50,
                1
        );
        return Result.success(product);
    }

    @GetMapping("/third-party/{id}")
    public ProductDetailVO getThirdPartyProduct(@PathVariable("id") Long id) {
        return new ProductDetailVO(
                id,
                "THIRD-PARTY-SKU",
                "第三方未封装商品",
                new BigDecimal("888.00"),
                200,
                1
        );
    }

    @GetMapping("/query")
    public Result<ProductQueryDTO> queryProducts(ProductQueryDTO query) {
        return Result.success(query);
    }

    @GetMapping(value = "/invoice/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] downloadInvoice() {
        return "INVOICE-PDF-BINARY-DATA-SAMPLE-2026".getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(value = "/attachment/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<String> uploadAttachment(@RequestPart("file") MultipartFile file,
                                           @RequestParam("description") String description) throws IOException {
        String summary = String.format("filename=%s;size=%d;description=%s;content=%s",
                file.getOriginalFilename(),
                file.getSize(),
                description,
                new String(file.getBytes(), StandardCharsets.UTF_8));
        return Result.success(summary);
    }

    @PostMapping("/create")
    public Result<OrderDetailVO> createOrder(HttpServletRequest request,
                                            @RequestBody OrderCreateDTO dto) {
        String traceId = MDC.get(FeignHeaderConstants.MDC_TRACE_ID);
        String userId = UserContextHolder.getUserId();
        String tenantId = TenantContextHolder.getTenantId();
        FeignTrafficPropagationSupport.TrafficSnapshot trafficSnapshot = FeignTrafficPropagationSupport.getSnapshot();
        String trafficTag = trafficSnapshot != null ? trafficSnapshot.tag() : MDC.get(FeignHeaderConstants.TRAFFIC_TAG);
        String sameToken = request.getHeader(FeignHeaderConstants.SAME_TOKEN);

        ProductDetailVO product = new ProductDetailVO(
                dto.getProductId(),
                "SKU-" + dto.getProductId(),
                "微服务下单商品",
                new BigDecimal("299.00"),
                99,
                1
        );

        BigDecimal totalAmount = product.getPrice().multiply(BigDecimal.valueOf(dto.getQuantity()));

        OrderDetailVO order = new OrderDetailVO(
                "ORD-" + System.currentTimeMillis(),
                userId != null ? userId : "anonymous-user",
                tenantId != null ? tenantId : "default-tenant",
                product,
                dto.getQuantity(),
                totalAmount,
                traceId,
                trafficTag,
                sameToken,
                LocalDateTime.now()
        );

        return Result.success(order);
    }

    @GetMapping("/error/business")
    public Result<ProductDetailVO> getBusinessError() {
        return Result.failure(5001, "商品库存不足，无法锁定库存");
    }

    @PostMapping("/void/success")
    public Result<Void> voidSuccess() {
        return Result.success();
    }

    @PostMapping("/void/error")
    public Result<Void> voidError() {
        return Result.failure(5002, "无权删除该商品");
    }

    @GetMapping("/error/http-500")
    public ResponseEntity<Result<ProductDetailVO>> getHttp500() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(500, "商品中心内部服务崩溃"));
    }

    @GetMapping("/null-data")
    public Result<ProductDetailVO> getNullData() {
        return Result.success(null);
    }

    @GetMapping("/http-204")
    public ResponseEntity<Void> getHttp204() {
        return ResponseEntity.noContent().build();
    }
}
