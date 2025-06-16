/*
 * Copyright (c) 2018-2022 Cisco Systems, Inc. and others.  All rights reserved.
 */
package org.bgpdata;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bgpdata.api.parsed.message.*;
import org.bgpdata.api.parsed.processor.Router;
import org.bgpdata.api.parsed.processor.Peer;
import org.bgpdata.api.parsed.processor.UnicastPrefix;
import org.bgpdata.api.parsed.processor.L3VpnPrefix;
import org.bgpdata.api.parsed.processor.Collector;
import org.bgpdata.api.parsed.processor.BaseAttribute;
import org.bgpdata.api.parsed.processor.LsNode;
import org.bgpdata.api.parsed.processor.LsLink;
import org.bgpdata.api.parsed.processor.LsPrefix;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.bgpdata.psqlquery.*;

import java.math.BigInteger;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.bgpdata.psqlquery.PsqlFunctions.create_sql_string;

/**
 * Consumer class
 *
 *   A thread to process a topic partition.  Supports all bgpdata.parsed.* topics.
 */
public class ConsumerRunnable implements Runnable {

    public enum ThreadType {
        THREAD_DEFAULT(0);
        //THREAD_ATTRIBUTES(1);

        private final int value;

        private ThreadType(int value) {
            this.value = value;
        }

        public int getValue() {
            return this.value;
        }
    }

    private static final Logger logger = LogManager.getFormatterLogger(ConsumerRunnable.class.getName());
    private Boolean running;
    private Boolean nowShutdown;

    private ExecutorService executor;
    private Long last_collector_msg_time;
    private Long last_writer_thread_chg_time;


    private KafkaConsumer<String, String> consumer;
    private KafkaProducer<String, String> producer;
    private ConsumerRebalanceListener rebalanceListener;
    private Config cfg;
    private PSQLHandler db;

    private int topics_subscribed_count;
    private boolean topics_all_subscribed;
    private List<Pattern> topic_patterns;
    private StringBuilder topic_regex_pattern;

    private HashMap<String, Long> processed_attr;

    private BigInteger messageCount;
    private long collector_msg_count;
    private long router_msg_count;
    private long peer_msg_count;
    private long base_attribute_msg_count;
    private long unicast_prefix_msg_count;
    private long l3vpn_prefix_msg_count;
    private long ls_node_msg_count;
    private long ls_link_msg_count;
    private long ls_prefix_msg_count;
    private long subscription_msg_count;
    private long stat_msg_count;

    private Collection<TopicPartition> pausedTopics;
    private long last_paused_time;

    /*
     * Writers thread map
     *      Key = Type of thread
     *      Value = List of writers
     *
     * Each writer object has the following:
     *      Connection to MySQL
     *      FIFO msg queue
     *      Map of assigned record keys to the writer
     */
    private final Map<ThreadType, List<WriterObject>> writer_thread_map;

    /**
     * routerMap is a persistent map routers and associated info in RouterObject
     *      routerMap[routerHashId] = RouterObject
     */
    private Map<String, RouterObject> routerMap;

    /**
     * Consumer queue/buffer of messages to send to writers
     */
    private final LinkedBlockingQueue<ConsumerMessageObject> message_queue;

    /**
     * Subscriptions
     */
    private Map<String, Long> subscriptions;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * Constructor
     *
     * @param cfg                  Configuration from cli/config file
     */
    public ConsumerRunnable(Config cfg) {

        message_queue = new LinkedBlockingQueue<>(cfg.getConsumer_queue_size());
        writer_thread_map = new HashMap<>();
        last_writer_thread_chg_time = 0L;

        processed_attr = new HashMap<>();

        messageCount = BigInteger.valueOf(0);
        this.cfg = cfg;
        this.routerMap = new HashMap<>();
        db = new PSQLHandler(cfg);

        this.running = true;
        this.nowShutdown = false;

        pausedTopics = new HashSet<>();
        last_paused_time = 0L;

        this.producer = new KafkaProducer<>(cfg.getKafka_producer_props());

        /*
         * It's imperative to first process messages from some topics before subscribing to others.
         *    When connecting to Kafka, topics will be subscribed at an interval.  When the
         *    topics_subscribe_count is equal to the topics size, then all topics have been subscribed to.
         */
        this.topics_subscribed_count = 0;
        this.topics_all_subscribed = false;
        this.topic_patterns = new LinkedList<>();

        // Convert to list so that we can access items by index.
        for (Iterator<Pattern> it = cfg.getKafka_topic_patterns().iterator(); it.hasNext(); ) {
            this.topic_patterns.add(it.next());
        }

        this.topic_regex_pattern = new StringBuilder();

        this.rebalanceListener = new ConsumerRebalanceListener(consumer);


        /*
         * Start the subscription manager
         */
        this.subscriptions = new ConcurrentHashMap<>();
        scheduler.scheduleAtFixedRate(this::cleanup_subscriptions, 30, 30, TimeUnit.SECONDS);

        /*
         * Start DB Writer thread - one thread per type
         */
        executor = Executors.newFixedThreadPool(cfg.getWriter_max_threads_per_type() * ThreadType.values().length);

        // Init the list of threads for each thread type
        for (ThreadType t: ThreadType.values()) {
            writer_thread_map.put(t, new ArrayList<WriterObject>());

            // Start max writers first
            for (int i=0; i < cfg.getWriter_max_threads_per_type(); i++) {
                addWriterThread(t);
            }
        }
    }

