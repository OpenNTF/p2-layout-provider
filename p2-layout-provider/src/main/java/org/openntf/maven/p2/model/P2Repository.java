package org.openntf.maven.p2.model;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.jar.JarInputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.ibm.commons.util.PathUtil;
import com.ibm.commons.xml.DOMUtil;
import com.ibm.commons.xml.XMLException;

/**
 * Represents a local or remote P2 repository root.
 * 
 * @author Jesse Gallagher
 * @since 1.0.0
 */
public class P2Repository {
	private final URI uri;
	private List<P2Bundle> bundles;

	public P2Repository(URI uri) {
		this.uri = uri;
	}
	
	/**
	 * Retrieves a list of bundles in this repository. Specifically, this includes artifacts designated
	 * as "osgi.bundle" in the repository's artifacts.xml manifest.
	 * 
	 * @return a {@link List} of {@link P2Bundle}s. Never null
	 * @throws RuntimeException if there is a problem finding the repository or parsing its artifact manifest
	 */
	public synchronized List<P2Bundle> getBundles() {
		if(this.bundles == null) {
			URI artifactsJar = URI.create(PathUtil.concat(this.uri.toString(), "artifacts.jar", '/')); //$NON-NLS-1$
			
			try {
				Document xml;
				try(InputStream is = artifactsJar.toURL().openStream()) {
					try(JarInputStream jis = new JarInputStream(is)) {
						jis.getNextEntry();
						xml = DOMUtil.createDocument(jis);
					}
				}
				this.bundles = Stream.of(DOMUtil.nodes(xml, "/repository/artifacts/artifact[@classifier=\"osgi.bundle\"]")) //$NON-NLS-1$
					.map(Element.class::cast)
					.filter(el -> {
						try {
							return DOMUtil.nodes(el, "processing").length == 0; //$NON-NLS-1$
						} catch (XMLException e) {
							throw new RuntimeException(e);
						}
					})
					.map(P2Bundle::new)
					.collect(Collectors.toList());
			} catch(IOException | XMLException e) {
				throw new RuntimeException(e);
			}
		}
		return this.bundles;
	}

}
