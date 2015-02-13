/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.igfs;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.events.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.cluster.*;
import org.apache.ignite.internal.managers.communication.*;
import org.apache.ignite.internal.managers.eventstorage.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.internal.util.worker.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.thread.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

import static java.util.concurrent.TimeUnit.*;
import static org.apache.ignite.events.EventType.*;
import static org.apache.ignite.internal.GridTopic.*;
import static org.apache.ignite.internal.managers.communication.GridIoPolicy.*;
import static org.apache.ignite.internal.processors.igfs.IgfsFileAffinityRange.*;

/**
 * GGFS fragmentizer manager.
 */
public class IgfsFragmentizerManager extends IgfsManager {
    /** Message offer wait interval. */
    private static final int MSG_OFFER_TIMEOUT = 1000;

    /** Fragmentizer files check interval. */
    private static final int FRAGMENTIZER_CHECK_INTERVAL = 3000;

    /** Message send retry interval. */
    private static final int MESSAGE_SEND_RETRY_INTERVAL = 1000;

    /** How many times retry message send. */
    private static final int MESSAGE_SEND_RETRY_COUNT = 3;

    /** Manager stopping flag. */
    private volatile boolean stopping;

    /** Coordinator worker. */
    private volatile FragmentizerCoordinator fragmentizerCrd;

    /** This variable is used in tests only. */
    @SuppressWarnings("FieldCanBeLocal")
    private volatile boolean fragmentizerEnabled = true;

    /** Fragmentizer worker. */
    private FragmentizerWorker fragmentizerWorker;

    /** Shutdown lock. */
    private GridSpinReadWriteLock rw = new GridSpinReadWriteLock();

    /** Message topic. */
    private Object topic;

    /** {@inheritDoc} */
    @Override protected void start0() throws IgniteCheckedException {
        if (!igfsCtx.configuration().isFragmentizerEnabled())
            return;

        // We care only about node leave and fail events.
        igfsCtx.kernalContext().event().addLocalEventListener(new GridLocalEventListener() {
            @Override public void onEvent(Event evt) {
                assert evt.type() == EVT_NODE_LEFT || evt.type() == EVT_NODE_FAILED;

                DiscoveryEvent discoEvt = (DiscoveryEvent)evt;

                checkLaunchCoordinator(discoEvt);
            }
        }, EVT_NODE_LEFT, EVT_NODE_FAILED);

        fragmentizerWorker = new FragmentizerWorker();

        String ggfsName = igfsCtx.configuration().getName();

        topic = F.isEmpty(ggfsName) ? TOPIC_GGFS : TOPIC_GGFS.topic(ggfsName);

        igfsCtx.kernalContext().io().addMessageListener(topic, fragmentizerWorker);

        new IgniteThread(fragmentizerWorker).start();
    }