    private void cleanup_subscriptions() {
        try {
            long now = System.currentTimeMillis();
            int expired_count = 0;

            for (Iterator<Map.Entry<String, Long>> it = subscriptions.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Long> entry = it.next();

                if (entry.getValue() < now) {
                    it.remove();
                    expired_count++;
                }
            }

            if (expired_count > 0) {
                logger.info("Expired %d subscriptions", expired_count);
            }
        } catch (Exception e) {
            logger.error("Error cleaning up subscriptions", e);
        }
    }

    /**
     * Thread safe shutdown
     */
    synchronized public void safe_shutdown() {
        nowShutdown = true;
    }

    /**
     * Shutdown this thread and its threads
     */
    public void shutdown() {
        logger.info("postgres consumer thread shutting down");

        // Drain message queue
        logger.info("draining message queue %d", message_queue.size());
        int i = 0;
        int prev_queue_size = message_queue.size();
        int stalled_queue_check_count = 0;
        while (message_queue.size() > 0 && stalled_queue_check_count < 500) {

            if (prev_queue_size != message_queue.size()) {
                stalled_queue_check_count = 0;
            } else {
                stalled_queue_check_count++;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    // ignore
                }
            }
            prev_queue_size = message_queue.size();
            writePendingMessages();

            if (i > 100) {
                i = 0;
                logger.info("still draining message queue %d, stuck count %d",
                        message_queue.size(), stalled_queue_check_count);
            }

            i++;
        }

        // Shutdown all routers
        for (ThreadType t: ThreadType.values()) {
            shutdownWriters(t);
        }


        logger.info("Shutting down consumer");
        db.disconnect();

        if (executor != null) executor.shutdown();

        try {
            if (!executor.awaitTermination(5000, TimeUnit.MILLISECONDS)) {
                logger.warn("Timed out waiting for writer thread to shut down, exiting uncleanly");
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted during shutdown, exiting uncleanly");
        }

        running = false;

        close_consumer();
        close_producer();
    }

    private void close_consumer() {
        if (consumer != null) {
            consumer.close();
            consumer = null;
        }

    }

    private void close_producer() {
        if (producer != null) {
            producer.close();
            producer = null;
        }
    }

    /**
     * Connect to Kafka
     *
     * @return  True if connected, false if not connected
     */
    private boolean connect() {
        boolean status = false;

        try {
            close_consumer();

            consumer = new KafkaConsumer<>(cfg.getKafka_consumer_props());
            logger.info("Connected to kafka, subscribing to topics");

            this.rebalanceListener = new ConsumerRebalanceListener(consumer);

            status = true;

        } catch (ConfigException ex) {
            logger.error("Config Exception: %s", ex.getMessage());

        } catch (KafkaException ex) {
            logger.error("Exception: %s", ex.getMessage(), ex);

        } finally {
            return status;
        }

    }

    private void pause() {
        consumer.pause(consumer.assignment());
    }

    private void resume() {
        consumer.resume(consumer.paused());
    }

    private void resumePausedTopics() {
        if (pausedTopics.size() > 0) {
            logger.info("Resumed %d paused topics", pausedTopics.size());
            consumer.resume(pausedTopics);
            pausedTopics.clear();
        }
    }

