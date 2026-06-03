package com.brcolow.codeartifact;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.Authentication;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Mirror;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
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

/**
 * Maven lifecycle participant that configures project repositories for AWS CodeArtifact.
 */
@Named
@SuppressWarnings({"deprecation", "unused"})
public class CodeartifactRepositorySetter extends AbstractMavenLifecycleParticipant {
    static final String DOMAIN_PROPERTY = "codeartifact.domain";
    static final String DOMAIN_OWNER_PROPERTY = "codeartifact.domainOwner";
    static final String REPOSITORY_PROPERTY = "codeartifact.repository";
    static final String DURATION_PROPERTY = "codeartifact.durationSeconds";
    static final String PROFILE_PROPERTY = "codeartifact.profile";
    static final String REGION_PROPERTY = "codeartifact.region";
    static final String SOURCE_OF_TRUTH_PROPERTY = "codeartifact.sourceOfTruth";
    static final String PRUNE_PROPERTY = "codeartifact.prune";
    static final String CACHE_ENABLED_PROPERTY = "codeartifact.cache.enabled";
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

    /**
     * Creates the CodeArtifact repository lifecycle participant.
     */
    public CodeartifactRepositorySetter() {
    }

    @Override
    public void afterProjectsRead(final MavenSession session) throws MavenExecutionException {
        configuration = loadConfiguration(effectiveProperties(session));

        ArtifactRepository codeartifactRepository;
        try {
            codeartifactRepository = getCodeartifactRepository(configuration);
        } catch (SdkException ex) {
            throw new MavenExecutionException("Failed to configure the CodeArtifact repository.", ex);
        }

        for (MavenProject project : session.getProjects()) {
            configureProjectRepositories(project, codeartifactRepository, configuration);
        }

        if (configuration.isSourceOfTruth()) {
            session.getRequest().setMirrors(addOrReplaceMavenCentralMirror(
                    session.getRequest().getMirrors(), codeartifactRepository));
        }
    }

