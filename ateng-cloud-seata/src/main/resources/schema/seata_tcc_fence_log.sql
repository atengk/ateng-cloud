-- -----------------------------------------------------------------------------
-- Seata TCC 防卫日志表 (ANSI-SQL 标准 DDL 模板)
-- 涵盖: MySQL, PostgreSQL, Oracle, H2
-- @author Ateng
-- @since 2026-10-31
-- -----------------------------------------------------------------------------

-- =============================================================================
-- 1. MySQL 5.7+ / 8.0+ 生产推荐脚本
-- =============================================================================
CREATE TABLE IF NOT EXISTS `tcc_fence_log` (
    `xid` VARCHAR(128) NOT NULL COMMENT '全局事务唯一标识 XID',
    `branch_id` BIGINT NOT NULL COMMENT '分支事务唯一标识 branchId',
    `action_name` VARCHAR(64) NOT NULL COMMENT 'TCC 两阶段业务动作名称',
    `status` INT NOT NULL COMMENT '防卫状态: 1-TRIED, 2-COMMITTED, 3-ROLLBACKED, 4-SUSPENDED',
    `gmt_create` DATETIME(3) NOT NULL COMMENT '创建时间',
    `gmt_modified` DATETIME(3) NOT NULL COMMENT '最后更新时间',
    PRIMARY KEY (`xid`, `branch_id`),
    KEY `idx_tcc_fence_gmt_modified` (`gmt_modified`),
    KEY `idx_tcc_fence_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Seata TCC 防悬挂与幂等业务防卫日志表';

-- =============================================================================
-- 2. PostgreSQL 生产推荐脚本
-- =============================================================================
-- CREATE TABLE IF NOT EXISTS tcc_fence_log (
--     xid VARCHAR(128) NOT NULL,
--     branch_id BIGINT NOT NULL,
--     action_name VARCHAR(64) NOT NULL,
--     status INT NOT NULL,
--     gmt_create TIMESTAMP NOT NULL,
--     gmt_modified TIMESTAMP NOT NULL,
--     PRIMARY KEY (xid, branch_id)
-- );
-- CREATE INDEX IF NOT EXISTS idx_tcc_fence_gmt_modified ON tcc_fence_log (gmt_modified);
-- CREATE INDEX IF NOT EXISTS idx_tcc_fence_status ON tcc_fence_log (status);

-- =============================================================================
-- 3. Oracle 生产推荐脚本
-- =============================================================================
-- CREATE TABLE tcc_fence_log (
--     xid VARCHAR2(128) NOT NULL,
--     branch_id NUMBER(19) NOT NULL,
--     action_name VARCHAR2(64) NOT NULL,
--     status NUMBER(4) NOT NULL,
--     gmt_create TIMESTAMP NOT NULL,
--     gmt_modified TIMESTAMP NOT NULL,
--     CONSTRAINT pk_tcc_fence_log PRIMARY KEY (xid, branch_id)
-- );
-- CREATE INDEX idx_tcc_fence_gmt_modified ON tcc_fence_log (gmt_modified);
-- CREATE INDEX idx_tcc_fence_status ON tcc_fence_log (status);

-- =============================================================================
-- 4. ANSI-SQL / H2 内存库通用标准脚本 (开箱即用沙箱自愈模板)
-- =============================================================================
-- CREATE TABLE IF NOT EXISTS tcc_fence_log (
--     xid VARCHAR(128) NOT NULL,
--     branch_id BIGINT NOT NULL,
--     action_name VARCHAR(64) NOT NULL,
--     status INT NOT NULL,
--     gmt_create TIMESTAMP NOT NULL,
--     gmt_modified TIMESTAMP NOT NULL,
--     PRIMARY KEY (xid, branch_id)
-- );
