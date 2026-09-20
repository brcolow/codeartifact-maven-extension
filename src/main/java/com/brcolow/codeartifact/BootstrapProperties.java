package com.brcolow.codeartifact;

import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.interpolation.ModelInterpolator;
import org.apache.maven.model.io.ModelReader;
import org.apache.maven.model.profile.DefaultProfileActivationContext;
import org.apache.maven.model.profile.ProfileSelector;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

/** Reads locally available configuration without resolving a parent POM or an imported BOM. */
final class BootstrapProperties {
    static Properties load(MavenSession session) throws MavenExecutionException {
        try {
            return load(session.getRequest(),
                    session.getContainer().lookup(ModelReader.class),
                    session.getContainer().lookup(ProfileSelector.class),
                    session.getContainer().lookup(ModelInterpolator.class));
        } catch (ComponentLookupException | IOException ex) {
            throw new MavenExecutionException("Failed to read the CodeArtifact bootstrap configuration.", ex);
        }
    }

    static Properties load(MavenExecutionRequest request, ModelReader reader,
                           ProfileSelector selector, ModelInterpolator interpolator) throws IOException {
        File pom = request.getPom();
        Model model = pom != null && pom.isFile() ? reader.read(pom, Map.of()) : new Model();
        model.setPomFile(pom);
        File directory = pom == null ? null : pom.getAbsoluteFile().getParentFile();
        DefaultProfileActivationContext activation = new DefaultProfileActivationContext()
                .setActiveProfileIds(request.getActiveProfiles())
                .setInactiveProfileIds(request.getInactiveProfiles())
                .setSystemProperties(request.getSystemProperties())
                .setUserProperties(request.getUserProperties())
                .setProjectProperties(model.getProperties())
                .setProjectDirectory(directory);
        // The normal model build reports model/profile errors. Bootstrap only uses properties it can resolve locally.
        ModelProblemCollector problems = problem -> { };
        for (Profile profile : selector.getActiveProfiles(model.getProfiles(), activation, problems)) {
            model.getProperties().putAll(profile.getProperties());
        }
        for (Profile profile : selector.getActiveProfiles(request.getProfiles(), activation, problems)) {
            model.getProperties().putAll(profile.getProperties());
        }
        model = interpolator.interpolateModel(model, directory, new DefaultModelBuildingRequest()
                .setSystemProperties(request.getSystemProperties())
                .setUserProperties(request.getUserProperties()), problems);
        Properties properties = new Properties();
        properties.putAll(model.getProperties());
        properties.putAll(request.getSystemProperties());
        properties.putAll(request.getUserProperties());
        return properties;
    }
}
