package org.apache.maven.model.building;

import org.apache.maven.model.Model;

public interface InterpolatedModel {

    /**
     * Gets the assembled model.
     * 
     * @return The assembled model, null if interpolation failed
     */
    Model getInterpolatedModel();

    /**
     * Returns the parent models activated and injected into the model prior to interpolating values.
     * 
     * @return
     */
    Iterable<? extends ActivatedModel> getParentModels();

}