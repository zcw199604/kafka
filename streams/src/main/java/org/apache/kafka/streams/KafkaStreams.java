/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.MemberToRemove;
import org.apache.kafka.clients.admin.RemoveMembersFromConsumerGroupOptions;
import org.apache.kafka.clients.admin.RemoveMembersFromConsumerGroupResult;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.annotation.InterfaceStability.Evolving;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.metrics.JmxReporter;
import org.apache.kafka.common.metrics.KafkaMetricsContext;
import org.apache.kafka.common.metrics.MetricConfig;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.MetricsContext;
import org.apache.kafka.common.metrics.MetricsReporter;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.Sensor.RecordingLevel;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.errors.InvalidStateStorePartitionException;
import org.apache.kafka.streams.errors.ProcessorStateException;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.errors.StreamsNotStartedException;
import org.apache.kafka.streams.errors.StreamsStoppedException;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.apache.kafka.streams.errors.UnknownStateStoreException;
import org.apache.kafka.streams.internals.metrics.ClientMetrics;
import org.apache.kafka.streams.processor.StateRestoreListener;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.StreamPartitioner;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.processor.internals.ClientUtils;
import org.apache.kafka.streams.processor.internals.DefaultKafkaClientSupplier;
import org.apache.kafka.streams.processor.internals.GlobalStreamThread;
import org.apache.kafka.streams.processor.internals.GlobalStreamThread.State;
import org.apache.kafka.streams.processor.internals.StateDirectory;
import org.apache.kafka.streams.processor.internals.StreamThread;
import org.apache.kafka.streams.processor.internals.StreamsMetadataState;
import org.apache.kafka.streams.processor.internals.Task;
import org.apache.kafka.streams.processor.internals.ThreadStateTransitionValidator;
import org.apache.kafka.streams.processor.internals.TopologyMetadata;
import org.apache.kafka.streams.processor.internals.assignment.AssignorError;
import org.apache.kafka.streams.processor.internals.metrics.StreamsMetricsImpl;
import org.apache.kafka.streams.query.FailureReason;
import org.apache.kafka.streams.query.PositionBound;
import org.apache.kafka.streams.query.QueryConfig;
import org.apache.kafka.streams.query.QueryResult;
import org.apache.kafka.streams.query.StateQueryRequest;
import org.apache.kafka.streams.query.StateQueryResult;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.internals.GlobalStateStoreProvider;
import org.apache.kafka.streams.state.internals.QueryableStoreProvider;
import org.apache.kafka.streams.state.internals.StreamThreadStateStoreProvider;

import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.apache.kafka.streams.StreamsConfig.METRICS_RECORDING_LEVEL_CONFIG;
import static org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT;
import static org.apache.kafka.streams.internals.ApiUtils.prepareMillisCheckFailMsgPrefix;
import static org.apache.kafka.streams.internals.ApiUtils.validateMillisecondDuration;
import static org.apache.kafka.streams.processor.internals.ClientUtils.fetchEndOffsets;

/**
 * A Kafka client that allows for performing continuous computation on input coming from one or more input topics and
 * sends output to zero, one, or more output topics.
 * <p>
 * The computational logic can be specified either by using the {@link Topology} to define a DAG topology of
 * {@link org.apache.kafka.streams.processor.api.Processor}s or by using the {@link StreamsBuilder} which provides the high-level DSL to define
 * transformations.
 * <p>
 * One {@code KafkaStreams} instance can contain one or more threads specified in the configs for the processing work.
 * <p>
 * A {@code KafkaStreams} instance can co-ordinate with any other instances with the same
 * {@link StreamsConfig#APPLICATION_ID_CONFIG application ID} (whether in the same process, on other processes on this
 * machine, or on remote machines) as a single (possibly distributed) stream processing application.
 * These instances will divide up the work based on the assignment of the input topic partitions so that all partitions
 * are being consumed.
 * If instances are added or fail, all (remaining) instances will rebalance the partition assignment among themselves
 * to balance processing load and ensure that all input topic partitions are processed.
 * <p>
 * Internally a {@code KafkaStreams} instance contains a normal {@link KafkaProducer} and {@link KafkaConsumer} instance
 * that is used for reading input and writing output.
 * <p>
 * A simple example might look like this:
 * <pre>{@code
 * Properties props = new Properties();
 * props.put(StreamsConfig.APPLICATION_ID_CONFIG, "my-stream-processing-application");
 * props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
 * props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
 * props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());
 *
 * StreamsBuilder builder = new StreamsBuilder();
 * builder.<String, String>stream("my-input-topic").mapValues(value -> String.valueOf(value.length())).to("my-output-topic");
 *
 * KafkaStreams streams = new KafkaStreams(builder.build(), props);
 * streams.start();
 * }</pre>
 *
 * @see org.apache.kafka.streams.StreamsBuilder
 * @see org.apache.kafka.streams.Topology
 */
public class KafkaStreams implements AutoCloseable {

    private static final String JMX_PREFIX = "kafka.streams";

    private static final Set<Class<? extends Throwable>> EXCEPTIONS_NOT_TO_BE_HANDLED_BY_USERS =
        new HashSet<>(Arrays.asList(IllegalStateException.class, IllegalArgumentException.class));

    // processId is expected to be unique across JVMs and to be used
    // in userData of the subscription request to allow assignor be aware
    // of the co-location of stream thread's consumers. It is for internal
    // usage only and should not be exposed to users at all.
    private final Time time;
    private final Logger log;
    protected final String clientId;
    private final Metrics metrics;
    protected final StreamsConfig applicationConfigs;
    protected final List<StreamThread> threads;
    protected final StateDirectory stateDirectory;
    protected final StreamsMetadataState streamsMetadataState;
    private final ScheduledExecutorService stateDirCleaner;
    private final ScheduledExecutorService rocksDBMetricsRecordingService;
    protected final Admin adminClient;
    private final StreamsMetricsImpl streamsMetrics;
    private final long totalCacheSize;
    private final long inputBufferMaxBytes;
    private final StreamStateListener streamStateListener;
    private final StateRestoreListener delegatingStateRestoreListener;
    private final Map<Long, StreamThread.State> threadState;
    private final UUID processId;
    private final KafkaClientSupplier clientSupplier;
    protected final TopologyMetadata topologyMetadata;
    private final QueryableStoreProvider queryableStoreProvider;

    GlobalStreamThread globalStreamThread;
    private KafkaStreams.StateListener stateListener;
    private StateRestoreListener globalStateRestoreListener;
    private boolean oldHandler;
    private BiConsumer<Throwable, Boolean> streamsUncaughtExceptionHandler;
    private final Object changeThreadCount = new Object();

    // container states
    /**
     * Kafka Streams states are the possible state that a Kafka Streams instance can be in.
     * An instance must only be in one state at a time.
     * The expected state transition with the following defined states is:
     *
     * <pre>
     *                 +--------------+
     *         +&lt;----- | Created (0)  |
     *         |       +-----+--------+
     *         |             |
     *         |             v
     *         |       +----+--+------+
     *         |       | Re-          |
     *         +&lt;----- | Balancing (1)| --------&gt;+
     *         |       +-----+-+------+          |
     *         |             | ^                 |
     *         |             v |                 |
     *         |       +--------------+          v
     *         |       | Running (2)  | --------&gt;+
     *         |       +------+-------+          |
     *         |              |                  |
     *         |              v                  |
     *         |       +------+-------+     +----+-------+
     *         +-----&gt; | Pending      |     | Pending    |
     *                 | Shutdown (3) |     | Error (5)  |
     *                 +------+-------+     +-----+------+
     *                        |                   |
     *                        v                   v
     *                 +------+-------+     +-----+--------+
     *                 | Not          |     | Error (6)    |
     *                 | Running (4)  |     +--------------+
     *                 +--------------+
     *
     *
     * </pre>
     * Note the following:
     * - RUNNING state will transit to REBALANCING if any of its threads is in PARTITION_REVOKED or PARTITIONS_ASSIGNED state
     * - REBALANCING state will transit to RUNNING if all of its threads are in RUNNING state
     * - Any state except NOT_RUNNING, PENDING_ERROR or ERROR can go to PENDING_SHUTDOWN (whenever close is called)
     * - Of special importance: If the global stream thread dies, or all stream threads die (or both) then
     *   the instance will be in the ERROR state. The user will not need to close it.
     */
    public enum State {
        // Note: if you add a new state, check the below methods and how they are used within Streams to see if
        // any of them should be updated to include the new state. For example a new shutdown path or terminal
        // state would likely need to be included in methods like isShuttingDown(), hasCompletedShutdown(), etc.
        CREATED(1, 3),          // 0
        REBALANCING(2, 3, 5),   // 1
        RUNNING(1, 2, 3, 5),    // 2
        PENDING_SHUTDOWN(4),    // 3
        NOT_RUNNING,            // 4
        PENDING_ERROR(6),       // 5
        ERROR;                  // 6

        private final Set<Integer> validTransitions = new HashSet<>();

        State(final Integer... validTransitions) {
            this.validTransitions.addAll(Arrays.asList(validTransitions));
        }

        public boolean hasNotStarted() {
            return equals(CREATED);
        }

        public boolean isRunningOrRebalancing() {
            return equals(RUNNING) || equals(REBALANCING);
        }

        public boolean isShuttingDown() {
            return equals(PENDING_SHUTDOWN) || equals(PENDING_ERROR);
        }

        public boolean hasCompletedShutdown() {
            return equals(NOT_RUNNING) || equals(ERROR);
        }

        public boolean hasStartedOrFinishedShuttingDown() {
            return isShuttingDown() || hasCompletedShutdown();
        }

        public boolean isValidTransition(final State newState) {
            return validTransitions.contains(newState.ordinal());
        }
    }

