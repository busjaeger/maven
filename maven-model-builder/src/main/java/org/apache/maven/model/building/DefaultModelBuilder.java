package org.apache.maven.model.building;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import static com.google.common.collect.Iterables.addAll;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Lists.newArrayList;
import static org.apache.maven.model.building.DefaultActivatedModel.getActiveModel;
import static org.apache.maven.model.building.Result.error;
import static org.apache.maven.model.building.Result.newResult;
import static org.apache.maven.model.building.Result.success;
import static org.apache.maven.model.profile.DefaultProfileActivationContext.pac;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.model.Activation;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.ModelProblem.Severity;
import org.apache.maven.model.building.ModelProblem.Version;
import org.apache.maven.model.composition.DependencyManagementImporter;
import org.apache.maven.model.inheritance.InheritanceAssembler;
import org.apache.maven.model.interpolation.ModelInterpolator;
import org.apache.maven.model.io.ModelParseException;
import org.apache.maven.model.management.DependencyManagementInjector;
import org.apache.maven.model.management.PluginManagementInjector;
import org.apache.maven.model.normalization.ModelNormalizer;
import org.apache.maven.model.path.ModelPathTranslator;
import org.apache.maven.model.path.ModelUrlNormalizer;
import org.apache.maven.model.plugin.LifecycleBindingsInjector;
import org.apache.maven.model.plugin.PluginConfigurationExpander;
import org.apache.maven.model.plugin.ReportConfigurationExpander;
import org.apache.maven.model.plugin.ReportingConverter;
import org.apache.maven.model.profile.DefaultExternalProfileActivationContext;
import org.apache.maven.model.profile.ExternalProfileActivationContext;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.apache.maven.model.profile.ProfileInjector;
import org.apache.maven.model.profile.ProfileSelector;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.model.superpom.SuperPomProvider;
import org.apache.maven.model.validation.ModelValidator;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;

import com.google.common.collect.Lists;

/**
 * @author Benjamin Bentmann
 */
