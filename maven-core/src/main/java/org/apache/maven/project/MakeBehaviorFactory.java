package org.apache.maven.project;

import static java.util.Collections.singleton;
import static org.apache.maven.execution.MavenExecutionRequest.REACTOR_MAKE_BOTH;
import static org.apache.maven.execution.MavenExecutionRequest.REACTOR_MAKE_DOWNSTREAM;
import static org.apache.maven.execution.MavenExecutionRequest.REACTOR_MAKE_UPSTREAM;
import static org.apache.maven.model.building.ModelProblem.Severity.FATAL;
import static org.apache.maven.model.building.ModelProblem.Version.BASE;
import static org.apache.maven.model.building.Result.addProblem;
import static org.apache.maven.model.building.Result.error;
import static org.apache.maven.model.building.Result.success;

import java.io.File;
import java.util.Map;
import java.util.Set;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelProblem;
import org.apache.maven.model.building.Result;
import org.apache.maven.project.ProjectDependencyGraphBuilder.Builder;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;

/**
 * Creates {@link MakeBehavior} for the execution request.
 * 
 * @author bbusjaeger
 *
 */
@Component(role = MakeBehaviorFactory.class)
public class MakeBehaviorFactory
{

    /**
     * @author bbusjaeger
     *
     */
    static interface MakeBehavior
    {
        Iterable<? extends GA> getProjectsToBuild();

        boolean isProject( GA ga );

        Result<MavenProject> build( Builder builder, GA ga );
    }

    @Requirement
    private ModelSelector modelSelector;

    /**
     * Constructs make behavior for given execution request.
     * @param request
     * @param srcIndex
     * @param binIndex
     * @return
     */
    Result<? extends MakeBehavior> create( final MavenExecutionRequest request,
                                           final Map<GA, ? extends Model> srcIndex,
                                           final Map<GA, ? extends Model> binIndex )
    {
        final Result<? extends MakeBehavior> result;
        // empty means user did not specify a value
        if ( request.getSelectedProjects().isEmpty() )
        {
            result = success( new MakeAll( srcIndex ) );
        }
        // otherwise, user wants to build some subset of projects
        else
        {
            // determine selected projects based on '--projects'
            final Set<GA> selected = modelSelector.select( new File( request.getBaseDirectory() ),
                                                           request.getSelectedProjects(), srcIndex ).keySet();

            // determine make behavior (also-make, also-make-dependents, none, or both)
            final String mb = request.getMakeBehavior();
            if ( mb == null )
            {
                if ( binIndex == null )
                    result = error( singleton( new DefaultModelProblem(
                                                                        "Binary projects required to build selected projects",
                                                                        FATAL, BASE, null, -1, -1, null ) ) );
                else
                    result = success( new MakeSelected( srcIndex, binIndex, selected ) );
            }
            else if ( REACTOR_MAKE_DOWNSTREAM.equals( mb ) )
            {
                if ( binIndex == null )
                    result = error( singleton( new DefaultModelProblem(
                                                                        "Binary projects required to build projects and dependents",
                                                                        FATAL, BASE, null, -1, -1, null ) ) );
                else
                    result = success( new AlsoMakeDependents( srcIndex, binIndex, selected ) );
            }
            else if ( REACTOR_MAKE_UPSTREAM.equals( mb ) )
            {
                result = success( new AlsoMake( srcIndex, selected ) );
            }
            else if ( REACTOR_MAKE_BOTH.equals( mb ) )
            {
                // not sure if/how to implement. Seems to require iterations to do right 
                throw new UnsupportedOperationException( "Can't do " + mb );
            }
            else
            {
                throw new RuntimeException( "Unknown make behavior " + mb );
            }
        }
        return result;
    }

    /**
     * Make all - default. All and only source projects available for build.
     * @author bbusjaeger
     *
     */
    static class MakeAll
        implements MakeBehavior
    {
        private final Map<GA, ? extends Model> models;

        public MakeAll( Map<GA, ? extends Model> models )
        {
            this.models = models;
        }

        @Override
        public Iterable<? extends GA> getProjectsToBuild()
        {
            return models.keySet();
        }

        @Override
        public boolean isProject( GA ga )
        {
            return models.containsKey( ga );
        }

        @Override
        public Result<MavenProject> build( Builder builder, GA ga )
        {
            final Model model = models.get( ga );
            if ( model == null )
                throw new RuntimeException( "Assertion violation: build of non-existing project requested " + ga );
            return builder.buildProject( true, model );
        }
    }

    /**
     * Build only selected projects and their depednencies
     * @author bbusjaeger
     *
     */
    static class AlsoMake
        implements MakeBehavior
    {
        private final Map<GA, ? extends Model> models;

        private final Set<GA> selected;

        public AlsoMake( Map<GA, ? extends Model> models, Set<GA> selected )
        {
            this.models = models;
            this.selected = selected;
        }

        @Override
        public Iterable<? extends GA> getProjectsToBuild()
        {
            return selected;
        }

        @Override
        public boolean isProject( GA ga )
        {
            return models.containsKey( ga );
        }

        @Override
        public Result<MavenProject> build( Builder builder, GA ga )
        {
            final Model model = models.get( ga );
            if ( model == null )
                throw new RuntimeException( "Assertion violation: build of non-existing project requested " + ga );
            return builder.buildProject( true, model );
        }

    }

