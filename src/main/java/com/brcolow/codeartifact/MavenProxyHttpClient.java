package com.brcolow.codeartifact;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.AuthenticationContext;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.ProxyConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.utils.AttributeMap;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

/** Uses Maven's decrypted proxy credentials and nonProxyHosts rules for AWS HTTP requests. */
final class MavenProxyHttpClient implements SdkHttpClient {
    private final RepositorySystemSession session;
    private final AttributeMap defaults;
    private final Map<Proxy, SdkHttpClient> clients = new HashMap<>();

    private MavenProxyHttpClient(RepositorySystemSession session, AttributeMap defaults) {
        this.session = session;
        this.defaults = defaults;
    }

    @Override
    public synchronized ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
        RemoteRepository repository = new RemoteRepository.Builder(
                "codeartifact-api", "default", request.httpRequest().getUri().toString()).build();
        Proxy proxy = session.getProxySelector().getProxy(repository);
        return clients.computeIfAbsent(proxy, selected -> createClient(repository, selected)).prepareRequest(request);
    }

    private SdkHttpClient createClient(RemoteRepository repository, Proxy proxy) {
        // A null selection means Maven's bypass rules matched. Do not fall back to a JVM/environment proxy.
        ProxyConfiguration.Builder configuration = ProxyConfiguration.builder()
                .useSystemPropertyValues(false).useEnvironmentVariablesValues(false);
        if (proxy != null) {
            try {
                configuration.endpoint(new URI(proxy.getType(), null, proxy.getHost(), proxy.getPort(), null, null, null));
            } catch (URISyntaxException ex) {
                throw SdkClientException.create("Invalid Maven proxy endpoint.", ex);
            }
            RemoteRepository proxied = new RemoteRepository.Builder(repository).setProxy(proxy).build();
            try (AuthenticationContext authentication = AuthenticationContext.forProxy(session, proxied)) {
                if (authentication != null) {
                    configuration.username(authentication.get(AuthenticationContext.USERNAME))
                            .password(authentication.get(AuthenticationContext.PASSWORD));
                }
            }
        }
        return UrlConnectionHttpClient.builder().proxyConfiguration(configuration.build()).buildWithDefaults(defaults);
    }

    @Override
    public synchronized void close() {
        clients.values().forEach(SdkHttpClient::close);
        clients.clear();
    }

    static final class Builder implements SdkHttpClient.Builder<Builder> {
        private final RepositorySystemSession session;

        Builder(RepositorySystemSession session) {
            this.session = session;
        }

        @Override
        public SdkHttpClient buildWithDefaults(AttributeMap defaults) {
            return new MavenProxyHttpClient(session, defaults);
        }
    }
}
