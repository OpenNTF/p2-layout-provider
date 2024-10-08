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
package org.openntf.maven.p2.util;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

public enum P2Util {
	;

	public static Optional<InputStream> openConnection(URI uri) throws IOException {
		try {
			URLConnection conn = uri.toURL().openConnection();
			if(conn instanceof HttpURLConnection) {
				int status = ((HttpURLConnection)conn).getResponseCode();
				switch(status) {
				case HttpURLConnection.HTTP_MOVED_PERM:
				case HttpURLConnection.HTTP_MOVED_TEMP:
				case HttpURLConnection.HTTP_SEE_OTHER:
				case 307:
				case 308:
					// Try handling a redirect
					String location = ((HttpURLConnection)conn).getHeaderField("Location"); //$NON-NLS-1$
					if(StringUtils.isNotEmpty(location)) {
						return openConnection(uri.resolve(location));
					} else {
						return Optional.empty();
					}
				case 200:
					// Good
					return Optional.of(conn.getInputStream());
				default:
					// Assume it's an other error
					return Optional.empty();
				}
			} else {
				// For file://, etc., just try opening the connection
				return Optional.of(conn.getInputStream());
			}
		} catch(FileNotFoundException e) {
			return Optional.empty();
		}
	}
	
	public static String concatPath(char sep, String path1, String path2) {
    	if(path1 == null || path1.isEmpty()) {
    		return path2;
    	}
    	if(path2 == null || path2.isEmpty()) {
    		return path1;
    	}
    	StringBuilder b = new StringBuilder();
    	if(path1.charAt(path1.length()-1)==sep) {
    		b.append(path1,0,path1.length()-1);
    	} else {
    		b.append(path1);
    	}
    	b.append(sep);
    	if(path2.charAt(0)==sep) {
    		b.append(path2,1,path2.length());
    	} else {
    		b.append(path2);
    	}
    	return b.toString();
    }
	
	public static String concatPath(final char delim, final String... parts) {
		if (parts == null || parts.length == 0) {
			return ""; //$NON-NLS-1$
		}
		String path = parts[0];
		for (int i = 1; i < parts.length; i++) {
			path = concatPath(delim, path, parts[i]);
		}
		return path;
	}
}
