package org.apache.maven.model.profile;

import java.util.List;
import java.util.Properties;

public class DefaultExternalProfileActivationContext implements ExternalProfileActivationContext {

    private final List<String> activeProfileIds;

    private final List<String> inactiveProfileIds;

    private final Properties systemProperties;

    private final Properties userProperties;

    public DefaultExternalProfileActivationContext(List<String> activeProfileIds, List<String> inactiveProfileIds,
            Properties systemProperties, Properties userProperties) {
        this.activeProfileIds = activeProfileIds;
        this.inactiveProfileIds = inactiveProfileIds;
        this.systemProperties = systemProperties;
        this.userProperties = userProperties;
    }

    @Override
    public List<String> getActiveProfileIds() {
        return activeProfileIds;
    }

    @Override
    public List<String> getInactiveProfileIds() {
        return inactiveProfileIds;
    }

    @Override
    public Properties getSystemProperties() {
        return systemProperties;
    }

    @Override
    public Properties getUserProperties() {
        return userProperties;
    }

}