    /** {@inheritDoc} */
    @Override protected void onKernalStart0() throws IgniteCheckedException {
        if (igfsCtx.configuration().isFragmentizerEnabled()) {
            // Check at startup if this node is a fragmentizer coordinator.
            DiscoveryEvent locJoinEvt = igfsCtx.kernalContext().discovery().localJoinEvent();

            checkLaunchCoordinator(locJoinEvt);
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings("BusyWait")
    @Override protected void onKernalStop0(boolean cancel) {
        boolean interrupted = false;

        // Busy wait is intentional.
        while (true) {
            try {
                if (rw.tryWriteLock(200, TimeUnit.MILLISECONDS))
                    break;
                else
                    Thread.sleep(200);
            }
            catch (InterruptedException ignore) {
                // Preserve interrupt status & ignore.
                // Note that interrupted flag is cleared.
                interrupted = true;
            }
        }

        try {
            if (interrupted)
                Thread.currentThread().interrupt();

            stopping = true;
        }
        finally {
            rw.writeUnlock();
        }

        synchronized (this) {
            if (fragmentizerCrd != null)
                fragmentizerCrd.cancel();
        }

        if (fragmentizerWorker != null)
            fragmentizerWorker.cancel();

        U.join(fragmentizerCrd, log);
        U.join(fragmentizerWorker, log);
    }

    /**
     * @param nodeId Node ID to send message to.
     * @param msg Message to send.
     * @throws IgniteCheckedException If send failed.
     */
    private void sendWithRetries(UUID nodeId, IgfsCommunicationMessage msg) throws IgniteCheckedException {
        for (int i = 0; i < MESSAGE_SEND_RETRY_COUNT; i++) {
            try {
                igfsCtx.send(nodeId, topic, msg, SYSTEM_POOL);

                return;
            }
            catch (IgniteCheckedException e) {
                if (!igfsCtx.kernalContext().discovery().alive(nodeId))
                    throw new ClusterTopologyCheckedException("Failed to send message (node left the grid) " +
                        "[nodeId=" + nodeId + ", msg=" + msg + ']');

                if (i == MESSAGE_SEND_RETRY_COUNT - 1)
                    throw e;

                U.sleep(MESSAGE_SEND_RETRY_INTERVAL);
            }
        }
    }

    /**
     * Checks if current node is the oldest node in topology and starts coordinator thread if so.
     * Note that once node is the oldest one, it will be the oldest until it leaves grid.
     *
     * @param discoEvt Discovery event.
     */
    private void checkLaunchCoordinator(DiscoveryEvent discoEvt) {
        rw.readLock();

        try {
            if (stopping)
                return;

            if (fragmentizerCrd == null) {
                long minNodeOrder = Long.MAX_VALUE;

                Collection<ClusterNode> nodes = discoEvt.topologyNodes();

                for (ClusterNode node : nodes) {
                    if (node.order() < minNodeOrder && igfsCtx.igfsNode(node))
                        minNodeOrder = node.order();
                }

                ClusterNode locNode = igfsCtx.kernalContext().grid().localNode();

                if (locNode.order() == minNodeOrder) {
                    if (log.isDebugEnabled())
                        log.debug("Detected local node to be the eldest GGFS node in topology, starting fragmentizer " +
                            "coordinator thread [discoEvt=" + discoEvt + ", locNode=" + locNode + ']');

                    synchronized (this) {
                        if (fragmentizerCrd == null && !stopping) {
                            fragmentizerCrd = new FragmentizerCoordinator();

                            new IgniteThread(fragmentizerCrd).start();
                        }
                    }
                }
            }
        }
        finally {
            rw.readUnlock();
        }
    }

    /**
     * Processes fragmentizer request. For each range assigned to this node:
     * <ul>
     *     <li>Mark range as moving indicating that block copying started.</li>
     *     <li>Copy blocks to non-colocated keys.</li>
     *     <li>Update map to indicate that blocks were copied and old blocks should be deleted.</li>
     *     <li>Delete old blocks.</li>
     *     <li>Remove range from file map.</li>
     * </ul>
     *
     * @param req Request.
     * @throws IgniteCheckedException In case of error.
     */
    @SuppressWarnings("fallthrough")
    private void processFragmentizerRequest(IgfsFragmentizerRequest req) throws IgniteCheckedException {
        req.finishUnmarshal(igfsCtx.kernalContext().config().getMarshaller(), null);

        Collection<IgfsFileAffinityRange> ranges = req.fragmentRanges();
        IgniteUuid fileId = req.fileId();

        IgfsFileInfo fileInfo = igfsCtx.meta().info(fileId);

        if (fileInfo == null) {
            if (log.isDebugEnabled())
                log.debug("Failed to find file info for fragmentizer request: " + req);

            return;
        }

        if (log.isDebugEnabled())
            log.debug("Moving file ranges for fragmentizer request [req=" + req + ", fileInfo=" + fileInfo + ']');

        for (IgfsFileAffinityRange range : ranges) {
            try {
                IgfsFileInfo updated;

                switch (range.status()) {
                    case RANGE_STATUS_INITIAL: {
                        // Mark range as moving.
                        updated = igfsCtx.meta().updateInfo(fileId, updateRange(range, RANGE_STATUS_MOVING));

                        if (updated == null) {
                            igfsCtx.data().cleanBlocks(fileInfo, range, true);

                            continue;
                        }

                        // Fall-through.
                    }

                    case RANGE_STATUS_MOVING: {
                        // Move colocated blocks.
                        igfsCtx.data().spreadBlocks(fileInfo, range);

                        // Mark range as moved.
                        updated = igfsCtx.meta().updateInfo(fileId, updateRange(range, RANGE_STATUS_MOVED));

                        if (updated == null) {
                            igfsCtx.data().cleanBlocks(fileInfo, range, true);

                            continue;
                        }

                        // Fall-through.
                    }

                    case RANGE_STATUS_MOVED: {
                        // Remove old blocks.
                        igfsCtx.data().cleanBlocks(fileInfo, range, false);

                        // Remove range from map.
                        updated = igfsCtx.meta().updateInfo(fileId, deleteRange(range));

                        if (updated == null)
                            igfsCtx.data().cleanBlocks(fileInfo, range, true);
                    }
                }
            }
            catch (IgfsInvalidRangeException e) {
                if (log.isDebugEnabled())
                    log.debug("Failed to update file range " +
                        "[range=" + range + "fileId=" + fileId + ", err=" + e.getMessage() + ']');
            }
        }
    }

    /**
     * Creates update info closure that will mark given range as moving.
     *
     * @param range Range to mark as moving.
     * @param status Status.
     * @return Update closure.
     */
    private IgniteClosure<IgfsFileInfo, IgfsFileInfo> updateRange(final IgfsFileAffinityRange range,
        final int status) {
        return new CX1<IgfsFileInfo, IgfsFileInfo>() {
            @Override public IgfsFileInfo applyx(IgfsFileInfo info) throws IgniteCheckedException {
                IgfsFileMap map = new IgfsFileMap(info.fileMap());

                map.updateRangeStatus(range, status);

                if (log.isDebugEnabled())
                    log.debug("Updated file map for range [fileId=" + info.id() + ", range=" + range +
                        ", status=" + status + ", oldMap=" + info.fileMap() + ", newMap=" + map + ']');

                IgfsFileInfo updated = new IgfsFileInfo(info, info.length());

                updated.fileMap(map);

                return updated;
            }
        };
    }

    /**
     * Creates update info closure that will mark given range as moving.
     *
     * @param range Range to mark as moving.
     * @return Update closure.
     */
    private IgniteClosure<IgfsFileInfo, IgfsFileInfo> deleteRange(final IgfsFileAffinityRange range) {
        return new CX1<IgfsFileInfo, IgfsFileInfo>() {
            @Override public IgfsFileInfo applyx(IgfsFileInfo info) throws IgniteCheckedException {
                IgfsFileMap map = new IgfsFileMap(info.fileMap());

                map.deleteRange(range);

                if (log.isDebugEnabled())
                    log.debug("Deleted range from file map [fileId=" + info.id() + ", range=" + range +
                        ", oldMap=" + info.fileMap() + ", newMap=" + map + ']');

                IgfsFileInfo updated = new IgfsFileInfo(info, info.length());

                updated.fileMap(map);

                return updated;
            }
        };
    }

    /**
     * Fragmentizer coordinator thread.
     */
    private class FragmentizerCoordinator extends GridWorker implements GridLocalEventListener, GridMessageListener {
        /** Files being fragmented. */
        private ConcurrentMap<IgniteUuid, Collection<UUID>> fragmentingFiles = new ConcurrentHashMap<>();

        /** Node IDs captured on start. */
        private volatile Collection<UUID> startSync;

        /** Wait lock. */
        private Lock lock = new ReentrantLock();

        /** Wait condition. */
        private Condition cond = lock.newCondition();

        /**
         * Constructor.
         */
        protected FragmentizerCoordinator() {
            super(igfsCtx.kernalContext().gridName(), "fragmentizer-coordinator", igfsCtx.kernalContext().log());

            igfsCtx.kernalContext().event().addLocalEventListener(this, EVT_NODE_LEFT, EVT_NODE_FAILED);
            igfsCtx.kernalContext().io().addMessageListener(topic, this);
        }

        /** {@inheritDoc} */
        @Override protected void body() throws InterruptedException, IgniteInterruptedCheckedException {
            // Wait for all previous fragmentizer tasks to complete.
            syncStart();

            while (!isCancelled()) {
                // If we have room for files, add them to fragmentizer.
                try {
                    while (fragmentingFiles.size() < igfsCtx.configuration().getFragmentizerConcurrentFiles()) {
                        IgfsFileInfo fileInfo = fileForFragmentizer(fragmentingFiles.keySet());

                        // If no colocated files found, exit loop.
                        if (fileInfo == null)
                            break;

                        requestFragmenting(fileInfo);
                    }
                }
                catch (IgniteCheckedException | IgniteException e) {
                    if (!X.hasCause(e, InterruptedException.class) && !X.hasCause(e, IgniteInterruptedCheckedException.class))
                        LT.warn(log, e, "Failed to get fragmentizer file info (will retry).");
                    else {
                        if (log.isDebugEnabled())
                            log.debug("Got interrupted exception in fragmentizer coordinator (grid is stopping).");

                        break; // While.
                    }
                }

                lock.lock();

                try {
                    cond.await(FRAGMENTIZER_CHECK_INTERVAL, MILLISECONDS);
                }
                finally {
                    lock.unlock();
                }
            }
        }

        /** {@inheritDoc} */
        @Override public void onEvent(Event evt) {
            assert evt.type() == EVT_NODE_LEFT || evt.type() == EVT_NODE_FAILED;

            DiscoveryEvent discoEvt = (DiscoveryEvent)evt;

            if (log.isDebugEnabled())
                log.debug("Processing node leave event: " + discoEvt);

            boolean signal = false;

            Collection<UUID> startSync0 = startSync;

            if (startSync0 != null && !startSync0.isEmpty()) {
                startSync0.remove(discoEvt.eventNode().id());

                if (startSync0.isEmpty()) {
                    if (log.isDebugEnabled())
                        log.debug("Completed fragmentizer coordinator sync start.");

                    signal = true;
                }
            }

            if (!signal) {
                Iterator<Map.Entry<IgniteUuid, Collection<UUID>>> it = fragmentingFiles.entrySet().iterator();

                while (it.hasNext()) {
                    Map.Entry<IgniteUuid, Collection<UUID>> entry = it.next();

                    Collection<UUID> nodeIds = entry.getValue();

                    if (nodeIds.remove(discoEvt.eventNode().id())) {
                        if (nodeIds.isEmpty()) {
                            if (log.isDebugEnabled())
                                log.debug("Received all responses for fragmentizer task [fileId=" + entry.getKey() +
                                    ']');

                            it.remove();

                            signal = true;
                        }
                    }
                }
            }

            if (signal)
                wakeUp();
        }

        /** {@inheritDoc} */
        @Override public void onMessage(UUID nodeId, Object msg) {
            if (msg instanceof IgfsFragmentizerResponse) {
                IgfsFragmentizerResponse res = (IgfsFragmentizerResponse)msg;

                IgniteUuid fileId = res.fileId();

                Collection<UUID> nodeIds = fragmentingFiles.get(fileId);

                if (nodeIds != null) {
                    if (nodeIds.remove(nodeId)) {
                        if (nodeIds.isEmpty()) {
                            if (log.isDebugEnabled())
                                log.debug("Received all responses for fragmentizer task [fileId=" + fileId + ']');

                            fragmentingFiles.remove(fileId, nodeIds);

                            wakeUp();
                        }
                    }
                }
                else
                    log.warning("Received fragmentizer response for file ID which was not requested (will ignore) " +
                        "[nodeId=" + nodeId + ", fileId=" + res.fileId() + ']');
            }
            else if (msg instanceof IgfsSyncMessage) {
                IgfsSyncMessage sync = (IgfsSyncMessage)msg;

                if (sync.response() && sync.order() == igfsCtx.kernalContext().grid().localNode().order()) {
                    if (log.isDebugEnabled())
                        log.debug("Received fragmentizer sync response from remote node: " + nodeId);

                    Collection<UUID> startSync0 = startSync;

                    if (startSync0 != null) {
                        startSync0.remove(nodeId);

                        if (startSync0.isEmpty()) {
                            if (log.isDebugEnabled())
                                log.debug("Completed fragmentizer coordinator sync start: " + startSync0);

                            wakeUp();
                        }
                    }
                }
            }
        }

        /**
         * Signals condition.
         */
        private void wakeUp() {
            lock.lock();

            try {
                cond.signalAll();
            }
            finally {
                lock.unlock();
            }
        }

        /**
         * Sends sync message to remote nodes and awaits for response from all nodes.
         *
         * @throws InterruptedException If waiting was interrupted.
         */
        private void syncStart() throws InterruptedException {
            Collection<UUID> startSync0 = startSync = new GridConcurrentHashSet<>(
                F.viewReadOnly(
                    igfsCtx.kernalContext().discovery().allNodes(),
                    F.node2id(),
                    new P1<ClusterNode>() {
                        @Override public boolean apply(ClusterNode n) {
                            return igfsCtx.igfsNode(n);
                        }
                    }));

            ClusterNode locNode = igfsCtx.kernalContext().grid().localNode();

            while (!startSync0.isEmpty()) {
                for (UUID nodeId : startSync0) {
                    IgfsSyncMessage syncReq = new IgfsSyncMessage(locNode.order(), false);

                    try {
                        if (log.isDebugEnabled())
                            log.debug("Sending fragmentizer sync start request to remote node [nodeId=" + nodeId +
                                ", syncReq=" + syncReq + ']');

                        sendWithRetries(nodeId, syncReq);

                        // Close window between message sending and discovery event.
                        if (!igfsCtx.kernalContext().discovery().alive(nodeId))
                            startSync0.remove(nodeId);
                    }
                    catch (IgniteCheckedException e) {
                        if (e.hasCause(ClusterTopologyCheckedException.class)) {
                            if (log.isDebugEnabled())
                                log.debug("Failed to send sync message to remote node (node has left the grid): " +
                                    nodeId);
                        }
                        else
                            U.error(log, "Failed to send synchronize message to remote node (will not wait for reply): " +
                                nodeId, e);

                        startSync0.remove(nodeId);
                    }
                }

                lock.lock();

                try {
                    if (!startSync0.isEmpty())
                        cond.await(10000, MILLISECONDS);
                }
                finally {
                    lock.unlock();
                }
            }
        }

        /**
         * Starts file fragmenting. Will group file affinity ranges by nodes and send requests to each node.
         * File will be considered processed when each node replied with success (or error) or left the grid.
         *
         * @param fileInfo File info to process.
         */
        private void requestFragmenting(IgfsFileInfo fileInfo) {
            IgfsFileMap map = fileInfo.fileMap();

            assert map != null && !map.ranges().isEmpty();

            Map<UUID, Collection<IgfsFileAffinityRange>> grpMap = U.newHashMap(map.ranges().size());

            for (IgfsFileAffinityRange range : map.ranges()) {
                UUID nodeId = igfsCtx.data().affinityNode(range.affinityKey()).id();

                Collection<IgfsFileAffinityRange> nodeRanges = grpMap.get(nodeId);

                if (nodeRanges == null) {
                    nodeRanges = new LinkedList<>();

                    grpMap.put(nodeId, nodeRanges);
                }

                nodeRanges.addAll(range.split(igfsCtx.data().groupBlockSize()));
            }

            Collection<UUID> nodeIds = new IdentityHashSet(grpMap.keySet());

            if (log.isDebugEnabled())
                log.debug("Calculating fragmentizer groups for file [fileInfo=" + fileInfo +
                    ", nodeIds=" + nodeIds + ']');

            // Put assignment to map first.
            Object old = fragmentingFiles.putIfAbsent(fileInfo.id(), nodeIds);

            assert old == null;

            for (Map.Entry<UUID, Collection<IgfsFileAffinityRange>> entry : grpMap.entrySet()) {
                UUID nodeId = entry.getKey();

                IgfsFragmentizerRequest msg = new IgfsFragmentizerRequest(fileInfo.id(), entry.getValue());

                try {
                    if (log.isDebugEnabled())
                        log.debug("Sending fragmentizer request to remote node [nodeId=" + nodeId +
                            ", fileId=" + fileInfo.id() + ", msg=" + msg + ']');

                    sendWithRetries(nodeId, msg);
                }
                catch (IgniteCheckedException e) {
                    if (e.hasCause(ClusterTopologyCheckedException.class)) {
                        if (log.isDebugEnabled())
                            log.debug("Failed to send fragmentizer request to remote node (node left grid): " +
                                nodeId);
                    }
                    else
                        U.error(log, "Failed to send fragmentizer request to remote node [nodeId=" + nodeId +
                            ", msg=" + msg + ']', e);

                    nodeIds.remove(nodeId);
                }
            }

            if (nodeIds.isEmpty()) {
                if (log.isDebugEnabled())
                    log.debug("Got empty wait set for fragmentized file: " + fileInfo);

                fragmentingFiles.remove(fileInfo.id(), nodeIds);
            }
        }
    }

    /**
     * Gets next file for fragmentizer to be processed.
     *
     * @param exclude File IDs to exclude (the ones that are currently being processed).
     * @return File ID to process or {@code null} if there are no such files.
     * @throws IgniteCheckedException In case of error.
     */
    @Nullable private IgfsFileInfo fileForFragmentizer(Collection<IgniteUuid> exclude) throws IgniteCheckedException {
        return fragmentizerEnabled ? igfsCtx.meta().fileForFragmentizer(exclude) : null;
    }

    /**
     * Fragmentizer worker thread.
     */
    private class FragmentizerWorker extends GridWorker implements GridMessageListener {
        /** Requests for this worker. */
        private BlockingQueue<IgniteBiTuple<UUID, IgfsCommunicationMessage>> msgs = new LinkedBlockingDeque<>();

        /**
         * Constructor.
         */
        protected FragmentizerWorker() {
            super(igfsCtx.kernalContext().gridName(), "fragmentizer-worker", igfsCtx.kernalContext().log());
        }

        /** {@inheritDoc} */
        @Override public void onMessage(UUID nodeId, Object msg) {
            if (msg instanceof IgfsFragmentizerRequest ||
                msg instanceof IgfsSyncMessage) {
                if (log.isDebugEnabled())
                    log.debug("Received fragmentizer request from remote node [nodeId=" + nodeId +
                        ", msg=" + msg + ']');

                IgniteBiTuple<UUID, IgfsCommunicationMessage> tup = F.t(nodeId, (IgfsCommunicationMessage)msg);

                try {
                    if (!msgs.offer(tup, MSG_OFFER_TIMEOUT, TimeUnit.MILLISECONDS)) {
                        U.error(log, "Failed to process fragmentizer communication message (will discard) " +
                            "[nodeId=" + nodeId + ", msg=" + msg + ']');
                    }
                }
                catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();

                    U.warn(log, "Failed to process fragmentizer communication message (thread was interrupted) "+
                        "[nodeId=" + nodeId + ", msg=" + msg + ']');
                }
            }
        }

        /** {@inheritDoc} */
        @Override protected void body() throws InterruptedException, IgniteInterruptedCheckedException {
            while (!isCancelled()) {
                IgniteBiTuple<UUID, IgfsCommunicationMessage> req = msgs.take();

                UUID nodeId = req.get1();

                if (req.get2() instanceof IgfsFragmentizerRequest) {
                    IgfsFragmentizerRequest fragmentizerReq = (IgfsFragmentizerRequest)req.get2();

                    if (!rw.tryReadLock()) {
                        if (log.isDebugEnabled())
                            log.debug("Received fragmentizing request while stopping grid (will ignore) " +
                                "[nodeId=" + nodeId + ", req=" + req.get2() + ']');

                        continue; // while.
                    }

                    try {
                        try {
                            processFragmentizerRequest(fragmentizerReq);
                        }
                        catch (IgniteCheckedException e) {
                            if (e.hasCause(ClusterTopologyCheckedException.class)) {
                                if (log.isDebugEnabled())
                                    log.debug("Failed to process fragmentizer request (remote node left the grid) " +
                                        "[req=" + req + ", err=" + e.getMessage() + ']');
                            }
                            else
                                U.error(log, "Failed to process fragmentizer request [nodeId=" + nodeId +
                                    ", req=" + req + ']', e);
                        }
                        finally {
                            sendResponse(nodeId, new IgfsFragmentizerResponse(fragmentizerReq.fileId()));
                        }
                    }
                    finally {
                        rw.readUnlock();
                    }
                }
                else {
                    assert req.get2() instanceof IgfsSyncMessage;

                    IgfsSyncMessage syncMsg = (IgfsSyncMessage)req.get2();

                    if (!syncMsg.response()) {
                        IgfsSyncMessage res = new IgfsSyncMessage(syncMsg.order(), true);

                        if (log.isDebugEnabled())
                            log.debug("Sending fragmentizer sync response to remote node [nodeId=" + nodeId +
                                ", res=" + res + ']');

                        sendResponse(nodeId, res);
                    }
                }
            }
        }

        /**
         * Sends response to remote node.
         *
         * @param nodeId Node ID to send response to.
         * @param msg Message to send.
         */
        private void sendResponse(UUID nodeId, IgfsCommunicationMessage msg) {
            try {
                sendWithRetries(nodeId, msg);
            }
            catch (IgniteCheckedException e) {
                if (e.hasCause(ClusterTopologyCheckedException.class)) {
                    if (log.isDebugEnabled())
                        log.debug("Failed to send sync response to GGFS fragmentizer coordinator " +
                            "(originating node left the grid): " + nodeId);
                }
                else
                    U.error(log, "Failed to send sync response to GGFS fragmentizer coordinator: " + nodeId, e);
            }
        }
    }

    /**
     * Hash set that overrides equals to use identity comparison.
     */
    private static class IdentityHashSet extends GridConcurrentHashSet<UUID> {
        /** */
        private static final long serialVersionUID = 0L;

        /**
         * Constructor.
         *
         * @param c Collection to add.
         */
        private IdentityHashSet(Collection<UUID> c) {
            super(c);
        }

        /** {@inheritDoc} */
        @Override public boolean equals(Object o) {
            // Identity comparison.
            return this == o;
        }
    }
}
