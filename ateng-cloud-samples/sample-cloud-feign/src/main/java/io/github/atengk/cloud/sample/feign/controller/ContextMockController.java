package io.github.atengk.cloud.sample.feign.controller;

import io.github.atengk.boot.core.model.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * 上下文与标头反射自验 Mock 控制器
 *
 * @author Ateng
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/mock/context")
public class ContextMockController {

    @GetMapping("/echo-headers")
    public Result<Map<String, String>> echoHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                headers.put(name.toLowerCase(), request.getHeader(name));
            }
        }
        return Result.success(headers);
    }
}
