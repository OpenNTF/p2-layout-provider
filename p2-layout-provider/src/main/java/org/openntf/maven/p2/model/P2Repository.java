package org.openntf.maven.p2.model;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;

import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;
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
			this.bundles = new ArrayList<>();
			
			try {
				URI artifactsXml = URI.create(PathUtil.concat(this.uri.toString(), "artifacts.xml", '/')); //$NON-NLS-1$
				try(InputStream is = artifactsXml.toURL().openStream()) {
					collectBundles(is, this.bundles);
				} catch(FileNotFoundException e) {
					// artifacts.xml is not present
				}
				
				URI artifactsXz = URI.create(PathUtil.concat(this.uri.toString(), "artifacts.xml.xz", '/')); //$NON-NLS-1$
				try(InputStream is = artifactsXz.toURL().openStream()) {
					try(InputStream xis = CompressorStreamFactory.getSingleton().createCompressorInputStream(CompressorStreamFactory.getXz(), is)) {
						collectBundles(xis, this.bundles);
					}
				} catch(FileNotFoundException e) {
					// artifacts.xml.xz is not present
				}
				
				URI artifactsJar = URI.create(PathUtil.concat(this.uri.toString(), "artifacts.jar", '/')); //$NON-NLS-1$
				try(InputStream is = artifactsJar.toURL().openStream()) {
					try(JarInputStream jis = new JarInputStream(is)) {
						jis.getNextEntry();
						collectBundles(jis, this.bundles);
					}
				} catch(FileNotFoundException e) {
					// artifacts.jar is not present
				}
				
				
			} catch(IOException | XMLException | CompressorException e) {
				throw new RuntimeException(e);
			}
		}
		return this.bundles;
	}

	// *******************************************************************************
	// * Internal implementation methods
	// *******************************************************************************
	
	private static void collectBundles(InputStream is, List<P2Bundle> bundles) throws XMLException {
		Document artifactsXml = DOMUtil.createDocument(is);
		Stream.of(DOMUtil.nodes(artifactsXml, "/repository/artifacts/artifact[@classifier=\"osgi.bundle\"]")) //$NON-NLS-1$
			.map(Element.class::cast)
			.filter(el -> {
				try {
					return DOMUtil.nodes(el, "processing").length == 0; //$NON-NLS-1$
				} catch (XMLException e) {
					throw new RuntimeException(e);
				}
			})
			.map(P2Bundle::new)
			.forEach(bundles::add);
	}
	
}
