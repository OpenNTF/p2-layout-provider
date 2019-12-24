package controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;
import java.util.jar.JarInputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.ibm.commons.util.PathUtil;
import com.ibm.commons.util.io.StreamUtil;
import com.ibm.commons.xml.DOMUtil;
import com.ibm.commons.xml.XMLException;

import model.P2Repository;

@Path(RepoController.PATH)
public class RepoController {
	public static final String PATH = "repo"; //$NON-NLS-1$
	
	@Inject
	private List<P2Repository> repositories;
	
	@GET
	@Path("{groupId}")
	@Produces(MediaType.TEXT_PLAIN)
	public String get(@PathParam("groupId") String groupId) throws MalformedURLException, IOException, XMLException {
		
		P2Repository repo = repositories.stream()
			.filter(r -> r.getGroupId().equals(groupId))
			.findFirst()
			.orElseThrow(() -> new NotFoundException("Could not find repo with group ID " + groupId));
		
		URI artifactsJar = URI.create(PathUtil.concat(repo.getUri().toString(), "artifacts.jar", '/')); //$NON-NLS-1$
		
		Document xml;
		try(InputStream is = artifactsJar.toURL().openStream()) {
			try(JarInputStream jis = new JarInputStream(is)) {
				jis.getNextEntry();
				xml = DOMUtil.createDocument(jis);
			}
		}
		
		Object[] artifacts = DOMUtil.nodes(xml, "/repository/artifacts/artifact"); //$NON-NLS-1$
		return Stream.of(artifacts)
			.map(Element.class::cast)
			.map(artifact -> {
				// TODO consider using properties/property[@name="maven-artifactId"] et al
				String id = artifact.getAttribute("id"); //$NON-NLS-1$
				String version = artifact.getAttribute("version"); //$NON-NLS-1$
				
				return id + ":" + version; //$NON-NLS-1$
			})
			.collect(Collectors.joining("\n")); //$NON-NLS-1$
	}
	
	@GET
	@Path("{groupId}/{artifactId}/maven-metadata.xml")
	@Produces(MediaType.TEXT_XML)
	public Document getArtifactMetadata(@PathParam("groupId") String groupId, @PathParam("artifactId") String artifactId) throws MalformedURLException, IOException, XMLException {
		
		P2Repository repo = repositories.stream()
			.filter(r -> r.getGroupId().equals(groupId))
			.findFirst()
			.orElseThrow(() -> new NotFoundException("Could not find repo with group ID " + groupId));
		
		URI artifactsJar = URI.create(PathUtil.concat(repo.getUri().toString(), "artifacts.jar", '/')); //$NON-NLS-1$
		
		Document xml;
		try(InputStream is = artifactsJar.toURL().openStream()) {
			try(JarInputStream jis = new JarInputStream(is)) {
				jis.getNextEntry();
				xml = DOMUtil.createDocument(jis);
			}
		}
		
		Element artifact = (Element)DOMUtil.node(xml, "/repository/artifacts/artifact[@id=\"" + artifactId + "\"]"); //$NON-NLS-1$ //$NON-NLS-2$
		String version = artifact.getAttribute("version"); //$NON-NLS-1$
		
		Document result = DOMUtil.createDocument();
		Element metadata = DOMUtil.createElement(result, "metadata"); //$NON-NLS-1$
		DOMUtil.createElement(result, metadata, "groupId").setTextContent(groupId); //$NON-NLS-1$
		DOMUtil.createElement(result, metadata, "artifactId").setTextContent(artifactId); //$NON-NLS-1$
		Element versioning = DOMUtil.createElement(result, metadata, "versioning"); //$NON-NLS-1$
		DOMUtil.createElement(result, versioning, "latest").setTextContent(version); //$NON-NLS-1$
		DOMUtil.createElement(result, versioning, "release").setTextContent(version); //$NON-NLS-1$
		Element versions = DOMUtil.createElement(result, versioning, "versions"); //$NON-NLS-1$
		DOMUtil.createElement(result, versions, "version").setTextContent(version); //$NON-NLS-1$
		return result;
	}
	
	@GET
	@Path("{groupId}/{artifactId}/{version}/{artifact}.pom")
	@Produces(MediaType.TEXT_XML)
	public Document getArtifactPom(@PathParam("groupId") String groupId, @PathParam("artifactId") String artifactId, @PathParam("version") String version, @PathParam("artifact") String artifact) throws XMLException {
		Document xml = DOMUtil.createDocument();
		Element project = DOMUtil.createElement(xml, "project"); //$NON-NLS-1$
		DOMUtil.createElement(xml, project, "modelVersion").setTextContent("4.0.0"); //$NON-NLS-1$ //$NON-NLS-2$
		DOMUtil.createElement(xml, project, "groupId").setTextContent(groupId); //$NON-NLS-1$
		DOMUtil.createElement(xml, project, "artifactId").setTextContent(artifactId); //$NON-NLS-1$
		DOMUtil.createElement(xml, project, "name").setTextContent(artifactId); //$NON-NLS-1$
		DOMUtil.createElement(xml, project, "version").setTextContent(version); //$NON-NLS-1$
		return xml;
	}
	
	@GET
	@Path("{groupId}/{artifactId}/{version}/{artifact}")
	@Produces("application/x-java-archive")
	public StreamingOutput getArtifact(@PathParam("groupId") String groupId, @PathParam("artifactId") String artifactId, @PathParam("version") String version, @PathParam("artifact") String artifact) throws MalformedURLException, IOException {
		P2Repository repo = repositories.stream()
				.filter(r -> r.getGroupId().equals(groupId))
				.findFirst()
				.orElseThrow(() -> new NotFoundException("Could not find repo with group ID " + groupId));

		String plugins = PathUtil.concat(repo.getUri().toString(), "plugins", '/'); //$NON-NLS-1$
		String artifactUrl = PathUtil.concat(plugins, artifactId + "_" + version + ".jar", '/'); //$NON-NLS-1$ //$NON-NLS-2$
		URI url = URI.create(artifactUrl);
		
		return out -> {
			try(InputStream is = url.toURL().openStream()) {
				StreamUtil.copyStream(is, out);
			}
		};
	}
	
	@GET
	@Path("{s:.*}/maven-metadata.xml")
	@Produces(MediaType.TEXT_XML)
	public String getMetadata(@Context UriInfo uriInfo) {
		String path = uriInfo.getPath().substring(PATH.length());
		String basePath = path.startsWith("/") ? path.substring(1) : path; //$NON-NLS-1$
		
		return repositories.stream()
			.map(P2Repository::getGroupId)
			.map(groupId -> groupId.replace('.', '/'))
			.filter(groupPath -> basePath.isEmpty() || groupPath.startsWith(basePath + "/")) //$NON-NLS-1$
			.collect(Collectors.joining("\n")); //$NON-NLS-1$
	}
}
