package org.apache.maven.model.resolution;

import org.apache.maven.model.Model;

public interface WorkspaceResolver {

    Model resolveRawModel(String groupId, String artifactId, String version) throws UnresolvableModelException;

    Model resolveEffectiveModel(String groupId, String artifactId, String version) throws UnresolvableModelException;

}