-- 订单表
CREATE TABLE IF NOT EXISTS order_tbl (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    commodity_code VARCHAR(64) NOT NULL,
    count INT NOT NULL,
    money INT NOT NULL
);

-- 库存表
CREATE TABLE IF NOT EXISTS storage_tbl (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    commodity_code VARCHAR(64) NOT NULL UNIQUE,
    count INT NOT NULL
);

-- 账户表
CREATE TABLE IF NOT EXISTS account_tbl (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL UNIQUE,
    money INT NOT NULL
);

-- Seata TCC Fence 防卫日志表
CREATE TABLE IF NOT EXISTS tcc_fence_log (
    xid VARCHAR(128) NOT NULL,
    branch_id BIGINT NOT NULL,
    action_name VARCHAR(64) NOT NULL,
    status INT NOT NULL,
    gmt_create TIMESTAMP NOT NULL,
    gmt_modified TIMESTAMP NOT NULL,
    PRIMARY KEY (xid, branch_id)
);

-- Seata AT 模式 undo_log 回滚日志表
CREATE TABLE IF NOT EXISTS undo_log (
    branch_id BIGINT NOT NULL,
    xid VARCHAR(128) NOT NULL,
    context VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB NOT NULL,
    log_status INT NOT NULL,
    log_created TIMESTAMP NOT NULL,
    log_modified TIMESTAMP NOT NULL,
    PRIMARY KEY (branch_id, xid)
);
