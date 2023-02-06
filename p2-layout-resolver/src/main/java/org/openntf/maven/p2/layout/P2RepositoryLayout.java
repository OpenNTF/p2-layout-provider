/**
 * Copyright © 2019-2023 Contributors to the P2 Layout Resolver Project
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
package org.openntf.maven.p2.layout;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.MessageFormat;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.spi.connector.layout.RepositoryLayout;
import org.eclipse.aether.spi.log.Logger;
import org.eclipse.osgi.util.ManifestElement;
import org.openntf.maven.p2.Messages;
import org.openntf.maven.p2.model.P2Bundle;
import org.openntf.maven.p2.model.P2BundleManifest;
import org.openntf.maven.p2.model.P2Repository;
import org.openntf.maven.p2.util.P2Util;
import org.openntf.maven.p2.util.xml.XMLDocument;
import org.openntf.maven.p2.util.xml.XMLNode;
import org.osgi.framework.BundleException;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;
import org.xml.sax.SAXException;

public class P2RepositoryLayout implements RepositoryLayout, Closeable {
	private final Logger log;

	private String id;
	private final P2Repository p2Repo;
	private Path metadataScratch;
	
	private Map<String, Path> poms = new HashMap<>();
	private Map<String, Path> metadatas = new HashMap<>();
	private Map<Artifact, List<Checksum>> checksums = new HashMap<>();
	private Map<P2Bundle, Path> localJars = new HashMap<>();

	public P2RepositoryLayout(String id, String url, Logger log) throws IOException {
		this.id = id;
		this.log = log;
		P2Repository repo;
		try {
			repo = P2Repository.getInstance(URI.create(url), log);
			this.metadataScratch = Files.createTempDirectory(getClass().getName() + '-' + id + "-metadata"); //$NON-NLS-1$
		} catch(IllegalArgumentException e) {
			// This almost definitely means that the runtime hasn't interpolated a ${} property yet
			if(log.isWarnEnabled()) {
				log.warn(Messages.getString("P2RepositoryLayout.skippingUninterpretableUrl"), e); //$NON-NLS-1$
			}
			repo = null;
		}
		this.p2Repo = repo;
	}

	@Override
	public URI getLocation(Artifact artifact, boolean upload) {
		if(log.isDebugEnabled()) {
			log.debug(MessageFormat.format(Messages.getString("P2RepositoryLayout.getLocationArtifact"), artifact)); //$NON-NLS-1$
		}
		if(this.p2Repo == null) {
			return fakeUri();
		}
		
		switch(String.valueOf(artifact.getExtension())) {
		case "pom": { //$NON-NLS-1$
			return getPom(artifact).toUri();
		}
		case "jar": { //$NON-NLS-1$
			// Check for classifier
			switch(StringUtils.defaultString(artifact.getClassifier())) {
			case "sources": { //$NON-NLS-1$
				return findBundle(artifact.getArtifactId(), artifact.getVersion())
					.map(bundle -> bundle.getUri("source")) //$NON-NLS-1$
					.orElse(fakeUri());
			}
			case "javadoc": { //$NON-NLS-1$
				// TODO determine if there's a true standard to follow here
				return findBundle(artifact.getArtifactId(), artifact.getVersion())
						.map(bundle -> bundle.getUri("javadoc")) //$NON-NLS-1$
						.orElse(fakeUri());
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
						if(classifiedEntry == null) {
							classifiedEntry = jarFile.getEntry(uncleanClassifier(artifact.getClassifier()) + '.' + artifact.getExtension());
						}
						if(classifiedEntry != null) {
							return URI.create("jar:" + localJar.toUri().toString() + "!/" + classifiedEntry.getName()); //$NON-NLS-1$ //$NON-NLS-2$
						}
					} catch (IOException e) {
						throw new UncheckedIOException("Encountered exception reading local file " + localJar, e);
					}
				}
				return fakeUri();
			}
			}
		}
		default:
			return fakeUri();
		}
	}

	@Override
	public URI getLocation(Metadata metadata, boolean upload) {
		if(log.isDebugEnabled()) {
			log.debug(MessageFormat.format(Messages.getString("P2RepositoryLayout.getLocationMetadata"), metadata)); //$NON-NLS-1$
		}
		if(this.p2Repo == null) {
			return null;
		}
		
		return getMetadata(metadata).toUri();
	}

	@Override
	public List<Checksum> getChecksums(Artifact artifact, boolean upload, URI location) {
		if(this.p2Repo == null) {
			return null;
		}
		return this.checksums.computeIfAbsent(artifact, key -> {
			if(!"jar".equals(key.getExtension()) || StringUtils.isNotEmpty(artifact.getClassifier())) { //$NON-NLS-1$
				return Collections.emptyList();
			}
			
			P2Bundle bundle = findBundle(artifact.getArtifactId(), artifact.getVersion()).orElse(null);
			if(bundle != null) {
				return bundle.getProperties().entrySet().stream()
					.filter(entry -> String.valueOf(entry.getKey()).startsWith("download.checksum.")) //$NON-NLS-1$
					.map(property -> {
						String algorithm = property.getKey().substring("download.checksum.".length()); //$NON-NLS-1$
						String value = property.getValue();
						Path checksumFile = metadataScratch.resolve(toFileName(artifact, true) + "." + algorithm); //$NON-NLS-1$
						try {
							Files.write(checksumFile, value.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
							return new Checksum(algorithm, URI.create(checksumFile.getFileName().toString()));
						} catch(IOException e) {
							throw new UncheckedIOException("Encountered exception writing to local file " + checksumFile, e);
						}
					})
					.collect(Collectors.toList());
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
			if(this.metadataScratch != null) {
				Files.deleteIfExists(this.metadataScratch);
			}
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
				try {
					P2Bundle bundle = findBundle(artifact.getArtifactId(), artifact.getVersion()).orElse(null);
					if(bundle != null) {
						// Then it's safe to make a file for it
						XMLDocument xml = new XMLDocument();
						xml.loadString("<?xml version='1.0' encoding='UTF-8'?>\n<project xmlns='http://maven.apache.org/POM/4.0.0'/>"); //$NON-NLS-1$

						xml.appendChild(xml.createComment(MessageFormat.format(Messages.getString("P2RepositoryLayout.commentSynthesizedBy"), getClass().getName(), ZonedDateTime.now()))); //$NON-NLS-1$
						xml.appendChild(xml.createComment(MessageFormat.format(Messages.getString("P2RepositoryLayout.commentSource"), bundle.getUri(null)))); //$NON-NLS-1$
						
						XMLNode project = xml.getDocumentElement();
						project.addChildElement("modelVersion").setTextContent("4.0.0"); //$NON-NLS-1$ //$NON-NLS-2$
						project.addChildElement("groupId").setTextContent(artifact.getGroupId()); //$NON-NLS-1$
						project.addChildElement("artifactId").setTextContent(artifact.getArtifactId()); //$NON-NLS-1$
						project.addChildElement("version").setTextContent(artifact.getVersion()); //$NON-NLS-1$
						
						// Look for additional information to be gleaned from the bundle manifest
						Path jar = getLocalJar(artifact, true).orElse(null);
						if(jar != null) {
							P2BundleManifest manifest = new P2BundleManifest(jar);
							
							addBundleMetadata(project, manifest);
							addBundleDependencies(project, artifact, manifest);
						}
						
						project.setAttribute("xmlns", "http://maven.apache.org/POM/4.0.0"); //$NON-NLS-1$ //$NON-NLS-2$
						project.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance"); //$NON-NLS-1$ //$NON-NLS-2$
						project.setAttribute("xsi:schemaLocation", "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"); //$NON-NLS-1$ //$NON-NLS-2$
						
						try(Writer w = Files.newBufferedWriter(pomOut, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
							xml.getXml(null, w);
						}
					}
				} catch(IOException | SAXException | ParserConfigurationException e) {
					throw new RuntimeException("Encountered exception writing to local pom " + pomOut, e);
				}
			}
			return pomOut;
		});
	}
	
	private static void addBundleMetadata(XMLNode project, P2BundleManifest manifest) {
		XMLDocument xml = project.getOwnerDocument();
		String bundleName = manifest.get("Bundle-Name"); //$NON-NLS-1$
		if(StringUtils.isNotEmpty(bundleName)) {
			project.addChildElement("name").setTextContent(bundleName); //$NON-NLS-1$
		}
		String bundleDescription = manifest.get("Bundle-Description"); //$NON-NLS-1$
		if(StringUtils.isNotEmpty(bundleDescription)) {
			project.addChildElement("description").setTextContent(bundleDescription); //$NON-NLS-1$
		}
		String bundleLicense = manifest.get("Bundle-License"); //$NON-NLS-1$
		if(StringUtils.isNotEmpty(bundleLicense)) {
			XMLNode licenses = project.addChildElement("licenses"); //$NON-NLS-1$
			XMLNode license = licenses.addChildElement("license"); //$NON-NLS-1$
			license.addChildElement("url").setTextContent(bundleLicense); //$NON-NLS-1$
		}
		String bundleVendor = manifest.get("Bundle-Vendor"); //$NON-NLS-1$
		if(StringUtils.isNotEmpty(bundleVendor)) {
			XMLNode organization = project.addChildElement("organization"); //$NON-NLS-1$
			organization.addChildElement("name").setTextContent(bundleVendor); //$NON-NLS-1$
		}
		String bundleCopyright = manifest.get("Bundle-Copyright"); //$NON-NLS-1$
		if(StringUtils.isNotEmpty(bundleCopyright)) {
			project.appendChild(xml.createComment(MessageFormat.format(Messages.getString("P2RepositoryLayout.copyrightComment"), bundleCopyright))); //$NON-NLS-1$
		}
		String bundleDocUrl = manifest.get("Bundle-DocURL"); //$NON-NLS-1$
		if(StringUtils.isNotEmpty(bundleDocUrl)) {
			project.addChildElement("url").setTextContent(bundleDocUrl); //$NON-NLS-1$
		}
		String sourceRef = manifest.get("Eclipse-SourceReferences"); //$NON-NLS-1$
		if(StringUtils.isNotEmpty(sourceRef)) {
			// Only use the first
			sourceRef = StringUtils.split(sourceRef, ',')[0];
			XMLNode scm = project.addChildElement("scm"); //$NON-NLS-1$
			scm.addChildElement("url").setTextContent(sourceRef); //$NON-NLS-1$
		}
	}

	private void addBundleDependencies(XMLNode project, Artifact artifact, P2BundleManifest manifest) {
		XMLNode dependencies = null;
		
		String requireBundle = manifest.get("Require-Bundle"); //$NON-NLS-1$
		if(StringUtils.isNotEmpty(requireBundle)) {
			dependencies = project.addChildElement("dependencies"); //$NON-NLS-1$
			
			try {
				for(ManifestElement el : ManifestElement.parseHeader("Require-Bundle", requireBundle)) { //$NON-NLS-1$
					String bundleName = el.getValue();
					String v = el.getAttribute("bundle-version"); //$NON-NLS-1$
					VersionRange versionRange = StringUtils.isEmpty(v) ? null : new VersionRange(v);
					
					P2Bundle dep = this.p2Repo.getBundles().stream()
						.filter(bundle -> StringUtils.equals(bundleName, bundle.getId()))
						.filter(bundle -> versionRange == null || versionRange.includes(new Version(bundle.getVersion())))
						.findFirst()
						.orElse(null);
					if(dep != null) {
						XMLNode dependency = dependencies.addChildElement("dependency"); //$NON-NLS-1$
						dependency.addChildElement("groupId").setTextContent(this.id); //$NON-NLS-1$
						dependency.addChildElement("artifactId").setTextContent(dep.getId()); //$NON-NLS-1$
						dependency.addChildElement("version").setTextContent(dep.getVersion()); //$NON-NLS-1$
					}
				}
			} catch (BundleException e) {
				throw new RuntimeException("Encountered exception processing bundle manifest for " + artifact, e);
			}
		}

		Path localJar = getLocalJar(artifact, true).orElse(null);
		if(localJar != null) {
			String bundleClassPath = manifest.get("Bundle-ClassPath"); //$NON-NLS-1$
			if(StringUtils.isNotEmpty(bundleClassPath)) {
				if(dependencies == null) {
					dependencies = project.addChildElement("dependencies"); //$NON-NLS-1$
				}
				try {
					for(ManifestElement el : ManifestElement.parseHeader("Bundle-ClassPath", bundleClassPath)) { //$NON-NLS-1$
						String cpName = el.getValue();
						if(StringUtils.isEmpty(cpName) || ".".equals(cpName)) { //$NON-NLS-1$
							continue;
						}
						
						if(cpName.toLowerCase().endsWith(".jar")) { //$NON-NLS-1$
							cpName = cpName.substring(0, cpName.length()-4);
						}

						XMLNode dependency = dependencies.addChildElement("dependency"); //$NON-NLS-1$
						dependency.addChildElement("groupId").setTextContent(this.id); //$NON-NLS-1$
						dependency.addChildElement("artifactId").setTextContent(artifact.getArtifactId()); //$NON-NLS-1$
						dependency.addChildElement("version").setTextContent(artifact.getVersion()); //$NON-NLS-1$
						dependency.addChildElement("classifier").setTextContent(cleanClassifier(cpName)); //$NON-NLS-1$
					}
				} catch (BundleException e) {
					throw new RuntimeException("Encountered exception processing bundle manifest for " + artifact, e);
				}
			}
		}
	}
	
	private Path getMetadata(Metadata metadata) {
		return this.metadatas.computeIfAbsent(metadata.getArtifactId(), key -> {
			Path metadataOut = this.metadataScratch.resolve("maven-metadata-" + metadata.getArtifactId() + ".xml"); //$NON-NLS-1$ //$NON-NLS-2$
			if(!Files.exists(metadataOut) && this.id.equals(metadata.getGroupId())) {
				// Create a temporary maven-metadata.xml
				try {
					List<P2Bundle> bundles = findBundles(metadata.getArtifactId());
					if(!bundles.isEmpty()) {
						
						XMLDocument result = new XMLDocument();
						result.loadString("<?xml version='1.0' encoding='UTF-8'?>\n<metadata/>"); //$NON-NLS-1$
						XMLNode metadataNode = result.getDocumentElement();
						metadataNode.addChildElement("groupId").setTextContent(this.id); //$NON-NLS-1$
						metadataNode.addChildElement("artifactId").setTextContent(metadata.getArtifactId()); //$NON-NLS-1$
						XMLNode versioning = metadataNode.addChildElement("versioning"); //$NON-NLS-1$
						XMLNode versions = versioning.addChildElement("versions"); //$NON-NLS-1$

						for(P2Bundle bundle : bundles) {
							versions.addChildElement("version").setTextContent(bundle.getVersion()); //$NON-NLS-1$
						}
						
						// Just assume that the last one by string comparison is the newest for now
						String latestVersion = bundles.stream()
							.map(P2Bundle::getVersion)
							.map(Version::new)
							.sorted(Comparator.reverseOrder())
							.map(String::valueOf)
							.findFirst()
							.orElse(null);
						versioning.addChildElement("latest").setTextContent(latestVersion); //$NON-NLS-1$
						versioning.addChildElement("release").setTextContent(latestVersion); //$NON-NLS-1$
						
						try(Writer w = Files.newBufferedWriter(metadataOut, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
							result.getXml(null, w);
						}
					}
				} catch(Throwable e) {
					throw new RuntimeException(e);
				}
			}
			return metadataOut;
		});
	}
	
	private List<P2Bundle> findBundles(String artifactId) {
		return this.p2Repo.getBundles().stream()
			.filter(bundle -> StringUtils.equals(bundle.getId(), artifactId))
			.collect(Collectors.toList());
	}
	
	private Optional<P2Bundle> findBundle(String artifactId, String version) {
		return this.p2Repo.getBundles().stream()
			.filter(bundle -> StringUtils.equals(bundle.getId(), artifactId))
			.filter(bundle -> version == null || version.equals(bundle.getVersion()))
			.findFirst();
	}
	
	private Optional<Path> getLocalJar(Artifact artifact, boolean ignoreClassifier) {
		return findBundle(artifact.getArtifactId(), artifact.getVersion())
			.flatMap(bundle -> {
				return Optional.ofNullable(localJars.computeIfAbsent(bundle, key -> {
					String jar = toFileName(artifact, ignoreClassifier);
					
					URI uri = bundle.getUri(ignoreClassifier ? null : artifact.getClassifier());
					try {
						Optional<InputStream> optIs = P2Util.openConnection(uri);
						if(optIs.isPresent()) {
							try(InputStream is = optIs.get()) {
								Path localJar = this.metadataScratch.resolve(jar);
								Files.copy(is, localJar, StandardCopyOption.REPLACE_EXISTING);
								return localJar;
							}
						} else {
							return null;
						}
					} catch(IOException e) {
						if(log.isWarnEnabled()) {
							log.warn("Encountered exception reading " + uri, e);
							throw new RuntimeException(e);
						}
						return null;
					}
					
				}));
			});
	}
	
	private String toFileName(Artifact artifact, boolean ignoreClassifier) {
		StringBuilder builder = new StringBuilder();
		builder.append(artifact.getArtifactId());
		if(!ignoreClassifier && StringUtils.isNotEmpty(artifact.getClassifier())) {
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
	
	private static String cleanClassifier(String classifier) {
		if(classifier == null) {
			return null;
		}
		return classifier.replace('/', '$');
	}
	private static String uncleanClassifier(String classifier) {
		if(classifier == null) {
			return null;
		}
		return classifier.replace('$', '/');
	}
}
