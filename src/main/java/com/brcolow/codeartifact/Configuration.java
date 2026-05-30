package com.brcolow.codeartifact;

public class Configuration {
    private final String domain;
    private final String domainOwner;
    private final int durationSeconds;
    private final String repository;
    private final String profile;
    private final String region;
    private final boolean sourceOfTruth;
    private final boolean prune;

    public Configuration(String domain, String domainOwner, int durationSeconds, String repository, String profile, boolean prune) {
        this(domain, domainOwner, durationSeconds, repository, profile, null, true, prune);
    }

    public Configuration(
            String domain,
            String domainOwner,
            int durationSeconds,
            String repository,
            String profile,
            boolean sourceOfTruth,
            boolean prune) {
        this(domain, domainOwner, durationSeconds, repository, profile, null, sourceOfTruth, prune);
    }

    public Configuration(
            String domain,
            String domainOwner,
            int durationSeconds,
            String repository,
            String profile,
            String region,
            boolean sourceOfTruth,
            boolean prune) {
        this.domain = domain;
        this.domainOwner = domainOwner;
        this.durationSeconds = durationSeconds;
        this.repository = repository;
        this.profile = profile;
        this.region = region;
        this.sourceOfTruth = sourceOfTruth;
        this.prune = prune;
    }

    public String getDomain() {
        return domain;
    }

    public String getDomainOwner() {
        return domainOwner;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public String getRepository() {
        return repository;
    }

    public String getProfile() {
        return profile;
    }

    public String getRegion() {
        return region;
    }

    public boolean isSourceOfTruth() {
        return sourceOfTruth;
    }

    public boolean isPrune() {
        return prune;
    }

    @Override
    public String toString() {
        return "Configuration{" +
                "domain='" + domain + '\'' +
                ", domainOwner='" + domainOwner + '\'' +
                ", durationSeconds=" + durationSeconds +
                ", repository='" + repository + '\'' +
                ", profile='" + profile + '\'' +
                ", region='" + region + '\'' +
                ", sourceOfTruth='" + sourceOfTruth + '\'' +
                ", prune='" + prune + '\'' +
                '}';
    }
}
