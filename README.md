# P2 Maven Resolver

This project is a Maven plugin to allow basic resolution of artifacts from a p2 (Eclipse-style) repository.

## Use

To use this plugin, add it to the `<build>` section of your project's Pom with `<extensions>true</extensions>`. Then, add a repository with `<layout>p2</layout>` pointing at a location containing an "artifacts.jar" file and a "plugins" directory. Once that is set up, you can refer to p2-hosted artifacts with a `groupId` matching the `id` of the repository you add.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<groupId>com.example</groupId>
	<artifactId>p2-resolution.example</artifactId>
	<version>0.0.1-SNAPSHOT</version>
	
	<pluginRepositories>
		<!-- the plugin is hosted in OpenNTF's Maven repository -->
		<pluginRepository>
			<id>artifactory.openntf.org</id>
			<name>artifactory.openntf.org</name>
			<url>https://artifactory.openntf.org/openntf</url>
		</pluginRepository>
	</pluginRepositories>

	<repositories>
		<repository>
			<id>org.eclipse.p2.repo</id>   <!-- defines the groupId to be used below -->
			<url>http://download.eclipse.org/releases/2019-12</url>
			<layout>p2</layout>
		</repository>
	</repositories>

	<dependencies>
		<dependency>
			<groupId>org.eclipse.p2.repo</groupId>   <!-- matches id of the repo above -->
			<artifactId>ch.qos.logback.slf4j</artifactId>
			<version>1.1.2.v20160301-0943</version>
		</dependency>
	</dependencies>

	<build>
		<plugins>
			<plugin>
				<groupId>org.openntf.maven</groupId>
				<artifactId>p2-layout-resolver</artifactId>
				<version>1.5.0</version>
				<extensions>true</extensions>
			</plugin>
		</plugins>
	</build>
</project>
```

### Dependency Handling

When generating Poms for the p2 artifacts, this provider created `<dependency>` entries for bundles referenced in `Require-Bundle` headers that are contained within the same repository, as well as for embedded Jars within the same bundle.

### Embedded Jars

To access a Jar embedded inside an OSGi artifact directly, use the embedded Jar's base name as a classifier:

```xml
<dependency>
    <groupId>some.repo.id</groupId>
    <artifactId>some.artifact</artifactId>
    <version>[1.0.0,)</version>
    <classifier>example.embedded</classifier>   <!-- matches example.embedded.jar -->
</dependency>
```

If the embedded Jar is within a directory, it should be referenced with a "$" in place of the "/":

```xml
<dependency>
    <groupId>some.repo.id</groupId>
    <artifactId>some.artifact</artifactId>
    <version>[1.0.0,)</version>
    <classifier>lib$example.embedded</classifier>   <!-- matches lib/example.embedded.jar -->
</dependency>
```

### Sources

This provider handles source Jars by looking for a bundle of the same artifact ID plus ".source" when asked for the "sources" classifier.
