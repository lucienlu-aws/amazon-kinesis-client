package software.amazon.kinesis.worker.metric.impl.linux;

import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.amazon.kinesis.worker.metric.WorkerMetricsTestUtils.writeLineToFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import software.amazon.kinesis.worker.metric.OperatingRange;

public class LinuxNetworkWorkerMetricTest {

    // The first and the second input in both cases has a difference of 1048576(1MB) rx bytes and tx 2097152(2MB) bytes.
    private static final String INPUT_1 =
            "Inter-|   Receive                                                |  Transmit\n" +
                    " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n" +
                    "     lo: 51335658  460211    0    0    0     0          0         0 51335658  460211    0    0    0     0       0          0\n" +
                    "   eth0: 0 11860562    0    0    0     0          0   4234156 0 3248505    0    0    0     0       0          0\n";

    private static final String INPUT_2 =
            "Inter-|   Receive                                                |  Transmit\n" +
                    " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n" +
                    "     lo: 51335668  460211    0    0    0     0          0         0 51335678  460211    0    0    0     0       0          0\n" +
                    "   eth0: 1048576 11860562    0    0    0     0          0   4234156 2097152 3248505    0    0    0     0       0          0\n";

    private static final String NO_WHITESPACE_INPUT_1 =
        "Inter-|   Receive                                                |  Transmit\n" +
                " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n" +
                "     lo:51335658  460211    0    0    0     0          0         0 51335658  460211    0    0    0     0       0          0\n" +
                "   eth0:3120842478 11860562    0    0    0     0          0   4234156 336491180 3248505    0    0    0     0       0          0\n";

    private static final String NO_WHITESPACE_INPUT_2 =
        "Inter-|   Receive                                                |  Transmit\n" +
                " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n" +
                "     lo:51335668  460211    0    0    0     0          0         0 51335678  460211    0    0    0     0       0          0\n" +
                "   eth0:3121891054 11860562    0    0    0     0          0   4234156 338588332 3248505    0    0    0     0       0          0\n";

    private final static OperatingRange TEST_OPERATING_RANGE = OperatingRange.builder().build();

    @TempDir
    private Path tempDir;

    @Test
    void sense_sanityWith1SecondTicker() throws IOException {
        executeTestForInAndOutSensor(INPUT_1, INPUT_2, 1000L, 10, 20);
        executeTestForInAndOutSensor(NO_WHITESPACE_INPUT_1, NO_WHITESPACE_INPUT_2, 1000L, 10, 20);
    }

    @Test
    void sense_sanityWith500MsTicker() throws IOException {
        executeTestForInAndOutSensor(INPUT_1, INPUT_2, 500L, 20, 40);
        executeTestForInAndOutSensor(NO_WHITESPACE_INPUT_1, NO_WHITESPACE_INPUT_2, 500L, 20, 40);
    }

    @Test
    void sense_withNoTimeElapsed() {
        assertThrows(IllegalArgumentException.class, () -> executeTestForInAndOutSensor(INPUT_1, INPUT_2, 0L, 20, 40));
    }

    void executeTestForInAndOutSensor(final String input1, final String input2, final long tickMillis,
            final long expectedIn, final long expectedOut) throws IOException {
        final File statFile = new File(tempDir.toAbsolutePath() + "/netStat");

        final LinuxNetworkInWorkerMetric linuxNetworkInSensor = new LinuxNetworkInWorkerMetric(TEST_OPERATING_RANGE, "eth0",
                statFile.getAbsolutePath(), 10, getMockedStopWatchWithOneSecondTicker(tickMillis));

        writeFileAndRunTest(statFile, linuxNetworkInSensor, input1, input2, expectedIn);

        final LinuxNetworkOutWorkerMetric linuxNetworkOutSensor = new LinuxNetworkOutWorkerMetric(TEST_OPERATING_RANGE, "eth0",
                statFile.getAbsolutePath(), 10, getMockedStopWatchWithOneSecondTicker(tickMillis));

        writeFileAndRunTest(statFile, linuxNetworkOutSensor, input1, input2, expectedOut);
    }

    @Test
    void sense_nonExistingFile_assertIllegalArgumentException() {
        final LinuxNetworkInWorkerMetric linuxNetworkInSensor = new LinuxNetworkInWorkerMetric(TEST_OPERATING_RANGE, "eth0",
                "/non/existing/file", 10, getMockedStopWatchWithOneSecondTicker(1000L));

        assertThrows(IllegalArgumentException.class, linuxNetworkInSensor::capture);
    }

    @Test
    void sense_nonExistingNetworkInterface_assertIllegalArgumentException() throws IOException {

        final File statFile = new File(tempDir.toAbsolutePath() + "/netStat");

        final LinuxNetworkInWorkerMetric linuxNetworkInSensor = new LinuxNetworkInWorkerMetric(TEST_OPERATING_RANGE, "randomName",
                statFile.getAbsolutePath(), 10, getMockedStopWatchWithOneSecondTicker(1000L));

        writeLineToFile(statFile, INPUT_1);

        assertThrows(IllegalArgumentException.class, linuxNetworkInSensor::capture);
    }

    @Test
    void sense_configuredMaxLessThanUtilized_assert100Percent() throws IOException {
        final File statFile = new File(tempDir.toAbsolutePath() + "/netStat");

        // configured bandwidth is 1 MB and utilized bandwidth is 2 MB.
        final LinuxNetworkOutWorkerMetric linuxNetworkOutSensor = new LinuxNetworkOutWorkerMetric(TEST_OPERATING_RANGE, "eth0",
                statFile.getAbsolutePath(), 1, getMockedStopWatchWithOneSecondTicker(1000L));

        writeFileAndRunTest(statFile, linuxNetworkOutSensor, NO_WHITESPACE_INPUT_1, NO_WHITESPACE_INPUT_2, 100);
    }

    @Test
    void sense_maxBandwidthInMBAsZero_assertIllegalArgumentException() throws IOException {
        final File statFile = new File(tempDir.toAbsolutePath() + "/netStat");

        assertThrows(IllegalArgumentException.class,
                () -> new LinuxNetworkOutWorkerMetric(TEST_OPERATING_RANGE, "eth0", statFile.getAbsolutePath(), 0,
                        getMockedStopWatchWithOneSecondTicker(1000L)));
    }

    private void writeFileAndRunTest(final File statFile, final LinuxNetworkWorkerMetricBase linuxNetworkSensorBase,
            final String input1, final String input2, final double expectedValues) throws IOException {

        writeLineToFile(statFile, input1);
        // The First call is expected to be returning 0;
        assertEquals(0, linuxNetworkSensorBase.capture().getValue());

        writeLineToFile(statFile, input2);
        assertEquals(expectedValues, linuxNetworkSensorBase.capture().getValue());

    }


    private Stopwatch getMockedStopWatchWithOneSecondTicker(final long tickMillis) {
        final Ticker ticker = new Ticker() {
            private int readCount = 0;

            @Override
            public long read() {
                readCount++;
                return Duration.ofMillis(readCount * tickMillis).toNanos();
            }
        };
        return Stopwatch.createUnstarted(ticker);
    }
}
