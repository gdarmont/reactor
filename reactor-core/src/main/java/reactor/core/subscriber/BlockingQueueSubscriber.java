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
package reactor.core.subscriber;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.support.Assert;

import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * @author Stephane Maldini
 */
public class BlockingQueueSubscriber<IN> extends BaseSubscriber<IN> implements Subscription, BlockingQueue<IN> {

	private final Publisher<IN>  source;
	private final Subscriber<IN> target;
	private final Queue<IN>      store;
	private final int            capacity;

	private volatile Throwable endError;
	private volatile boolean   terminated;

	private volatile Subscription subscription;

	private volatile int remainingCapacity;
	private static final AtomicIntegerFieldUpdater<BlockingQueueSubscriber> REMAINING = AtomicIntegerFieldUpdater
	  .newUpdater(BlockingQueueSubscriber.class, "remainingCapacity");

	public BlockingQueueSubscriber(Publisher<IN> source, Subscriber<IN> target, Queue<IN> store, int capacity) {
		Assert.isTrue(store != null, "A queue must be provided");
		Assert.isTrue(capacity > 0, "A strict positive capacity is required");
		this.source = source;
		this.target = target;
		this.remainingCapacity = this.capacity = capacity;
		this.store = store;
		if (source != null) {
			source.subscribe(this);
		}
	}

	@Override
	public void request(long n) {
		Subscription subscription = this.subscription;
		if (subscription != null) {
			subscription.request(n);
		}
	}

	@Override
	public void cancel() {
		Subscription subscription = this.subscription;
		if (subscription != null) {
			subscription.cancel();
			this.subscription = null;
		}
	}

	@Override
	public void onSubscribe(Subscription s) {
		super.onSubscribe(s);
		this.subscription = s;
	}

	@Override
	public void onNext(IN in) {
		super.onNext(in);
		if (source == null && target != null) {
			target.onNext(in);
		} else {
			store.offer(in);
		}
	}

	@Override
	public void onError(Throwable t) {
		super.onError(t);
		terminated = true;
		endError = t;
		if (source == null && target != null) {
			target.onError(t);
		}
	}

	@Override
	public void onComplete() {
		super.onComplete();
		terminated = true;
		if (source == null && target != null) {
			target.onComplete();
		}
	}

	@Override
	public boolean add(IN in) {
		if (target == null) {
			throw new UnsupportedOperationException("This operation requires a write queue");
		}
		if (remainingCapacity == 0) throw new IllegalStateException("no space");
		onNext(in);
		return true;
	}

	@Override
	public boolean offer(IN in) {
		if (target == null) {
			throw new UnsupportedOperationException("This operation requires a write queue");
		}
		if (remainingCapacity == 0) return false;
		onNext(in);
		return false;
	}

	@Override
	public void put(IN in) throws InterruptedException {
		if (target == null) {
			throw new UnsupportedOperationException("This operation requires a write queue");
		}
		onNext(in);
	}

	@Override
	public boolean offer(IN in, long timeout, TimeUnit unit) throws InterruptedException {
		if (target == null) {
			throw new UnsupportedOperationException("This operation requires a write queue");
		}
		return false;
	}

	@Override
	public IN take() throws InterruptedException {
		if (source == null) {
			throw new UnsupportedOperationException("This operation requires a read queue");
		}
		return null;
	}

	@Override
	public IN poll(long timeout, TimeUnit unit) throws InterruptedException {
		if (source == null) {
			throw new UnsupportedOperationException("This operation requires a read queue");
		}
		return null;
	}

	@Override
	public boolean remove(Object o) {
		if (source == null) {
			throw new UnsupportedOperationException("This operation requires a read queue");
		}
		return store.remove(o);
	}

	@Override
	public int drainTo(Collection<? super IN> c) {
		if (source == null) {
			throw new UnsupportedOperationException("This operation requires a read queue");
		}
		if (store instanceof BlockingQueue) {
			((BlockingQueue<IN>) store).drainTo(c);
		}
		return 0;
	}

	@Override
	public int drainTo(Collection<? super IN> c, int maxElements) {
		if (source == null) {
			throw new UnsupportedOperationException("This operation requires a read queue");
		}
		if (store instanceof BlockingQueue) {
			((BlockingQueue<IN>) store).drainTo(c, maxElements);
		}
		return 0;
	}

	@Override
	public IN remove() {
		if (source == null) {
			throw new UnsupportedOperationException("This operation requires a read queue");
		}
		return null;
	}

	@Override
	public IN poll() {
		if (source == null) {
			throw new UnsupportedOperationException("This operation requires a read queue");
		}
		return null;
	}

	@Override
	public boolean addAll(Collection<? extends IN> c) {
		if (target == null) {
			throw new UnsupportedOperationException("This operation requires a write queue");
		}
		return false;
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		if (source == null) {
			throw new UnsupportedOperationException("This operation requires a read queue");
		}
		return store.removeAll(c);
	}


	@Override
	public boolean retainAll(Collection<?> c) {
		if (source == null) {
			throw new UnsupportedOperationException("This operation requires a read queue");
		}
		return store.retainAll(c);
	}

	@Override
	public void clear() {
		if (source == null) {
			throw new UnsupportedOperationException("This operation requires a read queue");
		}
		store.clear();

	}

	@Override
	public int remainingCapacity() {
		return remainingCapacity;
	}

	@Override
	public boolean contains(Object o) {
		return store.contains(o);
	}

	@Override
	public IN element() {
		return store.element();
	}

	@Override
	public IN peek() {
		return store.peek();
	}

	@Override
	public int size() {
		return store.size();
	}

	@Override
	public boolean isEmpty() {
		return store.isEmpty();
	}

	@Override
	public Iterator<IN> iterator() {
		return store.iterator();
	}

	@Override
	public Object[] toArray() {
		return store.toArray();
	}

	@Override
	public <T> T[] toArray(T[] a) {
		return store.toArray(a);
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		return store.containsAll(c);
	}

}
