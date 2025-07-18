/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.tasks;

import org.apache.flink.api.common.eventtime.TimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeutils.base.IntSerializer;
import org.apache.flink.api.common.typeutils.base.StringSerializer;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.mocks.MockSource;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.execution.CheckpointingMode;
import org.apache.flink.core.execution.SavepointFormatType;
import org.apache.flink.runtime.checkpoint.CheckpointMetaData;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.checkpoint.CheckpointType;
import org.apache.flink.runtime.checkpoint.SavepointType;
import org.apache.flink.runtime.checkpoint.TaskStateSnapshot;
import org.apache.flink.runtime.io.network.api.CheckpointBarrier;
import org.apache.flink.runtime.io.network.api.EndOfData;
import org.apache.flink.runtime.io.network.api.EndOfPartitionEvent;
import org.apache.flink.runtime.io.network.api.StopMode;
import org.apache.flink.runtime.io.network.api.writer.RecordOrEventCollectingResultPartitionWriter;
import org.apache.flink.runtime.io.network.api.writer.ResultPartitionWriter;
import org.apache.flink.runtime.io.network.partition.PartitionTestUtils;
import org.apache.flink.runtime.io.network.partition.ResultPartition;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.state.CheckpointStorageLocationReference;
import org.apache.flink.streaming.api.operators.AbstractStreamOperatorFactory;
import org.apache.flink.streaming.api.operators.Input;
import org.apache.flink.streaming.api.operators.MultipleInputStreamOperator;
import org.apache.flink.streaming.api.operators.SourceOperator;
import org.apache.flink.streaming.api.operators.SourceOperatorFactory;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorParameters;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.StreamElement;
import org.apache.flink.streaming.runtime.streamrecord.StreamElementSerializer;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.LifeCycleMonitor.LifeCyclePhase;
import org.apache.flink.streaming.runtime.tasks.MultipleInputStreamTaskTest.MapToStringMultipleInputOperatorFactory;
import org.apache.flink.streaming.runtime.watermarkstatus.WatermarkStatus;
import org.apache.flink.streaming.util.CompletingCheckpointResponder;
import org.apache.flink.testutils.junit.extensions.parameterized.Parameter;
import org.apache.flink.testutils.junit.extensions.parameterized.ParameterizedTestExtension;
import org.apache.flink.testutils.junit.extensions.parameterized.Parameters;

import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.apache.flink.streaming.runtime.tasks.MultipleInputStreamTaskTest.addSourceRecords;
import static org.apache.flink.streaming.runtime.tasks.MultipleInputStreamTaskTest.applyObjectReuse;
import static org.apache.flink.streaming.runtime.tasks.MultipleInputStreamTaskTest.buildTestHarness;
import static org.apache.flink.streaming.runtime.tasks.StreamTaskFinalCheckpointsTest.triggerCheckpoint;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MultipleInputStreamTask} combined with {@link
 * org.apache.flink.streaming.api.operators.SourceOperator} chaining.
 */
@ExtendWith(ParameterizedTestExtension.class)
class MultipleInputStreamTaskChainedSourcesCheckpointingTest {

    private static final int MAX_STEPS = 100;

    @Parameters(name = "objectReuse = {0}")
    private static Collection<Boolean> parameters() {
        return Arrays.asList(true, false);
    }

    @Parameter private boolean objectReuse;

    private final CheckpointMetaData metaData =
            new CheckpointMetaData(1L, System.currentTimeMillis());

