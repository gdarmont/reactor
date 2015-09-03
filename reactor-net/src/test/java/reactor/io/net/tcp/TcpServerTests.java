/*
 * Copyright (c) 2011-2015 Pivotal Software Inc., Inc. All Rights Reserved.
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

package reactor.io.net.tcp;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.LineBasedFrameDecoder;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.reactivestreams.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;
import reactor.Timers;
import reactor.core.processor.RingBufferWorkProcessor;
import reactor.core.support.UUIDUtils;
import reactor.fn.Consumer;
import reactor.fn.Supplier;
import reactor.io.buffer.Buffer;
import reactor.io.codec.*;
import reactor.io.codec.json.JsonCodec;
import reactor.io.net.ChannelStream;
import reactor.io.net.NetStreams;
import reactor.io.net.ReactorChannelHandler;
import reactor.io.net.config.ServerSocketOptions;
import reactor.io.net.config.SslOptions;
import reactor.io.net.http.HttpServer;
import reactor.io.net.impl.netty.NettyServerSocketOptions;
import reactor.io.net.impl.netty.tcp.NettyTcpClient;
import reactor.io.net.impl.zmq.tcp.ZeroMQTcpServer;
import reactor.io.net.tcp.support.SocketUtils;
import reactor.rx.Streams;
import reactor.rx.broadcast.Broadcaster;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public class TcpServerTests {

	final Logger log = LoggerFactory.getLogger(TcpServerTests.class);
	ExecutorService threadPool;
	final int msgs    = 10;
	final int threads = 4;

	CountDownLatch latch;
	AtomicLong count = new AtomicLong();
	AtomicLong start = new AtomicLong();
	AtomicLong end   = new AtomicLong();

	@Before
	public void loadEnv() {
		Timers.global();
		latch = new CountDownLatch(msgs * threads);
		threadPool = Executors.newCachedThreadPool();
	}

	@After
	public void cleanup() {
		threadPool.shutdownNow();
		Timers.unregisterGlobal();
	}

	@Test
	public void tcpServerHandlesJsonPojosOverSsl() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		SslOptions serverOpts = new SslOptions()
		  .keystoreFile("./src/test/resources/server.jks")
		  .keystorePasswd("changeit");

		SslOptions clientOpts = new SslOptions()
		  .keystoreFile("./src/test/resources/client.jks")
		  .keystorePasswd("changeit")
		  .trustManagers(new Supplier<TrustManager[]>() {
			  @Override
			  public TrustManager[] get() {
				  return new TrustManager[]{
					new X509TrustManager() {
						@Override
						public void checkClientTrusted(X509Certificate[] x509Certificates, String s)
						  throws CertificateException {
							// trust all
						}

						@Override
						public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
						  throws CertificateException {
							// trust all
						}

						@Override
						public X509Certificate[] getAcceptedIssuers() {
							return new X509Certificate[0];
						}
					}
				  };
			  }
		  });

		JsonCodec<Pojo, Pojo> codec = new JsonCodec<Pojo, Pojo>(Pojo.class);

		final CountDownLatch latch = new CountDownLatch(1);
		final TcpClient<Pojo, Pojo> client = NetStreams.tcpClient(s ->
			s
			  .ssl(clientOpts)
			  .codec(codec)
			  .connect("localhost", port)
		);

		final TcpServer<Pojo, Pojo> server = NetStreams.tcpServer(s ->
			s
			  .ssl(serverOpts)
			  .listen("localhost", port)
			  .codec(codec)
		);

		server.start(channel -> {
			channel.log("conn").consume(data -> {
				if ("John Doe".equals(data.getName())) {
					latch.countDown();
				}
			});
			return Streams.never();
		});


		client.start(ch -> ch.writeWith(Streams.just(new Pojo("John Doe")))).await();

		assertTrue("Latch was counted down", latch.await(5, TimeUnit.SECONDS));

		client.shutdown().await();
		server.shutdown().await();
	}

	@Test
	public void tcpServerHandlesLengthFieldData() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();

		TcpServer<byte[], byte[]> server = NetStreams.tcpServer(s ->
			s
			  .options(new ServerSocketOptions()
				.backlog(1000)
				.reuseAddr(true)
				.tcpNoDelay(true))
			  .listen(port)
			  .codec(new LengthFieldCodec<>(StandardCodecs.BYTE_ARRAY_CODEC))
		);

		System.out.println(latch.getCount());

		server.start(ch -> {
			  log.info(ch.consume(new Consumer<byte[]>() {
				  long num = 1;

				  @Override
				  public void accept(byte[] bytes) {
					  latch.countDown();
					  ByteBuffer bb = ByteBuffer.wrap(bytes);
					  if (bb.remaining() < 4) {
						  System.err.println("insufficient len: " + bb.remaining());
					  }
					  int next = bb.getInt();
					  if (next != num++) {
						  System.err.println(this + " expecting: " + next + " but got: " + (num - 1));
					  } else {
						  log.info("received " + (num - 1));
					  }
				  }
			  }).debug().toString());
			  return Streams.never();
		  }
		).await();

		start.set(System.currentTimeMillis());
		for (int i = 0; i < threads; i++) {
			threadPool.submit(new LengthFieldMessageWriter(port));
		}

		latch.await(10, TimeUnit.SECONDS);
		System.out.println(latch.getCount());

		assertTrue("Latch was counted down: " + latch.getCount(), latch.getCount() == 0);
		end.set(System.currentTimeMillis());

		double elapsed = (end.get() - start.get()) * 1.0;
		System.out.println("elapsed: " + (int) elapsed + "ms");
		System.out.println("throughput: " + (int) ((msgs * threads) / (elapsed / 1000)) + "/sec");

		server.shutdown().await();
	}

	@Test
	public void tcpServerHandlesFrameData() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();

		TcpServer<Frame, Frame> server = NetStreams.tcpServer(spec ->
			spec
			  .options(new ServerSocketOptions()
			    .backlog(1000)
			    .reuseAddr(true)
			    .tcpNoDelay(true))
			  .listen(port)
			  .codec(new FrameCodec(2, FrameCodec.LengthField.SHORT))
		);

		server.start(ch -> {
			ch.consume(frame -> {
				short prefix = frame.getPrefix().readShort();
				assertThat("prefix is 0", prefix == 0);
				Buffer data = frame.getData();
				assertThat("len is 128", data.remaining() == 128);

				latch.countDown();
			});
			return Streams.never();
		}).await();

		start.set(System.currentTimeMillis());
		for (int i = 0; i < threads; i++) {
			threadPool.submit(new FramedLengthFieldMessageWriter(port));
		}

		latch.await(10, TimeUnit.SECONDS);
		assertTrue("Latch was counted down:" + latch.getCount(), latch.getCount() == 0);
		end.set(System.currentTimeMillis());

		double elapsed = (end.get() - start.get()) * 1.0;
		System.out.println("elapsed: " + (int) elapsed + "ms");
		System.out.println("throughput: " + (int) ((msgs * threads) / (elapsed / 1000)) + "/sec");

		server.shutdown().await();
	}

	@Test
	public void exposesRemoteAddress() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(1);

		TcpClient<Buffer, Buffer> client = NetStreams.<Buffer, Buffer>tcpClient(NettyTcpClient.class, s ->
			s.connect("localhost", port)
		);

		TcpServer<Buffer, Buffer> server = NetStreams.tcpServer(s ->
			s.listen(port)
			  .codec(new PassThroughCodec<Buffer>())
		);

		server.start(ch -> {
			InetSocketAddress remoteAddr = ch.remoteAddress();
			assertNotNull("remote address is not null", remoteAddr.getAddress());
			latch.countDown();

			return Streams.never();
		}).await();

		client.start(ch -> ch.writeWith(Streams.just(Buffer.wrap("Hello World!"))));

		assertTrue("latch was counted down", latch.await(5, TimeUnit.SECONDS));

		server.shutdown().await();
	}

	@Test
	public void exposesNettyPipelineConfiguration() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(2);

		final TcpClient<String, String> client = NetStreams.tcpClient(s ->
			s
			  .connect("localhost", port)
			  .codec(StandardCodecs.LINE_FEED_CODEC)
		);

		ReactorChannelHandler<String, String, ChannelStream<String, String>> serverHandler = ch -> {
			ch.consume(data -> {
				log.info("data " + data + " on " + ch);
				latch.countDown();
			});
			return Streams.never();
		};

		TcpServer<String, String> server = NetStreams.tcpServer(s ->
			s
			  .options(new NettyServerSocketOptions()
			    .pipelineConfigurer(pipeline -> pipeline.addLast(new LineBasedFrameDecoder(8 * 1024))))
			  .listen(port)
			  .codec(StandardCodecs.STRING_CODEC)
		);

		server.start(serverHandler).await();

		client.start(ch -> ch.writeWith(Streams.just("Hello World!", "Hello 11!"))).await();

		assertTrue("Latch was counted down", latch.await(10, TimeUnit.SECONDS));

		client.shutdown().await();
		server.shutdown().await();
	}

	@Test
	public void exposesNettyByteBuf() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(msgs);

		TcpServer<ByteBuf, ByteBuf> server = NetStreams.tcpServer(spec -> spec
			.listen(port)
			.rawData(true)
		);

		log.info("Starting raw server on tcp://localhost:{}", port);
		server.start(ch -> {
			ch.consume(byteBuf -> {
				byteBuf.forEachByte(value -> {
					if (value == '\n') {
						latch.countDown();
					}
					return true;
				});
				byteBuf.release();
			});
			return Streams.never();
		}).await();


		for (int i = 0; i < threads; i++) {
			threadPool.submit(new DataWriter(port));
		}

		try {
			assertTrue("Latch was counted down", latch.await(10, TimeUnit.SECONDS));
		} finally {
			server.shutdown().await();
		}

	}

	@Test(timeout = 60000)
	public void exposesZeroMQServer() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(2);
		ZContext zmq = new ZContext();

		TcpServer<Buffer, Buffer> server = NetStreams.tcpServer(ZeroMQTcpServer.class, spec -> spec
			.listen("127.0.0.1", port)
		);

		server.start(ch ->
			ch.writeWith(
			  ch
				.take(1)
				.observe(buff -> {
					if (buff.remaining() == 128) {
						latch.countDown();
					} else {
						log.info("data: {}", buff.asString());
					}
				})
				.map(d -> Buffer.wrap("Goodbye World!"))
				.log("conn")
			)
		).await();

		ZeroMQWriter zmqw = new ZeroMQWriter(zmq, port, latch);
		threadPool.submit(zmqw);

		assertTrue("reply was received", latch.await(500, TimeUnit.SECONDS));
		assertTrue("Server was stopped", server.shutdown().awaitSuccess(5, TimeUnit.SECONDS));

		//zmq.destroy();
	}

	@Test
	@Ignore
	public void test5() throws Exception {
		//Hot stream of data, could be injected from anywhere
		Broadcaster<String> broadcaster = Broadcaster.<String>create();

		//Get a reference to the tail of the operation pipeline (microbatching + partitioning)
		final Processor<List<String>, List<String>> processor = RingBufferWorkProcessor.create(false);

		broadcaster

		  //transform 10 data in a [] of 10 elements or wait up to 1 Second before emitting whatever the list contains
		  .buffer(10, 1, TimeUnit.SECONDS)
		  .log("broadcaster")
		  .subscribe(processor);


		//create a server dispatching data on the default shared dispatcher, and serializing/deserializing as string
		HttpServer<String, String> httpServer = NetStreams.httpServer(server -> server
		  .codec(StandardCodecs.STRING_CODEC)
		  .listen(0)
		  );

		//Listen for anything exactly hitting the root URI and route the incoming connection request to the callback
		httpServer.get("/", (request) -> {
			//prepare a response header to be appended first before any reply
			request.addResponseHeader("X-CUSTOM", "12345");
			//attach to the shared tail, take the most recent generated substream and merge it to the high level stream
			//returning a stream of String from each microbatch merged
			return
			  request.writeWith(
				Streams.wrap(processor)
				  //split each microbatch data into individual data
				  .flatMap(Streams::from)
				  .take(5, TimeUnit.SECONDS)
				  .concatWith(Streams.just("end\n"))
			  );
		});

		httpServer.start().awaitSuccess();


		for (int i = 0; i < 50; i++) {
			Thread.sleep(500);
			broadcaster.onNext(System.currentTimeMillis() + "\n");
		}


		httpServer.shutdown().awaitSuccess();

	}

	public static class Pojo {
		private String name;

		private Pojo() {
		}

		private Pojo(String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		@Override
		public String toString() {
			return "Pojo{" +
			  "name='" + name + '\'' +
			  '}';
		}
	}

	private class LengthFieldMessageWriter implements Runnable {
		private final Random rand = new Random();
		private final int port;
		private final int length;

		private LengthFieldMessageWriter(int port) {
			this.port = port;
			this.length = rand.nextInt(156) + 100;
		}

		@Override
		public void run() {
			try {
				java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open(new InetSocketAddress(port));

				System.out.println("writing " + msgs + " messages of " + length + " byte length...");

				int num = 1;
				start.set(System.currentTimeMillis());
				for (int j = 0; j < msgs; j++) {
					ByteBuffer buff = ByteBuffer.allocate(length + 4);
					buff.putInt(length);
					buff.putInt(num++);
					buff.position(0);
					buff.limit(length + 4);

					ch.write(buff);

					count.incrementAndGet();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private class FramedLengthFieldMessageWriter implements Runnable {
		private final short length = 128;
		private final int port;

		private FramedLengthFieldMessageWriter(int port) {
			this.port = port;
		}

		@Override
		public void run() {
			try {
				java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open(new InetSocketAddress(port));

				System.out.println("writing " + msgs + " messages of " + length + " byte length...");

				start.set(System.currentTimeMillis());
				for (int j = 0; j < msgs; j++) {
					ByteBuffer buff = ByteBuffer.allocate(length + 4);
					buff.putShort((short) 0);
					buff.putShort(length);
					for (int i = 4; i < length; i++) {
						buff.put((byte) 1);
					}
					buff.flip();
					buff.limit(length + 4);

					ch.write(buff);

					count.incrementAndGet();
				}
				ch.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	@Test
	public void testIssue462() throws InterruptedException {

		final CountDownLatch countDownLatch = new CountDownLatch(1);

		TcpServer<String, String> server =
		  NetStreams.tcpServer(s ->
			  s
				.codec(new StringCodec())
				.listen(0)
		  );

		server.start(ch -> {
			ch.log("channel").consume(trip -> {
				countDownLatch.countDown();
			});
			return Streams.never();
		}).await();

		TcpClient<String, String> client =
		  NetStreams.tcpClient(s ->
			  s
				.codec(new StringCodec())
				.connect("127.0.0.1", server.listenAddress.getPort())
		  );


		client.start(ch ->
			ch.writeWith(Streams.just("test"))
		);

		assertThat("countDownLatch counted down", countDownLatch.await(5, TimeUnit.SECONDS));
	}

	@Test
	@Ignore
	public void proxyTest() throws Exception {
		HttpServer<Buffer, Buffer> server = NetStreams.httpServer();
		server.get("/search/{search}", requestIn ->
			NetStreams.httpClient()
			  .get("foaas.herokuapp.com/life/" + requestIn.param("search"))
			  .flatMap(repliesOut ->
				  requestIn
					.writeWith(repliesOut)
			  )
		);
		server.start().await();
		//System.in.read();
		Thread.sleep(1000000);
	}

	@Test
	@Ignore
	public void wsTest() throws Exception {
		HttpServer<Buffer, Buffer> server = NetStreams.httpServer();
		server.get("/search/{search}", requestIn ->
			NetStreams.httpClient()
			  .ws("ws://localhost:3000", requestOut ->
				  requestOut.writeWith(Streams.just(Buffer.wrap("ping")))
			  )
			  .flatMap(repliesOut ->
				  requestIn
					.writeWith(repliesOut.capacity(100))
			  )
		);
		server.start().await();
		//System.in.read();
		Thread.sleep(1000000);
	}

	private class HttpRequestWriter implements Runnable {
		private final int port;

		private HttpRequestWriter(int port) {
			this.port = port;
		}

		@Override
		public void run() {

			start.set(System.currentTimeMillis());
			for (int i = 0; i < msgs; i++) {
				try {
					java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open(new InetSocketAddress
					  (port));
					ch.write(Buffer.wrap("GET /test HTTP/1.1\r\nConnection: Close\r\n\r\n").byteBuffer());
					ByteBuffer buff = ByteBuffer.allocate(4 * 1024);
					ch.read(buff);
					ch.close();

				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private class DataWriter implements Runnable {
		private final int port;

		private DataWriter(int port) {
			this.port = port;
		}

		@Override
		public void run() {
			try {
				java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open(new InetSocketAddress(port));
				start.set(System.currentTimeMillis());
				for (int i = 0; i < 100; i++) {
					ch.write(Buffer.wrap("Hello World!\n").byteBuffer());
				}
				ch.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private class ZeroMQWriter implements Runnable {
		private final Random random = new Random();
		private final ZContext       zmq;
		private final int            port;
		private final CountDownLatch latch;

		private ZeroMQWriter(ZContext zmq, int port, CountDownLatch latch) {
			this.zmq = zmq;
			this.port = port;
			this.latch = latch;
		}

		@Override
		public void run() {
			String id = UUIDUtils.random().toString();
			ZMQ.Socket socket = zmq.createSocket(ZMQ.DEALER);
			socket.setIdentity(id.getBytes());
			socket.connect("tcp://127.0.0.1:" + port);

			byte[] data = new byte[128];
			random.nextBytes(data);

			socket.send(data);

			ZMsg reply = ZMsg.recvMsg(socket);
			log.info("reply: {}", reply);
			latch.countDown();

			//zmq.destroySocket(socket);
		}
	}

}
