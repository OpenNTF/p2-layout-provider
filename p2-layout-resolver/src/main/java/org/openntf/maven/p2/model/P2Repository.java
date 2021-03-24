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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarInputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.ibm.commons.util.PathUtil;
import com.ibm.commons.util.io.StreamUtil;
import com.ibm.commons.xml.DOMUtil;
import com.ibm.commons.xml.XMLException;

/**
 * Represents a local or remote P2 repository root.
 * 
 * @author Jesse Gallagher
 * @since 1.0.0
 */
public class P2Repository {
	private static final Map<URI, P2Repository> instances = Collections.synchronizedMap(new HashMap<>());
	
	public static P2Repository getInstance(URI uri) {
		return instances.computeIfAbsent(uri, P2Repository::new);
	}
	
	private final URI uri;
	private List<P2Bundle> bundles;

	private P2Repository(URI uri) {
		String baseUri = uri.toString();
		this.uri = URI.create(baseUri.endsWith("/") ? baseUri : (baseUri+"/")); //$NON-NLS-1$ //$NON-NLS-2$
	}
	
	/**
	 * Retrieves a list of bundles in this repository. Specifically, this includes artifacts designated
	 * as "osgi.bundle" in the repository's artifacts.xml manifest.
	 * 
	 * @return a {@link List} of {@link P2Bundle}s. Never null
	 * @throws RuntimeException if there is a problem finding the repository or parsing its artifact manifest
	 */
	public synchronized List<P2Bundle> getBundles() {
		if(this.bundles == null) {
			this.bundles = new ArrayList<>();
			
			try {
				// Check if this is a composite repository
				InputStream compositeArtifacts = findXml(this.uri, "compositeArtifacts"); //$NON-NLS-1$
				if(compositeArtifacts != null) {
					try {
						resolveCompositeChildren(compositeArtifacts, this.uri).stream()
							.map(P2Repository::getBundles)
							.forEach(this.bundles::addAll);
					} finally {
						StreamUtil.close(compositeArtifacts);
					}
				}
				
				// Check if this is a single repository
				InputStream artifactsXml = findXml(this.uri, "artifacts"); //$NON-NLS-1$
				if(artifactsXml != null) {
					try {
						collectBundles(artifactsXml, this.bundles, this.uri);
					} finally {
						StreamUtil.close(artifactsXml);
					}
				}
			} catch(IOException | XMLException | CompressorException e) {
				throw new RuntimeException(e);
			}
		}
		return this.bundles;
	}

	// *******************************************************************************
	// * Internal implementation methods
	// *******************************************************************************
	
	private static InputStream findXml(URI baseUri, String baseName) throws IOException, CompressorException {
		URI xml = URI.create(PathUtil.concat(baseUri.toString(), baseName + ".xml", '/')); //$NON-NLS-1$
		try {
			return xml.toURL().openStream();
		} catch(FileNotFoundException e) {
			// Plain XML not present
		}
		
		URI xz = URI.create(PathUtil.concat(baseUri.toString(), baseName + ".xml.xz", '/')); //$NON-NLS-1$
		try {
			InputStream is = xz.toURL().openStream();
			return CompressorStreamFactory.getSingleton().createCompressorInputStream(CompressorStreamFactory.getXz(), is);
		} catch(FileNotFoundException e) {
			// XZ-compressed XML not present
		}
		
		URI jar = URI.create(PathUtil.concat(baseUri.toString(), baseName + ".jar", '/')); //$NON-NLS-1$
		try {
			InputStream is = jar.toURL().openStream();
			JarInputStream jis = new JarInputStream(is);
			jis.getNextEntry();
			return jis;
		} catch(FileNotFoundException e) {
			// Jar-compressed XML not present
		}
		
		return null;
	}
	
	private static void collectBundles(InputStream is, List<P2Bundle> bundles, URI base) throws XMLException {
		Document artifactsXml = DOMUtil.createDocument(is);
		Stream.of(DOMUtil.nodes(artifactsXml, "/repository/artifacts/artifact[@classifier=\"osgi.bundle\"]")) //$NON-NLS-1$
			.map(Element.class::cast)
			.filter(el -> {
				try {
					return DOMUtil.nodes(el, "processing").length == 0; //$NON-NLS-1$
				} catch (XMLException e) {
					throw new RuntimeException(e);
				}
			})
			.map(el -> new P2Bundle(base, el))
			.forEach(bundles::add);
	}
	
	private static List<P2Repository> resolveCompositeChildren(InputStream is, URI baseUri) throws XMLException {
		Document compositeArtifacts = DOMUtil.createDocument(is);
		return Stream.of(DOMUtil.nodes(compositeArtifacts, "/repository/children/child")) //$NON-NLS-1$
			.map(Element.class::cast)
			.map(el -> el.getAttribute("location")) //$NON-NLS-1$
			.map(location -> baseUri.resolve(location))
			.map(P2Repository::getInstance)
			.collect(Collectors.toList());
	}
	
}
