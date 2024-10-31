package software.amazon.kinesis.worker.metric.impl.jmx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import software.amazon.kinesis.worker.metric.OperatingRange;
import software.amazon.kinesis.worker.metric.WorkerMetricType;

class HeapMemoryAfterGCWorkerMetricsTest {

    @Test
    void sense_sanity() {
        final HeapMemoryAfterGCWorkerMetric heapMemoryAfterGCSensor = new HeapMemoryAfterGCWorkerMetric(
                OperatingRange.builder()
                              .maxUtilization(100)
                              .build());

        assertNotNull(heapMemoryAfterGCSensor.capture().getValue());

        assertEquals(WorkerMetricType.MEMORY, heapMemoryAfterGCSensor.getWorkerMetricType());
        assertEquals(WorkerMetricType.MEMORY.getShortName(), heapMemoryAfterGCSensor.getShortName());
    }
}
