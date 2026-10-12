package com.hidewnd.winds.jx3.model;

import java.time.Instant;

/** 每个网关独立确认状态；未知结果打断候选序列，入库成功后才提交变化。 */
public class ServerStateTracker {
    private String confirmedStatus;
    private ProbeStatus candidate;
    private Instant firstObservedAt;
    private ProbeStatus lastProbeStatus;

    public ServerTransition observe(ProbeStatus result, Instant now) {
        lastProbeStatus = result;
        if (result == ProbeStatus.UNKNOWN) {
            candidate = null;
            return null;
        }
        if (candidate != result) {
            candidate = result;
            firstObservedAt = now;
            return null;
        }
        String status = result == ProbeStatus.REACHABLE ? "reachable" : "unreachable";
        if (confirmedStatus == null) {
            confirmedStatus = status;
            return null;
        }
        return status.equals(confirmedStatus)
                ? null
                : new ServerTransition(status, firstObservedAt);
    }

    public String getConfirmedStatus() {
        return confirmedStatus;
    }

    public ProbeStatus getLastProbeStatus() {
        return lastProbeStatus;
    }

    public void confirm(String status) {
        confirmedStatus = status;
    }
}
