package reactor.io.codec.json

import com.fasterxml.jackson.databind.ObjectMapper
import reactor.io.buffer.Buffer
import spock.lang.Specification

/**
 * @author Jon Brisbin
 */
class JacksonJsonCodecSpec extends Specification {

	ObjectMapper mapper

	def setup() {
		mapper = new ObjectMapper()
	}

	def "serializes and deserializes objects properly"() {

		given: "a Codec and a Buffer"
			def codec = new JacksonJsonCodec<Person, Person>(mapper)
			Buffer buffer

		when: "an object is serialized"
			buffer = codec.apply(new Person(name: "John Doe"))

		then: "the object was serialized"
			buffer.remaining() == 76

		when: "an object is deserialized"
			Person p = codec.decoder(null).apply(buffer)

		then: "the object was deserialized"
			p.name == "John Doe"

	}

	static class Person {
		String name
	}

}
