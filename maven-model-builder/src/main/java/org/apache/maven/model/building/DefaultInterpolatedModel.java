package org.apache.maven.model.building;

import org.apache.maven.model.Model;

class DefaultInterpolatedModel implements InterpolatedModel {

    private final Model interpolatedModel;
    private final Iterable<? extends ActivatedModel> parentModels;

    public DefaultInterpolatedModel(Model interpolatedModel, Iterable<? extends ActivatedModel> parentModels) {
        this.interpolatedModel = interpolatedModel;
        this.parentModels = parentModels;
    }

    public Model getInterpolatedModel() {
        return interpolatedModel;
    }

    public Iterable<? extends ActivatedModel> getParentModels() {
        return parentModels;
    }

}
