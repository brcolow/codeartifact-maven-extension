package com.brcolow.codeartifact;

public class Configuration {
    private final String domain;
    private final String domainOwner;
    private final int durationSeconds;
    private final String repository;
    private final String profile;

    public Configuration(String domain, String domainOwner, int durationSeconds, String repository, String profile) {
        this.domain = domain;
        this.domainOwner = domainOwner;
        this.durationSeconds = durationSeconds;
        this.repository = repository;
        this.profile = profile;
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

    @Override
    public String toString() {
        return "Configuration{" +
                "domain='" + domain + '\'' +
                ", domainOwner='" + domainOwner + '\'' +
                ", durationSeconds=" + durationSeconds +
                ", repository='" + repository + '\'' +
                ", profile='" + profile + '\'' +
                '}';
    }
}