    /**
     * Build only selected projects. Satisfy dependencies from binary as needed.
     * @author bbusjaeger
     *
     */
    static class MakeSelected
        implements MakeBehavior
    {
        private final Map<GA, ? extends Model> sourceModels;

        private final Map<GA, ? extends Model> binaryModels;

        private final Set<GA> selected;

        public MakeSelected( Map<GA, ? extends Model> sourceModels, Map<GA, ? extends Model> binaryModels,
                             Set<GA> selected )
        {
            this.sourceModels = sourceModels;
            this.binaryModels = binaryModels;
            this.selected = selected;
        }

        @Override
        public Iterable<? extends GA> getProjectsToBuild()
        {
            return selected;
        }

        @Override
        public boolean isProject( GA ga )
        {
            return sourceModels.containsKey( ga ) || binaryModels.containsKey( ga );
        }

        @Override
        public Result<MavenProject> build( Builder builder, GA ga )
        {
            final boolean source;
            final Map<GA, ? extends Model> models;
            if ( selected.contains( ga ) )
            {
                source = true;
                models = sourceModels;
            }
            else
            {
                source = false;
                models = binaryModels;
            }
            final Model model = models.get( ga );
            if ( model == null )
                throw new RuntimeException( "Assertion violation: build of non-existing project requested " + ga );
            return builder.buildProject( source, model );
        }
    }

    /**
     * Build selected projects and any projects that depend on them directly or indirectly. To determine which projects need to built, all projects need to built.
     * @author bbusjaeger
     *
     */
    static class AlsoMakeDependents
        implements MakeBehavior
    {
        private final Map<GA, ? extends Model> sourceModels;

        private final Map<GA, ? extends Model> binaryModels;

        private final Set<GA> selected;

        public AlsoMakeDependents( Map<GA, ? extends Model> sourceModels, Map<GA, ? extends Model> binaryModels,
                                   Set<GA> selected )
        {
            this.sourceModels = sourceModels;
            this.binaryModels = binaryModels;
            this.selected = selected;
        }

        @Override
        public Iterable<? extends GA> getProjectsToBuild()
        {
            return sourceModels.keySet();
        }

        @Override
        public boolean isProject( GA ga )
        {
            return sourceModels.containsKey( ga ) || binaryModels.containsKey( ga );
        }

        @Override
        public Result<MavenProject> build( Builder builder, GA ga )
        {
            final Result<MavenProject> result;
            final Model srcModel = sourceModels.get( ga );
            /*
             * If we recurse via a binary project's dependency, the project targeted by the dependency may not be available
             * in source form. For example, say A -> B in source, A -> B -> C in binary, and A is selected. Then building
             * the binary project B will trigger building C, which exists only in binary form.
             */
            if ( srcModel == null )
            {
                // selected IDs are computed from the source projects, so they should never contains binary-only project
                if ( selected.contains( ga ) )
                    throw new RuntimeException( "Assertion violation: selected set contains binary project" );

                final Model binaryModel = binaryModels.get( ga );

                // this method must only be called for IDs that are either in the source or binary project set
                if ( binaryModel == null )
                    throw new RuntimeException( "Assertion violation: build of non-existing project requested " + ga );

                final Result<MavenProject> binProject = builder.buildProject( false, binaryModel );
                if ( binProject.get().hasSourceDependency() )
                    // TODO strategy: 1. use binary project anyway, 2. fail with no src avail, 3. fail (currently 2)
                    result = addProblem( binProject, new DefaultModelProblem( "Binary project " + ga
                        + " refers to a source project, but no source project with same id available to use instead",
                                                                              FATAL, BASE, binProject.get().getModel(),
                                                                              -1, -1, null ) );
                else
                    result = binProject;
            }
            /*
             * If a source project exists, we build it first to determine whether it has source dependencies
             */
            else
            {
                final Result<MavenProject> srcProject = builder.buildProject( true, srcModel );
                if ( selected.contains( ga ) )
                {
                    result = srcProject;
                }
                else if ( srcProject.get().hasSourceDependency() )
                {
                    // log that project in source mode, because it depends on source project
                    result = srcProject;
                }
                else
                {
                    final Model binModel = binaryModels.get( ga );
                    if ( binModel == null )
                    {
                        // log that project in source, because no binary project exists
                        result = srcProject;
                    }
                    else
                    {
                        final Result<MavenProject> binProject = builder.buildProject( false, binModel );
                        if ( binProject.get().hasSourceDependency() )
                        {
                            // log that project in source, because binary project depends on source project
                            // TODO strategy: 1. use binary project anyway, 2. use source project, 3. fail (currently 2)
                            result = srcProject;
                        }
                        else
                        {
                            // log that project in binary, because it does not depend on source projects
                            result = binProject;
                        }
                    }
                }
            }
            return result;
        }
    }

}
