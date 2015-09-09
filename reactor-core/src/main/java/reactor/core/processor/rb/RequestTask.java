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
package reactor.core.processor.rb;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.error.CancelException;
import reactor.core.error.Exceptions;
import reactor.core.processor.rb.disruptor.WaitStrategy;
import reactor.fn.Consumer;
import reactor.fn.LongSupplier;

/**
 * An async request client for ring buffer impls
 *
 * @author Stephane Maldini
 */
public final class RequestTask implements Runnable {

	final WaitStrategy   waitStrategy;
	final LongSupplier   readCount;
	final Subscription   upstream;
	final Consumer<Void> spinObserver;
	final Consumer<Long> postWaitCallback;
	final Subscriber<?>  errorSubscriber;

	final long prefetch;

	public RequestTask(Subscription upstream,
	                   Consumer<Void> stopCondition,
	                   Consumer<Long> postWaitCallback,
	                   LongSupplier readCount,
	                   WaitStrategy waitStrategy,
	                   Subscriber<?> errorSubscriber,
	                   long prefetch
	) {
		this.waitStrategy = waitStrategy;
		this.readCount = readCount;
		this.postWaitCallback = postWaitCallback;
		this.errorSubscriber = errorSubscriber;
		this.upstream = upstream;
		this.spinObserver = stopCondition;
		this.prefetch = prefetch;
	}

	@Override
	public void run() {
		long cursor = -1;
		try {
			spinObserver.accept(null);
			upstream.request((prefetch * 2) - 1);

			while (true) {
				cursor = waitStrategy.waitFor(cursor + prefetch , readCount, spinObserver);
				if(postWaitCallback != null){
					postWaitCallback.accept(cursor);
				}
				//spinObserver.accept(null);
				upstream.request(prefetch);
			}
		} catch (CancelException ce) {
			upstream.cancel();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable t) {
			Exceptions.throwIfFatal(t);
			errorSubscriber.onError(t);
		}
	}
}
