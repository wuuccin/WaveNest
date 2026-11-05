package com.waterlogistics.dispatch.rendezvous;

import com.waterlogistics.contract.ws.RendezvousFrame;

/** 已提交的会面方案投影；由 M2 在派发结果落库时发布。 */
public record RendezvousPlanCommittedEvent(RendezvousFrame frame) {

    public RendezvousPlanCommittedEvent {
        if (frame == null) {
            throw new IllegalArgumentException("rendezvous frame is required");
        }
    }
}
