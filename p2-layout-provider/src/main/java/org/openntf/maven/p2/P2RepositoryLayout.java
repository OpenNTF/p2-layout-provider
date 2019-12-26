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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarInputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.spi.connector.layout.RepositoryLayout;
import org.eclipse.aether.spi.log.Logger;
import org.osgi.framework.Version;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.ibm.commons.util.PathUtil;
import com.ibm.commons.util.StringUtil;
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
	
	private Map<String, Path> poms = new HashMap<>();
	private Map<String, Path> metadatas = new HashMap<>();
	private Map<Artifact, List<Checksum>> checksums = new HashMap<>();
	private Map<String, Path> localJars = new HashMap<>();

	public P2RepositoryLayout(String id, String url, Logger log) throws IOException {
		this.id = id;
		this.url = url;
		this.log = log;
		this.metadataScratch = Files.createTempDirectory(getClass().getName() + '-' + id + "-metadata"); //$NON-NLS-1$
	}

	@Override
	public URI getLocation(Artifact artifact, boolean upload) {
		if(log.isDebugEnabled()) {
			log.debug(MessageFormat.format(Messages.getString("P2RepositoryLayout.getLocationArtifact"), artifact)); //$NON-NLS-1$
		}
		
		switch(String.valueOf(artifact.getExtension())) {
		case "pom": { //$NON-NLS-1$
			return getPom(artifact).toUri();
		}
		case "jar": { //$NON-NLS-1$
			// Check for classifier
			switch(StringUtil.toString(artifact.getClassifier())) {
			case "sources": { //$NON-NLS-1$
				String jar = artifact.getArtifactId() + ".source_" + artifact.getVersion() + ".jar"; //$NON-NLS-1$ //$NON-NLS-2$
				String url = PathUtil.concat(this.url, "plugins/" + jar, '/'); //$NON-NLS-1$
				return URI.create(url);
			}
			case "javadoc": { //$NON-NLS-1$
				// TODO determine if there's a true standard to follow here
				String jar = artifact.getArtifactId() + ".javadoc_" + artifact.getVersion() + ".jar"; //$NON-NLS-1$ //$NON-NLS-2$
				String url = PathUtil.concat(this.url, "plugins/" + jar, '/'); //$NON-NLS-1$
				return URI.create(url);
			}
			case "": { //$NON-NLS-1$
				// Then it's just the jar
				return getLocalJar(artifact, false)
					.map(Path::toUri)
					.orElse(fakeUri());
			}
			default: {
				Path localJar = getLocalJar(artifact, true).orElse(null);
				if(localJar != null) {
					try(ZipFile jarFile = new ZipFile(localJar.toFile())) {
						ZipEntry classifiedEntry = jarFile.getEntry(artifact.getClassifier() + '.' + artifact.getExtension());
						if(classifiedEntry != null) {
							return URI.create("jar:" + localJar.toUri().toString() + "!/" + classifiedEntry.getName()); //$NON-NLS-1$ //$NON-NLS-2$
						}
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
					String jar = artifact.getArtifactId() + "." + artifact.getClassifier() + "_" + artifact.getVersion() + ".jar"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					String url = PathUtil.concat(this.url, "plugins/" + jar, '/'); //$NON-NLS-1$
					return URI.create(url);
				}
				return fakeUri();
			}
			}
		}
		}
		
		return null;
	}

	@Override
	public URI getLocation(Metadata metadata, boolean upload) {
		if(log.isDebugEnabled()) {
			log.debug(MessageFormat.format(Messages.getString("P2RepositoryLayout.getLocationMetadata"), metadata)); //$NON-NLS-1$
		}
		
		return getMetadata(metadata).toUri();
	}

	@Override
	public List<Checksum> getChecksums(Artifact artifact, boolean upload, URI location) {
		return this.checksums.computeIfAbsent(artifact, key -> {
			if("pom".equals(key.getExtension()) || StringUtil.isNotEmpty(artifact.getClassifier())) { //$NON-NLS-1$
				return Collections.emptyList();
			}
			
			Document artifactsXml = getArtifactsXml();
			if(artifactsXml != null) {
				try {
					Element artifactNode = findArtifactNode(artifactsXml, artifact.getArtifactId(), artifact.getVersion());
					if(artifactNode != null) {
						return Stream.of(DOMUtil.nodes(artifactNode, "properties/property")) //$NON-NLS-1$
							.map(Element.class::cast)
							.filter(property -> String.valueOf(property.getAttribute("name")).startsWith("download.checksum.")) //$NON-NLS-1$ //$NON-NLS-2$
							.map(property -> {
								try {
									String algorithm = property.getAttribute("name").substring("download.checksum.".length()); //$NON-NLS-1$ //$NON-NLS-2$
									String value = property.getAttribute("value"); //$NON-NLS-1$
									Path checksumFile = metadataScratch.resolve(toFileName(artifact, true) + "." + algorithm); //$NON-NLS-1$
									Files.write(checksumFile, value.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
									return new Checksum(algorithm, URI.create(checksumFile.getFileName().toString()));
								} catch(IOException e) {
									throw new RuntimeException(e);
								}
							})
							.collect(Collectors.toList());
					}
				} catch(XMLException e) {
					throw new RuntimeException(e);
				}
			}
			return Collections.emptyList();
		});
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
		for(Path path : localJars.values()) {
			if(path != null) {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					// Ignore
				}
			}
		}
		for(List<Checksum> cks : checksums.values()) {
			for(Checksum checksum : cks) {
				try {
					Path path = this.metadataScratch.resolve(checksum.getLocation().toString());
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
	
	private URI fakeUri() {
		return this.metadataScratch.resolve(Long.toString(System.nanoTime())).toUri();
	}
	
	private Path getPom(Artifact artifact) {
		return this.poms.computeIfAbsent(artifact.getArtifactId() + artifact.getVersion(), key -> {
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
		Object[] nodes = DOMUtil.nodes(xml, "/repository/artifacts/artifact[@classifier=\"osgi.bundle\"][@id=\"" + artifactId + "\"]"); //$NON-NLS-1$ //$NON-NLS-2$
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
		return nodes.stream()
			.filter(node -> version == null || version.equals(node.getAttribute("version"))) //$NON-NLS-1$
			.findFirst()
			.orElse(null);
	}
	
	private Optional<Path> getLocalJar(Artifact artifact, boolean ignoreClassifier) {
		String jar = toFileName(artifact, ignoreClassifier);
		return Optional.ofNullable(localJars.computeIfAbsent(jar, key -> {
			String url = PathUtil.concat(this.url, "plugins/" + key, '/'); //$NON-NLS-1$
			URI uri = URI.create(url);
			try(InputStream is = uri.toURL().openStream()) {
				Path localJar = this.metadataScratch.resolve(jar);
				Files.copy(is, localJar, StandardCopyOption.REPLACE_EXISTING);
				return localJar;
			} catch(FileNotFoundException e) {
				// 404
				return null;
			} catch(IOException e) {
				throw new RuntimeException(e);
			}
		}));
	}
	
	private String toFileName(Artifact artifact, boolean ignoreClassifier) {
		StringBuilder builder = new StringBuilder();
		builder.append(artifact.getArtifactId());
		if(!ignoreClassifier && StringUtil.isNotEmpty(artifact.getClassifier())) {
			builder.append("."); //$NON-NLS-1$
			if("sources".equals(artifact.getClassifier())) { //$NON-NLS-1$
				builder.append("source"); //$NON-NLS-1$
			} else {
				builder.append(artifact.getClassifier());
			}
		}
		builder.append('_');
		builder.append(artifact.getVersion());
		builder.append('.');
		builder.append(artifact.getExtension());
		return builder.toString();
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
