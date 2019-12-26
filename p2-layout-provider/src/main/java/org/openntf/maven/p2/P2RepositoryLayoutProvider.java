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
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.inject.Named;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.layout.RepositoryLayout;
import org.eclipse.aether.spi.connector.layout.RepositoryLayoutFactory;
import org.eclipse.aether.transfer.NoRepositoryLayoutException;

@Named("p2")
public class P2RepositoryLayoutProvider implements RepositoryLayoutFactory {
	public static final Logger log = Logger.getLogger(P2RepositoryLayoutProvider.class.getPackage().getName());

	@Override
	public RepositoryLayout newInstance(RepositorySystemSession session, RemoteRepository repository)
			throws NoRepositoryLayoutException {
		if (!"p2".equals(repository.getContentType())) { //$NON-NLS-1$
			throw new NoRepositoryLayoutException(repository);
		}
		
		if(log.isLoggable(Level.INFO)) {
			log.info("Creating new P2RepositoryLayout for repository " + repository.getUrl());
		}
		
		try {
			return new P2RepositoryLayout(repository.getId(), repository.getUrl());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public float getPriority() {
		return 1;
	}

}
