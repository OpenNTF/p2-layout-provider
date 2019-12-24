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
package org.openntf.maven.p2proxy;

import java.util.List;
import java.util.prefs.Preferences;

import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;

import model.P2Repository;

public enum Storage {
	;
	
	private static final Jsonb jsonb = JsonbBuilder.create();
	
	private static abstract class ListOfRepos implements List<P2Repository> {
		
	}
	
	public static final Preferences prefs = Preferences.userNodeForPackage(Storage.class);
	
	public static List<P2Repository> loadRepositories() {
		String json = prefs.get("repositories", "[]"); //$NON-NLS-1$ //$NON-NLS-2$
		return jsonb.fromJson(json, ListOfRepos.class.getGenericInterfaces()[0]);
	}
	
	public static void saveRepositories(List<P2Repository> repositories) {
		String json = jsonb.toJson(repositories);
		prefs.put("repositories", json); //$NON-NLS-1$
	}
}
