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
package org.openntf.maven.p2.layout;

import java.io.IOException;
import java.text.MessageFormat;

import javax.inject.Inject;
import javax.inject.Named;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.checksum.ChecksumAlgorithmFactorySelector;
import org.eclipse.aether.spi.connector.layout.RepositoryLayout;
import org.eclipse.aether.spi.connector.layout.RepositoryLayoutFactory;
import org.eclipse.aether.transfer.NoRepositoryLayoutException;
import org.openntf.maven.p2.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Named("p2")
public class P2RepositoryLayoutFactory implements RepositoryLayoutFactory {
	public final Logger log = LoggerFactory.getLogger(getClass());

	private final ChecksumAlgorithmFactorySelector checksumAlgorithmFactorySelector;

	@Inject
	public P2RepositoryLayoutFactory(ChecksumAlgorithmFactorySelector checksumAlgorithmFactorySelector) {
		this.checksumAlgorithmFactorySelector = checksumAlgorithmFactorySelector;
	}

	@Override
	public RepositoryLayout newInstance(RepositorySystemSession session, RemoteRepository repository)
			throws NoRepositoryLayoutException {
		if (!"p2".equals(repository.getContentType())) { //$NON-NLS-1$
			throw new NoRepositoryLayoutException(repository);
		}
		
		if(log.isDebugEnabled()) {
			log.debug(MessageFormat.format(Messages.getString("P2RepositoryLayoutFactory.creatingNew"), repository.getUrl())); //$NON-NLS-1$
		}
		
		try {
			return new P2RepositoryLayout(repository.getId(), repository.getUrl(), log, checksumAlgorithmFactorySelector);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public float getPriority() {
		return 1;
	}

}
