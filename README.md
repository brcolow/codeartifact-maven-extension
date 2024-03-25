## Extension Purpose

Automatically retrieves a CodeArtifact authentication token and uses it to properly configure your remote repository to
use your Codeartifact repository.

## Codeartifact Setup For Maven

You need to create a Codeartifact repository and a domain that contains it. You can follow 
[these instructions](https://docs.aws.amazon.com/codeartifact/latest/ug/create-repo.html#create-repo-console).

You should also [add "maven-central-store" as an upstream repository](https://docs.aws.amazon.com/codeartifact/latest/ug/repo-upstream-add-console.html)
if you did not already do so when creating your repository.

## Add Extension to `pom.xml`

You can add `codeartifact-maven-extension` to your projects `pom.xml` like so:

```pom
<build>
  <extensions>
    <extension>
      <groupId>io.github.brcolow</groupId>
      <artifactId>codeartifact-maven-extension</artifactId>
      <version>0.0.1</version>
    </extension>
  </extensions>
  <plugins>
    < !-- etc. -->
  </plugins>
</build>
```

## AWS Authentication

### Create New IAM User

We recommend creating a new IAM User called "codeartifact-admin" and attach to it the AWS managed policy `AWSCodeArtifactAdminAccess`:

```json
{
   "Version": "2012-10-17",
   "Statement": [
      {
         "Action": [
            "codeartifact:Describe*",
            "codeartifact:Get*",
            "codeartifact:List*",
            "codeartifact:ReadFromRepository"
         ],
         "Effect": "Allow",
         "Resource": "*"
      },
      {
         "Effect": "Allow",
         "Action": "sts:GetServiceBearerToken",
         "Resource": "*",
         "Condition": {
            "StringEquals": {
               "sts:AWSServiceName": "codeartifact.amazonaws.com"
            }
         }
      }  
   ]
}
```

### Create New Credentials Profile

Create a new profile in your AWS credentials file (defaults to: `~/.aws/credentials`):

```
[default]
aws_access_key_id={YOUR_ACCESS_KEY_ID}
aws_secret_access_key={YOUR_SECRET_ACCESS_KEY}

[codeartifact]
aws_access_key_id={CODEARTIFACT_ADMIN_ACCESS_KEY_ID}
aws_secret_access_key={CODEARTIFACT_ADMIN_SECRET_ACCESS_KEY}
```

If this is your first time creating a non-default profile, you will only see the `default` profile. In the above example
we added a new profile called `codeartifact` and set the access key and secret key from the IAM user creation step above.

### Pass Profile Name to `codeartifact-maven-extension`

Make sure the name of the profile you created above is passed to `codeartifact-maven-extension` if different from the default
`codeartifact` name. For example, if you named the new profile `codeartifact_profile`, you would configure the extension like
so:

```pom
<properties>
  <codeartifact.profile>codeartifact_profile</codeartifact.profile>
</properties>
```

## Extension Configuration

Unfortunately extensions cannot be configured like plugins (with a `<configuration>` element). Instead, the easiest way is
to use the projects `<properties>` element.

The following configuration parameters must be supplied:

* codeartifact.domain
* codeartifact.domainOwner
* codeartifact.repository

The following configuration parameters are optional:

* codeartifact.durationSeconds [Default = 43200 (12 hours max)] - The time, in seconds, that the generated authorization token is valid. Valid values are 0 and any number between 900 (15 minutes) and 43200 (12 hours).
* codeartifact.profile [Default = "codeartifact"] - The profile name to retrieve credentials from to authorize Codeartifact requests.
* codeartifact.prune [Default = 'false] - If true, will prune unlisted versions from all packages in repository (to cut-down on repository size).

### Example Configuration

```pom
<properties>
  <codeartifact.domain>myDomain</codeartifact.domain>
  <codeartifact.domainOwner>123456789123</codeartifact.domainOwner>
  <codeartifact.repository>myRepo</codeartifact.repository>
</properties>
```

## Known Issues

Codeartifact sometimes reports that it can't upload a checksum file. This is not our fault - it is a [known Codeartifact issue](https://repost.aws/questions/QUPTjhfj0cSYqEk7TgZJRKnw/maven-fails-to-upload-maven-metadata-xml-checksum).
The recommended fix is to add Maven property `-Daether.checksums.algorithms=MD5` when deploying to the Codeartifact repository.

## Publish New Release

```shell
./mvnw versions:set -DnewVersion=0.0.2
./mvnw release:clean release:prepare
./mvnw release:perform
```

## TODO

* Cache the repository endpoint and authorization token with its expiration timestamp so we only have to fetch them when
necessary. The somewhat difficult question is...where to cache them? `target` directory seems unsafe, XDG_CACHE recommendations?