    private final Object stateLock = new Object();
    protected volatile State state = State.CREATED;

    private boolean waitOnState(final State targetState, final long waitMs) {
        final long begin = time.milliseconds();
        synchronized (stateLock) {
            boolean interrupted = false;
            long elapsedMs = 0L;
            try {
                while (state != targetState) {
                    if (waitMs > elapsedMs) {
                        final long remainingMs = waitMs - elapsedMs;
                        try {
                            stateLock.wait(remainingMs);
                        } catch (final InterruptedException e) {
                            interrupted = true;
                        }
                    } else {
                        log.debug("Cannot transit to {} within {}ms", targetState, waitMs);
                        return false;
                    }
                    elapsedMs = time.milliseconds() - begin;
                }
            } finally {
                // Make sure to restore the interruption status before returning.
                // We do not always own the current thread that executes this method, i.e., we do not know the
                // interruption policy of the thread. The least we can do is restore the interruption status before
                // the current thread exits this method.
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        }
    }

    /**
     * Sets the state
     * @param newState New state
     */
    private boolean setState(final State newState) {
        final State oldState;

        synchronized (stateLock) {
            oldState = state;

            if (state == State.PENDING_SHUTDOWN && newState != State.NOT_RUNNING) {
                // when the state is already in PENDING_SHUTDOWN, all other transitions than NOT_RUNNING (due to thread dying) will be
                // refused but we do not throw exception here, to allow appropriate error handling
                return false;
            } else if (state == State.NOT_RUNNING && (newState == State.PENDING_SHUTDOWN || newState == State.NOT_RUNNING)) {
                // when the state is already in NOT_RUNNING, its transition to PENDING_SHUTDOWN or NOT_RUNNING (due to consecutive close calls)
                // will be refused but we do not throw exception here, to allow idempotent close calls
                return false;
            } else if (state == State.REBALANCING && newState == State.REBALANCING) {
                // when the state is already in REBALANCING, it should not transit to REBALANCING again
                return false;
            } else if (state == State.ERROR && (newState == State.PENDING_ERROR || newState == State.ERROR)) {
                // when the state is already in ERROR, its transition to PENDING_ERROR or ERROR (due to consecutive close calls)
                return false;
            } else if (state == State.PENDING_ERROR && newState != State.ERROR) {
                // when the state is already in PENDING_ERROR, all other transitions than ERROR (due to thread dying) will be
                // refused but we do not throw exception here, to allow appropriate error handling
                return false;
            } else if (!state.isValidTransition(newState)) {
                throw new IllegalStateException("Stream-client " + clientId + ": Unexpected state transition from " + oldState + " to " + newState);
            } else {
                log.info("State transition from {} to {}", oldState, newState);
            }
            state = newState;
            stateLock.notifyAll();
        }

        // we need to call the user customized state listener outside the state lock to avoid potential deadlocks
        if (stateListener != null) {
            stateListener.onChange(newState, oldState);
        }

        return true;
    }

    /**
     * Return the current {@link State} of this {@code KafkaStreams} instance.
     *
     * @return the current state of this Kafka Streams instance
     */
    public State state() {
        return state;
    }

    protected boolean isRunningOrRebalancing() {
        synchronized (stateLock) {
            return state.isRunningOrRebalancing();
        }
    }

    protected boolean hasStartedOrFinishedShuttingDown() {
        synchronized (stateLock) {
            return state.hasStartedOrFinishedShuttingDown();
        }
    }

    protected void validateIsRunningOrRebalancing() {
        synchronized (stateLock) {
            if (state.hasNotStarted()) {
                throw new StreamsNotStartedException("KafkaStreams has not been started, you can retry after calling start()");
            }
            if (!state.isRunningOrRebalancing()) {
                throw new IllegalStateException("KafkaStreams is not running. State is " + state + ".");
            }
        }
    }

    /**
     * Listen to {@link State} change events.
     */
    public interface StateListener {

        /**
         * Called when state changes.
         *
         * @param newState new state
         * @param oldState previous state
         */
        void onChange(final State newState, final State oldState);
    }

    /**
     * An app can set a single {@link KafkaStreams.StateListener} so that the app is notified when state changes.
     *
     * @param listener a new state listener
     * @throws IllegalStateException if this {@code KafkaStreams} instance has already been started.
     */
    public void setStateListener(final KafkaStreams.StateListener listener) {
        synchronized (stateLock) {
            if (state.hasNotStarted()) {
                stateListener = listener;
            } else {
                throw new IllegalStateException("Can only set StateListener before calling start(). Current state is: " + state);
            }
        }
    }

    /**
     * Set the handler invoked when an internal {@link StreamsConfig#NUM_STREAM_THREADS_CONFIG stream thread} abruptly
     * terminates due to an uncaught exception.
     *
     * @param uncaughtExceptionHandler the uncaught exception handler for all internal threads; {@code null} deletes the current handler
     * @throws IllegalStateException if this {@code KafkaStreams} instance has already been started.
     *
     * @deprecated Since 2.8.0. Use {@link KafkaStreams#setUncaughtExceptionHandler(StreamsUncaughtExceptionHandler)} instead.
     *
     */
    @Deprecated
    public void setUncaughtExceptionHandler(final Thread.UncaughtExceptionHandler uncaughtExceptionHandler) {
        synchronized (stateLock) {
            if (state.hasNotStarted()) {
                oldHandler = true;
                processStreamThread(thread -> thread.setUncaughtExceptionHandler(uncaughtExceptionHandler));

                if (globalStreamThread != null) {
                    globalStreamThread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
                }
            } else {
                throw new IllegalStateException("Can only set UncaughtExceptionHandler before calling start(). " +
                    "Current state is: " + state);
            }
        }
    }

    /**
     * Set the handler invoked when an internal {@link StreamsConfig#NUM_STREAM_THREADS_CONFIG stream thread}
     * throws an unexpected exception.
     * These might be exceptions indicating rare bugs in Kafka Streams, or they
     * might be exceptions thrown by your code, for example a NullPointerException thrown from your processor logic.
     * The handler will execute on the thread that produced the exception.
     * In order to get the thread that threw the exception, use {@code Thread.currentThread()}.
     * <p>
     * Note, this handler must be threadsafe, since it will be shared among all threads, and invoked from any
     * thread that encounters such an exception.
     *
     * @param userStreamsUncaughtExceptionHandler the uncaught exception handler of type {@link StreamsUncaughtExceptionHandler} for all internal threads
     * @throws IllegalStateException if this {@code KafkaStreams} instance has already been started.
     * @throws NullPointerException if userStreamsUncaughtExceptionHandler is null.
     */
    public void setUncaughtExceptionHandler(final StreamsUncaughtExceptionHandler userStreamsUncaughtExceptionHandler) {
        synchronized (stateLock) {
            if (state.hasNotStarted()) {
                Objects.requireNonNull(userStreamsUncaughtExceptionHandler);
                streamsUncaughtExceptionHandler =
                    (exception, skipThreadReplacement) ->
                        handleStreamsUncaughtException(exception, userStreamsUncaughtExceptionHandler, skipThreadReplacement);
                processStreamThread(thread -> thread.setStreamsUncaughtExceptionHandler(streamsUncaughtExceptionHandler));
                if (globalStreamThread != null) {
                    globalStreamThread.setUncaughtExceptionHandler(
                        exception -> handleStreamsUncaughtException(exception, userStreamsUncaughtExceptionHandler, false)
                    );
                }
            } else {
                throw new IllegalStateException("Can only set UncaughtExceptionHandler before calling start(). " +
                    "Current state is: " + state);
            }
        }
    }

    private void defaultStreamsUncaughtExceptionHandler(final Throwable throwable, final boolean skipThreadReplacement) {
        if (oldHandler) {
            threads.remove(Thread.currentThread());
            if (throwable instanceof RuntimeException) {
                throw (RuntimeException) throwable;
            } else if (throwable instanceof Error) {
                throw (Error) throwable;
            } else {
                throw new RuntimeException("Unexpected checked exception caught in the uncaught exception handler", throwable);
            }
        } else {
            handleStreamsUncaughtException(throwable, t -> SHUTDOWN_CLIENT, skipThreadReplacement);
        }
    }

    private void replaceStreamThread(final Throwable throwable) {
        if (globalStreamThread != null && Thread.currentThread().getName().equals(globalStreamThread.getName())) {
            log.warn("The global thread cannot be replaced. Reverting to shutting down the client.");
            log.error("Encountered the following exception during processing " +
                    " The streams client is going to shut down now. ", throwable);
            closeToError();
        }
        final StreamThread deadThread = (StreamThread) Thread.currentThread();
        deadThread.shutdown();
        addStreamThread();
        if (throwable instanceof RuntimeException) {
            throw (RuntimeException) throwable;
        } else if (throwable instanceof Error) {
            throw (Error) throwable;
        } else {
            throw new RuntimeException("Unexpected checked exception caught in the uncaught exception handler", throwable);
        }
    }

    private boolean wrappedExceptionIsIn(final Throwable throwable, final Set<Class<? extends Throwable>> exceptionsOfInterest) {
        return throwable.getCause() != null && exceptionsOfInterest.contains(throwable.getCause().getClass());
    }

    private StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse getActionForThrowable(final Throwable throwable,
                                                                                                final StreamsUncaughtExceptionHandler streamsUncaughtExceptionHandler) {
        final StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse action;
        if (wrappedExceptionIsIn(throwable, EXCEPTIONS_NOT_TO_BE_HANDLED_BY_USERS)) {
            action = SHUTDOWN_CLIENT;
        } else {
            action = streamsUncaughtExceptionHandler.handle(throwable);
        }
        return action;
    }

    private void handleStreamsUncaughtException(final Throwable throwable,
                                                final StreamsUncaughtExceptionHandler streamsUncaughtExceptionHandler,
                                                final boolean skipThreadReplacement) {
        final StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse action = getActionForThrowable(throwable, streamsUncaughtExceptionHandler);
        if (oldHandler) {
            log.warn("Stream's new uncaught exception handler is set as well as the deprecated old handler." +
                    "The old handler will be ignored as long as a new handler is set.");
        }
        switch (action) {
            case REPLACE_THREAD:
                if (!skipThreadReplacement) {
                    log.error("Replacing thread in the streams uncaught exception handler", throwable);
                    replaceStreamThread(throwable);
                } else {
                    log.debug("Skipping thread replacement for recoverable error");
                }
                break;
            case SHUTDOWN_CLIENT:
                log.error("Encountered the following exception during processing " +
                        "and Kafka Streams opted to " + action + "." +
                        " The streams client is going to shut down now. ", throwable);
                closeToError();
                break;
            case SHUTDOWN_APPLICATION:
                if (getNumLiveStreamThreads() == 1) {
                    log.warn("Attempt to shut down the application requires adding a thread to communicate the shutdown. No processing will be done on this thread");
                    addStreamThread();
                }
                if (throwable instanceof Error) {
                    log.error("This option requires running threads to shut down the application." +
                            "but the uncaught exception was an Error, which means this runtime is no " +
                            "longer in a well-defined state. Attempting to send the shutdown command anyway.", throwable);
                }
                if (Thread.currentThread().equals(globalStreamThread) && getNumLiveStreamThreads() == 0) {
                    log.error("Exception in global thread caused the application to attempt to shutdown." +
                            " This action will succeed only if there is at least one StreamThread running on this client." +
                            " Currently there are no running threads so will now close the client.");
                    closeToError();
                    break;
                }
                processStreamThread(thread -> thread.sendShutdownRequest(AssignorError.SHUTDOWN_REQUESTED));
                log.error("Encountered the following exception during processing " +
                        "and sent shutdown request for the entire application.", throwable);
                break;
        }
    }

    /**
     * Set the listener which is triggered whenever a {@link StateStore} is being restored in order to resume
     * processing.
     *
     * @param globalStateRestoreListener The listener triggered when {@link StateStore} is being restored.
     * @throws IllegalStateException if this {@code KafkaStreams} instance has already been started.
     */
    public void setGlobalStateRestoreListener(final StateRestoreListener globalStateRestoreListener) {
        synchronized (stateLock) {
            if (state.hasNotStarted()) {
                this.globalStateRestoreListener = globalStateRestoreListener;
            } else {
                throw new IllegalStateException("Can only set GlobalStateRestoreListener before calling start(). " +
                    "Current state is: " + state);
            }
        }
    }

    /**
     * Get read-only handle on global metrics registry, including streams client's own metrics plus
     * its embedded producer, consumer and admin clients' metrics.
     *
     * @return Map of all metrics.
     */
    public Map<MetricName, ? extends Metric> metrics() {
        final Map<MetricName, Metric> result = new LinkedHashMap<>();
        // producer and consumer clients are per-thread
        processStreamThread(thread -> {
            result.putAll(thread.producerMetrics());
            result.putAll(thread.consumerMetrics());
            // admin client is shared, so we can actually move it
            // to result.putAll(adminClient.metrics()).
            // we did it intentionally just for flexibility.
            result.putAll(thread.adminClientMetrics());
        });
        // global thread's consumer client
        if (globalStreamThread != null) {
            result.putAll(globalStreamThread.consumerMetrics());
        }
        // self streams metrics
        result.putAll(metrics.metrics());
        return Collections.unmodifiableMap(result);
    }

    /**
     * Class that handles stream thread transitions
     */
    final class StreamStateListener implements StreamThread.StateListener {
        private final Map<Long, StreamThread.State> threadState;
        private GlobalStreamThread.State globalThreadState;
        // this lock should always be held before the state lock
        private final Object threadStatesLock;

        StreamStateListener(final Map<Long, StreamThread.State> threadState,
                            final GlobalStreamThread.State globalThreadState) {
            this.threadState = threadState;
            this.globalThreadState = globalThreadState;
            this.threadStatesLock = new Object();
        }

        /**
         * If all threads are up, including the global thread, set to RUNNING
         */
        private void maybeSetRunning() {
            // state can be transferred to RUNNING if all threads are either RUNNING or DEAD
            for (final StreamThread.State state : threadState.values()) {
                if (state != StreamThread.State.RUNNING && state != StreamThread.State.DEAD) {
                    return;
                }
            }

            // the global state thread is relevant only if it is started. There are cases
            // when we don't have a global state thread at all, e.g., when we don't have global KTables
            if (globalThreadState != null && globalThreadState != GlobalStreamThread.State.RUNNING) {
                return;
            }

            setState(State.RUNNING);
        }


        @Override
        public synchronized void onChange(final Thread thread,
                                          final ThreadStateTransitionValidator abstractNewState,
                                          final ThreadStateTransitionValidator abstractOldState) {
            synchronized (threadStatesLock) {
                // StreamThreads first
                if (thread instanceof StreamThread) {
                    final StreamThread.State newState = (StreamThread.State) abstractNewState;
                    threadState.put(thread.getId(), newState);

                    if (newState == StreamThread.State.PARTITIONS_REVOKED || newState == StreamThread.State.PARTITIONS_ASSIGNED) {
                        setState(State.REBALANCING);
                    } else if (newState == StreamThread.State.RUNNING) {
                        maybeSetRunning();
                    }
                } else if (thread instanceof GlobalStreamThread) {
                    // global stream thread has different invariants
                    final GlobalStreamThread.State newState = (GlobalStreamThread.State) abstractNewState;
                    globalThreadState = newState;

                    if (newState == GlobalStreamThread.State.RUNNING) {
                        maybeSetRunning();
                    } else if (newState == GlobalStreamThread.State.DEAD) {
                        if (state != State.PENDING_SHUTDOWN) {
                            log.error("Global thread has died. The streams application or client will now close to ERROR.");
                            closeToError();
                        }
                    }
                }
            }
        }
    }

    final class DelegatingStateRestoreListener implements StateRestoreListener {
        private void throwOnFatalException(final Exception fatalUserException,
                                           final TopicPartition topicPartition,
                                           final String storeName) {
            throw new StreamsException(
                    String.format("Fatal user code error in store restore listener for store %s, partition %s.",
                            storeName,
                            topicPartition),
                    fatalUserException);
        }

        @Override
        public void onRestoreStart(final TopicPartition topicPartition,
                                   final String storeName,
                                   final long startingOffset,
                                   final long endingOffset) {
            if (globalStateRestoreListener != null) {
                try {
                    globalStateRestoreListener.onRestoreStart(topicPartition, storeName, startingOffset, endingOffset);
                } catch (final Exception fatalUserException) {
                    throwOnFatalException(fatalUserException, topicPartition, storeName);
                }
            }
        }

        @Override
        public void onBatchRestored(final TopicPartition topicPartition,
                                    final String storeName,
                                    final long batchEndOffset,
                                    final long numRestored) {
            if (globalStateRestoreListener != null) {
                try {
                    globalStateRestoreListener.onBatchRestored(topicPartition, storeName, batchEndOffset, numRestored);
                } catch (final Exception fatalUserException) {
                    throwOnFatalException(fatalUserException, topicPartition, storeName);
                }
            }
        }

        @Override
        public void onRestoreEnd(final TopicPartition topicPartition, final String storeName, final long totalRestored) {
            if (globalStateRestoreListener != null) {
                try {
                    globalStateRestoreListener.onRestoreEnd(topicPartition, storeName, totalRestored);
                } catch (final Exception fatalUserException) {
                    throwOnFatalException(fatalUserException, topicPartition, storeName);
                }
            }
        }
    }

    /**
     * Create a {@code KafkaStreams} instance.
     * <p>
     * Note: even if you never call {@link #start()} on a {@code KafkaStreams} instance,
     * you still must {@link #close()} it to avoid resource leaks.
     *
     * @param topology the topology specifying the computational logic
     * @param props    properties for {@link StreamsConfig}
     * @throws StreamsException if any fatal error occurs
     */
    public KafkaStreams(final Topology topology,
                        final Properties props) {
        this(topology, new StreamsConfig(props), new DefaultKafkaClientSupplier());
    }

    /**
     * Create a {@code KafkaStreams} instance.
     * <p>
     * Note: even if you never call {@link #start()} on a {@code KafkaStreams} instance,
     * you still must {@link #close()} it to avoid resource leaks.
     *
     * @param topology       the topology specifying the computational logic
     * @param props          properties for {@link StreamsConfig}
     * @param clientSupplier the Kafka clients supplier which provides underlying producer and consumer clients
     *                       for the new {@code KafkaStreams} instance
     * @throws StreamsException if any fatal error occurs
     */
    public KafkaStreams(final Topology topology,
                        final Properties props,
                        final KafkaClientSupplier clientSupplier) {
        this(topology, new StreamsConfig(props), clientSupplier, Time.SYSTEM);
    }

    /**
     * Create a {@code KafkaStreams} instance.
     * <p>
     * Note: even if you never call {@link #start()} on a {@code KafkaStreams} instance,
     * you still must {@link #close()} it to avoid resource leaks.
     *
     * @param topology       the topology specifying the computational logic
     * @param props          properties for {@link StreamsConfig}
     * @param time           {@code Time} implementation; cannot be null
     * @throws StreamsException if any fatal error occurs
     */
    public KafkaStreams(final Topology topology,
                        final Properties props,
                        final Time time) {
        this(topology, new StreamsConfig(props), new DefaultKafkaClientSupplier(), time);
    }

    /**
     * Create a {@code KafkaStreams} instance.
     * <p>
     * Note: even if you never call {@link #start()} on a {@code KafkaStreams} instance,
     * you still must {@link #close()} it to avoid resource leaks.
     *
     * @param topology       the topology specifying the computational logic
     * @param props          properties for {@link StreamsConfig}
     * @param clientSupplier the Kafka clients supplier which provides underlying producer and consumer clients
     *                       for the new {@code KafkaStreams} instance
     * @param time           {@code Time} implementation; cannot be null
     * @throws StreamsException if any fatal error occurs
     */
    public KafkaStreams(final Topology topology,
                        final Properties props,
                        final KafkaClientSupplier clientSupplier,
                        final Time time) {
        this(topology, new StreamsConfig(props), clientSupplier, time);
    }

    /**
     * Create a {@code KafkaStreams} instance.
     * <p>
     * Note: even if you never call {@link #start()} on a {@code KafkaStreams} instance,
     * you still must {@link #close()} it to avoid resource leaks.
     *
     * @param topology  the topology specifying the computational logic
     * @param applicationConfigs    configs for Kafka Streams
     * @throws StreamsException if any fatal error occurs
     */
    public KafkaStreams(final Topology topology,
                        final StreamsConfig applicationConfigs) {
        this(topology, applicationConfigs, new DefaultKafkaClientSupplier());
    }

    /**
     * Create a {@code KafkaStreams} instance.
     * <p>
     * Note: even if you never call {@link #start()} on a {@code KafkaStreams} instance,
     * you still must {@link #close()} it to avoid resource leaks.
     *
     * @param topology       the topology specifying the computational logic
     * @param applicationConfigs         configs for Kafka Streams
     * @param clientSupplier the Kafka clients supplier which provides underlying producer and consumer clients
     *                       for the new {@code KafkaStreams} instance
     * @throws StreamsException if any fatal error occurs
     */
    public KafkaStreams(final Topology topology,
                        final StreamsConfig applicationConfigs,
                        final KafkaClientSupplier clientSupplier) {
        this(new TopologyMetadata(topology.internalTopologyBuilder, applicationConfigs), applicationConfigs, clientSupplier);
    }

    /**
     * Create a {@code KafkaStreams} instance.
     * <p>
     * Note: even if you never call {@link #start()} on a {@code KafkaStreams} instance,
     * you still must {@link #close()} it to avoid resource leaks.
     *
     * @param topology       the topology specifying the computational logic
     * @param applicationConfigs         configs for Kafka Streams
     * @param time           {@code Time} implementation; cannot be null
     * @throws StreamsException if any fatal error occurs
     */
    public KafkaStreams(final Topology topology,
                        final StreamsConfig applicationConfigs,
                        final Time time) {
        this(new TopologyMetadata(topology.internalTopologyBuilder, applicationConfigs), applicationConfigs, new DefaultKafkaClientSupplier(), time);
    }

    private KafkaStreams(final Topology topology,
                         final StreamsConfig applicationConfigs,
                         final KafkaClientSupplier clientSupplier,
                         final Time time) throws StreamsException {
        this(new TopologyMetadata(topology.internalTopologyBuilder, applicationConfigs), applicationConfigs, clientSupplier, time);
    }

    protected KafkaStreams(final TopologyMetadata topologyMetadata,
                           final StreamsConfig applicationConfigs,
                           final KafkaClientSupplier clientSupplier) throws StreamsException {
        this(topologyMetadata, applicationConfigs, clientSupplier, Time.SYSTEM);
    }

    private KafkaStreams(final TopologyMetadata topologyMetadata,
                         final StreamsConfig applicationConfigs,
                         final KafkaClientSupplier clientSupplier,
                         final Time time) throws StreamsException {
        this.applicationConfigs = applicationConfigs;
        this.time = time;

        this.topologyMetadata = topologyMetadata;
        this.topologyMetadata.buildAndRewriteTopology();

        final boolean hasGlobalTopology = topologyMetadata.hasGlobalTopology();

        try {
            stateDirectory = new StateDirectory(applicationConfigs, time, topologyMetadata.hasPersistentStores(), topologyMetadata.hasNamedTopologies());
            processId = stateDirectory.initializeProcessId();
        } catch (final ProcessorStateException fatal) {
            throw new StreamsException(fatal);
        }

        // The application ID is a required config and hence should always have value
        final String userClientId = applicationConfigs.getString(StreamsConfig.CLIENT_ID_CONFIG);
        final String applicationId = applicationConfigs.getString(StreamsConfig.APPLICATION_ID_CONFIG);
        if (userClientId.length() <= 0) {
            clientId = applicationId + "-" + processId;
        } else {
            clientId = userClientId;
        }
        final LogContext logContext = new LogContext(String.format("stream-client [%s] ", clientId));
        this.log = logContext.logger(getClass());
        topologyMetadata.setLog(logContext);

        // use client id instead of thread client id since this admin client may be shared among threads
        this.clientSupplier = clientSupplier;
        adminClient = clientSupplier.getAdmin(applicationConfigs.getAdminConfigs(ClientUtils.getSharedAdminClientId(clientId)));

        log.info("Kafka Streams version: {}", ClientMetrics.version());
        log.info("Kafka Streams commit ID: {}", ClientMetrics.commitId());

        metrics = getMetrics(applicationConfigs, time, clientId);
        streamsMetrics = new StreamsMetricsImpl(
            metrics,
            clientId,
            applicationConfigs.getString(StreamsConfig.BUILT_IN_METRICS_VERSION_CONFIG),
            time
        );

        ClientMetrics.addVersionMetric(streamsMetrics);
        ClientMetrics.addCommitIdMetric(streamsMetrics);
        ClientMetrics.addApplicationIdMetric(streamsMetrics, applicationConfigs.getString(StreamsConfig.APPLICATION_ID_CONFIG));
        ClientMetrics.addTopologyDescriptionMetric(streamsMetrics, (metricsConfig, now) -> this.topologyMetadata.topologyDescriptionString());
        ClientMetrics.addStateMetric(streamsMetrics, (metricsConfig, now) -> state);
        threads = Collections.synchronizedList(new LinkedList<>());
        ClientMetrics.addNumAliveStreamThreadMetric(streamsMetrics, (metricsConfig, now) -> getNumLiveStreamThreads());

        streamsMetadataState = new StreamsMetadataState(
            this.topologyMetadata,
            parseHostInfo(applicationConfigs.getString(StreamsConfig.APPLICATION_SERVER_CONFIG)),
            logContext
        );

        oldHandler = false;
        streamsUncaughtExceptionHandler = this::defaultStreamsUncaughtExceptionHandler;
        delegatingStateRestoreListener = new DelegatingStateRestoreListener();

        totalCacheSize = applicationConfigs.getTotalCacheSize();
        inputBufferMaxBytes = applicationConfigs.getLong(StreamsConfig.INPUT_BUFFER_MAX_BYTES_CONFIG);
        final int numStreamThreads = topologyMetadata.getNumStreamThreads(applicationConfigs);

        GlobalStreamThread.State globalThreadState = null;
        if (hasGlobalTopology) {
            final String globalThreadId = clientId + "-GlobalStreamThread";
            globalStreamThread = new GlobalStreamThread(
                topologyMetadata.globalTaskTopology(),
                applicationConfigs,
                clientSupplier.getGlobalConsumer(applicationConfigs.getGlobalConsumerConfigs(clientId)),
                stateDirectory,
                0L,
                streamsMetrics,
                time,
                globalThreadId,
                delegatingStateRestoreListener,
                exception -> defaultStreamsUncaughtExceptionHandler(exception, false)
            );
            globalThreadState = globalStreamThread.state();
        }

        threadState = new HashMap<>(numStreamThreads);
        streamStateListener = new StreamStateListener(threadState, globalThreadState);

        final GlobalStateStoreProvider globalStateStoreProvider = new GlobalStateStoreProvider(this.topologyMetadata.globalStateStores());

        if (hasGlobalTopology) {
            globalStreamThread.setStateListener(streamStateListener);
        }

        queryableStoreProvider = new QueryableStoreProvider(globalStateStoreProvider);
        for (int i = 1; i <= numStreamThreads; i++) {
            createAndAddStreamThread(0L, 0L, i);
        }
        // Initially, all Stream Threads are created with 0 cache size and max buffer size and then resized here.
        resizeThreadCacheAndBufferMemory(numStreamThreads);

        stateDirCleaner = setupStateDirCleaner();
        rocksDBMetricsRecordingService = maybeCreateRocksDBMetricsRecordingService(clientId, applicationConfigs);
    }

    private StreamThread createAndAddStreamThread(final long cacheSizePerThread, final long maxBufferSizePerThread, final int threadIdx) {
        final StreamThread streamThread = StreamThread.create(
            topologyMetadata,
            applicationConfigs,
            clientSupplier,
            adminClient,
            processId,
            clientId,
            streamsMetrics,
            time,
            streamsMetadataState,
            cacheSizePerThread,
            maxBufferSizePerThread,
            stateDirectory,
            delegatingStateRestoreListener,
            threadIdx,
            KafkaStreams.this::closeToError,
            streamsUncaughtExceptionHandler
        );
        streamThread.setStateListener(streamStateListener);
        threads.add(streamThread);
        threadState.put(streamThread.getId(), streamThread.state());
        queryableStoreProvider.addStoreProviderForThread(streamThread.getName(), new StreamThreadStateStoreProvider(streamThread));
        return streamThread;
    }

    private static Metrics getMetrics(final StreamsConfig config, final Time time, final String clientId) {
        final MetricConfig metricConfig = new MetricConfig()
            .samples(config.getInt(StreamsConfig.METRICS_NUM_SAMPLES_CONFIG))
            .recordLevel(Sensor.RecordingLevel.forName(config.getString(StreamsConfig.METRICS_RECORDING_LEVEL_CONFIG)))
            .timeWindow(config.getLong(StreamsConfig.METRICS_SAMPLE_WINDOW_MS_CONFIG), TimeUnit.MILLISECONDS);
        final List<MetricsReporter> reporters = config.getConfiguredInstances(StreamsConfig.METRIC_REPORTER_CLASSES_CONFIG,
                                                                              MetricsReporter.class,
                                                                              Collections.singletonMap(StreamsConfig.CLIENT_ID_CONFIG, clientId));
        final JmxReporter jmxReporter = new JmxReporter();
        jmxReporter.configure(config.originals());
        reporters.add(jmxReporter);
        final MetricsContext metricsContext = new KafkaMetricsContext(JMX_PREFIX,
                                                                      config.originalsWithPrefix(CommonClientConfigs.METRICS_CONTEXT_PREFIX));
        return new Metrics(metricConfig, reporters, time, metricsContext);
    }

    /**
     * Adds and starts a stream thread in addition to the stream threads that are already running in this
     * Kafka Streams client.
     * <p>
     * Since the number of stream threads increases, the sizes of the caches in the new stream thread
     * and the existing stream threads are adapted so that the sum of the cache sizes over all stream
     * threads does not exceed the total cache size specified in configuration
     * {@link StreamsConfig#STATESTORE_CACHE_MAX_BYTES_CONFIG}.
     * <p>
     * Stream threads can only be added if this Kafka Streams client is in state RUNNING or REBALANCING.
     *
     * @return name of the added stream thread or empty if a new stream thread could not be added
     */
    public Optional<String> addStreamThread() {
        if (isRunningOrRebalancing()) {
            final StreamThread streamThread;
            synchronized (changeThreadCount) {
                final int threadIdx = getNextThreadIndex();
                // Creating thread should hold the lock in order to avoid duplicate thread index.
                // If the duplicate index happen, the metadata of thread may be duplicate too.
                // Also, we create the new thread with initial values of cache size and max buffer size as 0
                // and then resize them later
                streamThread = createAndAddStreamThread(0L, 0L, threadIdx);
                final int numLiveThreads = getNumLiveStreamThreads();
                resizeThreadCacheAndBufferMemory(numLiveThreads + 1);
                log.info("Adding StreamThread-{}, there are now {} threads with cache size/max buffer size values as {} per thread.",
                        threadIdx, numLiveThreads + 1, getThreadCacheAndBufferMemoryString());
            }

            synchronized (stateLock) {
                if (isRunningOrRebalancing()) {
                    streamThread.start();
                    return Optional.of(streamThread.getName());
                } else {
                    log.warn("Terminating the new thread because the Kafka Streams client is in state {}", state);
                    streamThread.shutdown();
                    threads.remove(streamThread);
                    resizeThreadCacheAndBufferMemory(getNumLiveStreamThreads());
                    log.info("Resizing thread cache and max buffer size per thread since new thread can not be " +
                                    "started, cache size/max buffer size per thread is {}", getThreadCacheAndBufferMemoryString());
                    return Optional.empty();
                }
            }
        } else {
            log.warn("Cannot add a stream thread when Kafka Streams client is in state {}", state);
            return Optional.empty();
        }
    }

    /**
     * Removes one stream thread out of the running stream threads from this Kafka Streams client.
     * <p>
     * The removed stream thread is gracefully shut down. This method does not specify which stream
     * thread is shut down.
     * <p>
     * Since the number of stream threads decreases, the sizes of the caches in the remaining stream
     * threads are adapted so that the sum of the cache sizes over all stream threads equals the total
     * cache size specified in configuration {@link StreamsConfig#STATESTORE_CACHE_MAX_BYTES_CONFIG}.
     *
     * @return name of the removed stream thread or empty if a stream thread could not be removed because
     *         no stream threads are alive
     */
    public Optional<String> removeStreamThread() {
        return removeStreamThread(Long.MAX_VALUE);
    }

    /**
     * Removes one stream thread out of the running stream threads from this Kafka Streams client.
     * <p>
     * The removed stream thread is gracefully shut down. This method does not specify which stream
     * thread is shut down.
     * <p>
     * Since the number of stream threads decreases, the sizes of the caches and buffer bytes in the remaining stream
     * threads are adapted so that the sum of the cache sizes and buffer bytes over all stream threads equals the total
     * cache size specified in configuration {@link StreamsConfig#STATESTORE_CACHE_MAX_BYTES_CONFIG} and
     * {@link StreamsConfig#INPUT_BUFFER_MAX_BYTES_CONFIG} respectively.
     *
     * @param timeout The length of time to wait for the thread to shutdown
     * @throws org.apache.kafka.common.errors.TimeoutException if the thread does not stop in time
     * @return name of the removed stream thread or empty if a stream thread could not be removed because
     *         no stream threads are alive
     */
    public Optional<String> removeStreamThread(final Duration timeout) {
        final String msgPrefix = prepareMillisCheckFailMsgPrefix(timeout, "timeout");
        final long timeoutMs = validateMillisecondDuration(timeout, msgPrefix);
        return removeStreamThread(timeoutMs);
    }

    private Optional<String> removeStreamThread(final long timeoutMs) throws TimeoutException {
        final long startMs = time.milliseconds();

        if (isRunningOrRebalancing()) {
            synchronized (changeThreadCount) {
                // make a copy of threads to avoid holding lock
                for (final StreamThread streamThread : new ArrayList<>(threads)) {
                    final boolean callingThreadIsNotCurrentStreamThread = !streamThread.getName().equals(Thread.currentThread().getName());
                    if (streamThread.isAlive() && (callingThreadIsNotCurrentStreamThread || getNumLiveStreamThreads() == 1)) {
                        log.info("Removing StreamThread " + streamThread.getName());
                        final Optional<String> groupInstanceID = streamThread.getGroupInstanceID();
                        streamThread.requestLeaveGroupDuringShutdown();
                        streamThread.shutdown();
                        if (!streamThread.getName().equals(Thread.currentThread().getName())) {
                            final long remainingTimeMs = timeoutMs - (time.milliseconds() - startMs);
                            if (remainingTimeMs <= 0 || !streamThread.waitOnThreadState(StreamThread.State.DEAD, remainingTimeMs)) {
                                log.warn("{} did not shutdown in the allotted time.", streamThread.getName());
                                // Don't remove from threads until shutdown is complete. We will trim it from the
                                // list once it reaches DEAD, and if for some reason it's hanging indefinitely in the
                                // shutdown then we should just consider this thread.id to be burned
                            } else {
                                log.info("Successfully removed {} in {}ms", streamThread.getName(), time.milliseconds() - startMs);
                                threads.remove(streamThread);
                                queryableStoreProvider.removeStoreProviderForThread(streamThread.getName());
                            }
                        } else {
                            log.info("{} is the last remaining thread and must remove itself, therefore we cannot wait "
                                    + "for it to complete shutdown as this will result in deadlock.", streamThread.getName());
                        }
                        resizeThreadCacheAndBufferMemory(getNumLiveStreamThreads());
                        log.info("Resizing thread cache/max buffer size due to removal of thread {}, new cache size/max buffer size per thread is {}", streamThread.getName(), getThreadCacheAndBufferMemoryString());
                        if (groupInstanceID.isPresent() && callingThreadIsNotCurrentStreamThread) {
                            final MemberToRemove memberToRemove = new MemberToRemove(groupInstanceID.get());
                            final Collection<MemberToRemove> membersToRemove = Collections.singletonList(memberToRemove);
                            final RemoveMembersFromConsumerGroupResult removeMembersFromConsumerGroupResult =
                                adminClient.removeMembersFromConsumerGroup(
                                    applicationConfigs.getString(StreamsConfig.APPLICATION_ID_CONFIG),
                                    new RemoveMembersFromConsumerGroupOptions(membersToRemove)
                                );
                            try {
                                final long remainingTimeMs = timeoutMs - (time.milliseconds() - startMs);
                                removeMembersFromConsumerGroupResult.memberResult(memberToRemove).get(remainingTimeMs, TimeUnit.MILLISECONDS);
                            } catch (final java.util.concurrent.TimeoutException e) {
                                log.error("Could not remove static member {} from consumer group {} due to a timeout: {}",
                                          groupInstanceID.get(), applicationConfigs.getString(StreamsConfig.APPLICATION_ID_CONFIG), e);
                                throw new TimeoutException(e.getMessage(), e);
                            } catch (final InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (final ExecutionException e) {
                                log.error("Could not remove static member {} from consumer group {} due to: {}",
                                          groupInstanceID.get(), applicationConfigs.getString(StreamsConfig.APPLICATION_ID_CONFIG), e);
                                throw new StreamsException(
                                        "Could not remove static member " + groupInstanceID.get()
                                            + " from consumer group " + applicationConfigs.getString(StreamsConfig.APPLICATION_ID_CONFIG)
                                            + " for the following reason: ",
                                        e.getCause()
                                );
                            }
                        }
                        final long remainingTimeMs = timeoutMs - (time.milliseconds() - startMs);
                        if (remainingTimeMs <= 0) {
                            throw new TimeoutException("Thread " + streamThread.getName() + " did not stop in the allotted time");
                        }
                        return Optional.of(streamThread.getName());
                    }
                }
            }
            log.warn("There are no threads eligible for removal");
        } else {
            log.warn("Cannot remove a stream thread when Kafka Streams client is in state  " + state());
        }
        return Optional.empty();
    }

    /**
     * Takes a snapshot and counts the number of stream threads which are not in PENDING_SHUTDOWN or DEAD
     *
     * note: iteration over SynchronizedList is not thread safe so it must be manually synchronized. However, we may
     * require other locks when looping threads and it could cause deadlock. Hence, we create a copy to avoid holding
     * threads lock when looping threads.
     * @return number of alive stream threads
     */
    private int getNumLiveStreamThreads() {
        final AtomicInteger numLiveThreads = new AtomicInteger(0);

        synchronized (threads) {
            processStreamThread(thread -> {
                if (thread.state() == StreamThread.State.DEAD) {
                    log.debug("Trimming thread {} from the threads list since it's state is {}", thread.getName(), StreamThread.State.DEAD);
                    threads.remove(thread);
                } else if (thread.state() == StreamThread.State.PENDING_SHUTDOWN) {
                    log.debug("Skipping thread {} from num live threads computation since it's state is {}",
                              thread.getName(), StreamThread.State.PENDING_SHUTDOWN);
                } else {
                    numLiveThreads.incrementAndGet();
                }
            });
            return numLiveThreads.get();
        }
    }

    private int getNextThreadIndex() {
        final HashSet<String> allLiveThreadNames = new HashSet<>();
        final AtomicInteger maxThreadId = new AtomicInteger(1);
        synchronized (threads) {
            processStreamThread(thread -> {
                // trim any DEAD threads from the list so we can reuse the thread.id
                // this is only safe to do once the thread has fully completed shutdown
                if (thread.state() == StreamThread.State.DEAD) {
                    threads.remove(thread);
                } else {
                    allLiveThreadNames.add(thread.getName());
                    // Assume threads are always named with the "-StreamThread-<threadId>" suffix
                    final int threadId = Integer.parseInt(thread.getName().substring(thread.getName().lastIndexOf("-") + 1));
                    if (threadId > maxThreadId.get()) {
                        maxThreadId.set(threadId);
                    }
                }
            });

            final String baseName = clientId + "-StreamThread-";
            for (int i = 1; i <= maxThreadId.get(); i++) {
                final String name = baseName + i;
                if (!allLiveThreadNames.contains(name)) {
                    return i;
                }
            }
            // It's safe to use threads.size() rather than getNumLiveStreamThreads() to infer the number of threads
            // here since we trimmed any DEAD threads earlier in this method while holding the lock
            return threads.size() + 1;
        }
    }

    private String getThreadCacheAndBufferMemoryString() {
        final StreamThread streamThread = threads.get(0);
        return streamThread.getCacheSize() + "/" + streamThread.getMaxBufferSize();
    }

    private void resizeThreadCacheAndBufferMemory(final int numStreamThreads) {
        final long cacheSizePerThread;
        final long inputBufferMaxBytesPerThread;
        if (numStreamThreads == 0) {
            cacheSizePerThread = totalCacheSize;
            inputBufferMaxBytesPerThread = inputBufferMaxBytes;
        } else {
            cacheSizePerThread = totalCacheSize / (numStreamThreads + (topologyMetadata.hasGlobalTopology() ? 1 : 0));
            inputBufferMaxBytesPerThread = inputBufferMaxBytes / (numStreamThreads + (topologyMetadata.hasGlobalTopology() ? 1 : 0));
        }
        processStreamThread(thread -> thread.resizeCacheAndBufferMemory(cacheSizePerThread, inputBufferMaxBytesPerThread));
        if (globalStreamThread != null) {
            globalStreamThread.resize(cacheSizePerThread);
        }
    }

    private ScheduledExecutorService setupStateDirCleaner() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, clientId + "-CleanupThread");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static ScheduledExecutorService maybeCreateRocksDBMetricsRecordingService(final String clientId,
                                                                                      final StreamsConfig config) {
        if (RecordingLevel.forName(config.getString(METRICS_RECORDING_LEVEL_CONFIG)) == RecordingLevel.DEBUG) {
            return Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread thread = new Thread(r, clientId + "-RocksDBMetricsRecordingTrigger");
                thread.setDaemon(true);
                return thread;
            });
        }
        return null;
    }

    private static HostInfo parseHostInfo(final String endPoint) {
        final HostInfo hostInfo = HostInfo.buildFromEndpoint(endPoint);
        if (hostInfo == null) {
            return StreamsMetadataState.UNKNOWN_HOST;
        } else {
            return hostInfo;
        }
    }

    /**
     * Start the {@code KafkaStreams} instance by starting all its threads.
     * This function is expected to be called only once during the life cycle of the client.
     * <p>
     * Because threads are started in the background, this method does not block.
     * However, if you have global stores in your topology, this method blocks until all global stores are restored.
     * As a consequence, any fatal exception that happens during processing is by default only logged.
     * If you want to be notified about dying threads, you can
     * {@link #setUncaughtExceptionHandler(Thread.UncaughtExceptionHandler) register an uncaught exception handler}
     * before starting the {@code KafkaStreams} instance.
     * <p>
     * Note, for brokers with version {@code 0.9.x} or lower, the broker version cannot be checked.
     * There will be no error and the client will hang and retry to verify the broker version until it
     * {@link StreamsConfig#REQUEST_TIMEOUT_MS_CONFIG times out}.

     * @throws IllegalStateException if process was already started
     * @throws StreamsException if the Kafka brokers have version 0.10.0.x or
     *                          if {@link StreamsConfig#PROCESSING_GUARANTEE_CONFIG exactly-once} is enabled for pre 0.11.0.x brokers
     */
    public synchronized void start() throws IllegalStateException, StreamsException {
        if (setState(State.REBALANCING)) {
            log.debug("Starting Streams client");

            if (globalStreamThread != null) {
                globalStreamThread.start();
            }

            final int numThreads = processStreamThread(StreamThread::start);

            log.info("Started {} stream threads", numThreads);

            final Long cleanupDelay = applicationConfigs.getLong(StreamsConfig.STATE_CLEANUP_DELAY_MS_CONFIG);
            stateDirCleaner.scheduleAtFixedRate(() -> {
                // we do not use lock here since we only read on the value and act on it
                if (state == State.RUNNING) {
                    stateDirectory.cleanRemovedTasks(cleanupDelay);
                }
            }, cleanupDelay, cleanupDelay, TimeUnit.MILLISECONDS);

            final long recordingDelay = 0;
            final long recordingInterval = 1;
            if (rocksDBMetricsRecordingService != null) {
                rocksDBMetricsRecordingService.scheduleAtFixedRate(
                    streamsMetrics.rocksDBMetricsRecordingTrigger(),
                    recordingDelay,
                    recordingInterval,
                    TimeUnit.MINUTES
                );
            }
        } else {
            throw new IllegalStateException("The client is either already started or already stopped, cannot re-start");
        }
    }

    /**
     * Shutdown this {@code KafkaStreams} instance by signaling all the threads to stop, and then wait for them to join.
     * This will block until all threads have stopped.
     */
    public void close() {
        close(Long.MAX_VALUE);
    }

    private Thread shutdownHelper(final boolean error) {
        stateDirCleaner.shutdownNow();
        if (rocksDBMetricsRecordingService != null) {
            rocksDBMetricsRecordingService.shutdownNow();
        }

        // wait for all threads to join in a separate thread;
        // save the current thread so that if it is a stream thread
        // we don't attempt to join it and cause a deadlock
        return new Thread(() -> {
            // notify all the threads to stop; avoid deadlocks by stopping any
            // further state reports from the thread since we're shutting down
            int numStreamThreads = processStreamThread(StreamThread::shutdown);

            log.info("Shutting down {} stream threads", numStreamThreads);

            topologyMetadata.wakeupThreads();

            numStreamThreads = processStreamThread(thread -> {
                try {
                    if (!thread.isRunning()) {
                        log.debug("Shutdown {} complete", thread.getName());

                        thread.join();
                    }
                } catch (final InterruptedException ex) {
                    log.warn("Shutdown {} interrupted", thread.getName());

                    Thread.currentThread().interrupt();
                }
            });

            log.info("Shutdown {} stream threads complete", numStreamThreads);

            if (globalStreamThread != null) {
                log.info("Shutting down the global stream threads");

                globalStreamThread.shutdown();
            }

            if (globalStreamThread != null && !globalStreamThread.stillRunning()) {
                try {
                    globalStreamThread.join();
                } catch (final InterruptedException e) {
                    log.warn("Shutdown the global stream thread interrupted");

                    Thread.currentThread().interrupt();
                }
                globalStreamThread = null;

                log.info("Shutdown global stream threads complete");
            }

            stateDirectory.close();
            adminClient.close();

            streamsMetrics.removeAllClientLevelSensorsAndMetrics();
            metrics.close();
            if (!error) {
                setState(State.NOT_RUNNING);
            } else {
                setState(State.ERROR);
            }
        }, clientId + "-CloseThread");
    }

    private boolean close(final long timeoutMs) {
        if (state.hasCompletedShutdown()) {
            log.info("Streams client is already in the terminal {} state, all resources are closed and the client has stopped.", state);
            return true;
        }
        if (state.isShuttingDown()) {
            log.info("Streams client is in {}, all resources are being closed and the client will be stopped.", state);
            if (state == State.PENDING_ERROR && waitOnState(State.ERROR, timeoutMs)) {
                log.info("Streams client stopped to ERROR completely");
                return true;
            } else if (state == State.PENDING_SHUTDOWN && waitOnState(State.NOT_RUNNING, timeoutMs)) {
                log.info("Streams client stopped to NOT_RUNNING completely");
                return true;
            } else {
                log.warn("Streams client cannot transition to {}} completely within the timeout",
                         state == State.PENDING_SHUTDOWN ? State.NOT_RUNNING : State.ERROR);
                return false;
            }
        }

        if (!setState(State.PENDING_SHUTDOWN)) {
            // if we can't transition to PENDING_SHUTDOWN but not because we're already shutting down, then it must be fatal
            log.error("Failed to transition to PENDING_SHUTDOWN, current state is {}", state);
            throw new StreamsException("Failed to shut down while in state " + state);
        } else {
            final Thread shutdownThread = shutdownHelper(false);

            shutdownThread.setDaemon(true);
            shutdownThread.start();
        }

        if (waitOnState(State.NOT_RUNNING, timeoutMs)) {
            log.info("Streams client stopped completely");
            return true;
        } else {
            log.info("Streams client cannot stop completely within the timeout");
            return false;
        }
    }

    private void closeToError() {
        if (!setState(State.PENDING_ERROR)) {
            log.info("Skipping shutdown since we are already in " + state());
        } else {
            final Thread shutdownThread = shutdownHelper(true);

            shutdownThread.setDaemon(true);
            shutdownThread.start();
        }
    }

    /**
     * Shutdown this {@code KafkaStreams} by signaling all the threads to stop, and then wait up to the timeout for the
     * threads to join.
     * A {@code timeout} of Duration.ZERO (or any other zero duration) makes the close operation asynchronous.
     * Negative-duration timeouts are rejected.
     *
     * @param timeout  how long to wait for the threads to shutdown
     * @return {@code true} if all threads were successfully stopped&mdash;{@code false} if the timeout was reached
     * before all threads stopped
     * Note that this method must not be called in the {@link StateListener#onChange(KafkaStreams.State, KafkaStreams.State)} callback of {@link StateListener}.
     * @throws IllegalArgumentException if {@code timeout} can't be represented as {@code long milliseconds}
     */
    public synchronized boolean close(final Duration timeout) throws IllegalArgumentException {
        final String msgPrefix = prepareMillisCheckFailMsgPrefix(timeout, "timeout");
        final long timeoutMs = validateMillisecondDuration(timeout, msgPrefix);
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("Timeout can't be negative.");
        }

        log.debug("Stopping Streams client with timeoutMillis = {} ms.", timeoutMs);

        return close(timeoutMs);
    }

    /**
     * Do a clean up of the local {@link StateStore} directory ({@link StreamsConfig#STATE_DIR_CONFIG}) by deleting all
     * data with regard to the {@link StreamsConfig#APPLICATION_ID_CONFIG application ID}.
     * <p>
     * May only be called either before this {@code KafkaStreams} instance is {@link #start() started} or after the
     * instance is {@link #close() closed}.
     * <p>
     * Calling this method triggers a restore of local {@link StateStore}s on the next {@link #start() application start}.
     *
     * @throws IllegalStateException if this {@code KafkaStreams} instance has been started and hasn't fully shut down
     * @throws StreamsException if cleanup failed
     */
    public void cleanUp() {
        if (!(state.hasNotStarted() || state.hasCompletedShutdown())) {
            throw new IllegalStateException("Cannot clean up while running.");
        }
        stateDirectory.clean();
    }

    /**
     * Find all currently running {@code KafkaStreams} instances (potentially remotely) that use the same
     * {@link StreamsConfig#APPLICATION_ID_CONFIG application ID} as this instance (i.e., all instances that belong to
     * the same Kafka Streams application) and return {@link StreamsMetadata} for each discovered instance.
     * <p>
     * Note: this is a point in time view and it may change due to partition reassignment.
     *
     * @return {@link StreamsMetadata} for each {@code KafkaStreams} instances of this application
     * @deprecated since 3.0.0 use {@link KafkaStreams#metadataForAllStreamsClients}
     */
    @Deprecated
    public Collection<org.apache.kafka.streams.state.StreamsMetadata> allMetadata() {
        validateIsRunningOrRebalancing();
        return streamsMetadataState.getAllMetadata().stream().map(streamsMetadata ->
                new org.apache.kafka.streams.state.StreamsMetadata(streamsMetadata.hostInfo(),
                        streamsMetadata.stateStoreNames(),
                        streamsMetadata.topicPartitions(),
                        streamsMetadata.standbyStateStoreNames(),
                        streamsMetadata.standbyTopicPartitions()))
                .collect(Collectors.toSet());
    }

    /**
     * Find all currently running {@code KafkaStreams} instances (potentially remotely) that use the same
     * {@link StreamsConfig#APPLICATION_ID_CONFIG application ID} as this instance (i.e., all instances that belong to
     * the same Kafka Streams application) and return {@link StreamsMetadata} for each discovered instance.
     * <p>
     * Note: this is a point in time view and it may change due to partition reassignment.
     *
     * @return {@link StreamsMetadata} for each {@code KafkaStreams} instances of this application
     */
    public Collection<StreamsMetadata> metadataForAllStreamsClients() {
        validateIsRunningOrRebalancing();
        return streamsMetadataState.getAllMetadata();
    }

    /**
     * Find all currently running {@code KafkaStreams} instances (potentially remotely) that
     * <ul>
     *   <li>use the same {@link StreamsConfig#APPLICATION_ID_CONFIG application ID} as this instance (i.e., all
     *       instances that belong to the same Kafka Streams application)</li>
     *   <li>and that contain a {@link StateStore} with the given {@code storeName}</li>
     * </ul>
     * and return {@link StreamsMetadata} for each discovered instance.
     * <p>
     * Note: this is a point in time view and it may change due to partition reassignment.
     *
     * @param storeName the {@code storeName} to find metadata for
     * @return {@link StreamsMetadata} for each {@code KafkaStreams} instances with the provide {@code storeName} of
     * this application
     * @deprecated since 3.0.0 use {@link KafkaStreams#streamsMetadataForStore} instead
     */
    @Deprecated
    public Collection<org.apache.kafka.streams.state.StreamsMetadata> allMetadataForStore(final String storeName) {
        validateIsRunningOrRebalancing();
        return streamsMetadataState.getAllMetadataForStore(storeName).stream().map(streamsMetadata ->
                new org.apache.kafka.streams.state.StreamsMetadata(streamsMetadata.hostInfo(),
                        streamsMetadata.stateStoreNames(),
                        streamsMetadata.topicPartitions(),
                        streamsMetadata.standbyStateStoreNames(),
                        streamsMetadata.standbyTopicPartitions()))
                .collect(Collectors.toSet());
    }

    /**
     * Find all currently running {@code KafkaStreams} instances (potentially remotely) that
     * <ul>
     *   <li>use the same {@link StreamsConfig#APPLICATION_ID_CONFIG application ID} as this instance (i.e., all
     *       instances that belong to the same Kafka Streams application)</li>
     *   <li>and that contain a {@link StateStore} with the given {@code storeName}</li>
     * </ul>
     * and return {@link StreamsMetadata} for each discovered instance.
     * <p>
     * Note: this is a point in time view and it may change due to partition reassignment.
     *
     * @param storeName the {@code storeName} to find metadata for
     * @return {@link StreamsMetadata} for each {@code KafkaStreams} instances with the provide {@code storeName} of
     * this application
     */
    public Collection<StreamsMetadata> streamsMetadataForStore(final String storeName) {
        validateIsRunningOrRebalancing();
        return streamsMetadataState.getAllMetadataForStore(storeName);
    }

    /**
     * Finds the metadata containing the active hosts and standby hosts where the key being queried would reside.
     *
     * @param storeName     the {@code storeName} to find metadata for
     * @param key           the key to find metadata for
     * @param keySerializer serializer for the key
     * @param <K>           key type
     * Returns {@link KeyQueryMetadata} containing all metadata about hosting the given key for the given store,
     * or {@code null} if no matching metadata could be found.
     */
    public <K> KeyQueryMetadata queryMetadataForKey(final String storeName,
                                                    final K key,
                                                    final Serializer<K> keySerializer) {
        validateIsRunningOrRebalancing();
        return streamsMetadataState.getKeyQueryMetadataForKey(storeName, key, keySerializer);
    }

    /**
     * Finds the metadata containing the active hosts and standby hosts where the key being queried would reside.
     *
     * @param storeName     the {@code storeName} to find metadata for
     * @param key           the key to find metadata for
     * @param partitioner the partitioner to be use to locate the host for the key
     * @param <K>           key type
     * Returns {@link KeyQueryMetadata} containing all metadata about hosting the given key for the given store, using the
     * the supplied partitioner, or {@code null} if no matching metadata could be found.
     */
    public <K> KeyQueryMetadata queryMetadataForKey(final String storeName,
                                                    final K key,
                                                    final StreamPartitioner<? super K, ?> partitioner) {
        validateIsRunningOrRebalancing();
        return streamsMetadataState.getKeyQueryMetadataForKey(storeName, key, partitioner);
    }

    /**
     * Get a facade wrapping the local {@link StateStore} instances with the provided {@link StoreQueryParameters}.
     * The returned object can be used to query the {@link StateStore} instances.
     *
     * @param storeQueryParameters   the parameters used to fetch a queryable store
     * @return A facade wrapping the local {@link StateStore} instances
     * @throws StreamsNotStartedException If Streams has not yet been started. Just call {@link KafkaStreams#start()}
     *                                    and then retry this call.
     * @throws UnknownStateStoreException If the specified store name does not exist in the topology.
     * @throws InvalidStateStorePartitionException If the specified partition does not exist.
     * @throws InvalidStateStoreException If the Streams instance isn't in a queryable state.
     *                                    If the store's type does not match the QueryableStoreType,
     *                                    the Streams instance is not in a queryable state with respect
     *                                    to the parameters, or if the store is not available locally, then
     *                                    an InvalidStateStoreException is thrown upon store access.
     */
    public <T> T store(final StoreQueryParameters<T> storeQueryParameters) {
        validateIsRunningOrRebalancing();
        final String storeName = storeQueryParameters.storeName();
        if (!topologyMetadata.hasStore(storeName)) {
            throw new UnknownStateStoreException(
                "Cannot get state store " + storeName + " because no such store is registered in the topology."
            );
        }
        return queryableStoreProvider.getStore(storeQueryParameters);
    }

    /**
     * handle each stream thread in a snapshot of threads.
     * noted: iteration over SynchronizedList is not thread safe so it must be manually synchronized. However, we may
     * require other locks when looping threads and it could cause deadlock. Hence, we create a copy to avoid holding
     * threads lock when looping threads.
     * @param consumer handler
     */
    protected int processStreamThread(final Consumer<StreamThread> consumer) {
        final List<StreamThread> copy = new ArrayList<>(threads);
        for (final StreamThread thread : copy) consumer.accept(thread);

        return copy.size();
    }

    /**
     * Returns runtime information about the local threads of this {@link KafkaStreams} instance.
     *
     * @return the set of {@link org.apache.kafka.streams.processor.ThreadMetadata}.
     * @deprecated since 3.0 use {@link #metadataForLocalThreads()}
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    public Set<org.apache.kafka.streams.processor.ThreadMetadata> localThreadsMetadata() {
        return metadataForLocalThreads().stream().map(threadMetadata -> new org.apache.kafka.streams.processor.ThreadMetadata(
                threadMetadata.threadName(),
                threadMetadata.threadState(),
                threadMetadata.consumerClientId(),
                threadMetadata.restoreConsumerClientId(),
                threadMetadata.producerClientIds(),
                threadMetadata.adminClientId(),
                threadMetadata.activeTasks().stream().map(taskMetadata -> new org.apache.kafka.streams.processor.TaskMetadata(
                        taskMetadata.taskId().toString(),
                        taskMetadata.topicPartitions(),
                        taskMetadata.committedOffsets(),
                        taskMetadata.endOffsets(),
                        taskMetadata.timeCurrentIdlingStarted())
                ).collect(Collectors.toSet()),
                threadMetadata.standbyTasks().stream().map(taskMetadata -> new org.apache.kafka.streams.processor.TaskMetadata(
                        taskMetadata.taskId().toString(),
                        taskMetadata.topicPartitions(),
                        taskMetadata.committedOffsets(),
                        taskMetadata.endOffsets(),
                        taskMetadata.timeCurrentIdlingStarted())
                ).collect(Collectors.toSet())))
                .collect(Collectors.toSet());
    }

    /**
     * Returns runtime information about the local threads of this {@link KafkaStreams} instance.
     *
     * @return the set of {@link ThreadMetadata}.
     */
    public Set<ThreadMetadata> metadataForLocalThreads() {
        final Set<ThreadMetadata> threadMetadata = new HashSet<>();
        processStreamThread(thread -> {
            synchronized (thread.getStateLock()) {
                if (thread.state() != StreamThread.State.DEAD) {
                    threadMetadata.add(thread.threadMetadata());
                }
            }
        });
        return threadMetadata;
    }

    /**
     * Returns {@link LagInfo}, for all store partitions (active or standby) local to this Streams instance. Note that the
     * values returned are just estimates and meant to be used for making soft decisions on whether the data in the store
     * partition is fresh enough for querying.
     *
     * Note: Each invocation of this method issues a call to the Kafka brokers. Thus its advisable to limit the frequency
     * of invocation to once every few seconds.
     *
     * @return map of store names to another map of partition to {@link LagInfo}s
     * @throws StreamsException if the admin client request throws exception
     */
    public Map<String, Map<Integer, LagInfo>> allLocalStorePartitionLags() {
        final List<Task> allTasks = new ArrayList<>();
        processStreamThread(thread -> allTasks.addAll(thread.allTasks().values()));
        return allLocalStorePartitionLags(allTasks);
    }

    protected Map<String, Map<Integer, LagInfo>> allLocalStorePartitionLags(final List<Task> tasksToCollectLagFor) {
        final Map<String, Map<Integer, LagInfo>> localStorePartitionLags = new TreeMap<>();
        final Collection<TopicPartition> allPartitions = new LinkedList<>();
        final Map<TopicPartition, Long> allChangelogPositions = new HashMap<>();

        // Obtain the current positions, of all the active-restoring and standby tasks
        for (final Task task : tasksToCollectLagFor) {
            allPartitions.addAll(task.changelogPartitions());
            // Note that not all changelog partitions, will have positions; since some may not have started
            allChangelogPositions.putAll(task.changelogOffsets());
        }

        log.debug("Current changelog positions: {}", allChangelogPositions);
        final Map<TopicPartition, ListOffsetsResultInfo> allEndOffsets;
        allEndOffsets = fetchEndOffsets(allPartitions, adminClient);
        log.debug("Current end offsets :{}", allEndOffsets);

        for (final Map.Entry<TopicPartition, ListOffsetsResultInfo> entry : allEndOffsets.entrySet()) {
            // Avoiding an extra admin API lookup by computing lags for not-yet-started restorations
            // from zero instead of the real "earliest offset" for the changelog.
            // This will yield the correct relative order of lagginess for the tasks in the cluster,
            // but it is an over-estimate of how much work remains to restore the task from scratch.
            final long earliestOffset = 0L;
            final long changelogPosition = allChangelogPositions.getOrDefault(entry.getKey(), earliestOffset);
            final long latestOffset = entry.getValue().offset();
            final LagInfo lagInfo = new LagInfo(changelogPosition == Task.LATEST_OFFSET ? latestOffset : changelogPosition, latestOffset);
            final String storeName = streamsMetadataState.getStoreForChangelogTopic(entry.getKey().topic());
            localStorePartitionLags.computeIfAbsent(storeName, ignored -> new TreeMap<>())
                .put(entry.getKey().partition(), lagInfo);
        }

        return Collections.unmodifiableMap(localStorePartitionLags);
    }

    /**
     * Run an interactive query against a state store.
     * <p>
     * This method allows callers outside of the Streams runtime to access the internal state of
     * stateful processors. See https://kafka.apache.org/documentation/streams/developer-guide/interactive-queries.html
     * for more information.
     *
     * @param <R> The result type specified by the query.
     * @throws StreamsNotStartedException If Streams has not yet been started. Just call {@link
     *                                    KafkaStreams#start()} and then retry this call.
     * @throws StreamsStoppedException    If Streams is in a terminal state like PENDING_SHUTDOWN,
     *                                    NOT_RUNNING, PENDING_ERROR, or ERROR. The caller should
     *                                    discover a new instance to query.
     * @throws UnknownStateStoreException If the specified store name does not exist in the
     *                                    topology.
     */
    @Evolving
    public <R> StateQueryResult<R> query(final StateQueryRequest<R> request) {
        final String storeName = request.getStoreName();
        if (!topologyMetadata.hasStore(storeName)) {
            throw new UnknownStateStoreException(
                "Cannot get state store "
                    + storeName
                    + " because no such store is registered in the topology."
            );
        }
        if (state().hasNotStarted()) {
            throw new StreamsNotStartedException(
                "KafkaStreams has not been started, you can retry after calling start()."
            );
        }
        if (state().isShuttingDown() || state.hasCompletedShutdown()) {
            throw new StreamsStoppedException(
                "KafkaStreams has been stopped (" + state + ")."
                    + " This instance can no longer serve queries."
            );
        }
        final StateQueryResult<R> result = new StateQueryResult<>();

        final Map<String, StateStore> globalStateStores = topologyMetadata.globalStateStores();
        if (globalStateStores.containsKey(storeName)) {
            // See KAFKA-13523
            result.setGlobalResult(
                QueryResult.forFailure(
                    FailureReason.UNKNOWN_QUERY_TYPE,
                    "Global stores do not yet support the KafkaStreams#query API. Use KafkaStreams#store instead."
                )
            );
        } else {
            for (final StreamThread thread : threads) {
                final Map<TaskId, Task> tasks = thread.allTasks();
                for (final Entry<TaskId, Task> entry : tasks.entrySet()) {

                    final TaskId taskId = entry.getKey();
                    final int partition = taskId.partition();
                    if (request.isAllPartitions()
                        || request.getPartitions().contains(partition)) {
                        final Task task = entry.getValue();
                        final StateStore store = task.getStore(storeName);
                        if (store != null) {
                            final StreamThread.State state = thread.state();
                            final boolean active = task.isActive();
                            if (request.isRequireActive()
                                && (state != StreamThread.State.RUNNING
                                || !active)) {
                                result.addResult(
                                    partition,
                                    QueryResult.forFailure(
                                        FailureReason.NOT_ACTIVE,
                                        "Query requires a running active task,"
                                            + " but partition was in state "
                                            + state + " and was "
                                            + (active ? "active" : "not active") + "."
                                    )
                                );
                            } else {
                                final QueryResult<R> r = store.query(
                                    request.getQuery(),
                                    request.isRequireActive()
                                        ? PositionBound.unbounded()
                                        : request.getPositionBound(),
                                    new QueryConfig(request.executionInfoEnabled())
                                );
                                result.addResult(partition, r);
                            }


                            // optimization: if we have handled all the requested partitions,
                            // we can return right away.
                            if (!request.isAllPartitions()
                                && result.getPartitionResults().keySet().containsAll(request.getPartitions())) {
                                return result;
                            }
                        }
                    }
                }
            }
        }

        if (!request.isAllPartitions()) {
            for (final Integer partition : request.getPartitions()) {
                if (!result.getPartitionResults().containsKey(partition)) {
                    result.addResult(partition, QueryResult.forFailure(
                        FailureReason.NOT_PRESENT,
                        "The requested partition was not present at the time of the query."
                    ));
                }
            }
        }

        return result;
    }

}
