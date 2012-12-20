/*
 * Copyright 2011-2012 Proofpoint, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.proofpoint.event.collector;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.proofpoint.event.collector.EventCounters.Counter;
import com.proofpoint.event.collector.EventCounters.CounterState;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.format;

public class AsyncBatchProcessor<T> implements BatchProcessor<T>
{
    private final BatchHandler<T> handler;
    private final int maxBatchSize;
    private final BlockingQueue<T> queue;
    private final ExecutorService executor;
    private final AtomicReference<Future<?>> future = new AtomicReference<Future<?>>();

    private final AtomicReference<Counter> counter = new AtomicReference<Counter>(new Counter());

    public AsyncBatchProcessor(String name, BatchHandler<T> handler, BatchProcessorConfig config)
    {
        checkNotNull(name, "name is null");
        checkNotNull(handler, "handler is null");

        this.handler = handler;
        this.maxBatchSize = checkNotNull(config, "config is null").getMaxBatchSize();
        this.queue = new ArrayBlockingQueue<T>(config.getQueueSize());

        this.executor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat(format("batch-processor-%s", name)).build());
    }

    @Override
    @PostConstruct
    public void start()
    {
        future.set(executor.submit(new Runnable()
        {
            @Override
            public void run()
            {
                while (!Thread.interrupted()) {
                    final List<T> entries = new ArrayList<T>(maxBatchSize);

                    try {
                        T first = queue.take();
                        entries.add(first);
                        queue.drainTo(entries, maxBatchSize - 1);

                        handler.processBatch(entries);
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }));
    }

    @Override
    @PreDestroy
    public void stop()
    {
        future.get().cancel(true);
        executor.shutdownNow();
    }

    @Override
    public void put(T entry)
    {
        checkState(future.get() != null && !future.get().isCancelled(), "Processor is not running");
        checkNotNull(entry, "entry is null");

        while (!queue.offer(entry)) {
            // throw away oldest and try again
            queue.poll();
            counter.get().recordLost(1);
        }

        counter.get().recordReceived(1);
    }

    @Override
    public CounterState getCounterState()
    {
        return counter.get().getState();
    }

    @Override
    public void resetCounter()
    {
        counter.set(new Counter());
    }

}