## Extension Purpose

Automatically retrieves a CodeArtifact authorization token, discovers the repository endpoint, and configures Maven to
use your CodeArtifact repository for dependency resolution and publishing.

This exists because AWS's documented Maven flow still requires fetching and refreshing a temporary CodeArtifact auth
token outside Maven.

## Compatibility

This project builds against Maven 3.9.16 APIs and targets Java 11 bytecode. The included Maven Wrapper is also pinned
to Maven 3.9.16.

## Intended Behavior

By default, this extension uses the "CodeArtifact is the source of truth" workflow:

* it discovers the configured CodeArtifact Maven repository endpoint
* it fetches a fresh authorization token for that repository
* it points dependency and plugin resolution at that repository
* it configures a `central` mirror so Maven Central is reached through CodeArtifact

Set `codeartifact.sourceOfTruth=false` if you want Maven Central and your other configured repositories to continue
resolving directly. In that mode, the extension adds the authenticated CodeArtifact repository to the existing
dependency and plugin repositories without configuring a Maven Central mirror.

If `codeartifact.prune=true` is enabled, the extension also deletes unlisted package versions from the configured
repository after the Maven session finishes.

## CodeArtifact Setup for Maven

Create a CodeArtifact domain and a Maven repository inside it. AWS documents that flow here:

* [Create a repository in CodeArtifact](https://docs.aws.amazon.com/codeartifact/latest/ug/create-repo.html#create-repo-console)
* [Add an upstream repository](https://docs.aws.amazon.com/codeartifact/latest/ug/repo-upstream-add-console.html)

If you want CodeArtifact to proxy Maven Central, add `maven-central-store` as an upstream repository.

## Add Extension

Create or update `.mvn/extensions.xml` in your project:

```xml
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.1.0"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://maven.apache.org/EXTENSIONS/1.1.0 https://maven.apache.org/xsd/core-extensions-1.1.0.xsd">
  <extension>
    <groupId>io.github.brcolow</groupId>
    <artifactId>codeartifact-maven-extension</artifactId>
    <version>0.0.7</version>
  </extension>
</extensions>
```

You can also add it as a build extension in `pom.xml`:

```pom
<build>
  <extensions>
    <extension>
      <groupId>io.github.brcolow</groupId>
      <artifactId>codeartifact-maven-extension</artifactId>
      <version>0.0.7</version>
    </extension>
  </extensions>
</build>
```

## AWS Authentication

By default, the extension uses the AWS SDK for Java default credential chain.

If you want to force a specific shared credentials profile for this extension, set `codeartifact.profile`:

```pom
<properties>
  <codeartifact.profile>codeartifact</codeartifact.profile>
  <codeartifact.region>us-west-2</codeartifact.region>
</properties>
```

When a named profile does not have its own region, either set `codeartifact.region`, set `aws.region` or
`AWS_REGION`, or configure a default profile region. The extension checks those in that order before falling back to
the instance metadata region provider.

## Extension Configuration

Extensions cannot use a plugin-style `<configuration>` block, so this extension is configured with project properties.

Required properties:

* `codeartifact.domain`
* `codeartifact.domainOwner`
* `codeartifact.repository`

Optional properties:

* `codeartifact.durationSeconds`
  Default: `43200`
  Valid values: `0`, or any value from `900` to `43200`
  `0` is primarily useful when you are using assumed-role credentials and want the token lifetime to track the
  remaining session duration.
* `codeartifact.profile`
  Optional override for the shared AWS profile to use. If omitted, the AWS default credential chain is used.
* `codeartifact.region`
  Optional override for the AWS region to use for CodeArtifact. This is useful when the selected profile comes from
  the shared credentials file and has no matching region entry in the shared config file.
* `codeartifact.sourceOfTruth`
  Default: `true`
  If `false`, the extension keeps existing dependency and plugin repositories, adds the authenticated CodeArtifact
  repository, and does not configure Maven Central to mirror through CodeArtifact.
* `codeartifact.prune`
  Default: `false`
  If `true`, the extension deletes unlisted package versions from the configured CodeArtifact repository after the
  Maven session ends.

The extension fails fast when required properties are missing or when `codeartifact.durationSeconds` or
`codeartifact.sourceOfTruth` is invalid.

Project properties can be overridden with normal Maven `-D` properties. For example:

```shell
./mvnw -Dcodeartifact.profile=codeartifact -Dcodeartifact.region=us-west-2 test
```

### Example Configuration

```pom
<properties>
  <codeartifact.domain>myDomain</codeartifact.domain>
  <codeartifact.domainOwner>123456789123</codeartifact.domainOwner>
  <codeartifact.repository>myRepo</codeartifact.repository>
  <codeartifact.profile>codeartifact</codeartifact.profile>
  <codeartifact.region>us-west-2</codeartifact.region>
  <codeartifact.durationSeconds>3600</codeartifact.durationSeconds>
</properties>
```

## Known Issues

CodeArtifact sometimes reports that it cannot upload a checksum file. This is a known CodeArtifact issue:

* [Maven fails to upload maven-metadata.xml checksum](https://repost.aws/questions/QUPTjhfj0cSYqEk7TgZJRKnw/maven-fails-to-upload-maven-metadata-xml-checksum)

The recommended workaround is to add Maven property `-Daether.checksums.algorithms=MD5` when deploying to the
CodeArtifact repository.

## Development

Run the test suite with:

```shell
./mvnw test
```

## Publish New Release

The `release` profile attaches sources and Javadocs, signs artifacts, and enables Maven Central publishing.

```shell
./mvnw versions:set -DnewVersion=<version>
./mvnw release:clean release:prepare
./mvnw release:perform
```
