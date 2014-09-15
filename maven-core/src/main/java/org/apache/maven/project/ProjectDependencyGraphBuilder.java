package org.apache.maven.project;

import static com.google.common.base.Predicates.and;
import static com.google.common.base.Predicates.compose;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.transform;
import static com.google.common.collect.Maps.newLinkedHashMap;
import static com.google.common.collect.Sets.newLinkedHashSet;
import static org.apache.maven.RepositoryUtils.toRepos;
import static org.apache.maven.artifact.repository.LegacyLocalRepositoryManager.overlay;
import static org.apache.maven.model.building.Result.error;
import static org.apache.maven.model.building.Result.newResultSet;
import static org.apache.maven.model.building.Result.success;
import static org.apache.maven.project.DefaultProjectDependencyGraph.newPDG;
import static org.apache.maven.project.GA.getDependencyId;
import static org.apache.maven.project.GA.getId;
import static org.apache.maven.project.GA.getPluginId;
import static org.eclipse.aether.RequestTrace.newChild;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.bridge.MavenRepositorySystem;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.DeploymentRepository;
import org.apache.maven.model.Extension;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.Result;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.model.resolution.WorkspaceResolver;
import org.apache.maven.model.superpom.SuperPomProvider;
import org.apache.maven.project.MakeBehaviorFactory.MakeBehavior;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.impl.RemoteRepositoryManager;

import com.google.common.base.Function;
import com.google.common.base.Predicate;

/**
 * Behavior changes (compared to default maven)
 * <ol>
 * <li>No interpolation of GA allowed => GA must be directly derivable from raw POM without inheritance assembly and
 * interpolation
 * <li>No interpolation of modules allowed => module elements must be directly derivable from raw POM without
 * inheritance assembly and interpolation
 * <li>Profiles cannot add modules => the module set is fixed and '--projects' mechanism to change what is built
 * <li>GA must be unique in a multi-module project
 * <li>All POM projects referenced as a parent must be listed as module somewhere
 * </ol>
 * Open questions
 * <ol>
 * <li>should profiles be able to inject modules? doesn't --project address that use case?
 * <li>Implications of collecting modules without merging parent or interpolating?
 * <li>Should project selector be applied on interpolated model?
 * </ol>
 * 
 * @author bbusjaeger
 */
@Component(role = ProjectDependencyGraphBuilder.class)
public class ProjectDependencyGraphBuilder
{
    @Requirement
    private ModelIndexLoader modelIndexLoader;

    @Requirement
    private MakeBehaviorFactory makeBehaviorFactory;

    @Requirement
    private ModelBuilder modelBuilder;

    @Requirement
    private RepositorySystem repoSystem;

    @Requirement
    private RemoteRepositoryManager repositoryManager;

    @Requirement
    private SuperPomProvider superPomProvider;

    @Requirement
    private ProjectBuildingHelper projectBuildingHelper;

    @Requirement
    private MavenRepositorySystem repositorySystem;

    /**
     * Builds the project graph for the given session
     * 
     * @param session
     * @return
     */
    public Result<? extends ProjectDependencyGraph> build( MavenSession session )
    {
        final MavenExecutionRequest request = session.getRequest();

        // 1. load source and binary indices (binary may be null)
        final Result<Map<GA, Model>> srcIndex = modelIndexLoader.load( request.getPom() );
        final Result<Map<GA, Model>> binIndex = Result.success( null ); // TODO
        if ( srcIndex.hasErrors() || binIndex.hasErrors() )
            return error( concat( srcIndex.getProblems(), binIndex.getProblems() ) );

        // 2. parse make behavior
        final Result<? extends MakeBehavior> makeBehavior = makeBehaviorFactory.create( request, srcIndex.get(),
                                                                                        binIndex.get() );
        if ( makeBehavior.hasErrors() )
            return error( makeBehavior.getProblems() );

        // 3. build graph
        final Builder builder = new Builder( makeBehavior.get(), request.getProjectBuildingRequest()
            .setRepositorySession( session.getRepositorySession() ) );

        return builder.build();
    }

