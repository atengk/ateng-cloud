package io.github.atengk.cloud.seata.tcc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TCC Fence 内存态自洽仓储实现
 * <p>
 * 依托 {@link ConcurrentHashMap} 实现原子主键判重与 CAS 状态转移，
 * 专职用于 100% 离线测试环境与纯内存轻量防卫场景。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class InMemoryTccFenceRepository implements TccFenceRepository {

    private final Map<String, TccFenceRecord> store = new ConcurrentHashMap<>();

    private String buildKey(String xid, Long branchId) {
        return xid + "#" + branchId;
    }

    @Override
    public Optional<TccFenceRecord> findByXidAndBranchId(String xid, Long branchId) {
        if (xid == null || branchId == null) {
            return Optional.empty();
        }
        TccFenceRecord record = store.get(buildKey(xid, branchId));
        if (record == null) {
            return Optional.empty();
        }
        // 返回防御性副本
        TccFenceRecord copy = new TccFenceRecord();
        copy.setXid(record.getXid());
        copy.setBranchId(record.getBranchId());
        copy.setActionName(record.getActionName());
        copy.setStatus(record.getStatus());
        copy.setGmtCreate(record.getGmtCreate());
        copy.setGmtModified(record.getGmtModified());
        return Optional.of(copy);
    }

    @Override
    public boolean insertRecord(TccFenceRecord record) {
        Objects.requireNonNull(record, "record 必须非空");
        String key = buildKey(record.getXid(), record.getBranchId());

        TccFenceRecord copy = new TccFenceRecord();
        copy.setXid(record.getXid());
        copy.setBranchId(record.getBranchId());
        copy.setActionName(record.getActionName());
        copy.setStatus(record.getStatus());
        copy.setGmtCreate(record.getGmtCreate() != null ? record.getGmtCreate() : LocalDateTime.now());
        copy.setGmtModified(copy.getGmtCreate());

        return store.putIfAbsent(key, copy) == null;
    }

    @Override
    public boolean updateStatus(String xid, Long branchId, TccFenceStatus oldStatus, TccFenceStatus newStatus) {
        if (xid == null || branchId == null || oldStatus == null || newStatus == null) {
            return false;
        }
        String key = buildKey(xid, branchId);
        boolean[] updated = new boolean[1];

        store.computeIfPresent(key, (k, existing) -> {
            if (existing.getStatus() == oldStatus) {
                existing.setStatus(newStatus);
                existing.setGmtModified(LocalDateTime.now());
                updated[0] = true;
            }
            return existing;
        });

        return updated[0];
    }

    @Override
    public boolean deleteRecord(String xid, Long branchId) {
        if (xid == null || branchId == null) {
            return false;
        }
        return store.remove(buildKey(xid, branchId)) != null;
    }

    @Override
    public int deleteExpiredRecords(LocalDateTime beforeTime, List<TccFenceStatus> statuses, int limit) {
        if (beforeTime == null) {
            throw new IllegalArgumentException("beforeTime 必须非空");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        if (statuses == null || statuses.isEmpty()) {
            return 0;
        }
        Set<TccFenceStatus> statusSet = EnumSet.copyOf(statuses);
        List<String> keysToDelete = new ArrayList<>();
        for (Map.Entry<String, TccFenceRecord> entry : store.entrySet()) {
            if (keysToDelete.size() >= limit) {
                break;
            }
            TccFenceRecord record = entry.getValue();
            LocalDateTime recordTime = record.getGmtModified() != null ? record.getGmtModified() : record.getGmtCreate();
            if (recordTime != null && recordTime.isBefore(beforeTime) && statusSet.contains(record.getStatus())) {
                keysToDelete.add(entry.getKey());
            }
        }
        int count = 0;
        for (String key : keysToDelete) {
            if (store.remove(key) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 清空所有内存日志（用于单测重置）
     */
    public void clear() {
        store.clear();
    }
}
