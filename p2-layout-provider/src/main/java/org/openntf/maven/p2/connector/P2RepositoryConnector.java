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
package org.openntf.maven.p2.connector;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import org.eclipse.aether.transfer.ArtifactNotFoundException;
import org.eclipse.aether.transfer.ArtifactTransferException;
import org.eclipse.aether.transfer.ChecksumFailureException;
import org.eclipse.aether.transfer.MetadataNotFoundException;
import org.eclipse.aether.transfer.MetadataTransferException;
import org.openntf.maven.p2.Messages;
import org.openntf.maven.p2.layout.P2RepositoryLayout;

import com.ibm.commons.util.StringUtil;

public class P2RepositoryConnector implements RepositoryConnector {
	private final Logger log;
	
	private final RemoteRepository repository;
	private final P2RepositoryLayout layout;
	private final ExecutorService executor = Executors.newCachedThreadPool();
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
			log.debug(MessageFormat.format(Messages.getString("P2RepositoryConnector.getCommand"), this.repository)); //$NON-NLS-1$
			log.debug(MessageFormat.format(Messages.getString("P2RepositoryConnector.getCommandDownloads"), artifactDownloads)); //$NON-NLS-1$
			log.debug(MessageFormat.format(Messages.getString("P2RepositoryConnector.getCommandMetadata"), metadataDownloads)); //$NON-NLS-1$
		}
		
		List<Callable<Void>> downloads = new ArrayList<>();
		
		if(artifactDownloads != null) {
			artifactDownloads.stream()
				.map(download -> (Callable<Void>)() -> {
					Path dest = download.getFile().toPath();
					URI sourceUri = layout.getLocation(download.getArtifact(), false);
					try {
						download(sourceUri, dest);
						
						for(Checksum checksum : layout.getChecksums(download.getArtifact(), false, sourceUri)) {
							String ext = checksum.getAlgorithm().replace("-", ""); //$NON-NLS-1$ //$NON-NLS-2$
							Path checksumPath = dest.getParent().resolve(dest.getFileName().toString()+"."+ext); //$NON-NLS-1$
							download(sourceUri.resolve(checksum.getLocation()), checksumPath);
							
							verifyChecksum(dest, checksumPath, checksum.getAlgorithm());
						}
					} catch(FileNotFoundException e) {
						download.setException(new ArtifactNotFoundException(download.getArtifact(), repository, Messages.getString("P2RepositoryConnector.artifactNotFound"), e)); //$NON-NLS-1$
					} catch(Exception e) {
						download.setException(new ArtifactTransferException(download.getArtifact(), repository, Messages.getString("P2RepositoryConnector.exceptionTransferringArtifact"), e)); //$NON-NLS-1$
					}
					return null;
				})
				.forEach(downloads::add);
		}
		
		if(metadataDownloads != null) {
			metadataDownloads.stream()
				.map(download -> (Callable<Void>)() -> {
					Path dest = download.getFile().toPath();
					URI sourceUri = layout.getLocation(download.getMetadata(), false);
					try {
						download(sourceUri, dest);
					} catch(FileNotFoundException e) {
						download.setException(new MetadataNotFoundException(download.getMetadata(), repository, Messages.getString("P2RepositoryConnector.metadataNotFound"), e)); //$NON-NLS-1$
					} catch(Exception e) {
						download.setException(new MetadataTransferException(download.getMetadata(), repository, Messages.getString("P2RepositoryConnector.exceptionTransferringMetadata"), e)); //$NON-NLS-1$
					}
					return null;
				})
				.forEach(downloads::add);
		}
		
		try {
			executor.invokeAll(downloads);
		} catch (InterruptedException e) {
			if(log.isWarnEnabled()) {
				log.warn(MessageFormat.format(Messages.getString("P2RepositoryConnector.interruptedDownloads"), downloads.size())); //$NON-NLS-1$
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
		List<Runnable> tasks = executor.shutdownNow();
		if(tasks != null && !tasks.isEmpty()) {
			if(log.isWarnEnabled()) {
				log.warn(MessageFormat.format(Messages.getString("P2RepositoryConnector.awaitingTermination"), tasks.size())); //$NON-NLS-1$
			}
			try {
				executor.awaitTermination(1, TimeUnit.MINUTES);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		this.closed = true;
	}
	
	// *******************************************************************************
	// * Internal implementation methods
	// *******************************************************************************

	private void checkClosed() {
		if(this.closed) {
			throw new IllegalStateException(Messages.getString("P2RepositoryConnector.connectorIsClosed")); //$NON-NLS-1$
		}
	}
	
	private void download(URI source, Path dest) throws FileNotFoundException, IOException {
		try(InputStream is = source.toURL().openStream()) {
			Files.createDirectories(dest.getParent());
			Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
		}
	}
	
	private void verifyChecksum(Path artifactPath, Path checksumPath, String algorithm) throws ChecksumFailureException {
		try {
			String checksum = new String(Files.readAllBytes(checksumPath));
			DigestUtils digest = new DigestUtils(algorithm);
			String fileChecksum = digest.digestAsHex(artifactPath.toFile());
			if(!StringUtil.equals(checksum, fileChecksum)) {
				throw new ChecksumFailureException(MessageFormat.format(
						Messages.getString("P2RepositoryConnector.checksumMismatch"), artifactPath, //$NON-NLS-1$
						algorithm, checksum, fileChecksum));
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
