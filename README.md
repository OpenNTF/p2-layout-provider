# P2 Maven Proxy

This project is a Java web app that serves as a basic proxy to provide a Maven repository front-end to p2 (Eclipse-style) repositories.

## Configuration

Configuration can be done by running the app and then visiting {appUrl}/admin/repos, which allows you to add p2 repositories with a group ID and a remote repo URI.

**Note:** currently, it is required that the group IDs _not_ contain dots. For example, "foo-bar" is legal, but "foo.bar" is not.

## Use

Once one or more repository has been added, you can use the running app by adding a repository to your Maven pom.xml:

```xml
	<repositories>
		<repository>
			<id>proxy</id>
			<url>http://example.com/p2proxy/repo</url>
		</repository>
	</repositories>
```

And then you can add dependencies in the form of (assuming you added an Eclipse IDE repo with the groupId "proxy-eclipse-201912"):

```xml
	<dependency>
		<groupId>proxy-eclipse-201912</groupId>
		<artifactId>org.eclipse.equinox.useradmin.source</artifactId>
		<version>1.1.700.v20181116-1551</version> <!-- or a range like [1.0.0,) -->
	</dependency>
```

## TODO

- Add support for browing repositories from the root
- Add a web UI
- Add a `java.util.prefs.PreferencesFactory` implementation