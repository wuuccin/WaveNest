package com.waterlogistics.dispatch.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.waterlogistics.contract.mq.DeliveryReceipt;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import org.junit.jupiter.api.Test;

class DeliveryReceiptListenerTest {

    @Test
    void successReceipt_shouldAdvanceHandoverOnce_andDuplicateIsIgnored() {
        StubQueue queue = new StubQueue();
        DeliveryReceiptListener listener = new DeliveryReceiptListener(queue);
        DeliveryReceipt receipt = new DeliveryReceipt("T1", "ORD-1", "UAV-01", 1000L, "success");

        listener.onReceipt(receipt);
        listener.onReceipt(receipt);

        assertEquals(1, queue.calls, "重复回执不得重复推进");
    }

    @Test
    void failureReceipt_shouldNotAdvanceHandover() {
        StubQueue queue = new StubQueue();
        DeliveryReceiptListener listener = new DeliveryReceiptListener(queue);

        listener.onReceipt(new DeliveryReceipt("T1", "ORD-1", "UAV-01", 1000L, "failure"));

        assertEquals(0, queue.calls, "投放失败不推进返航，留给时间兜底");
    }

    private static final class StubQueue extends TaskQueueService {

        private int calls;

        StubQueue() {
            super(null, null, null);
        }

        @Override
        public void markHandoverDone(String orderId) {
            calls++;
        }
    }
}
