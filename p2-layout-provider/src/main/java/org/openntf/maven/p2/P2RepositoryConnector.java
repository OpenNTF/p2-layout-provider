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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.ArtifactDownload;
import org.eclipse.aether.spi.connector.ArtifactUpload;
import org.eclipse.aether.spi.connector.MetadataDownload;
import org.eclipse.aether.spi.connector.MetadataUpload;
import org.eclipse.aether.spi.connector.RepositoryConnector;

public class P2RepositoryConnector implements RepositoryConnector {
	private static final Logger log = P2RepositoryLayoutProvider.log;
	
	private final RemoteRepository repository;
	private final P2RepositoryLayout layout;
	private boolean closed;
	
	public P2RepositoryConnector(RepositorySystemSession session, RemoteRepository repository) {
		this.repository = repository;
		try {
			// TODO support auth
			this.layout = new P2RepositoryLayout(repository.getId(), repository.getUrl());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void get(Collection<? extends ArtifactDownload> artifactDownloads, Collection<? extends MetadataDownload> metadataDownloads) {
		checkClosed();
		
		if(log.isLoggable(Level.FINEST)) {
			log.finest("Issuing get command in repo " + this.repository);
			log.finest("downloads are " + artifactDownloads);
			log.finest("metadata is " + metadataDownloads);
		}
		// TODO see if this should be routed through the session and/or multithreaded
		if(artifactDownloads != null) {
			for(ArtifactDownload download : artifactDownloads) {
				Path dest = download.getFile().toPath();
				URI sourceUri = layout.getLocation(download.getArtifact(), false);
				try(InputStream is = sourceUri.toURL().openStream()) {
					Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}
		if(metadataDownloads != null) {
			for(MetadataDownload download : metadataDownloads) {
				Path dest = download.getFile().toPath();
				URI sourceUri = layout.getLocation(download.getMetadata(), false);
				try(InputStream is = sourceUri.toURL().openStream()) {
					Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
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

	private void checkClosed() {
		if(this.closed) {
			throw new IllegalStateException("Connector is closed");
		}
	}
}
