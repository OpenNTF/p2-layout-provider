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

import javax.inject.Named;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.RepositoryConnector;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.transfer.NoRepositoryConnectorException;

@Named("p2repo")
public class P2RepositoryConnectorFactory implements RepositoryConnectorFactory {

	@Override
	public RepositoryConnector newInstance(RepositorySystemSession session, RemoteRepository repository)
			throws NoRepositoryConnectorException {
		if(!"p2".equals(repository.getContentType())) { //$NON-NLS-1$
			throw new NoRepositoryConnectorException(repository);
		}
		return new P2RepositoryConnector(session, repository);
	}

	@Override
	public float getPriority() {
		return 1;
	}

}
