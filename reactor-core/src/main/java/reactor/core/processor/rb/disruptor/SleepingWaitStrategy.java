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

import reactor.fn.Consumer;
import reactor.fn.LongSupplier;

import java.util.concurrent.locks.LockSupport;

/**
 * Sleeping strategy that initially spins, then uses a Thread.yield(), and
 * eventually sleep (<code>LockSupport.parkNanos(1)</code>) for the minimum
 * number of nanos the OS and JVM will allow while the
 * ringbuffer consumers are waiting on a barrier.
 *
 * This strategy is a good compromise between performance and CPU resource.
 * Latency spikes can occur after quiet periods.
 */
public final class SleepingWaitStrategy implements WaitStrategy
{
    private static final int DEFAULT_RETRIES = 200;

    private final int retries;

    public SleepingWaitStrategy()
    {
        this(DEFAULT_RETRIES);
    }

    public SleepingWaitStrategy(int retries)
    {
        this.retries = retries;
    }

    @Override
    public long waitFor(final long sequence, LongSupplier cursor, final Consumer<Void> barrier)
        throws AlertException, InterruptedException
    {
        long availableSequence;
        int counter = retries;

        while ((availableSequence = cursor.get()) < sequence)
        {
            counter = applyWaitMethod(barrier, counter);
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
    }

    private int applyWaitMethod(final Consumer<Void> barrier, int counter)
        throws AlertException
    {
        barrier.accept(null);

        if (counter > 100)
        {
            --counter;
        }
        else if (counter > 0)
        {
            --counter;
            Thread.yield();
        }
        else
        {
            LockSupport.parkNanos(1L);
        }

        return counter;
    }
}
