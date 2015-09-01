package reactor.io.net.tcp;

import com.esotericsoftware.kryo.Kryo;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import reactor.Timers;
import reactor.io.buffer.Buffer;
import reactor.io.codec.json.JacksonJsonCodec;
import reactor.io.codec.kryo.KryoCodec;
import reactor.io.net.AbstractNetClientServerTest;
import reactor.io.net.impl.zmq.tcp.ZeroMQ;
import reactor.io.net.impl.zmq.tcp.ZeroMQTcpClient;
import reactor.io.net.impl.zmq.tcp.ZeroMQTcpServer;
import reactor.rx.Streams;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public class ZeroMQClientServerTests extends AbstractNetClientServerTest {

	static Kryo                  KRYO;
	static KryoCodec<Data, Data> KRYO_CODEC;
	static ZeroMQ<Data>          ZMQ;

	CountDownLatch latch;

	@BeforeClass
	public static void classSetup() {
		KRYO = new Kryo();
		KRYO_CODEC = new KryoCodec<>(KRYO, false);
		ZMQ = new ZeroMQ<Data>(Timers.global()).codec(KRYO_CODEC);
	}

	@AfterClass
	public static void classCleanup() {
		ZMQ.shutdown();
		//ENV.shutdown();
	}

	@Override
	public void setup() {
		super.setup();
		latch = new CountDownLatch(1);
	}

	@Test(timeout = 60000)
	public void clientSendsDataToServerUsingKryo() throws InterruptedException {
		assertTcpClientServerExchangedData(ZeroMQTcpServer.class,
		  ZeroMQTcpClient.class,
		  KRYO_CODEC,
		  data,
		  d -> d.equals(data));
	}

	@Test(timeout = 60000)
	public void clientSendsDataToServerUsingJson() throws InterruptedException {
		assertTcpClientServerExchangedData(ZeroMQTcpServer.class,
		  ZeroMQTcpClient.class,
		  new JacksonJsonCodec<>(),
		  data,
		  d -> d.equals(data));
	}

	@Test(timeout = 60000)
	public void clientSendsDataToServerUsingBuffers() throws InterruptedException {
		assertTcpClientServerExchangedData(ZeroMQTcpServer.class,
		  ZeroMQTcpClient.class,
		  Buffer.wrap("Hello World!"));
	}

	@Test//(timeout = 60000)
	public void zmqRequestReply() throws InterruptedException {
		ZMQ.reply("tcp://*:" + getPort())
		  .onSuccess(ch -> ch.writeWith(ch.observe(d -> latch.countDown())).consume());

		ZMQ.request("tcp://127.0.0.1:" + getPort())
		  .onSuccess(ch -> {
				ch.consume(d -> latch.countDown());
				ch.writeWith(Streams.just(data)).consume();
			}
		  );

		assertTrue("REQ/REP socket exchanged data", latch.await(5, TimeUnit.SECONDS));
	}

	@Test(timeout = 60000)
	public void zmqPushPull() throws InterruptedException {
		ZMQ.pull("tcp://*:" + getPort())
		  .onSuccess(ch -> latch.countDown());

		ZMQ.push("tcp://127.0.0.1:" + getPort())
		  .onSuccess(ch -> ch.writeWith(Streams.just(data)).consume());

		assertTrue("PULL socket received data", latch.await(1, TimeUnit.SECONDS));
	}

	@Test(timeout = 60000)
	public void zmqRouterDealer() throws InterruptedException {
		ZMQ.router("tcp://*:" + getPort())
		  .onSuccess(ch -> latch.countDown());

		ZMQ.dealer("tcp://127.0.0.1:" + getPort())
		  .onSuccess(ch ->
			  ch.writeWith(Streams.just(data).log("zmqp")).consume()
		  );

		assertTrue("ROUTER socket received data", latch.await(50, TimeUnit.SECONDS));
	}

	@Test(timeout = 60000)
	public void zmqInprocRouterDealer() throws InterruptedException {
		ZMQ.router("inproc://queue" + getPort())
		  .onSuccess(ch -> {
			  ch.consume(data -> {
				  latch.countDown();
			  });
		  });

		// we have to sleep a couple cycles to let ZeroMQ get set up on inproc
		Thread.sleep(500);

		ZMQ.dealer("inproc://queue" + getPort())
		  .onSuccess(ch -> ch.writeWith(Streams.just(data)).consume());

		assertTrue("ROUTER socket received inproc data", latch.await(1, TimeUnit.SECONDS));
	}

}
