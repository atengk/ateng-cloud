package io.github.atengk.cloud.sample.feign.client;

import io.github.atengk.cloud.sample.feign.model.UserQuery;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/**
 * 生产深水区高级特性验证客户端
 * 覆盖二进制流下载、Multipart 单/多文件上传与 SpringQueryMap 自动参数平铺
 *
 * @author Ateng
 * @since 2026-10-28
 */
@FeignClient(name = "advanced-client", url = "http://localhost:${test.server.port}")
public interface AdvancedFeignClient {

    @GetMapping(value = "/mock/advanced/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    byte[] download();

    @PostMapping(value = "/mock/advanced/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    String upload(@RequestPart("file") MultipartFile file,
                  @RequestParam("description") String description);

    @GetMapping("/mock/advanced/query")
    UserQuery query(@SpringQueryMap UserQuery query);

    @GetMapping("/mock/advanced/large-payload")
    String largePayload();
}
