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
package controller;

import java.net.URI;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.mvc.Controller;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.MediaType;

import bean.RepositoriesBean;
import model.P2Repository;

@Path("admin")
@RequestScoped
@Controller
public class AdminController {
	@Inject
	private RepositoriesBean repositories;
	
	@GET
	@Path("repos")
	public String getRepos() {
		return "admin.jsp"; //$NON-NLS-1$
	}
	
	@POST
	@Path("repos")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String createRepo(@FormParam("groupId") @NotEmpty String groupId, @FormParam("uri") @NotNull URI uri) {
		P2Repository repo = new P2Repository();
		repo.setGroupId(groupId);
		repo.setUri(uri);
		repositories.addRepository(repo);
		return "redirect:admin/repos"; //$NON-NLS-1$
	}
	
	@DELETE
	@Path("repos/{repoId}")
	public String deleteRepo(@PathParam("repoId") String repoId) {
		repositories.deleteRepository(repoId);
		return "redirect:admin/repos"; //$NON-NLS-1$
	}
}
