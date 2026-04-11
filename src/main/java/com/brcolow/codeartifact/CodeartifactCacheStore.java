package com.brcolow.codeartifact;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

final class CodeartifactCacheStore {
    private static final String CACHE_DIRECTORY_NAME = "codeartifact-maven-extension";
    private static final String CACHE_VERSION_DIRECTORY = "v1";
    private static final String ENTRIES_DIRECTORY = "entries";
    private static final String LOCKS_DIRECTORY = "locks";
    private static final String CACHE_FILE_EXTENSION = ".properties";
    private static final String LOCK_FILE_EXTENSION = ".lock";
    private static final String FILE_FORMAT_VERSION = "1";
    private static final String VERSION_PROPERTY = "version";
    private static final String REGION_PROPERTY = "region";
    private static final String DOMAIN_PROPERTY = "domain";
    private static final String DOMAIN_OWNER_PROPERTY = "domainOwner";
    private static final String REPOSITORY_PROPERTY = "repository";
    private static final String AUTH_MODE_PROPERTY = "authMode";
    private static final String PROFILE_PROPERTY = "profile";
    private static final String REPOSITORY_ENDPOINT_PROPERTY = "repositoryEndpoint";
    private static final String ENDPOINT_CACHED_AT_PROPERTY = "endpointCachedAt";
    private static final String AUTHORIZATION_TOKEN_PROPERTY = "authorizationToken";
    private static final String TOKEN_EXPIRES_AT_PROPERTY = "tokenExpiresAt";
    private static final String TOKEN_CACHED_AT_PROPERTY = "tokenCachedAt";

    static final Duration TOKEN_EXPIRATION_SKEW = Duration.ofMinutes(5);

    private final Path rootDirectory;
    private final Clock clock;

    CodeartifactCacheStore() {
        this(defaultRootDirectory(), Clock.systemUTC());
    }

    CodeartifactCacheStore(Path rootDirectory, Clock clock) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Clock clock() {
        return clock;
    }

    CacheEntry load(CacheCoordinates coordinates) throws IOException {
        Path cacheFile = cacheFile(coordinates);
        if (!Files.exists(cacheFile)) {
            return CacheEntry.empty(coordinates);
        }

        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(cacheFile)) {
            properties.load(inputStream);
        }

        if (!FILE_FORMAT_VERSION.equals(properties.getProperty(VERSION_PROPERTY))) {
            return CacheEntry.empty(coordinates);
        }
        if (!coordinates.matches(properties)) {
            return CacheEntry.empty(coordinates);
        }

