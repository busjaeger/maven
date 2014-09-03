package org.apache.maven.model.building;

import java.util.List;

import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.profile.ProfileActivationContext;

import com.google.common.base.Function;

class DefaultActivatedModel implements ActivatedModel {

    static final Function<ActivatedModel, Model> getActiveModel = new Function<ActivatedModel, Model>() {
        @Override
        public Model apply(ActivatedModel input) {
            return input.getActiveModel();
        }
    };

    private final Model activeModel;
    private final List<Profile> activePomProfiles;
    private final List<Profile> activeExternalProfiles;
    private final ProfileActivationContext profileActivationContext;

    public DefaultActivatedModel(Model activeModel, List<Profile> activePomProfiles,
            List<Profile> activeExternalProfiles, ProfileActivationContext activeContext) {
        super();
        this.activeModel = activeModel;
        this.activePomProfiles = activePomProfiles;
        this.activeExternalProfiles = activeExternalProfiles;
        this.profileActivationContext = activeContext;
    }

    @Override
    public Model getActiveModel() {
        return activeModel;
    }

    @Override
    public List<Profile> getActivePomProfiles() {
        return activePomProfiles;
    }

    @Override
    public List<Profile> getActiveExternalProfiles() {
        return activeExternalProfiles;
    }

    @Override
    public ProfileActivationContext getProfileActivationContext() {
        return profileActivationContext;
    }

}