    private void pauseUnicastPrefix() {
        Set<TopicPartition> topics = consumer.assignment();
        last_paused_time = System.currentTimeMillis();

        for (TopicPartition topic: topics) {
            if (topic.topic().equalsIgnoreCase("bgpdata.parsed.unicast_prefix")) {
                if (! pausedTopics.contains(topic)) {
                    logger.info("Paused bgpdata.parsed.unicast_prefix");
                    pausedTopics.add(topic);
                    consumer.pause(pausedTopics);
                }
                break;
            }
        }
    }

    /**
     * Run the thread
     */
    public void run() {
        boolean unicast_prefix_paused = false;

        logger.info("Consumer started");

        db.connect();

        if (connect() == false) {
            logger.error("Failed to connect to Kafka, consumer exiting");

            synchronized (running) {
                running = false;
            }

            return;

        } else {
            logger.debug("Connected and now consuming messages from kafka");

            synchronized (running) {
                running = true;
            }
        }

        /*
         * Continuously read from Kafka stream and parse messages
         */
        Map<String, String> query;
        long prev_time = System.currentTimeMillis();
        long subscribe_prev_timestamp = 0L;

        // Update router tracking with indexes
        updateRouterMap();

        while (nowShutdown == false && running) {

            // Subscribe to topics if needed
            if (!topics_all_subscribed) {
                subscribe_prev_timestamp = subscribe_topics(subscribe_prev_timestamp);

            }

            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(10));

                if (records == null || records.count() <= 0) {
                    writePendingMessages();
                    continue;
                }

                /*
                 * Pause collection so that consumer.poll() doesn't fetch but will send heartbeats
                 */
                pause();

                ThreadType thread_type;
                for (ConsumerRecord<String, String> record : records) {

                    try {
                        messageCount = messageCount.add(BigInteger.ONE);

                        //Extract the Headers and Content from the message.
                        Message message = new Message(record.value());

                        Base obj = null;
                        Query dbQuery = null;
                        thread_type = ThreadType.THREAD_DEFAULT;

                        /*
                         * Parse the data based on topic
                         */
                        query = new HashMap<String, String>();
                        if ((message.getType() != null && message.getType().equalsIgnoreCase("collector"))
                                || record.topic().equals("bgpdata.parsed.collector")) {
                            logger.trace("Parsing collector message");
                            collector_msg_count++;

                            Collector collector = new Collector(message.getContent());
                            CollectorQuery collectorQuery = new CollectorQuery(collector.records);

                            last_collector_msg_time = System.currentTimeMillis();

                            if (collectorQuery != null) {
                                db.updateQuery(create_sql_string(collectorQuery), cfg.getDb_retries());

                                String sql = collectorQuery.genRouterCollectorUpdate();

                                if (sql != null && !sql.isEmpty()) {
                                    logger.debug("collectorUpdate: %s", sql);

                                    db.updateQuery(sql, cfg.getDb_retries());
                                }

                                consumer.poll(Duration.ofMillis(0));            // heartbeat
                            }

                            continue;

                        } else if ((message.getType() != null && message.getType().equalsIgnoreCase("router"))
                                || record.topic().equals("bgpdata.parsed.router")) {

                            /*
                             * DB is updated directly within this thread, not bulk
                             */
                            logger.trace("Parsing router message");
                            router_msg_count++;

                            Router router = new Router(message.getContent());
                            RouterQuery routerQuery = new RouterQuery(message.getCollector_hash_id(), router.records);

                            if (routerQuery != null) {
                                // Add/update routers

                                db.updateQuery(create_sql_string(routerQuery), cfg.getDb_retries());

                                consumer.poll(Duration.ZERO);       // heartbeat

                                // Update peers based on router change
                                String sql = routerQuery.genPeerRouterUpdate(routerMap);

                                if (sql != null && !sql.isEmpty()) {
                                    logger.debug("RouterUpdate = %s", sql);
                                    db.updateQuery(sql, cfg.getDb_retries());
                                }

                                // Update router tracking with indexes
                                updateRouterMap();
                            }

                            continue;

                        } else if ((message.getType() != null && message.getType().equalsIgnoreCase("peer"))
                                || record.topic().equals("bgpdata.parsed.peer")) {
                            /*
                             * DB is updated directly within this thread
                             */
                            logger.trace("Parsing peer message");
                            peer_msg_count++;

                            Peer peer = new org.bgpdata.api.parsed.processor.Peer(message.getContent());
                            PeerQuery peerQuery = new PeerQuery(peer.records);

                            if (peerQuery != null) {

                                // Add/update peers
                                db.updateQuery(create_sql_string(peerQuery), cfg.getDb_retries());

                                consumer.poll(Duration.ofMillis(0));       // heartbeat

                                // Update rib tables based on peer
                                for (String sql : peerQuery.genRibPeerUpdate()) {
                                    logger.debug("Updating NLRI's for peer change: %s", sql);

                                    db.updateQuery(sql, cfg.getDb_retries());

                                    consumer.poll(Duration.ofMillis(0)); // heartbeat
                                }
                            }

                            continue;

                        } else if ((message.getType() != null && message.getType().equalsIgnoreCase("base_attribute"))
                                || record.topic().equals("bgpdata.parsed.base_attribute")) {
                            logger.trace("Parsing base_attribute message");
                            base_attribute_msg_count++;

                            //thread_type = ThreadType.THREAD_ATTRIBUTES;

                            List<BaseAttributePojo> ba_list = new ArrayList();
                            BaseAttribute ba_temp = new org.bgpdata.api.parsed.processor.BaseAttribute(message.getContent());

                            // Cache in memory processed base attributes.  If processed, skip adding it to the DB again
                            for (BaseAttributePojo ba_entry : ba_temp.records) {
                                if (processed_attr.containsKey(ba_entry.getHash())) {
                                    processed_attr.put(ba_entry.getHash(), System.currentTimeMillis());
                                    continue;

                                } else {
                                    processed_attr.put(ba_entry.getHash(), System.currentTimeMillis());
                                    ba_list.add(ba_entry);
                                }
                            }

                            if (ba_list.size() <= 0)
                                continue;

                            dbQuery = new BaseAttributeQuery(ba_list);

                        } else if ((message.getType() != null && message.getType().equalsIgnoreCase("unicast_prefix"))
                                || record.topic().equals("bgpdata.parsed.unicast_prefix")) {
                            logger.trace("Parsing unicast_prefix message");
                            unicast_prefix_msg_count++;

                            UnicastPrefix up = new UnicastPrefix(message.getContent());
                            dbQuery = new UnicastPrefixQuery(up.records);

                            for (UnicastPrefixPojo up_entry : up.records) {
                                Set<String> matched = new HashSet<>();
                            
                                if (up_entry.getOrigin_asn() != null && subscriptions.containsKey("AS" + up_entry.getOrigin_asn().toString())) {
                                    matched.add("AS" + up_entry.getOrigin_asn().toString());
                                }
                            
                                String asPath = up_entry.getAs_path();
                                if (asPath != null) {
                                    for (String asnStr : asPath.trim().split(" ")) {
                                        try {
                                            long asn = Long.parseLong(asnStr);
                                            if (subscriptions.containsKey("AS" + Long.toString(asn))) {
                                                matched.add("AS" + Long.toString(asn));
                                            }
                                        } catch (NumberFormatException ignored) {}
                                    }
                                }
                            
                                for (String resource : matched) {
                                    try {
                                        producer.send(new ProducerRecord<>("bgpdata.parsed.notification",
                                            String.format("update\t%s", resource)));
                                    } catch (Exception e) {
                                        logger.error("Failed to send update for resource: " + resource, e);
                                    }
                                }
                            }
                            
                        } else if ((message.getType() != null && message.getType().equalsIgnoreCase("l3vpn"))
                                || record.topic().equals("bgpdata.parsed.l3vpn")) {
                            logger.trace("Parsing L3VPN prefix message");
                            l3vpn_prefix_msg_count++;

                            L3VpnPrefix vp = new L3VpnPrefix(message.getContent());
                            dbQuery = new L3VpnPrefixQuery(vp.records);

                        } else if ((message.getType() != null && message.getType().equalsIgnoreCase("bmp_stat"))
                                || record.topic().equals("bgpdata.parsed.bmp_stat")) {
                            logger.trace("Parsing bmp_stat message");
                            stat_msg_count++;

                            obj = new BmpStat(message.getContent());
                            dbQuery = new BmpStatQuery(obj.getRowMap());

                        } else if ((message.getType() != null && message.getType().equalsIgnoreCase("ls_node"))
                                || record.topic().equals("bgpdata.parsed.ls_node")) {
                            logger.trace("Parsing ls_node message");
                            ls_node_msg_count++;

                            LsNode ls = new LsNode(message.getContent());
                            dbQuery = new LsNodeQuery(ls.records);

                        } else if ((message.getType() != null && message.getType().equalsIgnoreCase("ls_link"))
                                || record.topic().equals("bgpdata.parsed.ls_link")) {
                            logger.trace("Parsing ls_link message");
                            ls_link_msg_count++;

                            LsLink ls = new LsLink(message.getContent());
                            dbQuery = new LsLinkQuery(ls.records);

                        } else if ((message.getType() != null && message.getType().equalsIgnoreCase("ls_prefix"))
                                || record.topic().equals("bgpdata.parsed.ls_prefix")) {
                            logger.trace("Parsing ls_prefix message");
                            ls_prefix_msg_count++;

                            LsPrefix ls = new LsPrefix(message.getContent());
                            dbQuery = new LsPrefixQuery(ls.records);

                        } else if ((message.getType() != null && message.getType().equalsIgnoreCase("subscription"))
                                || record.topic().equals("bgpdata.parsed.subscription")) {
                            logger.trace("Parsing subscription message");
                            subscription_msg_count++;

                            try {
                                Subscription subscription = new Subscription(message.getContent());
                                if ("subscribe".equals(subscription.getAction())) {
                                    String resource = subscription.getResource();
                                    long expirationTime = System.currentTimeMillis() + (cfg.getSubscription_timeout_seconds() * 1000L);
                                    subscriptions.put(resource, expirationTime);
                                    logger.info("Received/Refreshed subscription for resource: " + resource + ". New expiration: " + expirationTime);
                                }
                            } catch (Exception e) {
                                logger.warn("Failed to parse subscription message: " + message.getContent(), e);
                            }
                        } else {
                            logger.debug("Topic %s not implemented, ignoring", record.topic());
                            continue;
                        }

                        /*
                         * Add query to writer queue
                         */
                        if (dbQuery != null) {
                            addBulkQuerytoWriter(record.key(), dbQuery.genInsertStatement(),
                                    dbQuery.genValuesStatement(), thread_type);
                        }

                    } catch (Exception ex) {
                        // ignore
                    }
                }

                // Check writer threads
                prev_time = checkWriterThreads(prev_time);

                writePendingMessages();

                resume();


            } catch (NullPointerException ex1 ) {
                logger.warn("Ignoring kafka consumer exception: ", ex1);

            } catch (Exception ex) {
                logger.warn("kafka consumer exception: ", ex);
                running = false;
            }
        }

        shutdown();
    }

    /*
     * Update the routersMap with the indexes and any other info needed from the DB.
     *
     *      This method is called after updating a router. This will ensure the indexes are up-to-date
     */
    private void updateRouterMap() {
        List<Map<String, String>> rows = db.selectQuery("SELECT name,hash_id,state from routers");

        try {
            if (rows.size() > 0) {
                routerMap.clear();

                for (Map<String, String> row: rows) {
                    String routerHash = row.get("hash_id").replaceAll("-", "");

                    logger.info("Updating router '%s' (%s) state = %s",
                                row.get("name"), routerHash, row.get("state"));

                    boolean isUp = row.get("state").equals("up") ? true : false;

                    RouterObject rObj;
                    if (routerMap.containsKey(routerHash)) {
                        rObj = routerMap.get(routerHash);

                    } else {
                        rObj = new RouterObject();

                        if (isUp) {
                            rObj.connection_count += 1;
                        }

                        routerMap.put(row.get("hash_id").replaceAll("-", ""), rObj);
                    }
                }
            }
        } catch (Exception ex) {
            logger.warn("Error getting router indexes: ", ex);
        }
    }

    private void resetOneWriter(WriterObject writer, ThreadType type) {
        logger.info("Resetting writer type %s, draining queue size = %d", type.toString(), writer.writerQueue.size());

        int i = 0;
        while (writer.writerQueue.size() > 0) {
            if (i >= 5000) {
                i = 0;
                consumer.poll(Duration.ZERO);           // NOTE: consumer is paused already.

                logger.info("    ... drain queue writer size is " + writer.writerQueue.size());
            }
            ++i;

            try {
                Thread.sleep(1);
            } catch (Exception ex) {
                break;
            }
        }

        writer.assigned.clear();
        writer.above_count = 0;
        writer.message_count = 0L;
    }

    private void resetWriters(ThreadType thread_type) {
        List<WriterObject> writers = writer_thread_map.get(thread_type);

        if (writers.size() <= 1)
            return;

        if (writers != null) {

            logger.info("Thread type " + thread_type + ", draining queues to reset writers");
            for (WriterObject obj : writers) {
                resetOneWriter(obj, thread_type);
            }
        }
    }

    private void shutdownWriters(ThreadType thread_type) {
        List<WriterObject> writers = writer_thread_map.get(thread_type);

        resetWriters(thread_type);

        if (writers != null) {
            logger.info("Shutting down all writers for type " + thread_type);
            for (WriterObject obj : writers) {
                obj.writerThread.shutdown();
            }
        }
    }


    private void addWriterThread(ThreadType thread_type) {
        List<WriterObject> writers = writer_thread_map.get(thread_type);

        if (writers != null) {
            logger.info("Adding new writer thread for type " + thread_type);
            resetWriters(thread_type);

            WriterObject obj = new WriterObject(cfg);
            writers.add(obj);
            executor.submit(obj.writerThread);

            last_writer_thread_chg_time = System.currentTimeMillis();

            logger.info("Done adding new writer thread for type " + thread_type);

        }
    }

    private boolean rebalanceWriterThreads(ThreadType thread_type) {

        if ((System.currentTimeMillis() - last_writer_thread_chg_time) < cfg.getWriter_rebalance_millis()) {
            return false;
        }

        last_writer_thread_chg_time = System.currentTimeMillis();

        List<WriterObject> writers = writer_thread_map.get(thread_type);

        boolean rebalanced = false;

        for (WriterObject obj: writers) {
            if (obj.above_count > cfg.getWriter_allowed_over_queue_times() && obj.assigned.size() > 1) {
                rebalanced = true;
                resetOneWriter(obj, thread_type);
            } else {
                obj.message_count = Long.valueOf(obj.writerQueue.size());
            }
        }

        return rebalanced;
    }

    private void delWriterThread(ThreadType thread_type) {

        if ((System.currentTimeMillis() - last_writer_thread_chg_time) < cfg.getWriter_millis_thread_scale_back()) {
            return;
        }

        List<WriterObject> writers = writer_thread_map.get(thread_type);

        if (writers != null && writers.size() > 1) {
            last_writer_thread_chg_time = System.currentTimeMillis();

            logger.info("Deleting writer thread for type = " + thread_type);
            resetWriters(thread_type);

            writers.get(1).writerThread.shutdown();
            writers.remove(1);

            logger.info("Done deleting writer thread for type = " + thread_type);

        }

    }

    private long checkWriterThreads(long prev_time) {

        if (System.currentTimeMillis() - prev_time > 10000) {

            /*
             * Purge processed attributes that are too old (no updates for more than 15 minutes)
             */
            long purge_age = System.currentTimeMillis() - 1200000;
            HashMap<String, Long> new_processed_attr = new HashMap<>();
            for (Map.Entry<String, Long> entry: processed_attr.entrySet()) {
                if (entry.getValue().longValue() > purge_age) {
                    new_processed_attr.put(entry.getKey(), entry.getValue());
                }
            }

            logger.info("purged %d attributes from cache, current size is %d", processed_attr.size() - new_processed_attr.size(), new_processed_attr.size());
            processed_attr = new_processed_attr;

            for (ThreadType t: ThreadType.values()) {
                List<WriterObject> writers = writer_thread_map.get(t);
                int i = 0;
                int threadsBelowThreshold = 0;

                if ( (rebalanceWriterThreads(t)) == true) {
                    continue;
                }
                else {

                    for (WriterObject obj : writers) {
                        logger.debug("---->>> Writer %s %d: assigned = %d, queue = %d, above_count = %d, messages = %d",
                                t.toString(), i,
                                obj.assigned.size(),
                                obj.writerQueue.size(),
                                obj.above_count,
                                obj.message_count);

                        if (obj.writerQueue.size() > cfg.getWriter_queue_size() * 0.75) {

                            if (obj.above_count > cfg.getWriter_allowed_over_queue_times()) {

                                if (writers.size() < cfg.getWriter_max_threads_per_type()) {
                                    // Add new thread
                                    logger.info("Writer %s %d: assigned = %d, queue = %d, above_count = %d, threads = %d : adding new thread",
                                            t.toString(), i,
                                            obj.assigned.size(),
                                            obj.writerQueue.size(),
                                            obj.above_count,
                                            writers.size());

                                    obj.above_count = 0;

                                    addWriterThread(t);
                                    break;

                                } else {
                                    // At max threads
                                    //obj.above_count++;

                                    logger.info("Writer %s %d: assigned = %d, queue = %d, above_count = %d, threads = %d, running max threads",
                                            t.toString(), i,
                                            obj.assigned.size(),
                                            obj.writerQueue.size(),
                                            obj.above_count,
                                            writers.size());
                                }
                            } else {
                                obj.above_count++;

                                // under above threshold
                                logger.info("Writer %s %d: assigned = %d, queue = %d, above_count = %d, threads = %d",
                                        t.toString(), i,
                                        obj.assigned.size(),
                                        obj.writerQueue.size(),
                                        obj.above_count,
                                        writers.size());
                            }

                        } else if (obj.writerQueue.size() < (cfg.getWriter_queue_size() * .20)) {
                            obj.above_count = 0;
                            threadsBelowThreshold++;
                        }

                        i++;
                    }

                    if (threadsBelowThreshold >= writers.size()) {
                        delWriterThread(t);
                    }
                }
            }

            return System.currentTimeMillis();
        }
        else {
            return prev_time;
        }
    }

    /**
     * Gets the writer object for message object
     *
     *      A new writer will be assigned if not already assigned
     *
     * @param msg           Consumer message object
     *
     * @return  Returns writer object or null if error
     */
    private WriterObject getWriter(ConsumerMessageObject msg) {
        WriterObject cur_obj = null;

        int queueSizeThreshold = cfg.getWriter_queue_size() / 2;

        List<WriterObject> writers = writer_thread_map.get(msg.thread_type);

        if (writers != null) {

            // Choose and distribute to thread based on thread type
            switch (msg.thread_type) {

                default: {
                    /*
                     * Order is important for state data.  Sticky load balance
                     *      Ensuring the same thread writes/updates the same peer data
                     *      reduces deadlocks/shared lock waits
                     */
                    for (WriterObject obj : writers) {

                        if (obj.assigned.containsKey(msg.key)) {
                            obj.message_count++;
                            // Found existing writer - use it instead of finding a new one
                            return obj;
                        }

                        else {
                            // Reset message count on rebalance
                            if (obj.assigned.size() == 0) {
                                obj.message_count = 0L;
                            }

                            // Load balance/distribute by finding a thread to assign
                            if (cur_obj == null) {
                                cur_obj = obj;

                            } else if (cur_obj.assigned.size() != 0
                                    && (obj.assigned.size() == 0
                                        || (obj.writerQueue.size() < queueSizeThreshold && cur_obj.writerQueue.size() > queueSizeThreshold)
                                        || cur_obj.message_count > obj.message_count)) {
                                cur_obj = obj;
                            }
                        }
                    }

                    // If we made it this far, then the cur_obj is a newly assigned writer for this key
                    cur_obj.assigned.put(msg.key, 1);

                    break;
                }
            }
        }

        cur_obj.message_count++;

        return cur_obj;
    }

    /**
     * Write all pending messages to writer threads.  This method is where the actual
     *      message is sent to the writer.
     */
    private void writePendingMessages() {
        Set<WriterRunnable> busy_writers = new HashSet<>();

        /*
         * Process in FIFO order all pending messages
         */
        try {
            int i = message_queue.size();

            ConsumerMessageObject qmsg = message_queue.poll(0, TimeUnit.MILLISECONDS);

            while (qmsg != null && i > 0) {
                WriterObject wobj = getWriter(qmsg);

                // Skip any writers that are currently busy by putting the message back
                // TODO: This can cause out or order messages - Remove/Fix/Update
                if (busy_writers.contains(wobj.writerThread) == true) {
                    message_queue.offer(qmsg);
                }

                // Try to send to writer
                else if ((wobj.writerQueue.offer(qmsg.writer_msg)) == false) {

                    // failed, so mark this thread as busy
                    message_queue.offer(qmsg);
                    busy_writers.add(wobj.writerThread);
                }

                // Get next message and send if possible
                i--;

                qmsg = null;
                if (i > 0) {
                    qmsg = message_queue.poll(0, TimeUnit.MILLISECONDS);
                }

            }

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void addToMsgQueue(ConsumerMessageObject msg) {
        try {
            // Add msg to queue - block if needed
            while (message_queue.offer(msg) == false) {
                //logger.warn("message queue full: %d", message_queue.size());

                consumer.poll(Duration.ofMillis(0));                       // NOTE: consumer is paused already.

                writePendingMessages();
                Thread.sleep(1);
            }

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Add bulk query to writer
     *
     * \details This method will add the bulk object to the writer.
     *
     * @param key           Message key in kafka, such as the peer hash id
     * @param statement     String array statement from Query.getInsertStatement()
     * @param values        Values string from Query.getValuesStatement()
     * @param thread_type   Type of thread to use
     */
    private void addBulkQuerytoWriter(String key, String [] statement, Map<String,String> values, ThreadType thread_type) {
        Map<String, String> query = new HashMap<>();

        try {
            if (values.size() > 0) {
                WriterQueueMsg wmsg = new WriterQueueMsg();

                wmsg.prefix = statement[0];
                wmsg.suffix = statement[1];
                wmsg.values = values;

                // block if space is not available
                ConsumerMessageObject msg = new ConsumerMessageObject();
                msg.key = key;
                msg.writer_msg = wmsg;
                msg.thread_type = thread_type;

              addToMsgQueue(msg);
            }
        } catch (Exception ex) {
            logger.info("Get values Exception: ", ex);
        }

    }

    /**
     * Method will subscribe to pending topics
     *
     * @param prev_timestamp        Previous timestamp that topics were subscribed.
     *
     * @return Time in milliseconds that the topic was last subscribed
     */
    private long subscribe_topics(long prev_timestamp) {
        long sub_timestamp = prev_timestamp;

        if (topics_subscribed_count < topic_patterns.size()) {

            if ((System.currentTimeMillis() - prev_timestamp) >= cfg.getTopic_subscribe_delay_millis()) {

                consumer.commitSync();

                if (topics_subscribed_count > 0)
                    topic_regex_pattern.append('|');

                topic_regex_pattern.append('(');
                topic_regex_pattern.append(topic_patterns.get(topics_subscribed_count));
                topic_regex_pattern.append(')');

                consumer.subscribe(Pattern.compile(topic_regex_pattern.toString()), rebalanceListener);

                logger.info("Subscribed to topic: %s", topic_patterns.get(topics_subscribed_count).pattern());
                logger.debug("Topics regex pattern: %s", topic_regex_pattern.toString());

                topics_subscribed_count++;

                sub_timestamp = System.currentTimeMillis();
            }
        } else {
            topics_all_subscribed = true;
        }

        return sub_timestamp;
    }

    public synchronized boolean isRunning() { return running; }

    public synchronized BigInteger getMessageCount() { return messageCount; }

    public synchronized Integer getConsumerQueueSize() {
        return message_queue.size();
    }
    public synchronized Integer getQueueSize() {
        Integer qSize = 0;

        for (ThreadType t: ThreadType.values()) {
            List<WriterObject> writers = writer_thread_map.get(t);
            int i = 0;
            for (WriterObject obj: writers) {
                qSize += obj.writerQueue.size();
                i++;
            }
        }

        return qSize;
    }
    public synchronized Long getLast_collector_msg_time() { return last_collector_msg_time; }

    public long getCollector_msg_count() {
        return collector_msg_count;
    }

    public long getRouter_msg_count() {
        return router_msg_count;
    }

    public long getPeer_msg_count() {
        return peer_msg_count;
    }

    public long getBase_attribute_msg_count() {
        return base_attribute_msg_count;
    }

    public long getL3vpn_prefix_msg_count() {
        return l3vpn_prefix_msg_count;
    }

    public long getUnicast_prefix_msg_count() {
        return unicast_prefix_msg_count;
    }

    public long getLs_node_msg_count() {
        return ls_node_msg_count;
    }

    public long getLs_link_msg_count() {
        return ls_link_msg_count;
    }

    public long getLs_prefix_msg_count() {
        return ls_prefix_msg_count;
    }

    public long getSubscription_msg_count() {
        return subscription_msg_count;
    }

    public long getStat_msg_count() {
        return stat_msg_count;
    }
}
