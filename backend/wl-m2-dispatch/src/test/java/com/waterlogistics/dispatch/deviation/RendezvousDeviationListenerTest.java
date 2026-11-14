package com.waterlogistics.dispatch.deviation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.waterlogistics.contract.mq.RendezvousDeviationMessage;
import com.waterlogistics.contract.rest.GeoPoint;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 会面偏差回流消费方的分层聚合与幂等。 */
class RendezvousDeviationListenerTest {

    private static final long T0 = 1_790_000_000_000L;

    @Test
    void onDeviation_shouldAggregateByFourLayersAndIgnoreDuplicates() {
        RendezvousDeviationStats stats = new RendezvousDeviationStats();
        RendezvousDeviationListener listener = new RendezvousDeviationListener(stats);

        listener.onDeviation(message("S-1", "UAV-A", 100.0, 2.0));
        listener.onDeviation(message("S-1", "UAV-A", 100.0, 2.0));
        listener.onDeviation(message("S-2", "UAV-B", 300.0, 4.0));
        listener.onDeviation(message("S-3", "UAV-C", 200.0, 3.0));

        assertEquals(3L, stats.sampleCount(), "同一 sampleId 只计一次");
        List<RendezvousDeviationStats.Bucket> buckets = stats.snapshot();
        assertEquals(1, buckets.size(), "四层取值相同应聚成一层");
        RendezvousDeviationStats.Bucket bucket = buckets.get(0);
        assertEquals(3L, bucket.samples());
        assertEquals(200.0, bucket.meanSpaceM(), 1e-9);
        assertEquals(3.0, bucket.meanTimeS(), 1e-9);
        assertEquals(300.0, bucket.maxSpaceM(), 1e-9);
        assertEquals("UAV-A", bucket.key().model());
    }

    @Test
    void onDeviation_shouldIgnoreBlankSampleId() {
        RendezvousDeviationStats stats = new RendezvousDeviationStats();
        RendezvousDeviationListener listener = new RendezvousDeviationListener(stats);

        listener.onDeviation(null);
        listener.onDeviation(message("", "UAV-A", 10.0, 1.0));
        assertEquals(0L, stats.sampleCount());
    }

    private static RendezvousDeviationMessage message(String sampleId, String droneId,
            double dSpaceM, double dTimeS) {
        return new RendezvousDeviationMessage(sampleId, droneId, "ORD-1",
                new GeoPoint(115.86, 28.66), new GeoPoint(115.861, 28.661),
                T0, T0 + (long) (dTimeS * 1000), dSpaceM, dTimeS,
                "UAV-A", "NEST-01", "微风", "4-6");
    }
}
