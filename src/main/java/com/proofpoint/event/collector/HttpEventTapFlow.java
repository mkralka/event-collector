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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Sets;
import com.proofpoint.http.client.HttpClient;
import com.proofpoint.http.client.Request;
import com.proofpoint.http.client.RequestBuilder;
import com.proofpoint.http.client.Response;
import com.proofpoint.http.client.ResponseHandler;
import com.proofpoint.json.JsonCodec;
import com.proofpoint.log.Logger;
import com.proofpoint.units.Duration;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.net.URI;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.proofpoint.http.client.JsonBodyGenerator.jsonBodyGenerator;
import static java.lang.Thread.sleep;
import static javax.ws.rs.core.Response.Status.Family.SUCCESSFUL;
import static javax.ws.rs.core.Response.Status.fromStatusCode;

class HttpEventTapFlow implements EventTapFlow
{
    private static final Logger log = Logger.get(HttpEventTapFlow.class);
    private static final Random RANDOM = new Random();
    private static final String QOS_HEADER = "X-Proofpoint-QoS";
    private static final String QOS_HEADER_FIRST_BATCH = "firstBatch";
    private static final String QOS_HEADER_DROPPED_ENTRIES = "droppedMessages=%d";

    private final HttpClient httpClient;
    private final JsonCodec<List<Event>> eventsCodec;
    private final long retryDelayMillis;
    private final int retryCount;
    private final String eventType;
    private final String flowId;
    private final AtomicReference<List<URI>> taps = new AtomicReference<List<URI>>(ImmutableList.<URI>of());
    private final Observer observer;
    private final Set<URI> unestablishedTaps = Sets.newSetFromMap(new MapMaker().<URI, Boolean>makeMap());
    private final AtomicLong droppedEntries = new AtomicLong(0);

    public HttpEventTapFlow(HttpClient httpClient, JsonCodec<List<Event>> eventsCodec,
            String eventType, String flowId, Set<URI> taps, int retryCount, Duration retryDelay, Observer observer)
    {
        this.httpClient = checkNotNull(httpClient, "httpClient is null");
        this.eventsCodec = checkNotNull(eventsCodec, "eventsCodec is null");
        this.eventType = checkNotNull(eventType, "eventType is null");
        this.flowId = checkNotNull(flowId, "flowId is null");
        this.observer = checkNotNull(observer, "observer is null");
        this.retryCount = retryCount;
        if (this.retryCount > 0) {
            this.retryDelayMillis = (long) checkNotNull(retryDelay, "retryDelay is null").toMillis();
        }
        else {
            this.retryDelayMillis = 0;
        }
        setTaps(taps);
    }

    @Override
    public void processBatch(List<Event> entries)
    {
        List<URI> taps = null;

        for (int i = 0; i <= retryCount; ++i) {
            taps = this.taps.get();
            if (sendEvents(taps, entries)) {
                return;
            }

            try {
                sleep(retryDelayMillis);
            }
            catch (InterruptedException ignored) {
                break;
            }
        }

        // The events were not sent, track them.
        // NOTE: Since attempts to all destinations failed, it doesn't matter which
        //       destination the failure is assigned to. Just pick a random one.
        droppedEntries.getAndAdd(entries.size());
        observer.onRecordsLost(taps.get(RANDOM.nextInt(taps.size())), entries.size());
    }

    @Override
    public void notifyEntriesDropped(int count)
    {
        droppedEntries.getAndAdd(count);
    }

    @Override
    public Set<URI> getTaps()
    {
        return ImmutableSet.copyOf(taps.get());
    }

    @Override
    public void setTaps(Set<URI> taps)
    {
        checkNotNull(taps, "taps is null");
        checkArgument(!taps.isEmpty(), "taps is empty");

        List<URI> existingTaps = this.taps.getAndSet(ImmutableList.copyOf(taps));

        for (URI tap : taps) {
            if (!existingTaps.contains(tap)) {
                unestablishedTaps.add(tap);
            }
        }
    }

    private boolean sendEvents(List<URI> taps, List<Event> entries)
    {
        // In the event that we fail to send to *all* of the taps, assign the loss to the last tap
        // to be attempted.
        int startUriPos = RANDOM.nextInt(taps.size());

        for (URI tap : Iterables.concat(taps.subList(startUriPos, taps.size()), taps.subList(0, startUriPos))) {
            try {
                if (sendEvents(tap, entries)) {
                    return true;
                }
            }
            catch (Exception ex) {
                // failed, try the next one (already logged)
                log.warn(ex, "Error posting %s events to flow %s at %s ", eventType, flowId, tap);
            }
        }
        return false;
    }

    private boolean sendEvents(final URI uri, final List<Event> entries)
            throws Exception
    {
        RequestBuilder requestBuilder = RequestBuilder.preparePost()
                .setUri(uri)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .setBodyGenerator(jsonBodyGenerator(eventsCodec, entries));

        final boolean firstBatch = unestablishedTaps.remove(uri);
        if (firstBatch) {
            requestBuilder.addHeader(QOS_HEADER, QOS_HEADER_FIRST_BATCH);
        }

        // If there are multiple taps sharing the same flow that cares about
        // dropped messages, they must coordinate what events were received
        // anyway, so only one (the first) needs to get the dropped count.
        final long count = droppedEntries.getAndSet(0);
        if (count > 0) {
            requestBuilder.addHeader(QOS_HEADER, String.format(QOS_HEADER_DROPPED_ENTRIES, count));
        }

        return httpClient.execute(requestBuilder.build(), new ResponseHandler<Boolean, Exception>()
        {
            @Override
            public Exception handleException(Request request, Exception exception)
            {
                if (firstBatch) {
                    unestablishedTaps.add(uri);
                }
                droppedEntries.getAndAdd(count);
                return exception;
            }

            @Override
            public Boolean handle(Request request, Response response)
            {
                if (fromStatusCode(response.getStatusCode()).getFamily() != SUCCESSFUL) {
                    log.warn("Error posting %s events to flow %s at %s: got response %s %s ", eventType, flowId, uri, response.getStatusCode(), response.getStatusMessage());
                    if (firstBatch) {
                        unestablishedTaps.add(uri);
                    }
                    droppedEntries.getAndAdd(count);
                    return Boolean.FALSE;
                }
                else {
                    log.debug("Posted %s events", entries.size());
                    observer.onRecordsSent(uri, entries.size());
                    return Boolean.TRUE;
                }
            }
        });
    }

    @VisibleForTesting
    String getEventType()
    {
        return eventType;
    }

    @VisibleForTesting
    String getFlowId()
    {
        return flowId;
    }
}