    @Override
    public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
        try {
            if (configuration != null && configuration.isPrune()) {
                pruneUnlistedVersions(configuration);
            }
        } catch (SdkException ex) {
            throw new MavenExecutionException("Failed to prune unlisted CodeArtifact package versions.", ex);
        } finally {
            closeCodeArtifactClient();
        }
    }

    Configuration loadConfiguration(Properties properties) throws MavenExecutionException {
        String domain = requireProperty(properties, DOMAIN_PROPERTY);
        String domainOwner = requireProperty(properties, DOMAIN_OWNER_PROPERTY);
        String repository = requireProperty(properties, REPOSITORY_PROPERTY);
        int durationSeconds = parseDurationSeconds(properties.getProperty(DURATION_PROPERTY));
        String profile = normalize(properties.getProperty(PROFILE_PROPERTY));
        String region = normalize(properties.getProperty(REGION_PROPERTY));
        boolean sourceOfTruth = parseSourceOfTruth(properties.getProperty(SOURCE_OF_TRUTH_PROPERTY));
        boolean prune = parseBooleanProperty(PRUNE_PROPERTY, properties.getProperty(PRUNE_PROPERTY), false);
        boolean cacheEnabled = parseBooleanProperty(CACHE_ENABLED_PROPERTY, properties.getProperty(CACHE_ENABLED_PROPERTY), true);
        return new Configuration(
                domain, domainOwner, durationSeconds, repository, profile, region, sourceOfTruth, prune, cacheEnabled);
    }

    Properties effectiveProperties(MavenSession session) {
        return effectiveProperties(
                session.getCurrentProject().getProperties(),
                session.getSystemProperties(),
                session.getUserProperties());
    }

    Properties effectiveProperties(Properties projectProperties, Properties systemProperties, Properties userProperties) {
        Properties properties = new Properties();
        properties.putAll(projectProperties);
        properties.putAll(systemProperties);
        properties.putAll(userProperties);
        return properties;
    }

    boolean parseSourceOfTruth(String sourceOfTruthValue) throws MavenExecutionException {
        return parseBooleanProperty(SOURCE_OF_TRUTH_PROPERTY, sourceOfTruthValue, true);
    }

    boolean parseBooleanProperty(String propertyName, String propertyValue, boolean defaultValue)
            throws MavenExecutionException {
        String rawValue = normalize(propertyValue);
        if (rawValue == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(rawValue)) {
            return true;
        }
        if ("false".equalsIgnoreCase(rawValue)) {
            return false;
        }
        throw new MavenExecutionException("\"" + propertyName
                + "\" must be \"true\" or \"false\" but was: \"" + rawValue + "\".", (Throwable) null);
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

    void configureProjectRepositories(
            MavenProject project, ArtifactRepository codeartifactRepository, Configuration configuration) {
        if (configuration.isSourceOfTruth()) {
            project.setRemoteArtifactRepositories(List.of(codeartifactRepository));
            project.setPluginArtifactRepositories(List.of(codeartifactRepository));
        } else {
            project.setRemoteArtifactRepositories(addCodeartifactRepository(
                    project.getRemoteArtifactRepositories(), codeartifactRepository));
            project.setPluginArtifactRepositories(addCodeartifactRepository(
                    project.getPluginArtifactRepositories(), codeartifactRepository));
        }

        project.setSnapshotArtifactRepository(codeartifactRepository);
        project.setReleaseArtifactRepository(codeartifactRepository);
    }

    List<Mirror> addOrReplaceMavenCentralMirror(List<Mirror> mirrors, ArtifactRepository codeartifactRepository) {
        Mirror codeartifactMirror = mavenCentralMirror(codeartifactRepository);
        if (mirrors == null || mirrors.isEmpty()) {
            return List.of(codeartifactMirror);
        }

        List<Mirror> configuredMirrors = new ArrayList<>(mirrors.size() + 1);
        boolean replaced = false;
        for (Mirror mirror : mirrors) {
            if (isMavenCentralMirror(mirror)) {
                if (!replaced) {
                    configuredMirrors.add(codeartifactMirror);
                    replaced = true;
                }
                continue;
            }
            configuredMirrors.add(mirror);
        }
        if (!replaced) {
            configuredMirrors.add(codeartifactMirror);
        }
        return configuredMirrors;
    }

    private Mirror mavenCentralMirror(ArtifactRepository codeartifactRepository) {
        Mirror mavenCentralMirror = new Mirror();
        mavenCentralMirror.setId("central-mirror");
        mavenCentralMirror.setName("CodeArtifact Maven Central mirror");
        mavenCentralMirror.setUrl(codeartifactRepository.getUrl());
        mavenCentralMirror.setMirrorOf("central");
        return mavenCentralMirror;
    }

    private boolean isMavenCentralMirror(Mirror mirror) {
        return mirror != null
                && (Objects.equals(mirror.getId(), "central-mirror")
                || Objects.equals(mirror.getMirrorOf(), "central"));
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
                getCodeArtifactClient(configuration).deletePackageVersions(
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
            ListPackagesResponse response = getCodeArtifactClient(configuration).listPackages(
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
            ListPackageVersionsResponse response = getCodeArtifactClient(configuration).listPackageVersions(
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

    ArtifactRepository getCodeartifactRepository(Configuration configuration) {
        return getCodeartifactRepository(configuration.getProfile(), configuration.getRegion(), configuration.getDomain(),
                configuration.getDomainOwner(), configuration.getRepository(), configuration.getDurationSeconds(),
                configuration.isCacheEnabled());
    }

    ArtifactRepository getCodeartifactRepository(
            String profile, String domain, String domainOwner, String repository, int durationSeconds) {
        return getCodeartifactRepository(profile, null, domain, domainOwner, repository, durationSeconds, true);
    }

    ArtifactRepository getCodeartifactRepository(
            String profile, String region, String domain, String domainOwner, String repository, int durationSeconds) {
        return getCodeartifactRepository(profile, region, domain, domainOwner, repository, durationSeconds, true);
    }

    ArtifactRepository getCodeartifactRepository(
            String profile,
            String region,
            String domain,
            String domainOwner,
            String repository,
            int durationSeconds,
            boolean cacheEnabled) {
        CodeartifactClient client = getCodeArtifactClient(profile, region);
        CodeartifactCacheStore.CacheCoordinates cacheCoordinates = CodeartifactCacheStore.coordinates(
                getCodeArtifactRegion(profile, region).id(), domain, domainOwner, repository, profile);
        CodeartifactCacheStore.CacheEntry cacheEntry = cacheEnabled
                ? getOrRefreshCacheEntry(client, cacheCoordinates, domain, domainOwner, repository, durationSeconds)
                : fetchUncachedEntry(client, cacheCoordinates, domain, domainOwner, repository, durationSeconds);

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
        return getCodeArtifactClient(profile, null);
    }

    CodeartifactClient getCodeArtifactClient(Configuration configuration) {
        return configuration.getRegion() == null
                ? getCodeArtifactClient(configuration.getProfile())
                : getCodeArtifactClient(configuration.getProfile(), configuration.getRegion());
    }

    CodeartifactClient getCodeArtifactClient(String profile, String region) {
        String normalizedProfile = normalize(profile);
        Region resolvedRegion = resolveRegion(normalizedProfile, normalize(region));
        if (codeartifactClient == null
                || !Objects.equals(codeartifactClientProfile, normalizedProfile)
                || !Objects.equals(codeartifactClientRegion, resolvedRegion)) {
            closeCodeArtifactClient();
            codeartifactClientRegion = resolvedRegion;
            codeartifactClient = createCodeArtifactClient(normalizedProfile, resolvedRegion);
            codeartifactClientProfile = normalizedProfile;
        }
        return codeartifactClient;
    }

    CodeartifactClient createCodeArtifactClient(String profile, Region region) {
        return CodeartifactClient.builder()
                .credentialsProvider(getCredentialsProvider(profile))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(region)
                .build();
    }

    AwsCredentialsProvider getCredentialsProvider(String profile) {
        return profile == null
                ? DefaultCredentialsProvider.create()
                : ProfileCredentialsProvider.create(profile);
    }

    Region resolveRegion(String profile) {
        return resolveRegion(profile, null);
    }

    Region resolveRegion(String profile, String region) {
        String normalizedRegion = normalize(region);
        if (normalizedRegion != null) {
            return Region.of(normalizedRegion);
        }
        return getRegionProvider(profile).getRegion();
    }

    AwsRegionProvider getRegionProvider(String profile) {
        if (profile == null) {
            return DefaultAwsRegionProviderChain.builder().build();
        }
        return new AwsRegionProviderChain(
                new SystemSettingsRegionProvider(),
                new AwsProfileRegionProvider(ProfileFile::defaultProfileFile, profile),
                new AwsProfileRegionProvider(ProfileFile::defaultProfileFile, "default"),
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
        return getCodeArtifactRegion(profile, null);
    }

    private Region getCodeArtifactRegion(String profile, String region) {
        getCodeArtifactClient(profile, region);
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

    private CodeartifactCacheStore.CacheEntry fetchUncachedEntry(
            CodeartifactClient client,
            CodeartifactCacheStore.CacheCoordinates cacheCoordinates,
            String domain,
            String domainOwner,
            String repository,
            int durationSeconds) {
        GetRepositoryEndpointResponse getRepositoryEndpointResponse = client.getRepositoryEndpoint(
                GetRepositoryEndpointRequest.builder()
                        .domain(domain)
                        .domainOwner(domainOwner)
                        .format(PackageFormat.MAVEN)
                        .repository(repository)
                        .build());
        GetAuthorizationTokenResponse getAuthorizationTokenResponse = client.getAuthorizationToken(
                GetAuthorizationTokenRequest.builder()
                        .domain(domain)
                        .domainOwner(domainOwner)
                        .durationSeconds((long) durationSeconds)
                        .build());
        logger.info("Fetched uncached CodeArtifact repository endpoint and authorization token");
        return CodeartifactCacheStore.CacheEntry.empty(cacheCoordinates)
                .withRepositoryEndpoint(getRepositoryEndpointResponse.repositoryEndpoint(), Instant.now(getCacheStoreClock()))
                .withAuthorizationToken(
                        getAuthorizationTokenResponse.authorizationToken(),
                        resolveTokenExpiration(getAuthorizationTokenResponse, durationSeconds),
                        Instant.now(getCacheStoreClock()));
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