        return new CacheEntry(
                coordinates,
                normalize(properties.getProperty(REPOSITORY_ENDPOINT_PROPERTY)),
                parseInstant(properties.getProperty(ENDPOINT_CACHED_AT_PROPERTY)),
                normalize(properties.getProperty(AUTHORIZATION_TOKEN_PROPERTY)),
                parseInstant(properties.getProperty(TOKEN_EXPIRES_AT_PROPERTY)),
                parseInstant(properties.getProperty(TOKEN_CACHED_AT_PROPERTY)));
    }

    void save(CacheEntry cacheEntry) throws IOException {
        Path cacheFile = cacheFile(cacheEntry.coordinates());
        Files.createDirectories(cacheFile.getParent());

        Properties properties = new Properties();
        cacheEntry.writeTo(properties);

        Path tempFile = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        try (OutputStream outputStream = Files.newOutputStream(tempFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            properties.store(outputStream, null);
        }

        applyOwnerOnlyPermissions(tempFile);
        try {
            Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        }
        applyOwnerOnlyPermissions(cacheFile);
    }

    <T> T withEntryLock(CacheCoordinates coordinates, LockedCacheOperation<T> operation) throws IOException {
        Path lockFile = lockFile(coordinates);
        Files.createDirectories(lockFile.getParent());

        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            applyOwnerOnlyPermissions(lockFile);
            return operation.run();
        }
    }

    static CacheCoordinates coordinates(String region, String domain, String domainOwner, String repository, String profile) {
        return new CacheCoordinates(
                normalize(region),
                normalize(domain),
                normalize(domainOwner),
                normalize(repository),
                profile == null ? "default" : "profile",
                normalize(profile));
    }

    private Path cacheFile(CacheCoordinates coordinates) {
        return rootDirectory
                .resolve(CACHE_VERSION_DIRECTORY)
                .resolve(ENTRIES_DIRECTORY)
                .resolve(coordinates.cacheKey() + CACHE_FILE_EXTENSION);
    }

    private Path lockFile(CacheCoordinates coordinates) {
        return rootDirectory
                .resolve(CACHE_VERSION_DIRECTORY)
                .resolve(LOCKS_DIRECTORY)
                .resolve(coordinates.cacheKey() + LOCK_FILE_EXTENSION);
    }

    private void applyOwnerOnlyPermissions(Path path) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Ignore permission adjustments on non-POSIX file systems.
        }
    }

    static Path defaultRootDirectory() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String userHome = System.getProperty("user.home", ".");

        if (osName.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (normalize(localAppData) != null) {
                return Path.of(localAppData, CACHE_DIRECTORY_NAME, "Cache");
            }
        }

        if (osName.contains("mac")) {
            return Path.of(userHome, "Library", "Caches", CACHE_DIRECTORY_NAME);
        }

        String xdgCacheHome = System.getenv("XDG_CACHE_HOME");
        if (normalize(xdgCacheHome) != null) {
            return Path.of(xdgCacheHome, CACHE_DIRECTORY_NAME);
        }

        return Path.of(userHome, ".cache", CACHE_DIRECTORY_NAME);
    }

    private static Instant parseInstant(String value) {
        String normalizedValue = normalize(value);
        return normalizedValue == null ? null : Instant.parse(normalizedValue);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static final class CacheCoordinates {
        private final String region;
        private final String domain;
        private final String domainOwner;
        private final String repository;
        private final String authMode;
        private final String profile;

        private CacheCoordinates(String region, String domain, String domainOwner, String repository, String authMode, String profile) {
            this.region = region;
            this.domain = domain;
            this.domainOwner = domainOwner;
            this.repository = repository;
            this.authMode = authMode;
            this.profile = profile;
        }

        String cacheKey() {
            String rawKey = String.join("|",
                    region,
                    domain,
                    domainOwner,
                    repository,
                    authMode,
                    profile == null ? "" : profile);
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
                StringBuilder builder = new StringBuilder(hash.length * 2);
                for (byte value : hash) {
                    builder.append(String.format("%02x", value));
                }
                return builder.toString();
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 is not available.", ex);
            }
        }

        boolean matches(Properties properties) {
            return Objects.equals(region, normalize(properties.getProperty(REGION_PROPERTY)))
                    && Objects.equals(domain, normalize(properties.getProperty(DOMAIN_PROPERTY)))
                    && Objects.equals(domainOwner, normalize(properties.getProperty(DOMAIN_OWNER_PROPERTY)))
                    && Objects.equals(repository, normalize(properties.getProperty(REPOSITORY_PROPERTY)))
                    && Objects.equals(authMode, normalize(properties.getProperty(AUTH_MODE_PROPERTY)))
                    && Objects.equals(profile, normalize(properties.getProperty(PROFILE_PROPERTY)));
        }
    }

    static final class CacheEntry {
        private final CacheCoordinates coordinates;
        private final String repositoryEndpoint;
        private final Instant endpointCachedAt;
        private final String authorizationToken;
        private final Instant tokenExpiresAt;
        private final Instant tokenCachedAt;

        private CacheEntry(
                CacheCoordinates coordinates,
                String repositoryEndpoint,
                Instant endpointCachedAt,
                String authorizationToken,
                Instant tokenExpiresAt,
                Instant tokenCachedAt) {
            this.coordinates = coordinates;
            this.repositoryEndpoint = repositoryEndpoint;
            this.endpointCachedAt = endpointCachedAt;
            this.authorizationToken = authorizationToken;
            this.tokenExpiresAt = tokenExpiresAt;
            this.tokenCachedAt = tokenCachedAt;
        }

        static CacheEntry empty(CacheCoordinates coordinates) {
            return new CacheEntry(coordinates, null, null, null, null, null);
        }

        CacheCoordinates coordinates() {
            return coordinates;
        }

        String repositoryEndpoint() {
            return repositoryEndpoint;
        }

        String authorizationToken() {
            return authorizationToken;
        }

        Instant tokenExpiresAt() {
            return tokenExpiresAt;
        }

        boolean hasUsableAuthorizationToken(Clock clock) {
            return authorizationToken != null
                    && tokenExpiresAt != null
                    && tokenExpiresAt.minus(TOKEN_EXPIRATION_SKEW).isAfter(clock.instant());
        }

        CacheEntry withRepositoryEndpoint(String value, Instant cachedAt) {
            return new CacheEntry(coordinates, value, cachedAt, authorizationToken, tokenExpiresAt, tokenCachedAt);
        }

        CacheEntry withAuthorizationToken(String value, Instant expiresAt, Instant cachedAt) {
            return new CacheEntry(coordinates, repositoryEndpoint, endpointCachedAt, value, expiresAt, cachedAt);
        }

        private void writeTo(Properties properties) {
            properties.setProperty(VERSION_PROPERTY, FILE_FORMAT_VERSION);
            properties.setProperty(REGION_PROPERTY, coordinates.region);
            properties.setProperty(DOMAIN_PROPERTY, coordinates.domain);
            properties.setProperty(DOMAIN_OWNER_PROPERTY, coordinates.domainOwner);
            properties.setProperty(REPOSITORY_PROPERTY, coordinates.repository);
            properties.setProperty(AUTH_MODE_PROPERTY, coordinates.authMode);
            if (coordinates.profile != null) {
                properties.setProperty(PROFILE_PROPERTY, coordinates.profile);
            }
            if (repositoryEndpoint != null) {
                properties.setProperty(REPOSITORY_ENDPOINT_PROPERTY, repositoryEndpoint);
            }
            if (endpointCachedAt != null) {
                properties.setProperty(ENDPOINT_CACHED_AT_PROPERTY, endpointCachedAt.toString());
            }
            if (authorizationToken != null) {
                properties.setProperty(AUTHORIZATION_TOKEN_PROPERTY, authorizationToken);
            }
            if (tokenExpiresAt != null) {
                properties.setProperty(TOKEN_EXPIRES_AT_PROPERTY, tokenExpiresAt.toString());
            }
            if (tokenCachedAt != null) {
                properties.setProperty(TOKEN_CACHED_AT_PROPERTY, tokenCachedAt.toString());
            }
        }
    }

    @FunctionalInterface
    interface LockedCacheOperation<T> {
        T run() throws IOException;
    }
}
