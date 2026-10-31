package io.github.atengk.cloud.seata.tcc;

/**
 * TCC 防卫日志清理执行结果
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class TccFenceCleanResult {

    private final int terminalDeleted;
    private final int suspendedDeleted;
    private final int totalDeleted;

    public TccFenceCleanResult(int terminalDeleted, int suspendedDeleted) {
        this.terminalDeleted = terminalDeleted;
        this.suspendedDeleted = suspendedDeleted;
        this.totalDeleted = terminalDeleted + suspendedDeleted;
    }

    public int getTerminalDeleted() {
        return terminalDeleted;
    }

    public int getSuspendedDeleted() {
        return suspendedDeleted;
    }

    public int getTotalDeleted() {
        return totalDeleted;
    }

    @Override
    public String toString() {
        return "TccFenceCleanResult{" +
                "terminalDeleted=" + terminalDeleted +
                ", suspendedDeleted=" + suspendedDeleted +
                ", totalDeleted=" + totalDeleted +
                '}';
    }
}
