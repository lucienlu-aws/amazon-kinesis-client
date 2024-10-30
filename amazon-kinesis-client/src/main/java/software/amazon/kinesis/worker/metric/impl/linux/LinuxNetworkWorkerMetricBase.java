package software.amazon.kinesis.worker.metric.impl.linux;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.Duration;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import software.amazon.kinesis.worker.metric.OperatingRange;
import software.amazon.kinesis.worker.metric.WorkerMetricType;
import software.amazon.kinesis.worker.metric.WorkerMetric;

/**
 * Base class for EC2NetworkWorkerMetrics, this reads and parses /proc/net/dev file and look for the specific
 * interface and reads received and transmitted bytes.
 * To get the percentage of bandwidth consumed, the fetch bytes are converted to per second (based on the interval
 * between invocation) and percentage is calculated by dividing it by the maximum bandwidth in MBps.
 *
 * When this is invoked for the first time, the value returned is always 0 as the prev values are not available
 * to calculate the diff.
 * In case the stat file is not present or any other exception occurs, this throws IllegalArgumentException.
 */
@Slf4j
public abstract class LinuxNetworkWorkerMetricBase implements WorkerMetric {

    protected final static String DEFAULT_NETWORK_STAT_FILE = "/proc/net/dev";
    protected final static String DEFAULT_INTERFACE_NAME = "eth0";
    private final Object lockObject = new Object();

    private final OperatingRange operatingRange;
    private final String interfaceName;
    private final String statFile;
    private final double maxBandwidthInMBps;
    // Stopwatch to keep track of elapsed time between invocation.
    private final Stopwatch stopwatch;

    public LinuxNetworkWorkerMetricBase(final OperatingRange operatingRange, final String interfaceName, final String statFile,
            final double maxBandwidthInMBps, final Stopwatch stopwatch) {
        Preconditions.checkArgument(maxBandwidthInMBps > 0, "maxBandwidthInMBps should be greater than 0.");
        this.operatingRange = operatingRange;
        this.interfaceName = interfaceName;
        this.statFile = statFile;
        this.maxBandwidthInMBps = maxBandwidthInMBps;
        this.stopwatch = stopwatch;
    }

    private long last_rx = -1;
    private long last_tx = -1;

    @Override
    public String getShortName() {
        return _getWorkerMetricsType().getShortName();
    }

    @Override
    public OperatingRange getOperatingRange() {
        return this.operatingRange;
    }

    @Override
    public WorkerMetricType getWorkerMetricType() {
        return _getWorkerMetricsType();
    }

    /**
     * Reads the stat file and find the total bytes (in and out) and divide it by the time elapsed since last read to
     * get the bytes per second.
     * Converts the bytes per second to MBps and then normalizes it to a percentage of the maximum bandwidth.
     * @return WorkerMetricValue with the % of network bandwidth consumed.
     */
    @Override
    public WorkerMetricValue capture() {
        final double percentageOfMaxBandwidth = convertToMBps(calculateNetworkUsage().get(_getWorkerMetricsType()))
                / maxBandwidthInMBps * 100;
        return WorkerMetricValue.builder()
                // If maxBandwidthInMBps is less than utilized (could be wrong configuration),
                // default to 100 % bandwidth utilization.
                .value(Math.min(100, percentageOfMaxBandwidth))
                .build();
    }

    private double convertToMBps(final long bytes) {
        final double elapsedTimeInSecond;
        if (!stopwatch.isRunning()) {
            // stopwatch is not running during the first request only, in this case assume 1 second as elapsed as
            // during the first request even bytes are zero, any value of elapsedTimeInSecond does not have any effect.
            elapsedTimeInSecond = 1.0;
        } else {
            // Specifically, getting nanos and converting to seconds to get the decimal precision.
            elapsedTimeInSecond = (double) stopwatch.elapsed().toNanos() / Duration.ofSeconds(1).toNanos();
        }
        stopwatch.reset().start();
        // Convert bytes to MB
        final double totalDataMB = (double) bytes / (1024 * 1024);
        if (elapsedTimeInSecond == 0) {
            // This should never happen, as getting called twice within 1 nanoSecond is never expected.
            // If this happens something is real wrong.
            throw new IllegalArgumentException("elapsedTimeInSecond is zero which in incorrect");
        }
        return totalDataMB / elapsedTimeInSecond;
    }

    protected abstract WorkerMetricType _getWorkerMetricsType();

    /**
     * Returns the absolute bytes in and out since the last invocation of the method.
     * @return Map of WorkerMetricType to bytes
     */
    private Map<WorkerMetricType, Long> calculateNetworkUsage() {
        BufferedReader bufferedReader = null;
        try {
            final File net = new File(statFile);
            if(net.exists()) {
                bufferedReader = new BufferedReader(new FileReader(net));

                // skip over header lines
                bufferedReader.readLine();
                bufferedReader.readLine();

                // find specified interface
                String line = bufferedReader.readLine();
                while (line != null && !line.matches("^\\s*" + interfaceName + ":.*")) {
                    line = bufferedReader.readLine();
                }
                if (line == null) {
                    throw new IllegalArgumentException("Failed to parse the file and find interface : " + interfaceName);
                }

                int n = line.indexOf(':') + 1;
                line = line.substring(n).trim();
                String[] parts = line.split("\\s+");

                long rx = Long.parseLong(parts[0]);
                long tx = Long.parseLong(parts[8]);
                long diff_rx = -1, diff_tx = -1;
                boolean skip = false;
                synchronized (lockObject) {
                    if (last_rx == -1) {
                        skip = true;
                    } else {
                        diff_rx = Math.abs(rx - last_rx);
                        diff_tx = Math.abs(tx - last_tx);
                    }
                    last_rx = rx;
                    last_tx = tx;
                }

                if (skip) {
                    return createResponse(0L, 0L);
                }

                return createResponse(diff_rx, diff_tx);
            } else {
                throw new IllegalArgumentException(
                        String.format("NetworkWorkerMetrics is not configured properly, file : %s does not exists",
                                this.statFile));
            }
        } catch (final Throwable t) {
            if (t instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) t;
            }
            throw new IllegalArgumentException("Cannot read/parse " + this.statFile, t);
        } finally {
            try {
                if (bufferedReader != null)
                    bufferedReader.close();
            } catch (Throwable x) {
                log.warn("Failed to close bufferedReader ", x);
            }
        }
    }

    private Map<WorkerMetricType, Long> createResponse(final long diff_rx, final long diff_tx) {
        return ImmutableMap.of(
                WorkerMetricType.NETWORK_IN, diff_rx ,
                WorkerMetricType.NETWORK_OUT, diff_tx);
    }
}
