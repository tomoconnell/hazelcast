/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client;

import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.proxy.*;
import com.hazelcast.client.proxy.listener.LockClientProxy;
import com.hazelcast.concurrent.idgen.IdGeneratorProxy;
import com.hazelcast.config.Config;
import com.hazelcast.core.*;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.logging.LoggingService;
import com.hazelcast.nio.serialization.SerializationService;
import com.hazelcast.nio.serialization.SerializationServiceImpl;
import com.hazelcast.nio.serialization.TypeSerializer;
import com.hazelcast.spi.RemoteService;
import com.hazelcast.transaction.*;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static com.hazelcast.core.LifecycleEvent.LifecycleState.STARTED;
import static com.hazelcast.core.LifecycleEvent.LifecycleState.STARTING;

/**
 * Hazelcast Client enables you to do all Hazelcast operations without
 * being a member of the cluster. It connects to one of the
 * cluster members and delegates all cluster wide operations to it.
 * When the connected cluster member dies, client will
 * automatically switch to another live member.
 */
public class HazelcastClient implements HazelcastInstance {

    private final static ILogger logger = Logger.getLogger(HazelcastClient.class.getName());
    private final static AtomicInteger clientIdCounter = new AtomicInteger();
    private final static List<HazelcastClient> lsClients = new CopyOnWriteArrayList<HazelcastClient>();
    private final int id;
    private final ClientConfig config;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Map<String, Map<Object, DistributedObject>> mapProxies = new ConcurrentHashMap<String, Map<Object, DistributedObject>>(10);
    private final ClusterClientProxy clusterClientProxy;
    private final PartitionClientProxy partitionClientProxy;
    private final LifecycleServiceClientImpl lifecycleService;
    private final SerializationServiceImpl serializationService = new SerializationServiceImpl(1, null);
    private final ConnectionManager connectionPool;

    private HazelcastClient(ClientConfig config) {
        this.config = config;
        this.id = clientIdCounter.incrementAndGet();
        lifecycleService = new LifecycleServiceClientImpl(this);
        lifecycleService.fireLifecycleEvent(STARTING);
        connectionPool = new ConnectionManager(config, serializationService);
        clusterClientProxy = new ClusterClientProxy(this);
        partitionClientProxy = new PartitionClientProxy(this);
        connectionPool.init(this);
        partitionClientProxy.initInternalPartitionTable();
        lifecycleService.fireLifecycleEvent(STARTED);
        lsClients.add(HazelcastClient.this);
    }

    public ConnectionManager getConnectionPool() {
        return connectionPool;
    }

    /**
     * @param config
     * @return
     */

    public static HazelcastClient newHazelcastClient(ClientConfig config) {
        if (config == null)
            config = new ClientConfig();
        return new HazelcastClient(config);
    }

    public Config getConfig() {
        throw new UnsupportedOperationException();
    }

    public static void shutdownAll() {
        for (HazelcastClient hazelcastClient : lsClients) {
            try {
                hazelcastClient.getLifecycleService().shutdown();
            } catch (Exception ignored) {
            }
        }
        lsClients.clear();
    }

    public static Collection<HazelcastClient> getAllHazelcastClients() {
        return Collections.unmodifiableCollection(lsClients);
    }

    void doShutdown() {
        if (active.compareAndSet(true, false)) {
            logger.log(Level.INFO, "HazelcastClient[" + this.id + "] is shutting down.");
            lsClients.remove(this);
            serializationService.destroy();
        }
    }

    private <V> DistributedObject putAndReturnProxy(Object key, Map<Object, DistributedObject> innerProxyMap, DistributedObject newProxy) {
        DistributedObject proxy;
        synchronized (innerProxyMap) {
            proxy = innerProxyMap.get(key);
            if (proxy == null) {
                proxy = newProxy;
                innerProxyMap.put(key, proxy);
            }
        }
        return proxy;
    }

    private Map<Object, DistributedObject> getProxiesMap(String type) {
        Map<Object, DistributedObject> innerProxyMap = mapProxies.get(type);
        if (innerProxyMap == null) {
            synchronized (mapProxies) {
                innerProxyMap = mapProxies.get(type);
                if (innerProxyMap == null) {
                    innerProxyMap = new ConcurrentHashMap<Object, DistributedObject>(10);
                    mapProxies.put(type, innerProxyMap);
                }
            }
        }
        return innerProxyMap;
    }


    public SerializationService getSerializationService() {
        return serializationService;
    }
    public Transaction getTransaction() {
        Context context = Context.getOrCreate();
        return context.getTransaction(this);
    }

    public ConcurrentMap<String, Object> getUserContext() {
        throw new UnsupportedOperationException();
    }

    public PartitionService getPartitionService() {
        return partitionClientProxy;
    }

    public ClientService getClientService() {
        throw new UnsupportedOperationException();
    }

    public LoggingService getLoggingService() {
        throw new UnsupportedOperationException();
    }

    public <K, V> IMap<K, V> getMap(String name) {
        Map<Object, DistributedObject> innerProxyMap = getProxiesMap("Map");
        DistributedObject proxy = innerProxyMap.get(name);
        if (proxy == null) {
            proxy = putAndReturnProxy(name, innerProxyMap, new MapClientProxy<Object, V>(this, name));
        }
        return (IMap<K, V>) proxy;
    }

    public void addDistributedObjectListener(DistributedObjectListener distributedObjectListener) {
        throw new UnsupportedOperationException();
    }

    public Cluster getCluster() {
        return clusterClientProxy;
    }

    public <T> T executeTransaction(TransactionalTask<T> task) throws TransactionException {
        return null;
    }