    /**
     * Actual (stateful) graph builder implementation
     * @author bbusjaeger
     *
     */
    class Builder
        implements WorkspaceResolver, Predicate<GA>
    {
        // immutable
        private final MakeBehavior makeBehavior;

        private final ProjectBuildingRequest request;

        // mutable
        private final Map<GA, Result<MavenProject>> completed;

        private final Set<GA> building;

        Builder( MakeBehavior makeBehavior, ProjectBuildingRequest request )
        {
            this.makeBehavior = makeBehavior;
            this.request = request;
            // using linked map should topologically-sort projects as they are added
            this.completed = newLinkedHashMap();
            // using linked set should preserve cycle order
            this.building = newLinkedHashSet();
        }

        /**
         * builds the project graph
         * 
         * @return
         */
        private Result<? extends ProjectDependencyGraph> build()
        {
            for ( GA ga : makeBehavior.getProjectsToBuild() )
                buildProject( ga );

            final Result<Iterable<MavenProject>> results = newResultSet( completed.values() );
            if ( results.hasErrors() )
                return error( results.getProblems() );
            else
                return success( newPDG( results.get() ), results.getProblems() );
        }

        /**
         * Builds graph node using the specified make behavior
         * 
         * @param id
         * @param model
         * @param projects
         * @return
         */
        private Result<MavenProject> buildProject( GA id )
        {
            // if the project is already built, return it
            final Result<MavenProject> existing = completed.get( id );
            if ( existing != null )
                return existing;

            /*
             * If we are currently building this project and traverse back to it via some dependency, we must have a cycle.
             * Failing here maintains the invariant that the project graph is a DAG, because any newly added project refers
             * only to projects already in the DAG. In other words, no back-edges are added.
             */
            if ( !building.add( id ) )
                throw new IllegalArgumentException( "Project dependency cycle detected " + building );

            final Result<MavenProject> result = makeBehavior.build( this, id );

            // the project is unmarked, since it is now part of the graph
            building.remove( id );

            // at this point all project dependencies are in the graph, so this project can be added as well
            completed.put( id, result );

            return result;
        }

        /**
         * Builds a source or binary node from the given raw model
         * @param source
         * @param model
         * @return
         */
        Result<MavenProject> buildProject( final boolean source, final Model model )
        {
            final MavenProject project = new MavenProject();
            project.setOriginalModel( model );
            project.setFile( model.getPomFile() );
            project.setSource( source );

            // 1. resolve parents (needed during first phase of build)
            final Parent parent = model.getParent();
            if ( parent != null )
            {
                final GA pid = getId( parent );
                if ( makeBehavior.isProject( pid ) )
                {
                    final Result<MavenProject> result = buildProject( pid );
                    if ( result.hasErrors() )
                        return error( project );
                    project.setParent( result.get() );
                    project.setParentFile( result.get().getFile() );
                }
            }

            // 2. perform first phase of build to assemble and interpolate model
            final ModelBuildingRequest request = newModelBuildingRequest( project );
            final ModelBuildingResult result;
            try
            {
                result = modelBuilder.build( request );
            }
            catch ( ModelBuildingException e )
            {
                return error( project, e.getProblems() );
            }

            // 3. resolve imports (needed during second phase of build)
            final Result<Iterable<MavenProject>> imports = buildDependencies( getImports( result.getEffectiveModel() ),
                                                                              getDependencyId );
            if ( imports.hasErrors() )
                return error( project, concat( result.getProblems(), imports.getProblems() ) );
            project.setProjectImports( imports.get() );

            // 4. perform second phase of build to compute effective model
            try
            {
                modelBuilder.build( request, result );
            }
            catch ( ModelBuildingException e )
            {
                return error( project, e.getProblems() );
            }
            final Model effectiveModel = result.getEffectiveModel();
            project.setModel( effectiveModel );

            // 5. resolve plugins
            final Result<Iterable<MavenProject>> plugins = buildDependencies( effectiveModel.getBuild().getPlugins(),
                                                                              getPluginId );
            if ( plugins.hasErrors() )
                return error( project, plugins.getProblems() );
            project.setProjectPlugins( plugins.get() );

            // 6. resolve dependencies
            final Result<Iterable<MavenProject>> dependencies = buildDependencies( effectiveModel.getDependencies(),
                                                                                   getDependencyId );
            if ( dependencies.hasErrors() )
                return error( project, dependencies.getProblems() );
            project.setProjectDependencies( dependencies.get() );

            initProject( project, result, this.request );

            return success( project );// TODO preserve errors
        }

        // returns partially applied function
        private Function<GA, Result<MavenProject>> buildProject()
        {
            return new Function<GA, Result<MavenProject>>()
            {
                @Override
                public Result<MavenProject> apply( GA id )
                {
                    return buildProject( id );
                }
            };
        }

        /**
         * Builds all dependencies that are projects
         *
         * @param deps
         * @param getId
         * @param projects
         * @return
         */
        private <T> Result<Iterable<MavenProject>> buildDependencies( Iterable<T> deps, Function<T, GA> getId )
        {
            // turns dependencies into IDs, filter out any IDs that are not projects, then build projects for each ID
            return newResultSet( newArrayList( transform( filter( transform( deps, getId ), this ), buildProject() ) ) );
        }

        @Override
        public Model resolveRawModel( String groupId, String artifactId, String version )
            throws UnresolvableModelException
        {
            final MavenProject project = resolve( groupId, artifactId, version );
            return project == null ? null : project.getOriginalModel();
        }

        @Override
        public Model resolveEffectiveModel( String groupId, String artifactId, String version )
            throws UnresolvableModelException
        {
            final MavenProject project = resolve( groupId, artifactId, version );
            return project == null ? null : project.getModel();
        }

        private MavenProject resolve( String groupId, String artifactId, String version )
            throws UnresolvableModelException
        {
            final GA id = new GA( groupId, artifactId );
            if ( !makeBehavior.isProject( id ) )
                return null;
            final Result<MavenProject> project = completed.get( id );
            if ( project == null )
                throw new RuntimeException( "Assertion violated: project " + id + " has not completed build" );
            if ( project.hasErrors() )
                throw new UnresolvableModelException( "Parent failed to build", groupId, artifactId, version );
            // TODO validate version
            return project.get();
        }

        @Override
        public boolean apply( GA id )
        {
            return makeBehavior.isProject( id );
        }

        private ModelBuildingRequest newModelBuildingRequest( MavenProject project )
        {
            final ModelBuildingRequest mbr = new DefaultModelBuildingRequest();
            mbr.setValidationLevel( request.getValidationLevel() );
            mbr.setProcessPlugins( request.isProcessPlugins() );
            mbr.setProfiles( request.getProfiles() );
            mbr.setActiveProfileIds( request.getActiveProfileIds() );
            mbr.setInactiveProfileIds( request.getInactiveProfileIds() );
            mbr.setSystemProperties( request.getSystemProperties() );
            mbr.setUserProperties( request.getUserProperties() );
            mbr.setBuildStartTime( request.getBuildStartTime() );
            mbr.setWorkspaceResolver( this );
            mbr.setTwoPhaseBuilding( true );
            mbr.setProcessPlugins( true );
            mbr.setModelResolver( new ProjectModelResolver( overlay( request.getLocalRepository(),
                                                                     request.getRepositorySession(), repoSystem ),
                                                            newChild( null, request ).newChild( mbr ), repoSystem,
                                                            repositoryManager,
                                                            toRepos( request.getRemoteRepositories() ), request
                                                                .getRepositoryMerging(), null ) );
            mbr.setModelBuildingListener( new DefaultModelBuildingListener( project, projectBuildingHelper, request ) );
            mbr.setRawModel( project.getOriginalModel() );
            return mbr;
        }

    }

