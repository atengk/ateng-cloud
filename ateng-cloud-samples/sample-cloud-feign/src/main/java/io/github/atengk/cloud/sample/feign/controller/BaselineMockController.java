package io.github.atengk.cloud.sample.feign.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 基础连通性 Mock 控制器
 *
 * @author Ateng
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/api/mock")
public class BaselineMockController {

    @GetMapping("/echo")
    public String echo(@RequestParam("msg") String msg) {
        return "Echo: " + msg;
    }
}
