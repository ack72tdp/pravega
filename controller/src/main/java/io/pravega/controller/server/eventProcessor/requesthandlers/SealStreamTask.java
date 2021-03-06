/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.controller.server.eventProcessor.requesthandlers;

import com.google.common.base.Preconditions;
import io.pravega.common.Exceptions;
import io.pravega.common.concurrent.Futures;
import io.pravega.controller.store.stream.OperationContext;
import io.pravega.controller.store.stream.Segment;
import io.pravega.controller.store.stream.StoreException;
import io.pravega.controller.store.stream.StreamMetadataStore;
import io.pravega.controller.store.stream.TxnStatus;
import io.pravega.controller.store.stream.tables.State;
import io.pravega.controller.task.Stream.StreamMetadataTasks;
import io.pravega.controller.task.Stream.StreamTransactionMetadataTasks;
import io.pravega.shared.controller.event.SealStreamEvent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Request handler for performing scale operations received from requeststream.
 */
@Slf4j
public class SealStreamTask implements StreamTask<SealStreamEvent> {

    private final StreamMetadataTasks streamMetadataTasks;
    private final StreamTransactionMetadataTasks streamTransactionMetadataTasks;
    private final StreamMetadataStore streamMetadataStore;
    private final ScheduledExecutorService executor;

    public SealStreamTask(final StreamMetadataTasks streamMetadataTasks,
                          final StreamTransactionMetadataTasks streamTransactionMetadataTasks,
                          final StreamMetadataStore streamMetadataStore,
                          final ScheduledExecutorService executor) {
        Preconditions.checkNotNull(streamMetadataStore);
        Preconditions.checkNotNull(streamMetadataTasks);
        Preconditions.checkNotNull(streamTransactionMetadataTasks);
        Preconditions.checkNotNull(executor);
        this.streamMetadataTasks = streamMetadataTasks;
        this.streamTransactionMetadataTasks = streamTransactionMetadataTasks;
        this.streamMetadataStore = streamMetadataStore;
        this.executor = executor;
    }


    @Override
    public CompletableFuture<Void> execute(final SealStreamEvent request) {
        String scope = request.getScope();
        String stream = request.getStream();
        final OperationContext context = streamMetadataStore.createContext(scope, stream);

        // when seal stream task is picked, if the state is sealing/sealed, process sealing, else postpone.
        return streamMetadataStore.getState(scope, stream, true, context, executor)
                .thenAccept(state -> {
                    if (!state.equals(State.SEALING) && !state.equals(State.SEALED)) {
                        throw new TaskExceptions.StartException("Seal stream task not started yet.");
                    }
                })
                .thenCompose(x -> abortTransaction(context, scope, stream)
                        .thenAccept(noTransactions -> {
                            if (!noTransactions) {
                                // If transactions exist on the stream, we will throw OperationNotAllowed so that this task
                                // is retried.
                                log.debug("Found open transactions on stream {}/{}. Postponing its sealing.", scope, stream);
                                throw StoreException.create(StoreException.Type.OPERATION_NOT_ALLOWED,
                                        "Found ongoing transactions. Abort transaction requested." +
                                                "Sealing stream segments should wait until transactions are aborted.");
                            }
                        }))
                .thenCompose(x -> streamMetadataStore.getActiveSegments(scope, stream, context, executor))
                .thenCompose(activeSegments -> {
                    if (activeSegments.isEmpty()) {
                        // idempotent check
                        // if active segment set is empty then the stream is sealed.
                        // Do not update the state if the stream is already sealed.
                        return CompletableFuture.completedFuture(null);
                    } else {
                        return notifySealed(scope, stream, context, activeSegments);
                    }
                });
    }

    /**
     * A method that issues abort request for all outstanding transactions on the stream, which are processed asynchronously.
     * This method returns false if it found transactions to abort, true otherwise.
     * @param context operation context
     * @param scope scope
     * @param stream stream
     * @return CompletableFuture which when complete will contain a boolean indicating if there are transactions of the
     * stream or not.
     */
    private CompletableFuture<Boolean> abortTransaction(OperationContext context, String scope, String stream) {
        return streamMetadataStore.getActiveTxns(scope, stream, context, executor)
                .thenCompose(activeTxns -> {
                    if (activeTxns == null || activeTxns.isEmpty()) {
                        return CompletableFuture.completedFuture(true);
                    } else {
                        // abort transactions
                        return Futures.allOf(activeTxns.entrySet().stream().map(txIdPair -> {
                            CompletableFuture<Void> voidCompletableFuture;
                            if (txIdPair.getValue().getTxnStatus().equals(TxnStatus.OPEN)) {
                                voidCompletableFuture = Futures.toVoid(streamTransactionMetadataTasks
                                        .abortTxn(scope, stream, txIdPair.getKey(), null, context)
                                        .exceptionally(e -> {
                                            Throwable cause = Exceptions.unwrap(e);
                                            if (cause instanceof StoreException.IllegalStateException ||
                                                    cause instanceof StoreException.WriteConflictException ||
                                                    cause instanceof StoreException.DataNotFoundException) {
                                                // IllegalStateException : The transaction is already in the process of being
                                                // completed. Ignore
                                                // WriteConflictException : Another thread is updating the transaction record.
                                                // ignore. We will effectively retry cleaning up the transaction if it is not
                                                // already being aborted.
                                                // DataNotFoundException: If transaction metadata is cleaned up after reading list
                                                // of active segments
                                                log.debug("A known exception thrown during seal stream while trying to abort transaction " +
                                                        "on stream {}/{}", scope, stream, cause);
                                            } else {
                                                // throw the original exception
                                                // Note: we can ignore this error because if there are transactions found on a stream,
                                                // seal stream reposts the event back into request stream.
                                                // So in subsequent iteration it will reattempt to abort all active transactions.
                                                // This is a valid course of action because it is important to understand that
                                                // all transactions are completable (either via abort of commit).
                                                log.warn("Exception thrown during seal stream while trying to abort transaction " +
                                                        "on stream {}/{}", scope, stream, cause);
                                            }
                                            return null;
                                        }));
                            } else {
                                voidCompletableFuture = CompletableFuture.completedFuture(null);
                            }

                            return voidCompletableFuture;
                        }).collect(Collectors.toList())).thenApply(v -> false);
                    }
                });
    }

    private CompletionStage<Void> notifySealed(String scope, String stream, OperationContext context, List<Segment> activeSegments) {
        List<Integer> segmentsToBeSealed = activeSegments.stream().map(Segment::getNumber).
                collect(Collectors.toList());
        log.debug("Sending notification to segment store to seal segments for stream {}/{}", scope, stream);
        return streamMetadataTasks.notifySealedSegments(scope, stream, segmentsToBeSealed,
                this.streamMetadataTasks.retrieveDelegationToken())
                .thenCompose(v -> setSealed(scope, stream, context));
    }

    private CompletableFuture<Void> setSealed(String scope, String stream, OperationContext context) {
        return Futures.toVoid(streamMetadataStore.setSealed(scope, stream, context, executor));
    }

    @Override
    public CompletableFuture<Void> writeBack(SealStreamEvent event) {
        return streamMetadataTasks.writeEvent(event);
    }
}