    static Iterable<Dependency> getImports( Model model )
    {
        return filter( model.getDependencyManagement().getDependencies(),
                       and( compose( equalTo( "pom" ), new Function<Dependency, String>()
                       {
                           @Override
                           public String apply( Dependency input )
                           {
                               return input.getType();
                           }
                       } ), compose( equalTo( "import" ), new Function<Dependency, String>()
                       {
                           @Override
                           public String apply( Dependency input )
                           {
                               return input.getScope();
                           }
                       } ) ) );
    }

    // TODO mostly copied from {@link DefaulProjectBuilder}
    @SuppressWarnings("deprecation")
    private void initProject( MavenProject project, ModelBuildingResult result,
                              ProjectBuildingRequest projectBuildingRequest )
    {
        // TODO set collected projects and execution root?
        // TODO inject maven project for external parent?

        project.setArtifact( repositorySystem.createArtifact( project.getGroupId(), project.getArtifactId(),
                                                              project.getVersion(), null, project.getPackaging() ) );

        final Build build = project.getBuild();
        project.addScriptSourceRoot( build.getScriptSourceDirectory() );
        project.addCompileSourceRoot( build.getSourceDirectory() );
        project.addTestCompileSourceRoot( build.getTestSourceDirectory() );

        List<Profile> activeProfiles = new ArrayList<Profile>();
        activeProfiles.addAll( result.getActivePomProfiles( result.getModelIds().get( 0 ) ) );
        activeProfiles.addAll( result.getActiveExternalProfiles() );
        project.setActiveProfiles( activeProfiles );

        project.setInjectedProfileIds( "external", getProfileIds( result.getActiveExternalProfiles() ) );
        for ( String modelId : result.getModelIds() )
        {
            project.setInjectedProfileIds( modelId, getProfileIds( result.getActivePomProfiles( modelId ) ) );
        }

        //
        // All the parts that were taken out of MavenProject for Maven 4.0.0
        //

        project.setProjectBuildingRequest( projectBuildingRequest );

        // pluginArtifacts
        Set<Artifact> pluginArtifacts = new HashSet<Artifact>();
        for ( Plugin plugin : project.getBuildPlugins() )
        {
            Artifact artifact = repositorySystem.createPluginArtifact( plugin );
            if ( artifact != null )
            {
                pluginArtifacts.add( artifact );
            }
        }
        project.setPluginArtifacts( pluginArtifacts );

        // reportArtifacts
        Set<Artifact> reportArtifacts = new HashSet<Artifact>();
        for ( ReportPlugin report : project.getReportPlugins() )
        {
            Plugin pp = new Plugin();
            pp.setGroupId( report.getGroupId() );
            pp.setArtifactId( report.getArtifactId() );
            pp.setVersion( report.getVersion() );

            Artifact artifact = repositorySystem.createPluginArtifact( pp );

            if ( artifact != null )
            {
                reportArtifacts.add( artifact );
            }
        }
        project.setReportArtifacts( reportArtifacts );

        // extensionArtifacts
        Set<Artifact> extensionArtifacts = new HashSet<Artifact>();
        List<Extension> extensions = project.getBuildExtensions();
        if ( extensions != null )
        {
            for ( Extension ext : extensions )
            {
                String version;
                if ( StringUtils.isEmpty( ext.getVersion() ) )
                {
                    version = "RELEASE";
                }
                else
                {
                    version = ext.getVersion();
                }

                Artifact artifact = repositorySystem.createArtifact( ext.getGroupId(), ext.getArtifactId(), version,
                                                                     null, "jar" );

                if ( artifact != null )
                {
                    extensionArtifacts.add( artifact );
                }
            }
        }
        project.setExtensionArtifacts( extensionArtifacts );

        // managedVersionMap
        Map<String, Artifact> map = null;
        if ( repositorySystem != null )
        {
            List<Dependency> deps;
            DependencyManagement dependencyManagement = project.getDependencyManagement();
            if ( ( dependencyManagement != null ) && ( ( deps = dependencyManagement.getDependencies() ) != null )
                && ( deps.size() > 0 ) )
            {
                map = new HashMap<String, Artifact>();
                for ( Dependency d : dependencyManagement.getDependencies() )
                {
                    Artifact artifact = repositorySystem.createDependencyArtifact( d );

                    if ( artifact == null )
                    {
                        map = Collections.emptyMap();
                    }

                    map.put( d.getManagementKey(), artifact );
                }
            }
            else
            {
                map = Collections.emptyMap();
            }
        }
        project.setManagedVersionMap( map );

        // release artifact repository
        if ( project.getDistributionManagement() != null && project.getDistributionManagement().getRepository() != null )
        {
            try
            {
                DeploymentRepository r = project.getDistributionManagement().getRepository();
                if ( !StringUtils.isEmpty( r.getId() ) && !StringUtils.isEmpty( r.getUrl() ) )
                {
                    ArtifactRepository repo = MavenRepositorySystem.buildArtifactRepository( project
                        .getDistributionManagement().getRepository() );
                    repositorySystem.injectProxy( projectBuildingRequest.getRepositorySession(), Arrays.asList( repo ) );
                    repositorySystem.injectAuthentication( projectBuildingRequest.getRepositorySession(),
                                                           Arrays.asList( repo ) );
                    project.setReleaseArtifactRepository( repo );
                }
            }
            catch ( InvalidRepositoryException e )
            {
                throw new IllegalStateException( "Failed to create release distribution repository for "
                    + project.getId(), e );
            }
        }

        // snapshot artifact repository
        if ( project.getDistributionManagement() != null
            && project.getDistributionManagement().getSnapshotRepository() != null )
        {
            try
            {
                DeploymentRepository r = project.getDistributionManagement().getSnapshotRepository();
                if ( !StringUtils.isEmpty( r.getId() ) && !StringUtils.isEmpty( r.getUrl() ) )
                {
                    ArtifactRepository repo = MavenRepositorySystem.buildArtifactRepository( project
                        .getDistributionManagement().getSnapshotRepository() );
                    repositorySystem.injectProxy( projectBuildingRequest.getRepositorySession(), Arrays.asList( repo ) );
                    repositorySystem.injectAuthentication( projectBuildingRequest.getRepositorySession(),
                                                           Arrays.asList( repo ) );
                    project.setSnapshotArtifactRepository( repo );
                }
            }
            catch ( InvalidRepositoryException e )
            {
                throw new IllegalStateException( "Failed to create snapshot distribution repository for "
                    + project.getId(), e );
            }
        }
    }

    private List<String> getProfileIds( List<Profile> profiles )
    {
        return transform( profiles, new Function<Profile, String>()
        {
            @Override
            public String apply( Profile input )
            {
                return input.getId();
            }
        } );
    }

}