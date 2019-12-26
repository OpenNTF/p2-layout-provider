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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;

import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.ArtifactDownload;
import org.eclipse.aether.spi.connector.ArtifactUpload;
import org.eclipse.aether.spi.connector.MetadataDownload;
import org.eclipse.aether.spi.connector.MetadataUpload;
import org.eclipse.aether.spi.connector.RepositoryConnector;
import org.eclipse.aether.spi.connector.layout.RepositoryLayout.Checksum;
import org.eclipse.aether.spi.log.Logger;
import org.eclipse.aether.transfer.ChecksumFailureException;

import com.ibm.commons.util.StringUtil;

public class P2RepositoryConnector implements RepositoryConnector {
	private final Logger log;
	
	private final RemoteRepository repository;
	private final P2RepositoryLayout layout;
	private boolean closed;
	
	public P2RepositoryConnector(RepositorySystemSession session, RemoteRepository repository, Logger logger) {
		this.repository = repository;
		this.log = logger;
		try {
			// TODO support auth
			this.layout = new P2RepositoryLayout(repository.getId(), repository.getUrl(), log);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void get(Collection<? extends ArtifactDownload> artifactDownloads, Collection<? extends MetadataDownload> metadataDownloads) {
		checkClosed();
		
		if(log.isDebugEnabled()) {
			log.debug("Issuing get command in repo " + this.repository);
			log.debug("downloads are " + artifactDownloads);
			log.debug("metadata is " + metadataDownloads);
		}
		// TODO see if this should be routed through the session and/or multithreaded
		if(artifactDownloads != null) {
			for(ArtifactDownload download : artifactDownloads) {
				Path dest = download.getFile().toPath();
				URI sourceUri = layout.getLocation(download.getArtifact(), false);
				download(sourceUri, dest);
				
				// TODO verify using downloaded checksums
				for(Checksum checksum : layout.getChecksums(download.getArtifact(), false, sourceUri)) {
					String ext = checksum.getAlgorithm().replace("-", ""); //$NON-NLS-1$ //$NON-NLS-2$
					Path checksumPath = dest.getParent().resolve(dest.getFileName().toString()+"."+ext); //$NON-NLS-1$
					download(sourceUri.resolve(checksum.getLocation()), checksumPath);
					
					try {
						verifyChecksum(dest, checksumPath, checksum.getAlgorithm());
					} catch (ChecksumFailureException e) {
						throw new RuntimeException(e);
					}
				}
			}
		}
		if(metadataDownloads != null) {
			for(MetadataDownload download : metadataDownloads) {
				Path dest = download.getFile().toPath();
				URI sourceUri = layout.getLocation(download.getMetadata(), false);
				download(sourceUri, dest);
			}
		}
	}

	@Override
	public void put(Collection<? extends ArtifactUpload> artifactUploads,
			Collection<? extends MetadataUpload> metadataUploads) {
		checkClosed();
		
		// Not supported
	}

	@Override
	public void close() {
		this.layout.close();
		this.closed = true;
	}
	
	// *******************************************************************************
	// * Internal implementation methods
	// *******************************************************************************

	private void checkClosed() {
		if(this.closed) {
			throw new IllegalStateException("Connector is closed");
		}
	}
	
	private void download(URI source, Path dest) {
		try(InputStream is = source.toURL().openStream()) {
			Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
		} catch(FileNotFoundException e) {
			// Ignore
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	private void verifyChecksum(Path artifactPath, Path checksumPath, String algorithm) throws ChecksumFailureException {
		try {
			String checksum = new String(Files.readAllBytes(checksumPath));
			DigestUtils digest = new DigestUtils(algorithm);
			String fileChecksum = digest.digestAsHex(artifactPath.toFile());
			if(!StringUtil.equals(checksum, fileChecksum)) {
				throw new ChecksumFailureException("Checksum for " + artifactPath + "does not match expected " + algorithm + " value " + checksum);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
