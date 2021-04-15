/**
 * Copyright © 2019-2021 Jesse Gallagher
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
package org.openntf.maven.p2.model;

import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import org.openntf.maven.p2.util.xml.XMLNode;

import com.ibm.commons.util.StringUtil;

/**
 * Represents a bundle entry inside of a P2 repository, based on the containing
 * artifacts.xml.
 * 
 * @author Jesse Gallagher
 * @since 1.0.0
 */
public class P2Bundle {
	private final URI baseUri;
	private final String id;
	private final String version;
	private final Map<String, String> properties;
	
	public P2Bundle(URI baseUri, XMLNode element) {
		this.baseUri = baseUri;
		this.id = element.getAttribute("id"); //$NON-NLS-1$
		this.version = element.getAttribute("version"); //$NON-NLS-1$
		this.properties = element.getElementsByTagName("property") //$NON-NLS-1$
			.stream()
			.collect(Collectors.toMap(
				prop -> prop.getAttribute("name"), //$NON-NLS-1$
				prop -> prop.getAttribute("value") //$NON-NLS-1$
			));
	}

	/**
	 * @return the symbolic name of the bundle
	 */
	public String getId() {
		return id;
	}

	/**
	 * @return the version of the bundle
	 */
	public String getVersion() {
		return version;
	}
	
	/**
	 * @return an unmodifiable view {@link Map} of any properties associated with this bundle
	 */
	public Map<String, String> getProperties() {
		return Collections.unmodifiableMap(properties);
	}
	
	public URI getUri(String classifier) {
		StringBuilder result = new StringBuilder();
		result.append(this.baseUri.toString());
		result.append("plugins/"); //$NON-NLS-1$
		result.append(this.id);
		if("sources".equals(classifier)) { //$NON-NLS-1$
			result.append(".source"); //$NON-NLS-1$
		} else if(StringUtil.isNotEmpty(classifier)) {
			result.append('.');
			result.append(classifier);
		}
		result.append('_');
		result.append(version);
		result.append(".jar"); //$NON-NLS-1$
		return URI.create(result.toString());
	}
}
