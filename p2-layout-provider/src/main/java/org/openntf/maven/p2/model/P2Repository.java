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
import com.ibm.commons.util.io.StreamUtil;
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
				InputStream artifactsXml = findXml(this.uri, "artifacts"); //$NON-NLS-1$
				if(artifactsXml != null) {
					try {
						collectBundles(artifactsXml, this.bundles);
					} finally {
						StreamUtil.close(artifactsXml);
					}
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
	
	private static InputStream findXml(URI baseUri, String baseName) throws IOException, CompressorException {
		URI xml = URI.create(PathUtil.concat(baseUri.toString(), baseName + ".xml", '/')); //$NON-NLS-1$
		try {
			return xml.toURL().openStream();
		} catch(FileNotFoundException e) {
			// Plain XML not present
		}
		
		URI xz = URI.create(PathUtil.concat(baseUri.toString(), baseName + ".xml.xz", '/')); //$NON-NLS-1$
		try {
			InputStream is = xz.toURL().openStream();
			return CompressorStreamFactory.getSingleton().createCompressorInputStream(CompressorStreamFactory.getXz(), is);
		} catch(FileNotFoundException e) {
			// XZ-compressed XML not present
		}
		
		URI jar = URI.create(PathUtil.concat(baseUri.toString(), baseName + ".jar", '/')); //$NON-NLS-1$
		try {
			InputStream is = jar.toURL().openStream();
			JarInputStream jis = new JarInputStream(is);
			jis.getNextEntry();
			return jis;
		} catch(FileNotFoundException e) {
			// Jar-compressed XML not present
		}
		
		return null;
	}
	
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
