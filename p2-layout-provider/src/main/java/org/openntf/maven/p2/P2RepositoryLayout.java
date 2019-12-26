/**
 * Copyright © 2019 Jesse Gallagher
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
package org.openntf.maven.p2;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarInputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.spi.connector.layout.RepositoryLayout;
import org.eclipse.aether.spi.log.Logger;
import org.osgi.framework.Version;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.ibm.commons.util.PathUtil;
import com.ibm.commons.xml.DOMUtil;
import com.ibm.commons.xml.Format;
import com.ibm.commons.xml.XMLException;

public class P2RepositoryLayout implements RepositoryLayout, Closeable {
	private final Logger log;

	private String id;
	private String url;
	private Path metadataScratch;
	private Document artifactsJar;
	private boolean checkedArtifacts;
	
	private Map<Artifact, Path> poms = new HashMap<>();
	private Map<String, Path> metadatas = new HashMap<>();
	private Map<Artifact, List<Checksum>> checksums = new HashMap<>();

	public P2RepositoryLayout(String id, String url, Logger log) throws IOException {
		this.id = id;
		this.url = url;
		this.log = log;
		this.metadataScratch = Files.createTempDirectory(getClass().getName() + "-metadata"); //$NON-NLS-1$
	}

	@Override
	public URI getLocation(Artifact artifact, boolean upload) {
		if(log.isDebugEnabled()) {
			log.debug("getLocation for artifact " + artifact);
		}
		
		switch(String.valueOf(artifact.getExtension())) {
		case "pom": { //$NON-NLS-1$
			return getPom(artifact).toUri();
		}
		case "jar": { //$NON-NLS-1$
			String jar = artifact.getArtifactId() + "_" + artifact.getVersion() + ".jar"; //$NON-NLS-1$ //$NON-NLS-2$
			String url = PathUtil.concat(this.url, "plugins/" + jar, '/'); //$NON-NLS-1$
			return URI.create(url);
		}
		}
		
		return null;
	}

	@Override
	public URI getLocation(Metadata metadata, boolean upload) {
		if(log.isDebugEnabled()) {
			log.debug("getLocation for metadata " + metadata);
		}
		
		return getMetadata(metadata).toUri();
	}

	@Override
	public List<Checksum> getChecksums(Artifact artifact, boolean upload, URI location) {
		// This implementation doesn't currently work, as it runs afoul of Checksum's constructor's requirement
		//   that the URI must be relative. That will mean that we'll have to pre-download the Jar, which is
		//   a bit of a shame
//		return this.checksums.computeIfAbsent(artifact, key -> {
//			if("pom".equals(key.getExtension())) { //$NON-NLS-1$
//				return Collections.emptyList();
//			}
//			
//			Document artifactsXml = getArtifactsXml();
//			if(artifactsXml != null) {
//				try {
//					Element artifactNode = findArtifactNode(artifactsXml, artifact.getArtifactId(), artifact.getVersion());
//					if(artifactNode != null) {
//						return Stream.of(DOMUtil.nodes(artifactNode, "properties/property")) //$NON-NLS-1$
//							.map(Element.class::cast)
//							.filter(property -> String.valueOf(property.getAttribute("name")).startsWith("download.checksum.")) //$NON-NLS-1$ //$NON-NLS-2$
//							.map(property -> {
//								try {
//									String algorithm = property.getAttribute("name").substring("download.checksum.".length()); //$NON-NLS-1$ //$NON-NLS-2$
//									String value = property.getAttribute("value"); //$NON-NLS-1$
//									Path checksumFile = metadataScratch.resolve(key.getArtifactId() + "-" + key.getVersion() + "-" + key.getClassifier() + "." + algorithm); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
//									Files.write(checksumFile, value.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
//									return new Checksum(algorithm, checksumFile.toUri());
//								} catch(IOException e) {
//									throw new RuntimeException(e);
//								}
//							})
//							.collect(Collectors.toList());
//					}
//				} catch(XMLException e) {
//					throw new RuntimeException(e);
//				}
//			}
//			return Collections.emptyList();
//		});
		return Collections.emptyList();
	}

	@Override
	public List<Checksum> getChecksums(Metadata metadata, boolean upload, URI location) {
		return Collections.emptyList();
	}

	@Override
	public void close() {
		for(Path path : poms.values()) {
			try {
				Files.deleteIfExists(path);
			} catch (IOException e) {
				// Ignore
			}
		}
		for(Path path : metadatas.values()) {
			try {
				Files.deleteIfExists(path);
			} catch (IOException e) {
				// Ignore
			}
		}
		for(List<Checksum> cks : checksums.values()) {
			for(Checksum checksum : cks) {
				try {
					Path path = Paths.get(checksum.getLocation());
					Files.deleteIfExists(path);
				} catch (IOException e) {
					// Ignore
				}
			}
		}
		try {
			Files.deleteIfExists(this.metadataScratch);
		} catch (IOException e) {
			// Ignore
		}
	}
	
	// *******************************************************************************
	// * Internal implementation methods
	// *******************************************************************************
	
	private Path getPom(Artifact artifact) {
		return this.poms.computeIfAbsent(artifact, key -> {
			Path pomOut = this.metadataScratch.resolve(artifact.getArtifactId() + "-" + artifact.getVersion() + ".pom"); //$NON-NLS-1$ //$NON-NLS-2$
			if(!Files.exists(pomOut) && this.id.equals(artifact.getGroupId())) {
				// Check if it exists in the artifacts.jar
				Document artifactsXml = getArtifactsXml();
				if(artifactsXml != null) {
					try {
						Element artifactNode = findArtifactNode(artifactsXml, artifact.getArtifactId(), artifact.getVersion());
						if(artifactNode != null) {
							// Then it's safe to make a file for it
							Document xml = DOMUtil.createDocument();
							Element project = DOMUtil.createElement(xml, "project"); //$NON-NLS-1$
							DOMUtil.createElement(xml, project, "modelVersion").setTextContent("4.0.0"); //$NON-NLS-1$ //$NON-NLS-2$
							DOMUtil.createElement(xml, project, "groupId").setTextContent(artifact.getGroupId()); //$NON-NLS-1$
							DOMUtil.createElement(xml, project, "artifactId").setTextContent(artifact.getArtifactId()); //$NON-NLS-1$
							DOMUtil.createElement(xml, project, "name").setTextContent(artifact.getArtifactId()); //$NON-NLS-1$
							DOMUtil.createElement(xml, project, "version").setTextContent(artifact.getVersion()); //$NON-NLS-1$
							try(OutputStream os = Files.newOutputStream(pomOut, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
								DOMUtil.serialize(os, xml, Format.defaultFormat);
							}
						}
					} catch(XMLException | IOException e) {
						throw new RuntimeException(e);
					}
				}
			}
			return pomOut;
		});
	}
	
	private Path getMetadata(Metadata metadata) {
		return this.metadatas.computeIfAbsent(metadata.getArtifactId(), key -> {
			Path metadataOut = this.metadataScratch.resolve("maven-metadata-" + metadata.getArtifactId() + ".xml"); //$NON-NLS-1$ //$NON-NLS-2$
			if(!Files.exists(metadataOut) && this.id.equals(metadata.getGroupId())) {
				Document artifactsXml = getArtifactsXml();
				if(artifactsXml != null) {
					// Create a temporary maven-metadata.xml
					try {
						List<Element> artifactNodes = findArtifactNodes(artifactsXml, metadata.getArtifactId());
						if(!artifactNodes.isEmpty()) {
							
							Document result = DOMUtil.createDocument();
							Element metadataNode = DOMUtil.createElement(result, "metadata"); //$NON-NLS-1$
							DOMUtil.createElement(result, metadataNode, "groupId").setTextContent(this.id); //$NON-NLS-1$
							DOMUtil.createElement(result, metadataNode, "artifactId").setTextContent(metadata.getArtifactId()); //$NON-NLS-1$
							Element versioning = DOMUtil.createElement(result, metadataNode, "versioning"); //$NON-NLS-1$
							Element versions = DOMUtil.createElement(result, versioning, "versions"); //$NON-NLS-1$

							for(Element artifactNode : artifactNodes) {
								String version = artifactNode.getAttribute("version"); //$NON-NLS-1$
								DOMUtil.createElement(result, versions, "version").setTextContent(version); //$NON-NLS-1$
							}
							
							// Just assume that the last one by string comparison is the newest for now
							String latestVersion = artifactNodes.stream()
								.map(el -> el.getAttribute("version")) //$NON-NLS-1$
								.map(Version::new)
								.sorted(Comparator.reverseOrder())
								.map(String::valueOf)
								.findFirst()
								.orElse(null);
							DOMUtil.createElement(result, versioning, "latest").setTextContent(latestVersion); //$NON-NLS-1$
							DOMUtil.createElement(result, versioning, "release").setTextContent(latestVersion); //$NON-NLS-1$
							
							try(OutputStream os = Files.newOutputStream(metadataOut, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
								DOMUtil.serialize(os, result, Format.defaultFormat);
							}
						}
					} catch(IOException | XMLException e) {
						throw new RuntimeException(e);
					}
				}
			}
			return metadataOut;
		});
	}
	
	private List<Element> findArtifactNodes(Document xml, String artifactId) throws XMLException {
		Object[] nodes = DOMUtil.nodes(xml, "/repository/artifacts/artifact[@id=\"" + artifactId + "\"]"); //$NON-NLS-1$ //$NON-NLS-2$
		// Filter out any with a "processing" child
		return Stream.of(nodes)
			.map(Element.class::cast)
			.filter(el -> {
				try {
					return DOMUtil.nodes(el, "processing").length == 0; //$NON-NLS-1$
				} catch (XMLException e) {
					throw new RuntimeException(e);
				}
			})
			.collect(Collectors.toList());
	}
	
	private Element findArtifactNode(Document xml, String artifactId, String version) throws XMLException {
		List<Element> nodes = findArtifactNodes(xml, artifactId);
		// TODO filter to version
		return nodes.isEmpty() ? null : nodes.get(0);
	}
	
	private synchronized Document getArtifactsXml() {
		if(!checkedArtifacts) {
			this.checkedArtifacts = true;
			if(this.artifactsJar == null) {
				URI artifactsJar = URI.create(PathUtil.concat(this.url, "artifacts.jar", '/')); //$NON-NLS-1$
				
				try {
					Document xml;
					try(InputStream is = artifactsJar.toURL().openStream()) {
						try(JarInputStream jis = new JarInputStream(is)) {
							jis.getNextEntry();
							xml = DOMUtil.createDocument(jis);
						}
					}
					this.artifactsJar = xml;
				} catch(IOException | XMLException e) {
					throw new RuntimeException(e);
				}
			}
		}
		return this.artifactsJar;
	}

}
