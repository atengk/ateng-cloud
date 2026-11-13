package io.github.atengk.cloud.sample.seata.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import org.springframework.stereotype.Service;

/**
 * 声明式 @DS 从库切换演示服务
 *
 * @author Ateng
 * @since 2026-11-13
 */
@Service
public class SeataSlaveDatasourceService {

    /**
     * 声明式切换至 slave 数据源执行读取或写入操作
     *
     * @return 目标数据源标识
     */
    @DS("slave")
    public String executeOnSlave() {
        return "slave";
    }
}
