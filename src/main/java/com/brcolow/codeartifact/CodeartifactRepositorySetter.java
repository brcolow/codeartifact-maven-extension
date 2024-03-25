package com.brcolow.codeartifact;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.Authentication;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.settings.Mirror;
import org.codehaus.plexus.component.annotations.Requirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.codeartifact.CodeartifactClient;
import software.amazon.awssdk.services.codeartifact.model.*;

import javax.inject.Named;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

@Named
@SuppressWarnings("unused")
public class CodeartifactRepositorySetter extends AbstractMavenLifecycleParticipant {
    @Requirement
    private final Logger logger = LoggerFactory.getLogger(CodeartifactRepositorySetter.class);
    private static CodeartifactClient codeartifactClient;
    private static Configuration configuration;

    @Override
    public void afterProjectsRead(final MavenSession session) throws MavenExecutionException {
        String domain = requireProperty(session.getCurrentProject().getProperties(), "codeartifact.domain");
        String domainOwner = requireProperty(session.getCurrentProject().getProperties(), "codeartifact.domainOwner");
        String repository = requireProperty(session.getCurrentProject().getProperties(), "codeartifact.repository");
        String durationSecondsStr = session.getCurrentProject().getProperties().getProperty("codeartifact.durationSeconds", "43200");
        int durationSeconds = 0;
        try {
            durationSeconds = Integer.parseInt(durationSecondsStr);
        } catch (NumberFormatException ex) {
            logger.error("\"codeartifact.durationSeconds\" property must be a number but was: \"" + durationSecondsStr + "\".", ex);
        }
        if (durationSeconds <= 0 || durationSeconds > 43200) {
            logger.error("\"codeartifact.durationSeconds\" property must be greater than 0 and less than or equal to 43200.");
        }

        String profile = session.getCurrentProject().getProperties().getProperty("codeartifact.profile", "codeartifact");

        configuration = new Configuration(domain, domainOwner, durationSeconds, repository, profile);

        ArtifactRepository codeartifactRepository;
        try {
            codeartifactRepository = getCodeartifactRepository(configuration);
        } catch (SdkException ex) {
            logger.error("AWS SDK error: ", ex);
            throw new RuntimeException(ex);
        }

        session.getCurrentProject().setRemoteArtifactRepositories(List.of(codeartifactRepository));
        session.getCurrentProject().setPluginArtifactRepositories(List.of(codeartifactRepository));

        session.getCurrentProject().setSnapshotArtifactRepository(codeartifactRepository);
        session.getCurrentProject().setReleaseArtifactRepository(codeartifactRepository);
        Mirror mavenCentralMirror = new Mirror();
        mavenCentralMirror.setId("central-mirror");
        mavenCentralMirror.setName("CodeArtifact Maven Central mirror");
        mavenCentralMirror.setUrl(codeartifactRepository.getUrl());
        mavenCentralMirror.setMirrorOf("central");
        session.getRequest().setMirrors(List.of(mavenCentralMirror));
    }

    @Override
    public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
        String prune = session.getCurrentProject().getProperties().getProperty("codeartifact.prune", "false");

        if (!Boolean.parseBoolean(prune)) {
            return;
        }

        logger.info("Pruning Codeartifact repository of Unlisted snapshots...");
        ListPackagesResponse listPackagesResponse = getCodeArtifactClient(configuration.getProfile()).listPackages(
                ListPackagesRequest.builder()
                        .domain(configuration.getDomain())
                        .domainOwner(configuration.getDomainOwner())
                        .format(PackageFormat.MAVEN)
                        .repository(configuration.getRepository())
                        .build());

        for (PackageSummary packageSummary : listPackagesResponse.packages()) {
            ListPackageVersionsResponse listPackageVersionsResponse = getCodeArtifactClient(configuration.getProfile()).listPackageVersions(
                    ListPackageVersionsRequest.builder()
                            .domain(configuration.getDomain())
                            .domainOwner(configuration.getDomainOwner())
                            .format(PackageFormat.MAVEN)
                            .repository(configuration.getRepository())
                            .namespace(packageSummary.namespace())
                            .packageValue(packageSummary.packageValue())
                            .status(PackageVersionStatus.UNLISTED)
                            .build());

            if (!listPackagesResponse.packages().isEmpty()) {
                logger.info("Pruning unlisted versions for package: " + packageSummary.namespace() + ":" + packageSummary.packageValue());
                DeletePackageVersionsResponse deletePackageVersionsResponse = getCodeArtifactClient(configuration.getProfile()).deletePackageVersions(
                        DeletePackageVersionsRequest.builder()
                                .domain(configuration.getDomain())
                                .domainOwner(configuration.getDomainOwner())
                                .format(PackageFormat.MAVEN)
                                .repository(configuration.getRepository())
                                .namespace(packageSummary.namespace())
                                .packageValue(packageSummary.packageValue())
                                .expectedStatus(PackageVersionStatus.UNLISTED)
                                .versions(listPackageVersionsResponse.versions().stream().map(PackageVersionSummary::version).collect(Collectors.toList()))
                                .build());
            }
        }
    }

    private String requireProperty(Properties properties, String propertyName) {
        String domain = properties.getProperty(propertyName);
        if (domain == null) {
            logger.error("\"" + propertyName + "\" was not set in project <properties> element.");
        }
        return domain;
    }

    public ArtifactRepository getCodeartifactRepository(Configuration configuration) {
        return getCodeartifactRepository(configuration.getProfile(), configuration.getDomain(),
                configuration.getDomainOwner(), configuration.getRepository(), configuration.getDurationSeconds());
    }

    public ArtifactRepository getCodeartifactRepository(
            String profile, String domain, String domainOwner, String repository, int durationSeconds) {
        // https://docs.aws.amazon.com/codeartifact/latest/APIReference/API_GetAuthorizationToken.html
        GetRepositoryEndpointResponse getRepositoryEndpointResponse = getCodeArtifactClient(profile).getRepositoryEndpoint(
                GetRepositoryEndpointRequest.builder()
                        .domain(domain)
                        .domainOwner(domainOwner)
                        .format(PackageFormat.MAVEN)
                        .repository(repository)
                        .build());

        logger.info("Codeartifact repository endpoint: " + getRepositoryEndpointResponse.repositoryEndpoint());
        // GetAuthorizationToken requires codeartifact:GetAuthorizationToken and sts:GetServiceBearerToken permissions.
        GetAuthorizationTokenResponse getAuthorizationTokenResponse = getCodeArtifactClient(profile).getAuthorizationToken(
                GetAuthorizationTokenRequest.builder()
                        .domain(domain)
                        .domainOwner(domainOwner)
                        .durationSeconds((long) durationSeconds)
                        .build());
        logger.info("Fetched Codeartifact authorization token");
        ArtifactRepository codeartifact = new MavenArtifactRepository("codeartifact",
                getRepositoryEndpointResponse.repositoryEndpoint(), new DefaultRepositoryLayout(),
                new ArtifactRepositoryPolicy(true,
                        ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS,
                        ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN),
                new ArtifactRepositoryPolicy(true,
                        ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS,
                        ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN));
        codeartifact.setAuthentication(new Authentication("aws", getAuthorizationTokenResponse.authorizationToken()));
        return codeartifact;
    }

    public static CodeartifactClient getCodeArtifactClient(String profile) {
        if (codeartifactClient == null) {
            codeartifactClient = CodeartifactClient.builder()
                    .credentialsProvider(ProfileCredentialsProvider.create(profile))
                    .build();
        }
        return codeartifactClient;
    }
}
