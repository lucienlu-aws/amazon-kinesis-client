package software.amazon.kinesis.worker.metric;

import lombok.Builder;
import lombok.Data;

import com.google.common.base.Preconditions;

@Data
@Builder
public class OperatingRange {

    /**
     * Max utilization percentage allowed for the workerMetrics.
     */
    private final int maxUtilization;

    private OperatingRange(final int maxUtilization) {
        Preconditions.checkArgument(!(maxUtilization < 0 || maxUtilization > 100), "Invalid maxUtilization value");
        this.maxUtilization = maxUtilization;
    }
}
