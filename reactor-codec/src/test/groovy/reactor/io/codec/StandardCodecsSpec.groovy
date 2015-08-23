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

package reactor.io.codec

import reactor.fn.Consumer
import reactor.io.buffer.Buffer
import spock.lang.Specification

/**
 * Tests to cover the basic, built-in Codecs.
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
class StandardCodecsSpec extends Specification {

	def "StringCodec can encode and decode to Strings"() {
		given: "String data"
			def codec = new StringCodec()
			def data = Buffer.wrap("Hello World!")
			def hello = ""

		when: "the Buffer is decoded"
			hello = codec.decoder(null).apply(data)

		then: "the String was decoded"
			hello == "Hello World!"

		when: "the String is encoded"
			data = codec.apply(hello)

		then: "the String was encoded"
			data instanceof Buffer
			data.asString() == "Hello World!"
	}

	def "DelimitedCodec can encode and decode delimited lines"() {
		given: "delimited data"
			def codec = new DelimitedCodec<String, String>(false, StandardCodecs.STRING_CODEC)
			def data = Buffer.wrap("Hello World!\nHello World!\nHello World!\n")
			def hellos = []

		when: "data is decoded"
			def decoder = codec.decoder({ String s ->
				hellos << s
			} as Consumer<String>)
			decoder.apply(data)

		then: "data was decoded"
			hellos == ["Hello World!\n", "Hello World!\n", "Hello World!\n"]

		when: "data is encoded"
			def encoder = codec
			def hw1 = encoder.apply("Hello World!")
			def hw2 = encoder.apply("Hello World!")
			def hw3 = encoder.apply("Hello World!")
			def buff = new Buffer().append(hw1, hw2, hw3).flip()

		then: "data was encoded"
			buff.asString() == data.flip().asString()
	}

	def "Once decoding has completed, the buffer's position and limit are at the end of the buffer"() {
		given: "A decoder and a buffer of delimited data"
			def codec = new DelimitedCodec<String, String>(false, StandardCodecs.STRING_CODEC)
			def string = 'Hello World!\nHello World!\nHello World!\n'
			def data = Buffer.wrap(string)

		when: "data has been decoded"
			def decoder = codec.decoder({} as Consumer<String>)
			decoder.apply(data)

		then: "the buffer's take and position are at the end of the buffer"
			data.limit() == string.length()
			data.position() == string.length()
	}

	def "Once delimiter stripping decoding has completed, the buffer's position and limit are at the end of the buffer"() {
		given: "A delimiter stripping decoder and a buffer of delimited data"
			def codec = new DelimitedCodec<String, String>(true, StandardCodecs.STRING_CODEC)
			def string = 'Hello World!\nHello World!\nHello World!\n'
			def data = Buffer.wrap(string)

		when: "data has been decoded"
			def res = ""
			def decoder = codec.decoder({ res += it } as Consumer<String>)
			decoder.apply(data)

		then: "the buffer's take and position are at the end of the buffer"
			res.length() == string.length() - 3
	}

	def "LengthFieldCodec can encode and decode length-prefixed items"() {
		given: "length-prefixed data"
			def codec = new LengthFieldCodec<String, String>(StandardCodecs.STRING_CODEC)
			def data = new Buffer().append((int) 12).append("Hello World!").flip()

		when: "the data is decoded"
			def len = data.readInt()
			def hello = data.asString()

		then: "the data was decoded"
			len == 12
			hello == "Hello World!"

		when: "the data is encoded"
			data = codec.apply(hello)

		then: "the data was encoded"
			data.readInt() == 12
			data.asString() == "Hello World!"
	}
}
