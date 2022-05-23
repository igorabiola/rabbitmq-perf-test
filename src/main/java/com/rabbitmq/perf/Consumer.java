// Copyright (c) 2007-2020 VMware, Inc. or its affiliates.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 2.0 ("MPL"), the GNU General Public License version 2
// ("GPL") and the Apache License version 2 ("ASL"). For the MPL, please see
// LICENSE-MPL-RabbitMQ. For the GPL, please see LICENSE-GPL2.  For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.perf;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.*;
import com.rabbitmq.client.AMQP.Queue.DeclareOk;
import com.rabbitmq.perf.PerfTest.EXIT_WHEN;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

public class Consumer extends AgentBase implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Consumer.class);

    private static final AckNackOperation ACK_OPERATION =
            (ch, envelope, multiple, requeue) -> ch.basicAck(envelope.getDeliveryTag(), multiple);

    private static final AckNackOperation NACK_OPERATION =
            (ch, envelope, multiple, requeue) -> ch.basicNack(envelope.getDeliveryTag(), multiple, requeue);
    static final String STOP_REASON_CONSUMER_REACHED_MESSAGE_LIMIT = "Consumer reached message limit";
    static final String STOP_REASON_CONSUMER_IDLE = "Consumer is idle for more than 1 second";
    static final String STOP_REASON_CONSUMER_QUEUE_EMPTY = "Consumer queue(s) empty";

    private volatile ConsumerImpl       q;
    private final Channel               channel;
    private final String                id;
    private final int                   txSize;
    private final boolean               autoAck;
    private final int                   multiAckEvery;
    private final boolean               requeue;
    private final Stats                 stats;
    private final int                   msgLimit;
    private final Map<String, String>   consumerTagBranchMap = Collections.synchronizedMap(new HashMap<>());
    private final ConsumerLatency       consumerLatency;
    private final BiFunction<BasicProperties, byte[], Long> timestampExtractor;
    private final TimestampProvider     timestampProvider;
    private final MulticastSet.CompletionHandler completionHandler;
    private final AtomicBoolean completed = new AtomicBoolean(false);

    private final AtomicReference<List<String>> queueNames = new AtomicReference<>();
    private final AtomicLong queueNamesVersion = new AtomicLong(0);
    private final List<String> initialQueueNames; // to keep original names of server-generated queue names (after recovery)
    private final List<String> queueNamesFull;

    private final ConsumerState state;

    private final Recovery.RecoveryProcess recoveryProcess;

    private final ExecutorService executorService;

    private final boolean polling;

    private final int pollingInterval;

    private final AckNackOperation ackNackOperation;

    private final Map<String, Object> consumerArguments;

    private final EXIT_WHEN exitWhen;

    private volatile long lastDeliveryTag, lastAckedDeliveryTag;

    public Consumer(ConsumerParameters parameters) {
        this.channel           = parameters.getChannel();
        this.id                = parameters.getId();
        this.txSize            = parameters.getTxSize();
        this.autoAck           = parameters.isAutoAck();
        this.multiAckEvery     = parameters.getMultiAckEvery();
        this.requeue           = parameters.isRequeue();
        this.stats             = parameters.getStats();
        this.msgLimit          = parameters.getMsgLimit();
        this.timestampProvider = parameters.getTimestampProvider();
        this.completionHandler = parameters.getCompletionHandler();
        this.executorService   = parameters.getExecutorService();
        this.polling           = parameters.isPolling();
        this.pollingInterval   = parameters.getPollingInterval();
        this.consumerArguments = parameters.getConsumerArguments();
        this.exitWhen          = parameters.getExitWhen();

        this.queueNamesFull = new ArrayList<>(parameters.getQueueNames());
        List<String> efectiveQueue = new ArrayList<>(this.queueNamesFull);
        this.queueNames.set(new ArrayList<>(efectiveQueue));
        this.initialQueueNames = new ArrayList<>(efectiveQueue);

        if(parameters.getConsumerLatenciesIndicator().isVariable()) {
            this.consumerLatency = new VariableConsumerLatency(parameters.getConsumerLatenciesIndicator());
        } else {
            long consumerLatencyInMicroSeconds = parameters.getConsumerLatenciesIndicator().getValue();
            if (consumerLatencyInMicroSeconds <= 0) {
                this.consumerLatency = new NoWaitConsumerLatency();
            } else if (consumerLatencyInMicroSeconds >= 1000) {
                this.consumerLatency = new ThreadSleepConsumerLatency(parameters.getConsumerLatenciesIndicator());
            } else {
                this.consumerLatency = new BusyWaitConsumerLatency(parameters.getConsumerLatenciesIndicator());
            }
        }

        if (timestampProvider.isTimestampInHeader()) {
            this.timestampExtractor = (properties, body) -> {
                    Object timestamp = properties.getHeaders().get(Producer.TIMESTAMP_HEADER);
                    return timestamp == null ? Long.MAX_VALUE : (Long) timestamp;
            };
        } else {
            this.timestampExtractor = (properties, body) -> {
                DataInputStream d = new DataInputStream(new ByteArrayInputStream(body));
                try {
                    d.readInt(); // read sequence number
                    return d.readLong();
                } catch (IOException e) {
                    throw new RuntimeException("Error while extracting timestamp from body");
                }

            };
        }

        if (parameters.isNack()) {
            this.ackNackOperation = NACK_OPERATION;
        } else {
            this.ackNackOperation = ACK_OPERATION;
        }


        this.state = new ConsumerState(parameters.getRateLimit(), timestampProvider);
        this.recoveryProcess = parameters.getRecoveryProcess();
        this.recoveryProcess.init(this);
    }

    public void run() {
        if (this.polling) {
            startBasicGetConsumer();
        } else {
            registerAsynchronousConsumer();
        }
    }

    public List<String> getQueueNames(){
        return this.queueNames.get();
    }

    public void shiftQueues( String oldQ, String newQ ) throws Exception{
        String consumerTag = consumerTagBranchMap.entrySet().stream().filter( e -> e.getValue().equals(oldQ) ).map(e -> e.getKey()).findFirst().orElse(null);
        if(consumerTag != null){
            this.channel.basicCancel(consumerTag);
            consumerTagBranchMap.remove(consumerTag);
            this.queueNames.get().remove(oldQ);
            this.initialQueueNames.remove(oldQ);
        }
        this.queueNames.get().add(newQ);
        this.initialQueueNames.add(newQ);
        registerAsynchronousConsumer(newQ);

    }

    private void startBasicGetConsumer() {
        this.executorService.execute(() -> {
            ConsumerImpl delegate = new ConsumerImpl(channel);
            final boolean shouldPause = this.pollingInterval > 0;
            long queueNamesVersion = this.queueNamesVersion.get();
            List<String> queues = this.queueNames.get();
            Channel ch = this.channel;
            Connection connection = this.channel.getConnection();
            while (!completed.get() && !Thread.interrupted()) {
                // queue name can change between recoveries, we refresh only if necessary
                if (queueNamesVersion != this.queueNamesVersion.get()) {
                    queues = this.queueNames.get();
                    queueNamesVersion = this.queueNamesVersion.get();
                }
                for (String queue : queues) {
                    if (!this.recoveryProcess.isRecoverying()) {
                        try {
                            GetResponse response = ch.basicGet(queue, autoAck);
                            if (response != null) {
                                delegate.handleMessage(response.getEnvelope(), response.getProps(), response.getBody(), ch);
                            }
                        } catch (IOException e) {
                            LOGGER.debug("Basic.get error on queue {}: {}", queue, e.getMessage());
                            try {
                                ch = connection.createChannel();
                            } catch (Exception ex) {
                                LOGGER.debug("Error while trying to create a channel: {}", queue, e.getMessage());
                            }
                        } catch (AlreadyClosedException e) {
                            LOGGER.debug("Tried to basic.get from a closed connection");
                        }
                        if (shouldPause) {
                            try {
                                Thread.sleep(this.pollingInterval);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    } else {
                        // The connection is recovering, waiting a bit.
                        // The duration is arbitrary: don't want to empty loop
                        // too much and don't want to catch too late with recovery
                        try {
                            LOGGER.debug("Recovery in progress, sleeping for a sec");
                            Thread.sleep(1000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        });
    }


    private void registerAsynchronousConsumer(String qName){
        try {
            if( q == null ){
                q = new ConsumerImpl(channel);
            }
            String tag = channel.basicConsume(qName, autoAck, this.consumerArguments, q);
            consumerTagBranchMap.put(tag, qName);
            
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ShutdownSignalException e) {
            throw new RuntimeException(e);
        }
    }

    private void registerAsynchronousConsumer() {

        for (String qName : queueNames.get()) {
            registerAsynchronousConsumer(qName);
        }
        
        /*
        try {
            q = new ConsumerImpl(channel);
            for (String qName : queueNames.get()) {
                String tag = channel.basicConsume(qName, autoAck, this.consumerArguments, q);
                consumerTagBranchMap.put(tag, qName);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ShutdownSignalException e) {
            throw new RuntimeException(e);
        }
        */
    }



    private class ConsumerImpl extends DefaultConsumer {

        private final boolean rateLimitation;

        private ConsumerImpl(Channel channel) {
            super(channel);
            state.setLastStatsTime(System.currentTimeMillis());
            state.setMsgCount(0);
            this.rateLimitation = state.getRateLimit() > 0.0f;
        }

        @Override
        public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties properties, byte[] body) throws IOException {
            this.handleMessage(envelope, properties, body, channel);
        }

        void handleMessage(Envelope envelope, BasicProperties properties, byte[] body, Channel ch) throws IOException {
            int currentMessageCount = state.incrementMessageCount();
            long nowTimestamp = timestampProvider.getCurrentTime();
            state.setLastActivityTimestamp(nowTimestamp);
            if (msgLimit == 0 || currentMessageCount <= msgLimit) {
                long messageTimestamp = timestampExtractor.apply(properties, body);
                long diff_time = timestampProvider.getDifference(nowTimestamp, messageTimestamp);

                if(initialQueueNames.contains(envelope.getRoutingKey())){
                    stats.handleRecv(diff_time);
                }else{
                    stats.handleRecv(id.equals(envelope.getRoutingKey()) ? diff_time : 0L);
                }

                if (consumerLatency.simulateLatency()) {
                    ackIfNecessary(envelope, currentMessageCount, ch);
                    commitTransactionIfNecessary(currentMessageCount, ch);
                    lastDeliveryTag = envelope.getDeliveryTag();

                    long now = System.currentTimeMillis();
                    if (this.rateLimitation) {
                        // if rate is limited, we need to reset stats every second
                        // otherwise pausing to throttle rate will be based on the whole history
                        // which is broken when rate varies
                        // as consumer does not choose the rate at which messages arrive,
                        // we can consider the rate is always subject to change,
                        // so we'd better off always resetting the stats
                        if (now - state.getLastStatsTime() > 1000) {
                            state.setLastStatsTime(now);
                            state.setMsgCount(0);
                        }
                        delay(now, state);
                    }
                }
            }
            if (msgLimit != 0 && currentMessageCount >= msgLimit) { // NB: not quite the inverse of above
                countDown(STOP_REASON_CONSUMER_REACHED_MESSAGE_LIMIT);
            }
        }

        private void ackIfNecessary(Envelope envelope, int currentMessageCount, final Channel ch) throws IOException {
            if (ackEnabled()) {
                dealWithWriteOperation(() -> {
                    if (multiAckEvery == 0) {
                        ackNackOperation.apply(ch, envelope, false, requeue);
                        lastAckedDeliveryTag = envelope.getDeliveryTag();
                    } else if (currentMessageCount % multiAckEvery == 0) {
                        ackNackOperation.apply(ch, envelope, true, requeue);
                        lastAckedDeliveryTag = envelope.getDeliveryTag();
                    }
                }, recoveryProcess);
            }
        }

        private void commitTransactionIfNecessary(int currentMessageCount, final Channel ch) throws IOException {
            if (transactionEnabled() && currentMessageCount % txSize == 0) {
                dealWithWriteOperation(() -> ch.txCommit(), recoveryProcess);
            }
        }

        @Override
        public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig) {
            LOGGER.debug(
                "Consumer received shutdown signal, recovery process enabled? {}, condition to trigger connection recovery? {}",
                recoveryProcess.isEnabled(), isConnectionRecoveryTriggered(sig)
            );
            if (!recoveryProcess.isEnabled()) {
                LOGGER.debug("Counting down for consumer");
                countDown("Consumer shut down");
            }
        }

        @Override
        public void handleCancel(String consumerTag) throws IOException {
            System.out.printf("Consumer cancelled by broker for tag: %s", consumerTag);
            if (consumerTagBranchMap.containsKey(consumerTag)) {
                String qName = consumerTagBranchMap.get(consumerTag);
                System.out.printf("Re-consuming. Queue: %s for Tag: %s", qName, consumerTag);
                channel.basicConsume(qName, autoAck, consumerArguments, q);
            } else {
                System.out.printf("Could not find queue for consumer tag: %s", consumerTag);
            }
        }
    }

    private boolean ackEnabled() {
        return !autoAck;
    }

    private boolean transactionEnabled() {
        return txSize != 0;
    }

    private void countDown(String reason) {
        if (completed.compareAndSet(false, true)) {
            completionHandler.countDown(reason);
        }
    }

    @Override
    public void recover(TopologyRecording topologyRecording) {
        if (this.polling) {
            // we get the "latest" names of the queue (useful only when there are server-generated name for recovered queues)
            List<String> queues = new ArrayList<>(this.initialQueueNames.size());
            for (String queue : initialQueueNames) {
                queues.add(queueName(topologyRecording, queue));
            }
            this.queueNames.set(queues);
            this.queueNamesVersion.incrementAndGet();
        } else {
            for (Map.Entry<String, String> entry : consumerTagBranchMap.entrySet()) {
                String queueName = queueName(topologyRecording, entry.getValue());
                LOGGER.debug("Recovering consumer, starting consuming on {}", queueName);
                try {
                    channel.basicConsume(queueName, autoAck, entry.getKey(), false, false, this.consumerArguments, q);
                } catch (IOException e) {
                    LOGGER.warn(
                            "Error while recovering consumer {} on queue {} on connection {}",
                            entry.getKey(), queueName, channel.getConnection().getClientProvidedName(), e
                    );
                }
            }
        }
    }

    void maybeStopIfNoActivityOrQueueEmpty() {
        LOGGER.debug("Checking consumer activity");
        if (this.exitWhen == EXIT_WHEN.NEVER) {
            return;
        }
        TimestampProvider tp = state.getTimestampProvider();
        long lastActivityTimestamp = state.getLastActivityTimestamp();
        if (lastActivityTimestamp == -1) {
            // this avoids not terminating a consumer that never consumes
            state.setLastActivityTimestamp(tp.getCurrentTime());
            return;
        }
        Duration idleDuration = tp.difference(tp.getCurrentTime(), lastActivityTimestamp);
        if (idleDuration.toMillis() > 1000) {
            LOGGER.debug("Consumer idle for {}", idleDuration);
            List<String> queues = queueNames.get();
            if (this.exitWhen == EXIT_WHEN.IDLE) {
                maybeAckCommitBeforeExit();
                LOGGER.debug("Terminating consumer {} because of inactivity", this);
                countDown(STOP_REASON_CONSUMER_IDLE);
            } else if (this.exitWhen == EXIT_WHEN.EMPTY){
                LOGGER.debug("Checking content of consumer queue(s)");
                boolean empty = false;
                for (String queue : queues) {
                    try {
                        DeclareOk declareOk = this.channel.queueDeclarePassive(queue);
                        LOGGER.debug("Message count for queue {}: {}", queue, declareOk.getMessageCount());
                        if (declareOk.getMessageCount() == 0) {
                            empty = true;
                        }
                    } catch (IOException e) {
                        LOGGER.info("Error when calling queue.declarePassive({}) in consumer {}", queue, this);
                    }
                }
                if (empty) {
                    maybeAckCommitBeforeExit();
                    LOGGER.debug("Terminating consumer {} because its queue(s) is (are) empty", this);
                    countDown(STOP_REASON_CONSUMER_QUEUE_EMPTY);
                }
            }
        }
    }

    private void maybeAckCommitBeforeExit() {
        if (ackEnabled() && lastAckedDeliveryTag < lastDeliveryTag) {
            LOGGER.debug("Acking/committing before exit");
            try {
                dealWithWriteOperation(() -> {
                    this.channel.basicAck(lastDeliveryTag, true);
                    if (transactionEnabled()) {
                        this.channel.txCommit();
                    }
                }, recoveryProcess);
            } catch (IOException e) {
                LOGGER.warn("Error while acking/committing on exit: {}", e.getMessage());
            }
        }
    }

    private static String queueName(TopologyRecording recording, String queue) {
        TopologyRecording.RecordedQueue queueRecord = recording.queue(queue);
        // The recording is missing when using pre-declared, so just using the initial name.
        // This is a decent fallback as the record is useful only to have the new name
        // of the queue after recovery (for server-generated queue names).
        // Queue names are supposed to be stable when using pre-declared.
        return queueRecord == null ? queue : queueRecord.name();
    }

    private static class ConsumerState implements AgentState {

        private final float rateLimit;
        private volatile long  lastStatsTime;
        private volatile long lastActivityTimestamp = -1;
        private final AtomicInteger msgCount = new AtomicInteger(0);
        private final TimestampProvider timestampProvider;

        protected ConsumerState(float rateLimit,
            TimestampProvider timestampProvider) {
            this.rateLimit = rateLimit;
            this.timestampProvider = timestampProvider;
        }

        public float getRateLimit() {
            return rateLimit;
        }

        public long getLastStatsTime() {
            return lastStatsTime;
        }

        protected void setLastStatsTime(long lastStatsTime) {
            this.lastStatsTime = lastStatsTime;
        }

        public void setLastActivityTimestamp(long lastActivityTimestamp) {
            this.lastActivityTimestamp = lastActivityTimestamp;
        }

        public long getLastActivityTimestamp() {
            return lastActivityTimestamp;
        }

        public int getMsgCount() {
            return msgCount.get();
        }

        public TimestampProvider getTimestampProvider() {
            return timestampProvider;
        }

        protected void setMsgCount(int msgCount) {
            this.msgCount.set(msgCount);
        }

        public int incrementMessageCount() {
            return this.msgCount.incrementAndGet();
        }

    }

    private interface ConsumerLatency {

        /**
         *
         * @return true if normal completion, false if not
         */
        boolean simulateLatency();

    }

    private static boolean latencySleep(long delay) {
        try {
            long ms = delay / 1000;
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean latencyBusyWait(long delay) {
        delay = delay * 1000;
        long start = System.nanoTime();
        while (System.nanoTime() - start < delay) ;
        return true;
    }

    private static class VariableConsumerLatency implements ConsumerLatency {

        private final ValueIndicator<Long> consumerLatenciesIndicator;

        private VariableConsumerLatency(ValueIndicator<Long> consumerLatenciesIndicator) {
            this.consumerLatenciesIndicator = consumerLatenciesIndicator;
        }

        @Override
        public boolean simulateLatency() {
            long consumerLatencyInMicroSeconds = consumerLatenciesIndicator.getValue();
            if (consumerLatencyInMicroSeconds <= 0) {
                return true;
            } else if (consumerLatencyInMicroSeconds >= 1000) {
                return latencySleep(consumerLatencyInMicroSeconds);
            } else {
                return latencyBusyWait(consumerLatencyInMicroSeconds);
            }
        }

    }

    private static class NoWaitConsumerLatency implements ConsumerLatency {

        @Override
        public boolean simulateLatency() {
            return true;
        }

    }

    private static class ThreadSleepConsumerLatency implements ConsumerLatency {

        private final ValueIndicator<Long> consumerLatenciesIndicator;

        private ThreadSleepConsumerLatency(ValueIndicator<Long> consumerLatenciesIndicator) {
            this.consumerLatenciesIndicator = consumerLatenciesIndicator;
        }

        @Override
        public boolean simulateLatency() {
            return latencySleep(consumerLatenciesIndicator.getValue());
        }
    }

    // from https://stackoverflow.com/a/11499351
    private static class BusyWaitConsumerLatency implements ConsumerLatency {

        private final ValueIndicator<Long> consumerLatenciesIndicator;

        private BusyWaitConsumerLatency(ValueIndicator<Long> consumerLatenciesIndicator) {
            this.consumerLatenciesIndicator = consumerLatenciesIndicator;
        }

        @Override
        public boolean simulateLatency() {
            return latencyBusyWait(consumerLatenciesIndicator.getValue());
        }
    }

    @FunctionalInterface
    private interface AckNackOperation {

        void apply(Channel channel, Envelope envelope, boolean multiple, boolean requeue) throws IOException;

    }

}
