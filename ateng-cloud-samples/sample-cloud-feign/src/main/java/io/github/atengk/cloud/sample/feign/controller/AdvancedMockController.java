package io.github.atengk.cloud.sample.feign.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.feign.model.UserQuery;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 生产深水区特性 Mock 控制器
 * 覆盖二进制流下载、Multipart 文件上传、GET POJO 查询平铺及超大载荷测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/mock/advanced")
public class AdvancedMockController {

    @GetMapping(value = "/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] download() {
        return new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<String> upload(@RequestPart("file") MultipartFile file,
                                 @RequestParam("description") String description) throws IOException {
        String summary = String.format("filename=%s;size=%d;description=%s;content=%s",
                file.getOriginalFilename(),
                file.getSize(),
                description,
                new String(file.getBytes()));
        return Result.success(summary);
    }

    @GetMapping("/query")
    public Result<UserQuery> query(@ModelAttribute UserQuery query) {
        return Result.success(query);
    }

    @GetMapping("/large-payload")
    public Result<String> largePayload() {
        // 返回约 100KB 的超大文本响应
        String largeText = "A".repeat(100 * 1024);
        return Result.success(largeText);
    }
}
