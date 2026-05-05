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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.AwsProfileRegionProvider;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.regions.providers.AwsRegionProviderChain;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.regions.providers.InstanceProfileRegionProvider;
import software.amazon.awssdk.regions.providers.SystemSettingsRegionProvider;
import software.amazon.awssdk.services.codeartifact.CodeartifactClient;
import software.amazon.awssdk.services.codeartifact.model.DeletePackageVersionsRequest;
import software.amazon.awssdk.services.codeartifact.model.GetAuthorizationTokenRequest;
import software.amazon.awssdk.services.codeartifact.model.GetAuthorizationTokenResponse;
import software.amazon.awssdk.services.codeartifact.model.GetRepositoryEndpointRequest;
import software.amazon.awssdk.services.codeartifact.model.GetRepositoryEndpointResponse;
import software.amazon.awssdk.services.codeartifact.model.ListPackageVersionsRequest;
import software.amazon.awssdk.services.codeartifact.model.ListPackageVersionsResponse;
import software.amazon.awssdk.services.codeartifact.model.ListPackagesRequest;
import software.amazon.awssdk.services.codeartifact.model.ListPackagesResponse;
import software.amazon.awssdk.services.codeartifact.model.PackageFormat;
import software.amazon.awssdk.services.codeartifact.model.PackageSummary;
import software.amazon.awssdk.services.codeartifact.model.PackageVersionStatus;
import software.amazon.awssdk.services.codeartifact.model.PackageVersionSummary;

import javax.inject.Named;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;

@Named
@SuppressWarnings("unused")
public class CodeartifactRepositorySetter extends AbstractMavenLifecycleParticipant {
    static final String DOMAIN_PROPERTY = "codeartifact.domain";
    static final String DOMAIN_OWNER_PROPERTY = "codeartifact.domainOwner";
    static final String REPOSITORY_PROPERTY = "codeartifact.repository";
    static final String DURATION_PROPERTY = "codeartifact.durationSeconds";
    static final String PROFILE_PROPERTY = "codeartifact.profile";
    static final String SOURCE_OF_TRUTH_PROPERTY = "codeartifact.sourceOfTruth";
    static final String PRUNE_PROPERTY = "codeartifact.prune";
    static final int DEFAULT_DURATION_SECONDS = 43200;
    static final int MIN_DURATION_SECONDS = 900;
    static final int MAX_DURATION_SECONDS = 43200;
    static final int DELETE_BATCH_SIZE = 100;

    private static final Logger logger = LoggerFactory.getLogger(CodeartifactRepositorySetter.class);

    private CodeartifactClient codeartifactClient;
    private String codeartifactClientProfile;
    private Region codeartifactClientRegion;
    private CodeartifactCacheStore cacheStore;
    private Configuration configuration;

    @Override
    public void afterProjectsRead(final MavenSession session) throws MavenExecutionException {
        configuration = loadConfiguration(session.getCurrentProject().getProperties());

        ArtifactRepository codeartifactRepository;
        try {
            codeartifactRepository = getCodeartifactRepository(configuration);
        } catch (SdkException ex) {
            throw new MavenExecutionException("Failed to configure the CodeArtifact repository.", ex);
        }

        if (configuration.isSourceOfTruth()) {
            session.getCurrentProject().setRemoteArtifactRepositories(List.of(codeartifactRepository));
            session.getCurrentProject().setPluginArtifactRepositories(List.of(codeartifactRepository));
        } else {
            session.getCurrentProject().setRemoteArtifactRepositories(addCodeartifactRepository(
                    session.getCurrentProject().getRemoteArtifactRepositories(), codeartifactRepository));
            session.getCurrentProject().setPluginArtifactRepositories(addCodeartifactRepository(
                    session.getCurrentProject().getPluginArtifactRepositories(), codeartifactRepository));
        }

        session.getCurrentProject().setSnapshotArtifactRepository(codeartifactRepository);
        session.getCurrentProject().setReleaseArtifactRepository(codeartifactRepository);
        if (configuration.isSourceOfTruth()) {
            Mirror mavenCentralMirror = new Mirror();
            mavenCentralMirror.setId("central-mirror");
            mavenCentralMirror.setName("CodeArtifact Maven Central mirror");
            mavenCentralMirror.setUrl(codeartifactRepository.getUrl());
            mavenCentralMirror.setMirrorOf("central");
            session.getRequest().setMirrors(List.of(mavenCentralMirror));
        }
    }

