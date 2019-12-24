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
package bean;

import java.util.List;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Named;

import org.openntf.maven.p2proxy.Storage;

import model.P2Repository;

@ApplicationScoped
@Named("repos")
public class RepositoriesBean {
	private List<P2Repository> repositories = Storage.loadRepositories();
	
	@Produces
	public List<P2Repository> getRepositories() {
		return repositories;
	}
	public void addRepository(P2Repository repo) {
		this.repositories.add(repo);
		Storage.saveRepositories(this.repositories);
	}
	public void deleteRepository(String id) {
		this.repositories = this.repositories.stream()
			.filter(repo -> !repo.getId().equals(id))
			.collect(Collectors.toList());
		Storage.saveRepositories(this.repositories);
	}
}