@Component( role = ModelBuilder.class )
public class DefaultModelBuilder
    implements ModelBuilder
{
    @Requirement
    private ModelProcessor modelProcessor;

    @Requirement
    private ModelValidator modelValidator;

    @Requirement
    private ModelNormalizer modelNormalizer;

    @Requirement
    private ModelInterpolator modelInterpolator;

    @Requirement
    private ModelPathTranslator modelPathTranslator;

    @Requirement
    private ModelUrlNormalizer modelUrlNormalizer;

    @Requirement
    private SuperPomProvider superPomProvider;

    @Requirement
    private InheritanceAssembler inheritanceAssembler;

    @Requirement
    private ProfileSelector profileSelector;

    @Requirement
    private ProfileInjector profileInjector;

    @Requirement
    private PluginManagementInjector pluginManagementInjector;

    @Requirement
    private DependencyManagementInjector dependencyManagementInjector;

    @Requirement
    private DependencyManagementImporter dependencyManagementImporter;

    @Requirement( optional = true )
    private LifecycleBindingsInjector lifecycleBindingsInjector;

    @Requirement
    private PluginConfigurationExpander pluginConfigurationExpander;

    @Requirement
    private ReportConfigurationExpander reportConfigurationExpander;

    @Requirement
    private ReportingConverter reportingConverter;

    public DefaultModelBuilder setModelProcessor( ModelProcessor modelProcessor )
    {
        this.modelProcessor = modelProcessor;
        return this;
    }

    public DefaultModelBuilder setModelValidator( ModelValidator modelValidator )
    {
        this.modelValidator = modelValidator;
        return this;
    }

    public DefaultModelBuilder setModelNormalizer( ModelNormalizer modelNormalizer )
    {
        this.modelNormalizer = modelNormalizer;
        return this;
    }

    public DefaultModelBuilder setModelInterpolator( ModelInterpolator modelInterpolator )
    {
        this.modelInterpolator = modelInterpolator;
        return this;
    }

    public DefaultModelBuilder setModelPathTranslator( ModelPathTranslator modelPathTranslator )
    {
        this.modelPathTranslator = modelPathTranslator;
        return this;
    }

    public DefaultModelBuilder setModelUrlNormalizer( ModelUrlNormalizer modelUrlNormalizer )
    {
        this.modelUrlNormalizer = modelUrlNormalizer;
        return this;
    }

    public DefaultModelBuilder setSuperPomProvider( SuperPomProvider superPomProvider )
    {
        this.superPomProvider = superPomProvider;
        return this;
    }

    public DefaultModelBuilder setProfileSelector( ProfileSelector profileSelector )
    {
        this.profileSelector = profileSelector;
        return this;
    }

    public DefaultModelBuilder setProfileInjector( ProfileInjector profileInjector )
    {
        this.profileInjector = profileInjector;
        return this;
    }

    public DefaultModelBuilder setInheritanceAssembler( InheritanceAssembler inheritanceAssembler )
    {
        this.inheritanceAssembler = inheritanceAssembler;
        return this;
    }

    public DefaultModelBuilder setDependencyManagementImporter( DependencyManagementImporter depMngmntImporter )
    {
        this.dependencyManagementImporter = depMngmntImporter;
        return this;
    }

    public DefaultModelBuilder setDependencyManagementInjector( DependencyManagementInjector depMngmntInjector )
    {
        this.dependencyManagementInjector = depMngmntInjector;
        return this;
    }

    public DefaultModelBuilder setLifecycleBindingsInjector( LifecycleBindingsInjector lifecycleBindingsInjector )
    {
        this.lifecycleBindingsInjector = lifecycleBindingsInjector;
        return this;
    }

    public DefaultModelBuilder setPluginConfigurationExpander( PluginConfigurationExpander pluginConfigurationExpander )
    {
        this.pluginConfigurationExpander = pluginConfigurationExpander;
        return this;
    }

    public DefaultModelBuilder setPluginManagementInjector( PluginManagementInjector pluginManagementInjector )
    {
        this.pluginManagementInjector = pluginManagementInjector;
        return this;
    }

    public DefaultModelBuilder setReportConfigurationExpander( ReportConfigurationExpander reportConfigurationExpander )
    {
        this.reportConfigurationExpander = reportConfigurationExpander;
        return this;
    }

    public DefaultModelBuilder setReportingConverter( ReportingConverter reportingConverter )
    {
        this.reportingConverter = reportingConverter;
        return this;
    }

    public ModelBuildingResult build(final ModelBuildingRequest request) throws ModelBuildingException {
        final DefaultModelBuildingResult result = new DefaultModelBuildingResult();
        final DefaultModelProblemCollector problems = new DefaultModelProblemCollector(result);

        // load
        final ModelSource source = request.getModelSource() == null ? new FileModelSource(request.getPomFile())
                : request.getModelSource();
        final Result<Model> loaded = load(source, request.getValidationLevel(), request.isLocationTracking());
        final Model rawModel = loaded.get();

        // activate
        final ExternalProfileActivationContext epac = new DefaultExternalProfileActivationContext(
                request.getActiveProfileIds(), request.getInactiveProfileIds(), request.getSystemProperties(),
                request.getUserProperties());
        final Result<ActivatedModel> activated = activate(rawModel.clone(), request.getProfiles(), epac);
        final ActivatedModel activatedModel = activated.get();

        // assemble and interpolate
        final ModelResolver resolver = request.getModelResolver();
        configureResolver(resolver, activatedModel.getActiveModel(), problems);
        final Parents parents = new Parents() {
            @Override
            public void traverse(Visitor v) {
                Model current = activatedModel.getActiveModel();
                ModelSource currentSource = source;
                while (current.getParent() != null) {
                    final Result<ModelData> pr = readParent(current, currentSource, request);
                    final ModelData md = pr.get();
                    current = md == null ? null : md.getModel();
                    currentSource = md == null ? null : md.getSource();
                    final ActivatedModel activatedModel = v.visit(newResult(pr.hasErrors(), current, pr.getProblems()));
                    final Model activeModel = activatedModel.getActiveModel();
                    if (activeModel == null) return;
                    configureResolver(resolver, activeModel, problems);
                }
                final ActivatedModel m = v.visit(success(getSuperModel()));
                configureResolver(resolver, m.getActiveModel(), problems);
            }
        };
        final Result<InterpolatedModel> model = interpolate(activatedModel, parents, request.getValidationLevel(),
                request.getBuildStartTime(), request.getSystemProperties(), request.getUserProperties());

        result.setEffectiveModel(model.get().getInterpolatedModel());
        result.setActiveExternalProfiles(activatedModel.getActiveExternalProfiles());

        if (!request.isTwoPhaseBuilding()) return result;

        throw new UnsupportedOperationException("can't do two phase yet");
    }

    public ModelBuildingResult build( ModelBuildingRequest request, ModelBuildingResult result )
        throws ModelBuildingException
    {
        return build( request, result, new LinkedHashSet<String>() );
    }

    private ModelBuildingResult build( ModelBuildingRequest request, ModelBuildingResult result,
                                       Collection<String> imports )
        throws ModelBuildingException
    {
        DefaultModelProblemCollector problems = new DefaultModelProblemCollector(new DefaultModelBuildingResult());

        final InterpolatedModel model = new DefaultInterpolatedModel(result.getEffectiveModel(), null);// TODO
        final Result<Model> effective = enable(model, request.getValidationLevel(), request.isProcessPlugins(),
                request.getModelBuildingListener());

        problems.addAll(effective.getProblems());
        if (effective.hasErrors())
            problems.newModelBuildingException();

        return result;
    }

    @Override
    public Result<Model> enable(InterpolatedModel interpolatedModel, int validationLevel, boolean processPlugins, ModelBuildingListener listener) {
        Model effectiveModel = interpolatedModel.getInterpolatedModel();

        DefaultModelProblemCollector problems = new DefaultModelProblemCollector(new DefaultModelBuildingResult());
        problems.setSource(effectiveModel);
        problems.setRootModel(effectiveModel);

        modelPathTranslator.alignToBaseDirectory(effectiveModel, effectiveModel.getProjectDirectory());
        pluginManagementInjector.injectManagement(effectiveModel);

        try {
            fireEvent(effectiveModel, new DefaultModelBuildingRequest().setProcessPlugins(processPlugins)
                    .setModelBuildingListener(listener), problems,
                    ModelBuildingEventCatapult.BUILD_EXTENSIONS_ASSEMBLED);
        } catch (ModelBuildingException e) {
            e.printStackTrace();
        }

        if (processPlugins) {
            if ( lifecycleBindingsInjector == null )
                throw new IllegalStateException( "lifecycle bindings injector is missing" );
            lifecycleBindingsInjector.injectLifecycleBindings( effectiveModel, problems );
        }

        // TODO resolve DM
        

        dependencyManagementInjector.injectManagement(effectiveModel);
        modelNormalizer.injectDefaultValues(effectiveModel);
        if (processPlugins) {
            reportConfigurationExpander.expandPluginConfiguration(effectiveModel);
            reportingConverter.convertReporting(effectiveModel);
            pluginConfigurationExpander.expandPluginConfiguration(effectiveModel);
        }

        modelValidator.validateEffectiveModel(effectiveModel, validationLevel, problems);

        return newResult(effectiveModel, problems.getProblems());
    }

    @Override
    public Result<InterpolatedModel> interpolate(ActivatedModel activatedModel, Parents parents, int validationLevel,
            Date buildStartTime, Properties systemProperties, Properties userProperties) {
        final DefaultModelProblemCollector collector = new DefaultModelProblemCollector(
                new DefaultModelBuildingResult());

        // activate parent profiles using this project's activation context, i.e. base directory and properties
        final ProfileActivationContext pac = activatedModel.getProfileActivationContext();
        
        final Result<? extends Iterable<? extends ActivatedModel>> prs = parents.traverse(new Parents.Visitor() {
            @Override
            public Result<ActivatedModel> visit(Result<? extends Model> parent) {
                if (parent.hasErrors()) return error(parent.getProblems());
                // Note: not calling {@link activate} here, because we're not activating in parent's context
                final Model rawParent = parent.get();
                final Model activeParent = rawParent.clone();
                final ProfileActivationContext ppac = pac(pac, activeParent.getProperties());
                final DefaultModelProblemCollector collector = new DefaultModelProblemCollector(
                        new DefaultModelBuildingResult());
                final List<Profile> activeProfiles = activatePomProfiles(rawParent, activeParent, ppac, collector);
                return Result.<ActivatedModel> newResult(new DefaultActivatedModel(activeParent, activeProfiles,
                        Collections.<Profile> emptyList(), pac), collector.getProblems());
            }
        });
        if (collector.hasErrors()) collector.newModelBuildingException();
        final Iterable<? extends ActivatedModel> lineage = prs.get();

        // merge parent models into activated model
        final Model activeModel = activatedModel.getActiveModel();
        collector.setSource(activeModel);
        checkPluginVersions(lineage, validationLevel, collector);
        final Model interpolated = assembleInheritance(activatedModel, lineage, collector);

        // interpolate property values
        collector.setSource(interpolated);
        collector.setRootModel(interpolated);
        interpolateModel(interpolated, validationLevel, userProperties, systemProperties, buildStartTime, collector);
        modelUrlNormalizer.normalize(interpolated);

        return Result.<InterpolatedModel> newResult(new DefaultInterpolatedModel(interpolated, lineage),
                collector.getProblems());
    }

    @Override
    public Result<ActivatedModel> activate(Model model, List<Profile> profiles, ExternalProfileActivationContext epac) {
        final DefaultModelProblemCollector collector = new DefaultModelProblemCollector(
                new DefaultModelBuildingResult());

        // 1. determine and activate external profiles (from settings file)
        collector.setSource("(external profiles)");
        final List<Profile> activeExternalProfiles = profileSelector.getActiveProfiles(profiles, pac(epac, model),
                collector);
        final ExternalProfileActivationContext activeContext = effectiveActivationContext(epac, activeExternalProfiles);

        // 2. inject first pom, then external profiles
        collector.setSource(model);
        final ProfileActivationContext pac = pac(activeContext, model);
        final List<Profile> activePomProfiles = activatePomProfiles(model, model, pac, collector);
        injectProfiles(model, activeExternalProfiles);

        return Result.<ActivatedModel> newResult(new DefaultActivatedModel(model, activePomProfiles,
                activeExternalProfiles, pac), collector.getProblems());
    }

    private static ExternalProfileActivationContext effectiveActivationContext(ExternalProfileActivationContext epac,
            Iterable<? extends Profile> profiles) {
        // one-liner for combining properties?
        final Properties activeUserProperties = new Properties();
        for (Profile profile : profiles)
            activeUserProperties.putAll(profile.getProperties());
        activeUserProperties.putAll(epac.getUserProperties());
        return new DefaultExternalProfileActivationContext(epac.getActiveProfileIds(), epac.getInactiveProfileIds(),
                epac.getSystemProperties(), activeUserProperties);
    }

    /*
     * not sure why the raw model is used here, but keeping for backwards compatibility
     */
    private List<Profile> activatePomProfiles(Model rawModel, Model effectiveModel, ProfileActivationContext pac,
            DefaultModelProblemCollector problems) {
        // 1. normalize
        modelNormalizer.mergeDuplicates(effectiveModel, problems);

        // 2. determine active profiles
        final List<Profile> activePomProfiles = profileSelector
                .getActiveProfiles(rawModel.getProfiles(), pac, problems);

        // 3. not sure why this is needed
        final Map<String, Activation> interpolatedActivations = getProfileActivations(rawModel, false);
        injectProfileActivations(effectiveModel, interpolatedActivations);

        // 4. inject profiles
        injectProfiles(effectiveModel, activePomProfiles);
        return activePomProfiles;
    }

    private void injectProfiles(Model model, Iterable<? extends Profile> profiles) {
        for (Profile profile : profiles)
            profileInjector.injectProfile(model, profile);
    }

    @Override
    public Result<Model> load(ModelSource source, int validationLevel, boolean locationTracking) {
        final DefaultModelProblemCollector collector = new DefaultModelProblemCollector(new DefaultModelBuildingResult());
        try {
            return newResult(readModel(source, validationLevel, locationTracking, collector), collector.getProblems());
        } catch (ModelBuildingException e) {
            return error(collector.getProblems());
        }
    }

    private Model readModel(ModelSource modelSource, int validationLevel, boolean locationTracking,
            DefaultModelProblemCollector problems) throws ModelBuildingException
    {
        if (modelSource == null) throw new IllegalArgumentException("no model source specified");

        Model model;
        problems.setSource( modelSource.getLocation() );
        try
        {
            boolean strict = validationLevel >= ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_2_0;
            InputSource source = locationTracking ? new InputSource() : null;

            Map<String, Object> options = new HashMap<String, Object>();
            options.put( ModelProcessor.IS_STRICT, strict );
            options.put( ModelProcessor.INPUT_SOURCE, source );
            options.put( ModelProcessor.SOURCE, modelSource );

            try
            {
                model = modelProcessor.read( modelSource.getInputStream(), options );
            }
            catch ( ModelParseException e )
            {
                if ( !strict )
                {
                    throw e;
                }

                options.put( ModelProcessor.IS_STRICT, Boolean.FALSE );

                try
                {
                    model = modelProcessor.read( modelSource.getInputStream(), options );
                }
                catch ( ModelParseException ne )
                {
                    // still unreadable even in non-strict mode, rethrow original error
                    throw e;
                }

                if ( modelSource instanceof FileModelSource )
                {
                    problems.add( new ModelProblemCollectorRequest( Severity.ERROR, Version.V20 )
                            .setMessage( "Malformed POM " + modelSource.getLocation() + ": " + e.getMessage() )
                            .setException( e ) );
                }
                else
                {
                    problems.add( new ModelProblemCollectorRequest( Severity.WARNING, Version.V20 )
                            .setMessage( "Malformed POM " + modelSource.getLocation() + ": " + e.getMessage() )
                            .setException( e ) );
                }
            }

            if ( source != null )
            {
                source.setModelId( ModelProblemUtils.toId( model ) );
                source.setLocation( modelSource.getLocation() );
            }
        }
        catch ( ModelParseException e )
        {
            problems.add( new ModelProblemCollectorRequest( Severity.FATAL, Version.BASE )
                    .setMessage( "Non-parseable POM " + modelSource.getLocation() + ": " + e.getMessage() )
                    .setException( e ) );
            throw problems.newModelBuildingException();
        }
        catch ( IOException e )
        {
            String msg = e.getMessage();
            if ( msg == null || msg.length() <= 0 )
            {
                // NOTE: There's java.nio.charset.MalformedInputException and sun.io.MalformedInputException
                if ( e.getClass().getName().endsWith( "MalformedInputException" ) )
                {
                    msg = "Some input bytes do not match the file encoding.";
                }
                else
                {
                    msg = e.getClass().getSimpleName();
                }
            }
            problems.add( new ModelProblemCollectorRequest( Severity.FATAL, Version.BASE )
                    .setMessage( "Non-readable POM " + modelSource.getLocation() + ": " + msg )
                    .setException( e ) );
            throw problems.newModelBuildingException();
        }

        model.setPomFile(modelSource instanceof FileModelSource ? ((FileModelSource)modelSource).getPomFile() : null);

        problems.setSource( model );
        modelValidator.validateRawModel( model, validationLevel, problems );

        if ( hasFatalErrors( problems ) )
        {
            throw problems.newModelBuildingException();
        }

        return model;
    }

    private void configureResolver( ModelResolver modelResolver, Model model, DefaultModelProblemCollector problems )
    {
        configureResolver( modelResolver, model, problems, false );
    }

    private void configureResolver( ModelResolver modelResolver, Model model, DefaultModelProblemCollector problems, boolean replaceRepositories )
    {
        if ( modelResolver == null )
        {
            return;
        }

        problems.setSource( model );

        List<Repository> repositories = model.getRepositories();

        for ( Repository repository : repositories )
        {
            try
            {
                modelResolver.addRepository( repository, replaceRepositories );
            }
            catch ( InvalidRepositoryException e )
            {
                problems.add( new ModelProblemCollectorRequest( Severity.ERROR, Version.BASE )
                        .setMessage( "Invalid repository " + repository.getId() + ": " + e.getMessage() )
                        .setLocation( repository.getLocation( "" ) )
                        .setException( e ) );
            }
        }
    }

    private void checkPluginVersions( Iterable<? extends ActivatedModel> l, int validationLevel,
                                      ModelProblemCollector problems )
    {
        if ( validationLevel < ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_2_0 )
        {
            return;
        }

        Map<String, Plugin> plugins = new HashMap<String, Plugin>();
        Map<String, String> versions = new HashMap<String, String>();
        Map<String, String> managedVersions = new HashMap<String, String>();

        List<ActivatedModel> lineage = newArrayList(l);
        for ( int i = lineage.size() - 1; i >= 0; i-- )
        {
            Model model = lineage.get( i ).getActiveModel();
            Build build = model.getBuild();
            if ( build != null )
            {
                for ( Plugin plugin : build.getPlugins() )
                {
                    String key = plugin.getKey();
                    if ( versions.get( key ) == null )
                    {
                        versions.put( key, plugin.getVersion() );
                        plugins.put( key, plugin );
                    }
                }
                PluginManagement mngt = build.getPluginManagement();
                if ( mngt != null )
                {
                    for ( Plugin plugin : mngt.getPlugins() )
                    {
                        String key = plugin.getKey();
                        if ( managedVersions.get( key ) == null )
                        {
                            managedVersions.put( key, plugin.getVersion() );
                        }
                    }
                }
            }
        }

        for ( String key : versions.keySet() )
        {
            if ( versions.get( key ) == null && managedVersions.get( key ) == null )
            {
                InputLocation location = plugins.get( key ).getLocation( "" );
                problems.add( new ModelProblemCollectorRequest( Severity.WARNING, Version.V20 )
                        .setMessage( "'build.plugins.plugin.version' for " + key + " is missing." )
                        .setLocation( location ) );
            }
        }
    }

    private Model assembleInheritance(ActivatedModel activatedModel, Iterable<? extends ActivatedModel> lineage,
            ModelProblemCollector problems) {
        if (!lineage.iterator().hasNext()) throw new IllegalArgumentException("lineage must at least contain super pom");

        List<Model> models = new ArrayList<Model>();
        models.add(activatedModel.getActiveModel());
        addAll(models, transform(lineage, getActiveModel));

        final ListIterator<Model> it = models.listIterator(models.size());
        Model effectiveModel = it.previous();
        while (it.hasPrevious()) {
            Model child = it.previous().clone();
            inheritanceAssembler.assembleModelInheritance(child, effectiveModel, problems);
            effectiveModel = child;
        }
        return effectiveModel;
    }

    private Map<String, Activation> getProfileActivations( Model model, boolean clone )
    {
        Map<String, Activation> activations = new HashMap<String, Activation>();
        for ( Profile profile : model.getProfiles() )
        {
            Activation activation = profile.getActivation();

            if ( activation == null )
            {
                continue;
            }

            if ( clone )
            {
                activation = activation.clone();
            }

            activations.put( profile.getId(), activation );
        }

        return activations;
    }

    private void injectProfileActivations( Model model, Map<String, Activation> activations )
    {
        for ( Profile profile : model.getProfiles() )
        {
            Activation activation = profile.getActivation();

            if ( activation == null )
            {
                continue;
            }

            // restore activation
            profile.setActivation( activations.get( profile.getId() ) );
        }
    }

    private Model interpolateModel(Model model, int validationLevel, Properties userProperties,
            Properties systemProperties, Date buildStartTime, ModelProblemCollector problems)
    {
        // save profile activations before interpolation, since they are evaluated with limited scope
        Map<String, Activation> originalActivations = getProfileActivations( model, true );

        Model result = modelInterpolator.interpolateModel( model, model.getProjectDirectory(), validationLevel, userProperties, systemProperties, buildStartTime, problems );
        result.setPomFile( model.getPomFile() );

        // restore profiles with file activation to their value before full interpolation
        injectProfileActivations( model, originalActivations );

        return result;
    }

    private Result<ModelData> readParent(Model childModel, ModelSource childSource, ModelBuildingRequest request) {
        DefaultModelProblemCollector collector = new DefaultModelProblemCollector(new DefaultModelBuildingResult());
        try {
            return newResult(readParent(childModel, childSource, request, collector), collector.getProblems());
        } catch (ModelBuildingException e) {
            return error(collector.getProblems());
        }
    }

    private ModelData readParent( Model childModel, ModelSource childSource, ModelBuildingRequest request,
                                  DefaultModelProblemCollector problems )
        throws ModelBuildingException
    {
        ModelData parentData;

        Parent parent = childModel.getParent();

        if ( parent != null )
        {
            String groupId = parent.getGroupId();
            String artifactId = parent.getArtifactId();
            String version = parent.getVersion();

            parentData = getCache( request.getModelCache(), groupId, artifactId, version, ModelCacheTag.RAW );

            if ( parentData == null )
            {
                parentData = readParentLocally( childModel, childSource, request, problems );

                if ( parentData == null )
                {
                    parentData = readParentExternally( childModel, request, problems );
                }

                putCache( request.getModelCache(), groupId, artifactId, version, ModelCacheTag.RAW, parentData );
            }
            else
            {
                /*
                 * NOTE: This is a sanity check of the cache hit. If the cached parent POM was locally resolved, the
                 * child's <relativePath> should point at that parent, too. If it doesn't, we ignore the cache and
                 * resolve externally, to mimic the behavior if the cache didn't exist in the first place. Otherwise,
                 * the cache would obscure a bad POM.
                 */

                File pomFile = parentData.getModel().getPomFile();
                if ( pomFile != null )
                {
                    ModelSource expectedParentSource = getParentPomFile( childModel, childSource );

                    if ( expectedParentSource instanceof ModelSource2
                        && !pomFile.toURI().equals( ( (ModelSource2) expectedParentSource ).getLocationURI() ) )
                    {
                        parentData = readParentExternally( childModel, request, problems );
                    }
                }
            }

            Model parentModel = parentData.getModel();

            if ( !"pom".equals( parentModel.getPackaging() ) )
            {
                problems.add( new ModelProblemCollectorRequest( Severity.ERROR, Version.BASE )
                        .setMessage( "Invalid packaging for parent POM " + ModelProblemUtils.toSourceHint( parentModel )
                                     + ", must be \"pom\" but is \"" + parentModel.getPackaging() + "\"" )
                        .setLocation( parentModel.getLocation( "packaging" ) ) );
            }
        }
        else
        {
            parentData = null;
        }

        return parentData;
    }

    private ModelData readParentLocally( Model childModel, ModelSource childSource, ModelBuildingRequest request,
                                         DefaultModelProblemCollector problems )
        throws ModelBuildingException
    {
        ModelSource candidateSource = getParentPomFile( childModel, childSource );

        if ( candidateSource == null )
        {
            return null;
        }

        Model candidateModel = readModel( candidateSource, request.getValidationLevel(), request.isLocationTracking(), problems );

        String groupId = candidateModel.getGroupId();
        if ( groupId == null && candidateModel.getParent() != null )
        {
            groupId = candidateModel.getParent().getGroupId();
        }
        String artifactId = candidateModel.getArtifactId();
        String version = candidateModel.getVersion();
        if ( version == null && candidateModel.getParent() != null )
        {
            version = candidateModel.getParent().getVersion();
        }

        Parent parent = childModel.getParent();

        if ( groupId == null || !groupId.equals( parent.getGroupId() ) || artifactId == null
            || !artifactId.equals( parent.getArtifactId() ) )
        {
            StringBuilder buffer = new StringBuilder( 256 );
            buffer.append( "'parent.relativePath'" );
            if ( childModel != problems.getRootModel() )
            {
                buffer.append( " of POM " ).append( ModelProblemUtils.toSourceHint( childModel ) );
            }
            buffer.append( " points at " ).append( groupId ).append( ":" ).append( artifactId );
            buffer.append( " instead of " ).append( parent.getGroupId() ).append( ":" ).append( parent.getArtifactId() );
            buffer.append( ", please verify your project structure" );

            problems.setSource( childModel );
            problems.add( new ModelProblemCollectorRequest( Severity.WARNING, Version.BASE )
                    .setMessage( buffer.toString() )
                    .setLocation( parent.getLocation( "" ) ) );
            return null;
        }
        if ( version == null || !version.equals( parent.getVersion() ) )
        {
            return null;
        }

        ModelData parentData = new ModelData( candidateSource, candidateModel, groupId, artifactId, version );

        return parentData;
    }

    private ModelSource getParentPomFile( Model childModel, ModelSource source )
    {
        if ( !( source instanceof ModelSource2 ) )
        {
            return null;
        }

        String parentPath = childModel.getParent().getRelativePath();

        if ( parentPath == null || parentPath.length() <= 0 )
        {
            return null;
        }

        return ( (ModelSource2) source ).getRelatedSource( parentPath );
    }

    private ModelData readParentExternally( Model childModel, ModelBuildingRequest request,
                                            DefaultModelProblemCollector problems )
        throws ModelBuildingException
    {
        problems.setSource( childModel );

        Parent parent = childModel.getParent().clone();

        String groupId = parent.getGroupId();
        String artifactId = parent.getArtifactId();
        String version = parent.getVersion();

        ModelResolver modelResolver = request.getModelResolver();

        if ( modelResolver == null )
        {
            throw new IllegalArgumentException( "no model resolver provided, cannot resolve parent POM "
                + ModelProblemUtils.toId( groupId, artifactId, version ) + " for POM "
                + ModelProblemUtils.toSourceHint( childModel ) );
        }

        ModelSource modelSource;
        try
        {
            modelSource = modelResolver.resolveModel( parent );
        }
        catch ( UnresolvableModelException e )
        {
            StringBuilder buffer = new StringBuilder( 256 );
            buffer.append( "Non-resolvable parent POM" );
            if ( !containsCoordinates( e.getMessage(), groupId, artifactId, version ) )
            {
                buffer.append( " " ).append( ModelProblemUtils.toId( groupId, artifactId, version ) );
            }
            if ( childModel != problems.getRootModel() )
            {
                buffer.append( " for " ).append( ModelProblemUtils.toId( childModel ) );
            }
            buffer.append( ": " ).append( e.getMessage() );
            if ( childModel.getProjectDirectory() != null )
            {
                if ( parent.getRelativePath() == null || parent.getRelativePath().length() <= 0 )
                {
                    buffer.append( " and 'parent.relativePath' points at no local POM" );
                }
                else
                {
                    buffer.append( " and 'parent.relativePath' points at wrong local POM" );
                }
            }

            problems.add( new ModelProblemCollectorRequest( Severity.FATAL, Version.BASE )
                    .setMessage( buffer.toString() )
                    .setLocation( parent.getLocation( "" ) )
                    .setException( e ) );
            throw problems.newModelBuildingException();
        }

        int validationLevel = request.getValidationLevel();
        if (validationLevel > ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_2_0)
            validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_2_0;

        Model parentModel = readModel( modelSource, validationLevel, request.isLocationTracking(), problems );

        if ( !parent.getVersion().equals( version ) )
        {
            if ( childModel.getVersion() == null )
            {
                problems.add( new ModelProblemCollectorRequest( Severity.FATAL, Version.V31 ).
                    setMessage( "Version must be a constant" ).
                    setLocation( childModel.getLocation( "" ) ) );

            }
            else
            {
                if ( childModel.getVersion().indexOf( "${" ) > -1 )
                {
                    problems.add( new ModelProblemCollectorRequest( Severity.FATAL, Version.V31 ).
                        setMessage( "Version must be a constant" ).
                        setLocation( childModel.getLocation( "version" ) ) );

                }
            }

            // MNG-2199: What else to check here ?
        }

        ModelData parentData = new ModelData( modelSource, parentModel, parent.getGroupId(), parent.getArtifactId(),
                                              parent.getVersion() );

        return parentData;
    }

    private Model getSuperModel()
    {
        return superPomProvider.getSuperModel( "4.0.0" ).clone();
    }

    private void importDependencyManagement( Model model, ModelBuildingRequest request,
                                             DefaultModelProblemCollector problems, Collection<String> importIds )
    {
        DependencyManagement depMngt = model.getDependencyManagement();

        if ( depMngt == null )
        {
            return;
        }

        String importing = model.getGroupId() + ':' + model.getArtifactId() + ':' + model.getVersion();

        importIds.add( importing );

        ModelResolver modelResolver = request.getModelResolver();

        ModelBuildingRequest importRequest = null;

        List<DependencyManagement> importMngts = null;

        for ( Iterator<Dependency> it = depMngt.getDependencies().iterator(); it.hasNext(); )
        {
            Dependency dependency = it.next();

            if ( !"pom".equals( dependency.getType() ) || !"import".equals( dependency.getScope() ) )
            {
                continue;
            }

            it.remove();

            String groupId = dependency.getGroupId();
            String artifactId = dependency.getArtifactId();
            String version = dependency.getVersion();

            if ( groupId == null || groupId.length() <= 0 )
            {
                problems.add( new ModelProblemCollectorRequest( Severity.ERROR, Version.BASE )
                        .setMessage( "'dependencyManagement.dependencies.dependency.groupId' for "
                                        + dependency.getManagementKey() + " is missing." )
                        .setLocation( dependency.getLocation( "" ) ) );
                continue;
            }
            if ( artifactId == null || artifactId.length() <= 0 )
            {
                problems.add( new ModelProblemCollectorRequest( Severity.ERROR, Version.BASE )
                        .setMessage( "'dependencyManagement.dependencies.dependency.artifactId' for "
                                        + dependency.getManagementKey() + " is missing." )
                        .setLocation( dependency.getLocation( "" ) ) );
                continue;
            }
            if ( version == null || version.length() <= 0 )
            {
                problems.add( new ModelProblemCollectorRequest( Severity.ERROR, Version.BASE )
                        .setMessage( "'dependencyManagement.dependencies.dependency.version' for "
                                        + dependency.getManagementKey() + " is missing." )
                        .setLocation( dependency.getLocation( "" ) ) );
                continue;
            }

            String imported = groupId + ':' + artifactId + ':' + version;

            if ( importIds.contains( imported ) )
            {
                String message = "The dependencies of type=pom and with scope=import form a cycle: ";
                for ( String modelId : importIds )
                {
                    message += modelId + " -> ";
                }
                message += imported;
                problems.add( new ModelProblemCollectorRequest( Severity.ERROR, Version.BASE ).setMessage( message ) );

                continue;
            }

            DependencyManagement importMngt =
                getCache( request.getModelCache(), groupId, artifactId, version, ModelCacheTag.IMPORT );

            if ( importMngt == null )
            {
                if ( modelResolver == null )
                {
                    throw new IllegalArgumentException( "no model resolver provided, cannot resolve import POM "
                        + ModelProblemUtils.toId( groupId, artifactId, version ) + " for POM "
                        + ModelProblemUtils.toSourceHint( model ) );
                }

                ModelSource importSource;
                try
                {
                    importSource = modelResolver.resolveModel( groupId, artifactId, version );
                }
                catch ( UnresolvableModelException e )
                {
                    StringBuilder buffer = new StringBuilder( 256 );
                    buffer.append( "Non-resolvable import POM" );
                    if ( !containsCoordinates( e.getMessage(), groupId, artifactId, version ) )
                    {
                        buffer.append( " " ).append( ModelProblemUtils.toId( groupId, artifactId, version ) );
                    }
                    buffer.append( ": " ).append( e.getMessage() );

                    problems.add( new ModelProblemCollectorRequest( Severity.ERROR, Version.BASE )
                            .setMessage( buffer.toString() )
                            .setLocation( dependency.getLocation( "" ) )
                            .setException( e ) );
                    continue;
                }
                
                if ( importRequest == null )
                {
                    importRequest = new DefaultModelBuildingRequest();
                    importRequest.setValidationLevel( ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL );
                    importRequest.setModelCache( request.getModelCache() );
                    importRequest.setSystemProperties( request.getSystemProperties() );
                    importRequest.setUserProperties( request.getUserProperties() );
                    importRequest.setLocationTracking( request.isLocationTracking() );
                }

                importRequest.setModelSource( importSource );
                importRequest.setModelResolver( modelResolver.newCopy() );

                ModelBuildingResult importResult;
                try
                {
                    importResult = build( importRequest );
                }
                catch ( ModelBuildingException e )
                {
                    problems.addAll( e.getProblems() );
                    continue;
                }

                problems.addAll( importResult.getProblems() );

                Model importModel = importResult.getEffectiveModel();

                importMngt = importModel.getDependencyManagement();

                if ( importMngt == null )
                {
                    importMngt = new DependencyManagement();
                }

                putCache( request.getModelCache(), groupId, artifactId, version, ModelCacheTag.IMPORT, importMngt );
            }

            if ( importMngts == null )
            {
                importMngts = new ArrayList<DependencyManagement>();
            }

            importMngts.add( importMngt );
        }

        importIds.remove( importing );

        dependencyManagementImporter.importManagement( model, importMngts, request, problems );
    }

    private <T> void putCache( ModelCache modelCache, String groupId, String artifactId, String version,
                               ModelCacheTag<T> tag, T data )
    {
        if ( modelCache != null )
        {
            modelCache.put( groupId, artifactId, version, tag.getName(), tag.intoCache( data ) );
        }
    }

    private <T> T getCache( ModelCache modelCache, String groupId, String artifactId, String version,
                            ModelCacheTag<T> tag )
    {
        if ( modelCache != null )
        {
            Object data = modelCache.get( groupId, artifactId, version, tag.getName() );
            if ( data != null )
            {
                return tag.fromCache( tag.getType().cast( data ) );
            }
        }
        return null;
    }

    private void fireEvent( Model model, ModelBuildingRequest request, ModelProblemCollector problems,
                            ModelBuildingEventCatapult catapult )
        throws ModelBuildingException
    {
        ModelBuildingListener listener = request.getModelBuildingListener();

        if ( listener != null )
        {
            ModelBuildingEvent event = new DefaultModelBuildingEvent( model, request, problems );

            catapult.fire( listener, event );
        }
    }

    private boolean containsCoordinates( String message, String groupId, String artifactId, String version )
    {
        return message != null && ( groupId == null || message.contains( groupId ) )
            && ( artifactId == null || message.contains( artifactId ) )
            && ( version == null || message.contains( version ) );
    }

    protected boolean hasModelErrors( ModelProblemCollectorExt problems )
    {
        if ( problems instanceof DefaultModelProblemCollector )
        {
            return ( (DefaultModelProblemCollector) problems ).hasErrors();
        }
        else
        {
            // the default execution path only knows the DefaultModelProblemCollector,
            // only reason it's not in signature is because it's package private
            throw new IllegalStateException();
        }
    }

    protected boolean hasFatalErrors( ModelProblemCollectorExt problems )
    {
        if ( problems instanceof DefaultModelProblemCollector )
        {
            return ( (DefaultModelProblemCollector) problems ).hasFatalErrors();
        }
        else
        {
            // the default execution path only knows the DefaultModelProblemCollector,
            // only reason it's not in signature is because it's package private
            throw new IllegalStateException();
        }
    }

}
