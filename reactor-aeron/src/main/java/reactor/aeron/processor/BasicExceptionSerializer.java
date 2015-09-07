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
package reactor.aeron.processor;

import java.nio.charset.Charset;

/**
 * @author Anatoly Kadyshev
 */
class BasicExceptionSerializer implements Serializer<Throwable> {

	private final Charset UTF_8_CHARSET = Charset.forName("UTF-8");

	@Override
	public byte[] serialize(Throwable t) {
		String errorMessage = t.getMessage();
		if (errorMessage == null) {
			errorMessage = "";
		}
		return errorMessage.getBytes(UTF_8_CHARSET);
	}

	@Override
	public Throwable deserialize(byte[] data) {
		String errorMessage = new String(data, UTF_8_CHARSET);
		return new Exception(errorMessage);
	}

}
