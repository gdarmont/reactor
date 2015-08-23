package reactor.io.codec.compress

import reactor.io.buffer.Buffer
import spock.lang.Specification

import static reactor.io.codec.StandardCodecs.PASS_THROUGH_CODEC

/**
 * @author Jon Brisbin
 */
class CompressionCodecsSpec extends Specification {

	GzipCodec<Buffer, Buffer> gzip
	SnappyCodec<Buffer, Buffer> snappy

	def setup() {
		gzip = new GzipCodec<Buffer, Buffer>(PASS_THROUGH_CODEC)
		snappy = new SnappyCodec<Buffer, Buffer>(PASS_THROUGH_CODEC)
	}

	def "compression codecs preserve integrity of data"() {

		given:
			Buffer buffer

		when: "an object is encoded with GZIP"
			buffer = gzip.apply(Buffer.wrap("Hello World!"))

		then: "the Buffer was encoded and compressed"
			buffer.remaining() == 32

		when: "an object is decoded with GZIP"
			String hw = gzip.decoder(null).apply(buffer).asString()

		then: "the Buffer was decoded and uncompressed"
			hw == "Hello World!"

		when: "an object is encoded with Snappy"
			buffer = snappy.apply(Buffer.wrap("Hello World!"))

		then: "the Buffer was encoded and compressed"
			buffer.remaining() == 34

		when: "an object is decoded with Snappy"
			hw = snappy.decoder(null).apply(buffer).asString()

		then: "the Buffer was decoded and uncompressed"
			hw == "Hello World!"

	}

}
