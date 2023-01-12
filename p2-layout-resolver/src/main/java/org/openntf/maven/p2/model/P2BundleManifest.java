/**
 * Copyright © 2019-2023 Jesse Gallagher
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
package org.openntf.maven.p2.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.apache.commons.lang3.StringUtils;

/**
 * Basic representation of a bundle manifest from a provided {@link Path}, including
 * interpolating bundle localization information.
 * 
 * @author Jesse Gallagher
 * @since 1.1.0
 */
public class P2BundleManifest {
	private final Manifest manifest;
	private final Properties localization;
	
	/**
	 * Constructs a bundle manifest wrapper for the provided path.
	 * 
	 * <p>Note: the path must be a local filesystem path, as the implementation
	 * calls {@link Path#toFile()}.
	 * 
	 * @param path a {@link Path} to a bundle file on the local filesystem
	 */
	public P2BundleManifest(Path path) {
		try(JarFile jarFile = new JarFile(path.toFile())) {
			this.manifest = jarFile.getManifest();
			
			
			this.localization = new Properties();
			
			// Check for a Bundle-Localization header
			String locHeader = this.manifest.getMainAttributes().getValue("Bundle-Localization"); //$NON-NLS-1$
			if(StringUtils.isEmpty(locHeader)) {
				locHeader = "OSGI-INF/l10n/bundle"; // default when not otherwise specified //$NON-NLS-1$
			}
			Set<String> localeVariants = buildLocaleVariants();
			for(String locale : localeVariants) {
				String entryName;
				if(StringUtils.isEmpty(locale)) {
					entryName = locHeader + ".properties"; //$NON-NLS-1$
				} else {
					entryName = locHeader + "_" + locale + ".properties"; //$NON-NLS-1$ //$NON-NLS-2$
				}
				JarEntry entry = jarFile.getJarEntry(entryName);
				if(entry != null) {
					// Then we found the most specific
					try(InputStream is = jarFile.getInputStream(entry)) {
						this.localization.load(is);
					}
					
					break;
				}
			}
			
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public String get(String headerName) {
		String headerValue = this.manifest.getMainAttributes().getValue(headerName);
		if(StringUtils.isNotEmpty(headerValue) && headerValue.startsWith("%") && headerValue.length() > 1) { //$NON-NLS-1$
			String localeProp = headerValue.substring(1);
			return this.localization.getProperty(localeProp, localeProp);
		} else {
			return headerValue;
		}
	}
	
	// *******************************************************************************
	// * Internal mplementation methods
	// *******************************************************************************
	
	private static Set<String> buildLocaleVariants() {
		Set<String> localeVariants = new LinkedHashSet<>();
		String locale = Locale.getDefault().toString();
		localeVariants.add(locale);
		int dashIndex = locale.lastIndexOf('-');
		while(dashIndex > -1) {
			locale = locale.substring(0, dashIndex);
			localeVariants.add(locale);
			dashIndex = locale.lastIndexOf('-');
		}
		localeVariants.add(locale);
		localeVariants.add(""); //$NON-NLS-1$
		return localeVariants;
	}
}
