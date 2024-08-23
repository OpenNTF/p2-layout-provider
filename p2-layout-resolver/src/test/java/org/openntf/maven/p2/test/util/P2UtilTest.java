/*
 * Copyright © 2019-2024 Contributors to the P2 Layout Resolver Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openntf.maven.p2.test.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.openntf.maven.p2.util.P2Util;

@SuppressWarnings("nls")
public class P2UtilTest {
	public static class PathProvider implements ArgumentsProvider {
		@Override
		public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
			return Stream.of(
				Arguments.of("foo", new String[] { "foo" }),
				Arguments.of("foo/bar", new String[] { "foo", "bar" }),
				Arguments.of("/foo/bar", new String[] { "/foo", "bar" }),
				Arguments.of("/foo/bar/", new String[] { "/", "foo", "bar", "/" }),
				Arguments.of("/foo/bar/", new String[] { "/", "foo", "bar/" }),
				Arguments.of("/foo/bar/", new String[] { "/", "/foo", "bar", "/" })
			);
		}
	}
	
	@ParameterizedTest
	@ArgumentsSource(PathProvider.class)
	public void testPathUtil(String expected, String[] parts) {
		assertEquals(expected, P2Util.concatPath('/', parts));
	}
}
