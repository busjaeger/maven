package org.apache.maven.model.building;

import java.util.List;

import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.profile.ProfileActivationContext;

public interface ActivatedModel {

    /**
     * Gets the assembled model.
     * 
     * @return The assembled model, null if activation failed
     */
    Model getActiveModel();

    ProfileActivationContext getProfileActivationContext();

    /**
     * Gets the profiles from the specified model that were active during model building. The model identifier should be
     * from the collection obtained by {@link #getModelIds()}. As a special case, an empty string can be used as the
     * identifier for the super POM.
     * 
     * @param modelId The identifier of the model whose active profiles should be retrieved, must not be {@code null}.
     * @return The active profiles of the model or an empty list if none or {@code null} if the specified model id does
     *         not refer to a known model.
     */
    List<Profile> getActivePomProfiles();

    /**
     * Gets the external profiles that were active during model building. External profiles are those that were
     * contributed by {@link ModelBuildingRequest#getProfiles()}.
     * 
     * @return The active external profiles or an empty list if none, never {@code null}.
     */
    List<Profile> getActiveExternalProfiles();

}