    /**
     * In this scenario: 1. checkpoint is triggered via RPC and source is blocked 2. network inputs
     * are processed until CheckpointBarriers are processed 3. aligned checkpoint is performed
     */
    @TestTemplate
    void testSourceCheckpointFirst() throws Exception {
        try (StreamTaskMailboxTestHarness<String> testHarness = buildTestHarness(objectReuse)) {
            testHarness.setAutoProcess(false);
            ArrayDeque<Object> expectedOutput = new ArrayDeque<>();
            CheckpointBarrier barrier = createBarrier(testHarness);
            addRecordsAndBarriers(testHarness, barrier);

            Future<Boolean> checkpointFuture =
                    testHarness
                            .getStreamTask()
                            .triggerCheckpointAsync(metaData, barrier.getCheckpointOptions());
            processSingleStepUntil(testHarness, checkpointFuture::isDone);

            expectedOutput.add(new StreamRecord<>("44", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("44", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("47.0", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("47.0", TimestampAssigner.NO_TIMESTAMP));

            ArrayList<Object> actualOutput = new ArrayList<>(testHarness.getOutput());

            assertThat(actualOutput.subList(0, expectedOutput.size()))
                    .containsExactlyInAnyOrderElementsOf(expectedOutput);
            assertThat(actualOutput.get(expectedOutput.size())).isEqualTo(barrier);
        }
    }

    /**
     * In this scenario: 1. checkpoint is triggered via RPC and source is blocked 2. unaligned
     * checkpoint is performed 3. all data from network inputs are processed
     */
    @TestTemplate
    void testSourceCheckpointFirstUnaligned() throws Exception {
        try (StreamTaskMailboxTestHarness<String> testHarness =
                buildTestHarness(true, objectReuse)) {
            testHarness.setAutoProcess(false);
            ArrayDeque<Object> expectedOutput = new ArrayDeque<>();
            addRecords(testHarness);

            CheckpointBarrier barrier = createBarrier(testHarness);
            Future<Boolean> checkpointFuture =
                    testHarness
                            .getStreamTask()
                            .triggerCheckpointAsync(metaData, barrier.getCheckpointOptions());
            processSingleStepUntil(testHarness, checkpointFuture::isDone);

            assertThat(testHarness.getOutput()).containsExactly(barrier);

            testHarness.processAll();

            expectedOutput.add(new StreamRecord<>("44", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("44", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("47.0", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("47.0", TimestampAssigner.NO_TIMESTAMP));

            ArrayList<Object> actualOutput = new ArrayList<>(testHarness.getOutput());
            assertThat(actualOutput.subList(1, expectedOutput.size() + 1))
                    .containsExactlyInAnyOrderElementsOf(expectedOutput);
        }
    }

    /**
     * In this scenario: 1a. network inputs are processed until CheckpointBarriers are processed 1b.
     * source records are processed at the same time 2. checkpoint is triggered via RPC 3. aligned
     * checkpoint is performed
     */
    @TestTemplate
    void testSourceCheckpointLast() throws Exception {
        try (StreamTaskMailboxTestHarness<String> testHarness = buildTestHarness(objectReuse)) {
            testHarness.setAutoProcess(false);
            ArrayDeque<Object> expectedOutput = new ArrayDeque<>();
            CheckpointBarrier barrier = createBarrier(testHarness);
            addRecordsAndBarriers(testHarness, barrier);

            testHarness.processAll();

            Future<Boolean> checkpointFuture =
                    testHarness
                            .getStreamTask()
                            .triggerCheckpointAsync(metaData, barrier.getCheckpointOptions());
            processSingleStepUntil(testHarness, checkpointFuture::isDone);

            expectedOutput.add(new StreamRecord<>("42", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("42", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("42", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("44", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("44", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("47.0", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("47.0", TimestampAssigner.NO_TIMESTAMP));

            ArrayList<Object> actualOutput = new ArrayList<>(testHarness.getOutput());

            assertThat(actualOutput.subList(0, expectedOutput.size()))
                    .containsExactlyInAnyOrderElementsOf(expectedOutput);
            assertThat(actualOutput.get(expectedOutput.size())).isEqualTo(barrier);
        }
    }

    /**
     * In this scenario: 1. network inputs are processed until CheckpointBarriers are processed 2.
     * there are no source records to be processed 3. checkpoint is triggered on first received
     * CheckpointBarrier 4. unaligned checkpoint is performed at some point of time blocking the
     * source 5. more source records are added, that shouldn't be processed
     */
    @TestTemplate
    void testSourceCheckpointLastUnaligned() throws Exception {
        boolean unaligned = true;
        try (StreamTaskMailboxTestHarness<String> testHarness =
                buildTestHarness(unaligned, objectReuse)) {
            testHarness.setAutoProcess(false);
            ArrayDeque<Object> expectedOutput = new ArrayDeque<>();

            addNetworkRecords(testHarness);
            CheckpointBarrier barrier = createBarrier(testHarness);
            addBarriers(testHarness, barrier);

            testHarness.processAll();
            addSourceRecords(testHarness, 1, 1337, 1337, 1337);
            testHarness.processAll();

            expectedOutput.add(new StreamRecord<>("44", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("44", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("47.0", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("47.0", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(barrier);

            assertThat(testHarness.getOutput()).containsExactlyInAnyOrderElementsOf(expectedOutput);
        }
    }

    /**
     * In this scenario:
     *
     * <ul>
     *   <li>Network inputs are processed until CheckpointBarriers for synchronous savepoint are
     *       processed.
     *   <li>RPC for stop-with-savepoint comes for sources
     *   <li>Sources keep being invoked until they return END_OF_DATA
     *   <li>Synchronous savepoint is triggered
     * </ul>
     */
    @TestTemplate
    void testStopWithSavepointDrainWaitsForSourcesFinish() throws Exception {
        try (StreamTaskMailboxTestHarness<String> testHarness =
                new StreamTaskMailboxTestHarnessBuilder<>(
                                MultipleInputStreamTask::new, BasicTypeInfo.STRING_TYPE_INFO)
                        .setCollectNetworkEvents()
                        .modifyExecutionConfig(applyObjectReuse(objectReuse))
                        .addJobConfig(
                                CheckpointingOptions.CHECKPOINTING_INTERVAL, Duration.ofSeconds(1))
                        .addInput(BasicTypeInfo.STRING_TYPE_INFO)
                        .addSourceInput(
                                new SourceOperatorFactory<>(
                                        new MockSource(Boundedness.CONTINUOUS_UNBOUNDED, 1),
                                        WatermarkStrategy.noWatermarks()),
                                BasicTypeInfo.INT_TYPE_INFO)
                        .addSourceInput(
                                new SourceOperatorFactory<>(
                                        new MockSource(Boundedness.CONTINUOUS_UNBOUNDED, 1),
                                        WatermarkStrategy.noWatermarks()),
                                BasicTypeInfo.INT_TYPE_INFO)
                        .addInput(BasicTypeInfo.DOUBLE_TYPE_INFO)
                        .setupOutputForSingletonOperatorChain(
                                new MapToStringMultipleInputOperatorFactory(4, true))
                        .build(); ) {
            testHarness.setAutoProcess(false);
            ArrayDeque<Object> expectedOutput = new ArrayDeque<>();
            CheckpointBarrier barrier = createStopWithSavepointDrainBarrier();

            testHarness.processElement(new StreamRecord<>("44", TimestampAssigner.NO_TIMESTAMP), 0);
            testHarness.processEvent(new EndOfData(StopMode.DRAIN), 0);
            testHarness.processEvent(barrier, 0);
            testHarness.processElement(new StreamRecord<>(47d, TimestampAssigner.NO_TIMESTAMP), 1);
            testHarness.processEvent(new EndOfData(StopMode.DRAIN), 1);
            testHarness.processEvent(barrier, 1);

            addSourceRecords(testHarness, 1, Boundedness.CONTINUOUS_UNBOUNDED, 1, 2);
            addSourceRecords(testHarness, 2, Boundedness.CONTINUOUS_UNBOUNDED, 3, 4);

            testHarness.processAll();

            Future<Boolean> checkpointFuture =
                    testHarness
                            .getStreamTask()
                            .triggerCheckpointAsync(metaData, barrier.getCheckpointOptions());
            processSingleStepUntil(testHarness, checkpointFuture::isDone);

            expectedOutput.add(new StreamRecord<>("3", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("47.0", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("44", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("1", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("4", TimestampAssigner.NO_TIMESTAMP));
            expectedOutput.add(new StreamRecord<>("2", TimestampAssigner.NO_TIMESTAMP));

            ArrayList<Object> actualOutput = new ArrayList<>(testHarness.getOutput());
            assertThat(actualOutput.subList(0, expectedOutput.size()))
                    .containsExactlyInAnyOrderElementsOf(expectedOutput);
            assertThat(actualOutput.subList(actualOutput.size() - 3, actualOutput.size()))
                    .containsExactly(
                            new StreamRecord<>("FINISH"), new EndOfData(StopMode.DRAIN), barrier);
        }
    }

    @TestTemplate
    void testOnlyOneSource() throws Exception {
        try (StreamTaskMailboxTestHarness<String> testHarness =
                new StreamTaskMailboxTestHarnessBuilder<>(
                                MultipleInputStreamTask::new, BasicTypeInfo.STRING_TYPE_INFO)
                        .modifyExecutionConfig(applyObjectReuse(objectReuse))
                        .addSourceInput(
                                new SourceOperatorFactory<>(
                                        new MockSource(Boundedness.BOUNDED, 1),
                                        WatermarkStrategy.noWatermarks()),
                                BasicTypeInfo.INT_TYPE_INFO)
                        .setupOutputForSingletonOperatorChain(
                                new MapToStringMultipleInputOperatorFactory(1))
                        .build()) {
            testHarness.setAutoProcess(false);

            ArrayDeque<Object> expectedOutput = new ArrayDeque<>();

            addSourceRecords(testHarness, 0, 42, 43, 44);
            processSingleStepUntil(testHarness, () -> !testHarness.getOutput().isEmpty());
            expectedOutput.add(new StreamRecord<>("42", TimestampAssigner.NO_TIMESTAMP));

            CheckpointBarrier barrier = createBarrier(testHarness);
            Future<Boolean> checkpointFuture =
                    testHarness
                            .getStreamTask()
                            .triggerCheckpointAsync(metaData, barrier.getCheckpointOptions());
            processSingleStepUntil(testHarness, checkpointFuture::isDone);

            ArrayList<Object> actualOutput = new ArrayList<>(testHarness.getOutput());
            assertThat(actualOutput.subList(0, expectedOutput.size()))
                    .containsExactlyInAnyOrderElementsOf(expectedOutput);
            assertThat(actualOutput.get(expectedOutput.size())).isEqualTo(barrier);
        }
    }

    @TestTemplate
    void testTriggerAlignedNoTimeoutCheckpointWithFinishedChannelsAndSourceChain()
            throws Exception {
        testTriggerCheckpointWithFinishedChannelsAndSourceChain(
                CheckpointOptions.alignedNoTimeout(
                        CheckpointType.CHECKPOINT,
                        CheckpointStorageLocationReference.getDefault()));
    }

    @TestTemplate
    void testTriggerUnalignedCheckpointWithFinishedChannelsAndSourceChain() throws Exception {
        testTriggerCheckpointWithFinishedChannelsAndSourceChain(
                CheckpointOptions.unaligned(
                        CheckpointType.CHECKPOINT,
                        CheckpointStorageLocationReference.getDefault()));
    }

    @TestTemplate
    void testTriggerAlignedWithTimeoutCheckpointWithFinishedChannelsAndSourceChain()
            throws Exception {
        testTriggerCheckpointWithFinishedChannelsAndSourceChain(
                CheckpointOptions.alignedWithTimeout(
                        CheckpointType.CHECKPOINT,
                        CheckpointStorageLocationReference.getDefault(),
                        10L));
    }

    private void testTriggerCheckpointWithFinishedChannelsAndSourceChain(
            CheckpointOptions checkpointOptions) throws Exception {
        ResultPartition[] partitionWriters = new ResultPartition[2];
        try {
            for (int i = 0; i < partitionWriters.length; ++i) {
                partitionWriters[i] =
                        PartitionTestUtils.createPartition(ResultPartitionType.PIPELINED_BOUNDED);
                partitionWriters[i].setup();
            }

            CompletingCheckpointResponder checkpointResponder = new CompletingCheckpointResponder();
            try (StreamTaskMailboxTestHarness<String> testHarness =
                    new StreamTaskMailboxTestHarnessBuilder<>(
                                    MultipleInputStreamTask::new, BasicTypeInfo.STRING_TYPE_INFO)
                            .addJobConfig(
                                    CheckpointingOptions.CHECKPOINTING_INTERVAL,
                                    Duration.ofSeconds(1))
                            .addJobConfig(
                                    CheckpointingOptions.ENABLE_UNALIGNED,
                                    checkpointOptions.isUnalignedCheckpoint()
                                            || checkpointOptions.isTimeoutable())
                            .modifyExecutionConfig(applyObjectReuse(objectReuse))
                            .setCheckpointResponder(checkpointResponder)
                            .addInput(BasicTypeInfo.INT_TYPE_INFO)
                            .addInput(BasicTypeInfo.STRING_TYPE_INFO)
                            .addSourceInput(
                                    new SourceOperatorFactory<>(
                                            new MultipleInputStreamTaskTest
                                                    .LifeCycleTrackingMockSource(
                                                    Boundedness.CONTINUOUS_UNBOUNDED, 1),
                                            WatermarkStrategy.noWatermarks()),
                                    BasicTypeInfo.INT_TYPE_INFO)
                            .addSourceInput(
                                    new SourceOperatorFactory<>(
                                            new MultipleInputStreamTaskTest
                                                    .LifeCycleTrackingMockSource(
                                                    Boundedness.CONTINUOUS_UNBOUNDED, 1),
                                            WatermarkStrategy.noWatermarks()),
                                    BasicTypeInfo.INT_TYPE_INFO)
                            .addAdditionalOutput(partitionWriters)
                            .setupOperatorChain(new MapToStringMultipleInputOperatorFactory(4))
                            .finishForSingletonOperatorChain(StringSerializer.INSTANCE)
                            .build()) {

                checkpointResponder.setHandlers(
                        testHarness.streamTask::notifyCheckpointCompleteAsync,
                        testHarness.streamTask::notifyCheckpointAbortAsync);
                testHarness.getStreamTask().getCheckpointBarrierHandler().get();

                CompletableFuture<Boolean> checkpointFuture =
                        triggerCheckpoint(testHarness, 2, checkpointOptions);
                testHarness.processAll();

                // The checkpoint 2 would be aligned after received all the EndOfPartitionEvent.
                testHarness.processEvent(new EndOfData(StopMode.DRAIN), 0, 0);
                testHarness.processEvent(new EndOfData(StopMode.DRAIN), 1, 0);
                testHarness.processEvent(EndOfPartitionEvent.INSTANCE, 0, 0);
                testHarness.processEvent(EndOfPartitionEvent.INSTANCE, 1, 0);
                testHarness.getTaskStateManager().getWaitForReportLatch().await();
                assertThat(testHarness.getTaskStateManager().getReportedCheckpointId())
                        .isEqualTo(2);

                // Tests triggering checkpoint after all the inputs have received EndOfPartition.
                checkpointFuture = triggerCheckpoint(testHarness, 4, checkpointOptions);

                // Notifies the result partition that all records are processed after the
                // last checkpoint is triggered.
                checkpointFuture.thenAccept(
                        (ignored) -> {
                            for (ResultPartition resultPartition : partitionWriters) {
                                resultPartition.onSubpartitionAllDataProcessed(0);
                            }
                        });

                // The checkpoint 4 would be triggered successfully.
                testHarness.processAll();
                testHarness.finishProcessing();
                assertThat(checkpointFuture).isDone();
                testHarness.getTaskStateManager().getWaitForReportLatch().await();
                assertThat(testHarness.getTaskStateManager().getReportedCheckpointId())
                        .isEqualTo(4);

                // Each result partition should have emitted 2 barriers and 1 EndOfUserRecordsEvent.
                for (ResultPartition resultPartition : partitionWriters) {
                    assertThat(resultPartition.getNumberOfQueuedBuffers()).isEqualTo(3);
                }
            }
        } finally {
            for (ResultPartitionWriter writer : partitionWriters) {
                if (writer != null) {
                    writer.close();
                }
            }
        }
    }

    @TestTemplate
    void testSkipExecutionsIfFinishedOnRestoreWithSourceChained() throws Exception {
        OperatorID firstSourceOperatorId = new OperatorID();
        OperatorID secondSourceOperatorId = new OperatorID();
        OperatorID nonSourceOperatorId = new OperatorID();

        List<Object> output = new ArrayList<>();
        try (StreamTaskMailboxTestHarness<String> testHarness =
                new StreamTaskMailboxTestHarnessBuilder<>(
                                MultipleInputStreamTask::new, BasicTypeInfo.STRING_TYPE_INFO)
                        .addJobConfig(
                                CheckpointingOptions.CHECKPOINTING_INTERVAL, Duration.ofSeconds(1))
                        .modifyExecutionConfig(applyObjectReuse(objectReuse))
                        .addInput(BasicTypeInfo.INT_TYPE_INFO)
                        .addAdditionalOutput(
                                new RecordOrEventCollectingResultPartitionWriter<StreamElement>(
                                        output,
                                        new StreamElementSerializer<>(IntSerializer.INSTANCE)) {
                                    @Override
                                    public void notifyEndOfData(StopMode mode) throws IOException {
                                        broadcastEvent(new EndOfData(mode), false);
                                    }
                                })
                        .addSourceInput(
                                firstSourceOperatorId,
                                new SourceOperatorFactory<>(
                                        new SourceOperatorStreamTaskTest.LifeCycleMonitorSource(
                                                Boundedness.CONTINUOUS_UNBOUNDED, 1),
                                        WatermarkStrategy.noWatermarks()),
                                BasicTypeInfo.INT_TYPE_INFO)
                        .addSourceInput(
                                secondSourceOperatorId,
                                new SourceOperatorFactory<>(
                                        new SourceOperatorStreamTaskTest.LifeCycleMonitorSource(
                                                Boundedness.CONTINUOUS_UNBOUNDED, 1),
                                        WatermarkStrategy.noWatermarks()),
                                BasicTypeInfo.INT_TYPE_INFO)
                        .setTaskStateSnapshot(1, TaskStateSnapshot.FINISHED_ON_RESTORE)
                        .setupOperatorChain(
                                nonSourceOperatorId,
                                new LifeCycleMonitorMultipleInputOperatorFactory())
                        .chain(new TestFinishedOnRestoreStreamOperator(), StringSerializer.INSTANCE)
                        .finish()
                        .build()) {

            testHarness.processElement(Watermark.MAX_WATERMARK);
            assertThat(output).isEmpty();
            testHarness.waitForTaskCompletion();
            assertThat(output)
                    .containsExactly(Watermark.MAX_WATERMARK, new EndOfData(StopMode.DRAIN));

            for (StreamOperatorWrapper<?, ?> wrapper :
                    testHarness.getStreamTask().operatorChain.getAllOperators()) {
                if (wrapper.getStreamOperator() instanceof SourceOperator<?, ?>) {
                    SourceOperatorStreamTaskTest.LifeCycleMonitorSourceReader sourceReader =
                            (SourceOperatorStreamTaskTest.LifeCycleMonitorSourceReader)
                                    ((SourceOperator<?, ?>) wrapper.getStreamOperator())
                                            .getSourceReader();
                    sourceReader.getLifeCycleMonitor().assertCallTimes(0, LifeCyclePhase.values());
                }
            }
        }
    }

    private void addRecordsAndBarriers(
            StreamTaskMailboxTestHarness<String> testHarness, CheckpointBarrier checkpointBarrier)
            throws Exception {
        addRecords(testHarness);
        addBarriers(testHarness, checkpointBarrier);
    }

    private CheckpointBarrier createStopWithSavepointDrainBarrier() {
        CheckpointOptions checkpointOptions =
                CheckpointOptions.alignedNoTimeout(
                        SavepointType.terminate(SavepointFormatType.CANONICAL),
                        CheckpointStorageLocationReference.getDefault());

        return new CheckpointBarrier(
                metaData.getCheckpointId(), metaData.getTimestamp(), checkpointOptions);
    }

    private CheckpointBarrier createBarrier(StreamTaskMailboxTestHarness<String> testHarness) {
        Configuration jobConf = testHarness.getStreamTask().getJobConfiguration();
        CheckpointOptions checkpointOptions =
                CheckpointOptions.forConfig(
                        CheckpointType.CHECKPOINT,
                        CheckpointStorageLocationReference.getDefault(),
                        CheckpointingOptions.getCheckpointingMode(jobConf)
                                == CheckpointingMode.EXACTLY_ONCE,
                        CheckpointingOptions.isUnalignedCheckpointEnabled(jobConf),
                        jobConf.get(CheckpointingOptions.ALIGNED_CHECKPOINT_TIMEOUT).toMillis());

        return new CheckpointBarrier(
                metaData.getCheckpointId(), metaData.getTimestamp(), checkpointOptions);
    }

    private void addBarriers(
            StreamTaskMailboxTestHarness<String> testHarness, CheckpointBarrier checkpointBarrier)
            throws Exception {
        testHarness.processEvent(checkpointBarrier, 0);
        testHarness.processEvent(checkpointBarrier, 1);
    }

    private void addRecords(StreamTaskMailboxTestHarness<String> testHarness) throws Exception {
        addSourceRecords(testHarness, 1, 42, 42, 42);
        addNetworkRecords(testHarness);
    }

    private void addNetworkRecords(StreamTaskMailboxTestHarness<String> testHarness)
            throws Exception {
        testHarness.processElement(new StreamRecord<>("44", TimestampAssigner.NO_TIMESTAMP), 0);
        testHarness.processElement(new StreamRecord<>("44", TimestampAssigner.NO_TIMESTAMP), 0);
        testHarness.processElement(new StreamRecord<>(47d, TimestampAssigner.NO_TIMESTAMP), 1);
        testHarness.processElement(new StreamRecord<>(47d, TimestampAssigner.NO_TIMESTAMP), 1);
    }

    private void processSingleStepUntil(
            StreamTaskMailboxTestHarness<String> testHarness, Supplier<Boolean> condition)
            throws Exception {
        assertThat(condition.get()).isFalse();
        for (int i = 0; i < MAX_STEPS && !condition.get(); i++) {
            testHarness.processSingleStep();
        }
        assertThat(condition.get()).isTrue();
    }

    static class LifeCycleMonitorMultipleInputOperator extends TestFinishedOnRestoreStreamOperator
            implements MultipleInputStreamOperator<String> {

        public LifeCycleMonitorMultipleInputOperator() {}

        @Override
        public List<Input> getInputs() {
            ArrayList<Input> inputs = new ArrayList<>();
            inputs.add(new TestFinishedOnRestoreInput());
            inputs.add(new TestFinishedOnRestoreInput());
            inputs.add(new TestFinishedOnRestoreInput());
            return inputs;
        }

        private static class TestFinishedOnRestoreInput implements Input {
            @Override
            public void processElement(StreamRecord element) throws Exception {
                throw new IllegalStateException(MESSAGE);
            }

            @Override
            public void processWatermark(Watermark mark) throws Exception {
                throw new IllegalStateException(MESSAGE);
            }

            @Override
            public void processWatermarkStatus(WatermarkStatus watermarkStatus) throws Exception {
                throw new IllegalStateException(MESSAGE);
            }

            @Override
            public void processLatencyMarker(LatencyMarker latencyMarker) throws Exception {
                throw new IllegalStateException(MESSAGE);
            }

            @Override
            public void setKeyContextElement(StreamRecord record) throws Exception {
                throw new IllegalStateException(MESSAGE);
            }
        }
    }

    static class LifeCycleMonitorMultipleInputOperatorFactory
            extends AbstractStreamOperatorFactory<String> {
        @Override
        public <T extends StreamOperator<String>> T createStreamOperator(
                StreamOperatorParameters<String> parameters) {
            return (T) new LifeCycleMonitorMultipleInputOperator();
        }

        @Override
        public Class<? extends StreamOperator<String>> getStreamOperatorClass(
                ClassLoader classLoader) {
            return LifeCycleMonitorMultipleInputOperator.class;
        }
    }
}