    @Override
    public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
        if (configuration == null || !configuration.isPrune()) {
            return;
        }

        try {
            pruneUnlistedVersions(configuration);
        } catch (SdkException ex) {
            throw new MavenExecutionException("Failed to prune unlisted CodeArtifact package versions.", ex);
        }
    }

    Configuration loadConfiguration(Properties properties) throws MavenExecutionException {
        String domain = requireProperty(properties, DOMAIN_PROPERTY);
        String domainOwner = requireProperty(properties, DOMAIN_OWNER_PROPERTY);
        String repository = requireProperty(properties, REPOSITORY_PROPERTY);
        int durationSeconds = parseDurationSeconds(properties.getProperty(DURATION_PROPERTY));
        String profile = normalize(properties.getProperty(PROFILE_PROPERTY));
        boolean sourceOfTruth = parseSourceOfTruth(properties.getProperty(SOURCE_OF_TRUTH_PROPERTY));
        boolean prune = Boolean.parseBoolean(properties.getProperty(PRUNE_PROPERTY, "false"));
        return new Configuration(domain, domainOwner, durationSeconds, repository, profile, sourceOfTruth, prune);
    }

    boolean parseSourceOfTruth(String sourceOfTruthValue) throws MavenExecutionException {
        String rawSourceOfTruth = normalize(sourceOfTruthValue);
        if (rawSourceOfTruth == null) {
            return true;
        }
        if ("true".equalsIgnoreCase(rawSourceOfTruth)) {
            return true;
        }
        if ("false".equalsIgnoreCase(rawSourceOfTruth)) {
            return false;
        }
        throw new MavenExecutionException("\"" + SOURCE_OF_TRUTH_PROPERTY
                + "\" must be \"true\" or \"false\" but was: \"" + rawSourceOfTruth + "\".", (Throwable) null);
    }

    List<ArtifactRepository> addCodeartifactRepository(
            List<ArtifactRepository> repositories, ArtifactRepository codeartifactRepository) {
        if (repositories == null || repositories.isEmpty()) {
            return List.of(codeartifactRepository);
        }

        List<ArtifactRepository> configuredRepositories = new ArrayList<>(repositories.size() + 1);
        boolean replaced = false;
        for (ArtifactRepository repository : repositories) {
            if (Objects.equals(repository.getId(), codeartifactRepository.getId())) {
                if (!replaced) {
                    configuredRepositories.add(codeartifactRepository);
                    replaced = true;
                }
                continue;
            }
            configuredRepositories.add(repository);
        }
        if (!replaced) {
            configuredRepositories.add(codeartifactRepository);
        }
        return configuredRepositories;
    }

    int parseDurationSeconds(String durationSecondsValue) throws MavenExecutionException {
        String rawDurationSeconds = normalize(durationSecondsValue);
        if (rawDurationSeconds == null) {
            return DEFAULT_DURATION_SECONDS;
        }

        final int durationSeconds;
        try {
            durationSeconds = Integer.parseInt(rawDurationSeconds);
        } catch (NumberFormatException ex) {
            throw new MavenExecutionException("\"" + DURATION_PROPERTY + "\" must be a number but was: \"" + rawDurationSeconds + "\".", ex);
        }

        if (durationSeconds == 0) {
            return durationSeconds;
        }
        if (durationSeconds < MIN_DURATION_SECONDS || durationSeconds > MAX_DURATION_SECONDS) {
            throw new MavenExecutionException("\"" + DURATION_PROPERTY + "\" must be 0 or between "
                    + MIN_DURATION_SECONDS + " and " + MAX_DURATION_SECONDS + " seconds.", (Throwable) null);
        }
        return durationSeconds;
    }

    void pruneUnlistedVersions(Configuration configuration) {
        logger.info("Pruning CodeArtifact repository of unlisted versions...");
        for (PackageSummary packageSummary : listAllPackages(configuration)) {
            List<String> unlistedVersions = listAllUnlistedVersions(configuration, packageSummary);
            if (unlistedVersions.isEmpty()) {
                continue;
            }

            logger.info("Pruning {} unlisted version(s) for package: {}:{}",
                    unlistedVersions.size(), packageSummary.namespace(), packageSummary.packageValue());
            for (List<String> versionBatch : partition(unlistedVersions, DELETE_BATCH_SIZE)) {
                getCodeArtifactClient(configuration.getProfile()).deletePackageVersions(
                        DeletePackageVersionsRequest.builder()
                                .domain(configuration.getDomain())
                                .domainOwner(configuration.getDomainOwner())
                                .format(PackageFormat.MAVEN)
                                .repository(configuration.getRepository())
                                .namespace(packageSummary.namespace())
                                .packageValue(packageSummary.packageValue())
                                .expectedStatus(PackageVersionStatus.UNLISTED)
                                .versions(versionBatch)
                                .build());
            }
        }
    }

    List<PackageSummary> listAllPackages(Configuration configuration) {
        List<PackageSummary> packages = new ArrayList<>();
        String nextToken = null;
        do {
            ListPackagesResponse response = getCodeArtifactClient(configuration.getProfile()).listPackages(
                    ListPackagesRequest.builder()
                            .domain(configuration.getDomain())
                            .domainOwner(configuration.getDomainOwner())
                            .format(PackageFormat.MAVEN)
                            .repository(configuration.getRepository())
                            .nextToken(nextToken)
                            .build());
            packages.addAll(response.packages());
            nextToken = response.nextToken();
        } while (hasNextToken(nextToken));
        return packages;
    }

    List<String> listAllUnlistedVersions(Configuration configuration, PackageSummary packageSummary) {
        List<String> versions = new ArrayList<>();
        String nextToken = null;
        do {
            ListPackageVersionsResponse response = getCodeArtifactClient(configuration.getProfile()).listPackageVersions(
                    ListPackageVersionsRequest.builder()
                            .domain(configuration.getDomain())
                            .domainOwner(configuration.getDomainOwner())
                            .format(PackageFormat.MAVEN)
                            .repository(configuration.getRepository())
                            .namespace(packageSummary.namespace())
                            .packageValue(packageSummary.packageValue())
                            .status(PackageVersionStatus.UNLISTED)
                            .nextToken(nextToken)
                            .build());
            versions.addAll(response.versions().stream().map(PackageVersionSummary::version).collect(Collectors.toList()));
            nextToken = response.nextToken();
        } while (hasNextToken(nextToken));
        return versions;
    }

    public ArtifactRepository getCodeartifactRepository(Configuration configuration) {
        return getCodeartifactRepository(configuration.getProfile(), configuration.getDomain(),
                configuration.getDomainOwner(), configuration.getRepository(), configuration.getDurationSeconds());
    }

    public ArtifactRepository getCodeartifactRepository(
            String profile, String domain, String domainOwner, String repository, int durationSeconds) {
        CodeartifactClient client = getCodeArtifactClient(profile);
        CodeartifactCacheStore.CacheCoordinates cacheCoordinates = CodeartifactCacheStore.coordinates(
                getCodeArtifactRegion(profile).id(), domain, domainOwner, repository, profile);
        CodeartifactCacheStore.CacheEntry cacheEntry = getOrRefreshCacheEntry(
                client, cacheCoordinates, domain, domainOwner, repository, durationSeconds);

        ArtifactRepository codeartifact = new MavenArtifactRepository("codeartifact",
                cacheEntry.repositoryEndpoint(), new DefaultRepositoryLayout(),
                new ArtifactRepositoryPolicy(true,
                        ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS,
                        ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN),
                new ArtifactRepositoryPolicy(true,
                        ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS,
                        ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN));
        codeartifact.setAuthentication(new Authentication("aws", cacheEntry.authorizationToken()));
        return codeartifact;
    }

    CodeartifactClient getCodeArtifactClient(String profile) {
        String normalizedProfile = normalize(profile);
        if (codeartifactClient == null || !Objects.equals(codeartifactClientProfile, normalizedProfile)) {
            closeCodeArtifactClient();
            codeartifactClientRegion = resolveRegion(normalizedProfile);
            codeartifactClient = createCodeArtifactClient(normalizedProfile, codeartifactClientRegion);
            codeartifactClientProfile = normalizedProfile;
        }
        return codeartifactClient;
    }

    CodeartifactClient createCodeArtifactClient(String profile, Region region) {
        return CodeartifactClient.builder()
                .credentialsProvider(getCredentialsProvider(profile))
                .region(region)
                .build();
    }

    AwsCredentialsProvider getCredentialsProvider(String profile) {
        return profile == null
                ? DefaultCredentialsProvider.create()
                : ProfileCredentialsProvider.create(profile);
    }

    Region resolveRegion(String profile) {
        return getRegionProvider(profile).getRegion();
    }

    AwsRegionProvider getRegionProvider(String profile) {
        if (profile == null) {
            return DefaultAwsRegionProviderChain.builder().build();
        }
        return new AwsRegionProviderChain(
                new SystemSettingsRegionProvider(),
                new AwsProfileRegionProvider(ProfileFile::defaultProfileFile, profile),
                new InstanceProfileRegionProvider());
    }

    private String requireProperty(Properties properties, String propertyName) throws MavenExecutionException {
        String value = normalize(properties.getProperty(propertyName));
        if (value == null) {
            throw new MavenExecutionException("\"" + propertyName + "\" must be set in the project <properties> element.", (Throwable) null);
        }
        return value;
    }

    private void closeCodeArtifactClient() {
        if (codeartifactClient != null) {
            codeartifactClient.close();
        }
        codeartifactClient = null;
        codeartifactClientRegion = null;
    }

    private boolean hasNextToken(String nextToken) {
        return nextToken != null && !nextToken.isBlank();
    }

    private List<List<String>> partition(List<String> versions, int batchSize) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < versions.size(); i += batchSize) {
            batches.add(versions.subList(i, Math.min(i + batchSize, versions.size())));
        }
        return batches;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Region getCodeArtifactRegion(String profile) {
        getCodeArtifactClient(profile);
        return codeartifactClientRegion;
    }

    CodeartifactCacheStore getCacheStore() {
        if (cacheStore == null) {
            cacheStore = new CodeartifactCacheStore();
        }
        return cacheStore;
    }

    Clock getCacheStoreClock() {
        return getCacheStore().clock();
    }

    private CodeartifactCacheStore.CacheEntry loadCacheEntry(CodeartifactCacheStore.CacheCoordinates cacheCoordinates) {
        try {
            return getCacheStore().load(cacheCoordinates);
        } catch (IOException ex) {
            logger.warn("Failed to read the CodeArtifact cache. Continuing without cached values.", ex);
            return CodeartifactCacheStore.CacheEntry.empty(cacheCoordinates);
        }
    }

    private CodeartifactCacheStore.CacheEntry getOrRefreshCacheEntry(
            CodeartifactClient client,
            CodeartifactCacheStore.CacheCoordinates cacheCoordinates,
            String domain,
            String domainOwner,
            String repository,
            int durationSeconds) {
        CodeartifactCacheStore.CacheEntry cacheEntry = loadCacheEntry(cacheCoordinates);
        if (cacheEntry.repositoryEndpoint() != null) {
            logger.info("Using cached CodeArtifact repository endpoint: {}", cacheEntry.repositoryEndpoint());
        }
        if (cacheEntry.hasUsableAuthorizationToken(getCacheStoreClock())) {
            logger.info("Using cached CodeArtifact authorization token expiring at {}", cacheEntry.tokenExpiresAt());
        }
        if (cacheEntry.repositoryEndpoint() != null && cacheEntry.hasUsableAuthorizationToken(getCacheStoreClock())) {
            return cacheEntry;
        }

        return refreshCacheEntry(client, cacheCoordinates, domain, domainOwner, repository, durationSeconds);
    }

    private CodeartifactCacheStore.CacheEntry refreshCacheEntry(
            CodeartifactClient client,
            CodeartifactCacheStore.CacheCoordinates cacheCoordinates,
            String domain,
            String domainOwner,
            String repository,
            int durationSeconds) {
        try {
            return getCacheStore().withEntryLock(cacheCoordinates, () -> {
                CodeartifactCacheStore.CacheEntry cacheEntry = loadCacheEntry(cacheCoordinates);

                if (cacheEntry.repositoryEndpoint() == null) {
                    GetRepositoryEndpointResponse getRepositoryEndpointResponse = client.getRepositoryEndpoint(
                            GetRepositoryEndpointRequest.builder()
                                    .domain(domain)
                                    .domainOwner(domainOwner)
                                    .format(PackageFormat.MAVEN)
                                    .repository(repository)
                                    .build());
                    String repositoryEndpoint = getRepositoryEndpointResponse.repositoryEndpoint();
                    logger.info("Fetched CodeArtifact repository endpoint: {}", repositoryEndpoint);
                    cacheEntry = cacheEntry.withRepositoryEndpoint(repositoryEndpoint, Instant.now(getCacheStoreClock()));
                } else {
                    logger.info("Using cached CodeArtifact repository endpoint after lock acquisition: {}",
                            cacheEntry.repositoryEndpoint());
                }

                if (!cacheEntry.hasUsableAuthorizationToken(getCacheStoreClock())) {
                    GetAuthorizationTokenResponse getAuthorizationTokenResponse = client.getAuthorizationToken(
                            GetAuthorizationTokenRequest.builder()
                                    .domain(domain)
                                    .domainOwner(domainOwner)
                                    .durationSeconds((long) durationSeconds)
                                    .build());
                    logger.info("Fetched CodeArtifact authorization token");
                    cacheEntry = cacheEntry.withAuthorizationToken(
                            getAuthorizationTokenResponse.authorizationToken(),
                            resolveTokenExpiration(getAuthorizationTokenResponse, durationSeconds),
                            Instant.now(getCacheStoreClock()));
                } else {
                    logger.info("Using cached CodeArtifact authorization token after lock acquisition expiring at {}",
                            cacheEntry.tokenExpiresAt());
                }

                saveCacheEntry(cacheEntry);
                return cacheEntry;
            });
        } catch (IOException ex) {
            logger.warn("Failed to coordinate the CodeArtifact cache refresh. Falling back to a direct AWS fetch.", ex);
            return refreshCacheEntryWithoutLock(client, cacheCoordinates, domain, domainOwner, repository, durationSeconds);
        }
    }

    private CodeartifactCacheStore.CacheEntry refreshCacheEntryWithoutLock(
            CodeartifactClient client,
            CodeartifactCacheStore.CacheCoordinates cacheCoordinates,
            String domain,
            String domainOwner,
            String repository,
            int durationSeconds) {
        CodeartifactCacheStore.CacheEntry cacheEntry = loadCacheEntry(cacheCoordinates);
        if (cacheEntry.repositoryEndpoint() == null) {
            GetRepositoryEndpointResponse getRepositoryEndpointResponse = client.getRepositoryEndpoint(
                    GetRepositoryEndpointRequest.builder()
                            .domain(domain)
                            .domainOwner(domainOwner)
                            .format(PackageFormat.MAVEN)
                            .repository(repository)
                            .build());
            logger.info("Fetched CodeArtifact repository endpoint without cache lock");
            cacheEntry = cacheEntry.withRepositoryEndpoint(
                    getRepositoryEndpointResponse.repositoryEndpoint(),
                    Instant.now(getCacheStoreClock()));
        }
        if (!cacheEntry.hasUsableAuthorizationToken(getCacheStoreClock())) {
            GetAuthorizationTokenResponse getAuthorizationTokenResponse = client.getAuthorizationToken(
                    GetAuthorizationTokenRequest.builder()
                            .domain(domain)
                            .domainOwner(domainOwner)
                            .durationSeconds((long) durationSeconds)
                            .build());
            logger.info("Fetched CodeArtifact authorization token without cache lock");
            cacheEntry = cacheEntry.withAuthorizationToken(
                    getAuthorizationTokenResponse.authorizationToken(),
                    resolveTokenExpiration(getAuthorizationTokenResponse, durationSeconds),
                    Instant.now(getCacheStoreClock()));
        }
        saveCacheEntry(cacheEntry);
        return cacheEntry;
    }

    private void saveCacheEntry(CodeartifactCacheStore.CacheEntry cacheEntry) {
        try {
            getCacheStore().save(cacheEntry);
        } catch (IOException ex) {
            logger.warn("Failed to update the CodeArtifact cache. Continuing with the in-memory value.", ex);
        }
    }

    private Instant resolveTokenExpiration(GetAuthorizationTokenResponse response, int durationSeconds) {
        if (response.expiration() != null) {
            return response.expiration();
        }
        int effectiveDurationSeconds = durationSeconds == 0 ? DEFAULT_DURATION_SECONDS : durationSeconds;
        return Instant.now(getCacheStoreClock()).plusSeconds(effectiveDurationSeconds);
    }
}
