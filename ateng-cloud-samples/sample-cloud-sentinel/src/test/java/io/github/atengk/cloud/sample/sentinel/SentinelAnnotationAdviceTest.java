package io.github.atengk.cloud.sample.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowItem;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @SentinelResource} 未配置 blockHandler 时切面全局兜底集成测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootTest
@AutoConfigureMockMvc
class SentinelAnnotationAdviceTest {

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void tearDown() {
        // 清理规则，遵守上下文零污染铁律
        FlowRuleManager.loadRules(Collections.emptyList());
        ParamFlowRuleManager.loadRules(Collections.emptyList());
    }

    @Test
    @DisplayName("无 blockHandler 的 @SentinelResource 触发限流：SentinelBlockExceptionAdvice 兜底返回 HTTP 429 与 FLOW_LIMIT(4201)")
    void testAnnotationResourceFlowLimitBlocked() throws Exception {
        String resourceName = "custom-annotation-resource";
        FlowRule rule = new FlowRule(resourceName);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(1);
        FlowRuleManager.loadRules(List.of(rule));

        // 1. 第一次请求放行
        mockMvc.perform(get("/api/test/annotation-resource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("annotation-resource-pass"));

        // 2. 第二次请求超限，验证切面捕获逃逸异常并映射为 429
        mockMvc.perform(get("/api/test/annotation-resource"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(SentinelResultCode.FLOW_LIMIT.getCode()))
                .andExpect(jsonPath("$.msg").value(SentinelResultCode.FLOW_LIMIT.getMsg()));
    }

    @Test
    @DisplayName("无 blockHandler 的 @SentinelResource 触发热点限流：切面捕获并返回 HTTP 429 与 PARAM_FLOW_LIMIT(4203)")
    void testParamFlowBlocked() throws Exception {
        String resourceName = "custom-param-resource";
        ParamFlowRule rule = new ParamFlowRule(resourceName);
        rule.setParamIdx(0);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(100); // 普通参数阈值较高

        // 配置指定热点参数 item-hot 阈值为 0 (立即阻断)
        ParamFlowItem item = new ParamFlowItem()
                .setObject("item-hot")
                .setClassType(String.class.getName())
                .setCount(0);
        rule.setParamFlowItemList(List.of(item));

        ParamFlowRuleManager.loadRules(List.of(rule));

        // 1. 普通参数正常通行
        mockMvc.perform(get("/api/test/param-flow").param("itemId", "item-normal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("item-item-normal"));

        // 2. 触发热点参数限流，验证返回 429 与 4203
        mockMvc.perform(get("/api/test/param-flow").param("itemId", "item-hot"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(SentinelResultCode.PARAM_FLOW_LIMIT.getCode()))
                .andExpect(jsonPath("$.msg").value(SentinelResultCode.PARAM_FLOW_LIMIT.getMsg()));
    }
}