    public <T> T executeTransaction(TransactionalTask<T> task, TransactionOptions options) throws TransactionException {
        return null;
    }

    public TransactionContext newTransactionContext() {
        return null;
    }

    public TransactionContext newTransactionContext(TransactionOptions options) {
        return null;
    }

    public IExecutorService getExecutorService(String name) {
        Map<Object, DistributedObject> innerProxyMap = getProxiesMap("ExecutorService");
        DistributedObject proxy = innerProxyMap.get(name);
        if (proxy == null) {
            proxy = putAndReturnProxy(name, innerProxyMap, new ExecutorServiceClientProxy(this, name));
        }
        return (IExecutorService) proxy;
    }

    public IdGenerator getIdGenerator(String name) {
        return new IdGeneratorProxy(this, name);
    }

    public IAtomicLong getAtomicLong(String name) {
        Map<Object, DistributedObject> innerProxyMap = getProxiesMap("IAtomicLong");
        DistributedObject proxy = innerProxyMap.get(name);
        if (proxy == null) {
            proxy = putAndReturnProxy(name, innerProxyMap, new AtomicLongClientProxy(this, name));
        }
        return (IAtomicLong) proxy;
    }

    public ICountDownLatch getCountDownLatch(String name) {
        Map<Object, DistributedObject> innerProxyMap = getProxiesMap("CountDownLatch");
        DistributedObject proxy = innerProxyMap.get(name);
        if (proxy == null) {
            proxy = putAndReturnProxy(name, innerProxyMap, new CountDownLatchClientProxy(this, name));
        }
        return (ICountDownLatch) proxy;
    }

    public ISemaphore getSemaphore(String name) {
        Map<Object, DistributedObject> innerProxyMap = getProxiesMap("Semaphore");
        DistributedObject proxy = innerProxyMap.get(name);
        if (proxy == null) {
            proxy = putAndReturnProxy(name, innerProxyMap, new SemaphoreClientProxy(this, name));
        }
        return (ISemaphore) proxy;
    }

    public Collection<DistributedObject> getDistributedObjects() {
        throw new UnsupportedOperationException();
    }

    public <E> IList<E> getList(String name) {
        Map<Object, DistributedObject> innerProxyMap = getProxiesMap("List");
        DistributedObject proxy = innerProxyMap.get(name);
        if (proxy == null) {
            proxy = putAndReturnProxy(name, innerProxyMap, new ListClientProxy(this, name));
        }
        return (IList<E>) proxy;
    }

    public ILock getLock(Object obj) {
        Map<Object, DistributedObject> innerProxyMap = getProxiesMap("List");
        DistributedObject proxy = innerProxyMap.get(obj);
        if (proxy == null) {
            proxy = putAndReturnProxy(obj, innerProxyMap, new LockClientProxy(this, obj));
        }
        return (ILock) proxy;
    }

    public <K, V> MultiMap<K, V> getMultiMap(String name) {
        Map<Object, DistributedObject> innerProxyMap = getProxiesMap("MultiMap");
        DistributedObject proxy = innerProxyMap.get(name);
        if (proxy == null) {
            proxy = putAndReturnProxy(name, innerProxyMap, new MultiMapClientProxy(this, name));
        }
        return (MultiMap<K, V>) proxy;
    }

    public String getName() {
        return config.getGroupConfig().getName();
    }

    public <E> IQueue<E> getQueue(String name) {
        Map<Object, DistributedObject> innerProxyMap = getProxiesMap("Queue");
        DistributedObject proxy = innerProxyMap.get(name);
        if (proxy == null) {
            proxy = putAndReturnProxy(name, innerProxyMap, new QueueClientProxy(this, name));
        }
        return (IQueue<E>) proxy;
    }

    public <E> ISet<E> getSet(String name) {
        Map<Object, DistributedObject> innerProxyMap = getProxiesMap("Set");
        DistributedObject proxy = innerProxyMap.get(name);
        if (proxy == null) {
            proxy = putAndReturnProxy(name, innerProxyMap, new SetClientProxy(this, name));
        }
        return (ISet<E>) proxy;
    }

    public <E> ITopic<E> getTopic(String name) {
        Map<Object, DistributedObject> innerProxyMap = getProxiesMap("Topic");
        DistributedObject proxy = innerProxyMap.get(name);
        if (proxy == null) {
            proxy = putAndReturnProxy(name, innerProxyMap, new TopicClientProxy(this, name));
        }
        return (ITopic<E>) proxy;
    }

    public void removeDistributedObjectListener(DistributedObjectListener distributedObjectListener) {
        throw new UnsupportedOperationException();
    }

    protected void destroy(String proxyName) {
        mapProxies.remove(proxyName);
    }

    public LifecycleService getLifecycleService() {
        return lifecycleService;
    }

    public <S extends DistributedObject> S getDistributedObject(Class<? extends RemoteService> serviceClass, Object id) {
        return null;
    }

    public <S extends DistributedObject> S getDistributedObject(String serviceName, Object id) {
        return null;
    }

    static <V> V callAsyncAndWait(final Callable<V> callable) {
        final ExecutorService es = Executors.newSingleThreadExecutor();
        try {
            Future<V> future = es.submit(callable);
            try {
                return future.get();
            } catch (Throwable e) {
                logger.log(Level.WARNING, e.getMessage(), e);
                return null;
            }
        } finally {
            es.shutdown();
        }
    }

    public ClientConfig getClientConfig() {
        return config;
    }

    public void registerGlobalSerializer(final TypeSerializer serializer) {
        serializationService.registerFallback(serializer);
    }

    public void registerSerializer(final TypeSerializer serializer, final Class type) {
        serializationService.register(serializer, type);
    }
}
