/*
 * Copyright (c) 2011-2015 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.processor.rb.disruptor;

import reactor.core.error.InsufficientCapacityException;
import reactor.core.processor.rb.disruptor.util.Util;
import reactor.fn.Consumer;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Base class for the various sequencer types (single/multi).  Provides
 * common functionality like the management of gating sequences (add/remove) and
 * ownership of the current cursor.
 */
public abstract class Sequencer
{
    /** Set to -1 as sequence starting point */
    public static final  long                                               INITIAL_CURSOR_VALUE = -1L;
    private static final AtomicReferenceFieldUpdater<Sequencer, Sequence[]> SEQUENCE_UPDATER     =
      AtomicReferenceFieldUpdater.newUpdater(Sequencer.class, Sequence[].class, "gatingSequences");

    protected final Consumer<Void> spinObserver;
    protected final int            bufferSize;
    protected final WaitStrategy   waitStrategy;
    protected final    Sequence   cursor          = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    protected volatile Sequence[] gatingSequences = new Sequence[0];

    /**
     * Create with the specified buffer size and wait strategy.
     *
     * @param bufferSize The total number of entries, must be a positive power of 2.
     * @param waitStrategy
     * @param spinObserver
     */
    public Sequencer(int bufferSize, WaitStrategy waitStrategy, Consumer<Void> spinObserver) {
        if (bufferSize < 1) {
            throw new IllegalArgumentException("bufferSize must not be less than 1");
        }
        if (Integer.bitCount(bufferSize) != 1) {
            throw new IllegalArgumentException("bufferSize must be a power of 2");
        }

        this.spinObserver = spinObserver;
        this.bufferSize = bufferSize;
        this.waitStrategy = waitStrategy;
    }

    /**
     * Get the current cursor value.
     *
     * @return current cursor value
     */
    public final long getCursor() {
        return cursor.get();
    }



    /**
     * Get the current cursor value.
     *
     * @return current cursor value
     */
    public final Sequence getSequence() {
        return cursor;
    }

    /**
     * The capacity of the data structure to hold entries.
     *
     * @return the size of the RingBuffer.
     */
    public final int getBufferSize() {
        return bufferSize;
    }

    /**
     * Add the specified gating sequences to this instance of the Disruptor.  They will
         * safely and atomically added to the list of gating sequences.
         *
         * @param gatingSequences The sequences to add.
         */
    public final void addGatingSequences(Sequence... gatingSequences)
    {
        SequenceGroups.addSequences(this, SEQUENCE_UPDATER, this, gatingSequences);
    }

    /**
         * Remove the specified sequence from this sequencer.
         *
         * @param sequence to be removed.
         * @return <tt>true</tt> if this sequence was found, <tt>false</tt> otherwise.
         */
    public boolean removeGatingSequence(Sequence sequence)
    {
        return SequenceGroups.removeSequence(this, SEQUENCE_UPDATER, sequence);
    }

    /**
         * Get the minimum sequence value from all of the gating sequences
         * added to this ringBuffer.
         *
         * @return The minimum gating sequence or the cursor sequence if
         * no sequences have been added.
         */
    public long getMinimumSequence()
    {
        return Util.getMinimumSequence(gatingSequences, cursor.get());
    }

    /**
         * Create a new SequenceBarrier to be used by an EventProcessor to track which messages
         * are available to be read from the ring buffer
         *
         * @see SequenceBarrier
         * @return A sequence barrier that will track the specified sequences.
         */
    public SequenceBarrier newBarrier()
    {
        return new SequenceBarrier(this, waitStrategy, cursor);
    }

    /**
     * Claim a specific sequence.  Only used if initialising the ring buffer to
     * a specific value.
     *
     * @param sequence The sequence to initialise too.
     */
    public abstract void claim(long sequence);

    /**
     * Confirms if a sequence is published and the event is available for use; non-blocking.
     *
     * @param sequence of the buffer to check
     * @return true if the sequence is available for use, false if not
     */
    public abstract boolean isAvailable(long sequence);

    /**
     * Get the highest sequence number that can be safely read from the ring buffer.  Depending
     * on the implementation of the Sequencer this call may need to scan a number of values
     * in the Sequencer.  The scan will range from nextSequence to availableSequence.  If
     * there are no available values <code>&gt;= nextSequence</code> the return value will be
     * <code>nextSequence - 1</code>.  To work correctly a consumer should pass a value that
     * it 1 higher than the last sequence that was successfully processed.
     *
     * @param nextSequence The sequence to start scanning from.
     * @param availableSequence The sequence to scan to.
     * @return The highest value that can be safely read, will be at least <code>nextSequence - 1</code>.
     */
    public abstract long getHighestPublishedSequence(long nextSequence, long availableSequence);

    /**
     * @return Get the latest cached consumed value
     */
    public abstract long cachedRemainingCapacity();

    /**
     * Has the buffer got capacity to allocate another sequence.  This is a concurrent
     * method so the response should only be taken as an indication of available capacity.
     * @param requiredCapacity in the buffer
     * @return true if the buffer has the capacity to allocate the next sequence otherwise false.
     */
    public abstract boolean hasAvailableCapacity(final int requiredCapacity);

    /**
     * Get the remaining capacity for this sequencer.
     * @return The number of slots remaining.
     */
    public abstract long remainingCapacity();

    /**
     * Claim the next event in sequence for publishing.
     * @return the claimed sequence value
     */
    public abstract long next();

    /**
     * Claim the next n events in sequence for publishing.  This is for batch event producing.  Using batch producing
     * requires a little care and some math.
     * <pre>
     * int n = 10;
     * long hi = sequencer.next(n);
     * long lo = hi - (n - 1);
     * for (long sequence = lo; sequence &lt;= hi; sequence++) {
     *     // Do work.
     * }
     * sequencer.publish(lo, hi);
     * </pre>
     *
     * @param n the number of sequences to claim
     * @return the highest claimed sequence value
     */
    public abstract long next(int n);

    /**
     * Attempt to claim the next event in sequence for publishing.  Will return the
     * number of the slot if there is at least <code>requiredCapacity</code> slots
     * available.
     * @return the claimed sequence value
     * @throws InsufficientCapacityException
     */
    public abstract long tryNext() throws InsufficientCapacityException;

    /**
     * Attempt to claim the next n events in sequence for publishing.  Will return the
     * highest numbered slot if there is at least <code>requiredCapacity</code> slots
     * available.  Have a look at {@link Sequencer#next()} for a description on how to
     * use this method.
     *
     * @param n the number of sequences to claim
     * @return the claimed sequence value
     * @throws InsufficientCapacityException
     */
    public abstract long tryNext(int n) throws InsufficientCapacityException;

    /**
     * Publishes a sequence. Call when the event has been filled.
     *
     * @param sequence
     */
    public abstract void publish(long sequence);

    /**
     * Batch publish sequences.  Called when all of the events have been filled.
     *
     * @param lo first sequence number to publish
     * @param hi last sequence number to publish
     */
    public abstract void publish(long lo, long hi);
}