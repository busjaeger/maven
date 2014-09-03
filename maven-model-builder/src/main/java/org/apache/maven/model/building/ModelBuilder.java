package org.apache.maven.model.building;

import java.util.Date;
import java.util.List;
import java.util.Properties;

import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.profile.ExternalProfileActivationContext;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by
 * applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

/**
 * Builds the effective model from a POM.
 * 
 * @author Benjamin Bentmann
 */
public interface ModelBuilder {

    /**
     * Builds the effective model of the specified POM.
     * 
     * @param request
     *            The model building request that holds the parameters, must not be {@code null}.
     * @return The result of the model building, never {@code null}.
     * @throws ModelBuildingException
     *             If the effective model could not be built.
     */
    ModelBuildingResult build(ModelBuildingRequest request) throws ModelBuildingException;

    /**
     * Builds the effective model by completing the specified interim result which was produced by a previous call to
     * {@link #build(ModelBuildingRequest)} with {@link ModelBuildingRequest#isTwoPhaseBuilding()} being {@code true}.
     * The model building request passed to this method must be the same as the one used for the first phase of the
     * model building.
     * 
     * @param request
     *            The model building request that holds the parameters, must not be {@code null}.
     * @param result
     *            The interim result of the first phase of model building, must not be {@code null}.
     * @return The result of the model building, never {@code null}.
     * @throws ModelBuildingException
     *             If the effective model could not be built.
     */
    ModelBuildingResult build(ModelBuildingRequest request, ModelBuildingResult result) throws ModelBuildingException;

    // New methods below break down methods above further for new project graph loader

    /**
     * Loads model from the given model source. To load from file create a {@link FileModelSource}. If the model is free
     * of syntax errors, model rules are validated at the given {@code validationLevel}.
     * 
     * @param source
     *            Source from which to load the model
     * @param validationLevel
     *            level of validation to apply
     * @param locationTracking
     *            if true, line and column number for model problems will be collected
     * @return
     */
    Result<Model> load(ModelSource source, int validationLevel, boolean locationTracking);

    /**
     * Activates external and project profiles for the given model
     *
     * @param model
     * @param externalProfiles
     * @param externalContext
     * @return
     */
    Result<ActivatedModel> activate(Model model, List<Profile> externalProfiles,
            ExternalProfileActivationContext externalContext);

    interface Parents {

        void traverse(Visitor v);

        interface Visitor {
            ActivatedModel visit(Result<? extends Model> parent);
        }

    }

    /**
     * assembles parent hierarchy and interpolates
     *
     * TODO does too much (too many parameters)
     *
     * @param activatedModel
     * @param parentModels
     * @param validationLevel
     * @param buildStartTime
     * @return
     */
    Result<InterpolatedModel> interpolate(ActivatedModel activatedModel, Parents parents, int validationLevel,
            Date buildStartTime, Properties systemProperties, Properties userProperties);

    interface Imports {
        
    }

    Result<Model> enable(InterpolatedModel model, int validationLevel, boolean processPlugins,
            ModelBuildingListener listener);

}