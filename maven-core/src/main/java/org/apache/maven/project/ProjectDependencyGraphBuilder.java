package org.apache.maven.project;

import static com.google.common.base.Predicates.and;
import static com.google.common.base.Predicates.compose;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.notNull;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Lists.newArrayList;
import static org.apache.maven.model.building.ModelBuildingRequest.VALIDATION_LEVEL_STRICT;
import static org.apache.maven.model.building.ModelProblem.Severity.FATAL;
import static org.apache.maven.model.building.ModelProblem.Version.BASE;
import static org.apache.maven.model.building.Result.error;
import static org.apache.maven.model.building.Result.newResult;
import static org.apache.maven.model.building.Result.newResultSet;
import static org.apache.maven.model.building.Result.success;
import static org.apache.maven.project.DefaultProjectDependencyGraph.newPDG;
import static org.apache.maven.project.GA.getDependencyId;
import static org.apache.maven.project.GA.getId;
import static org.apache.maven.project.GA.getParentId;
import static org.apache.maven.project.GA.getPluginId;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.tools.DiagnosticListener;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.repository.LegacyLocalRepositoryManager;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.ActivatedModel;
import org.apache.maven.model.building.DefaultModelProblem;
import org.apache.maven.model.building.InterpolatedModel;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuilder.Parents;
import org.apache.maven.model.building.ModelLoader;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.ModelProblem.Version;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.building.Result;
import org.apache.maven.model.profile.DefaultExternalProfileActivationContext;
import org.apache.maven.model.profile.ExternalProfileActivationContext;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.model.superpom.SuperPomProvider;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.RequestTrace;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;

import com.google.common.base.Function;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

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
 * <li>Feasible to run project selector before merging parent or interpolating?
 * </ol>
 * TODO
 * <ol>
 * <li>evaluate profiles during model loading, since profiles can add modules
 * <li>pass a {@link DiagnosticListener} around to collect errors instead of failing immediately
 * </ol>
 * 
 * @author bbusjaeger
 */
@Component(role = ProjectDependencyGraphBuilder.class)
public class ProjectDependencyGraphBuilder {

    @Requirement
    private ModelLoader modelLoader;

    @Requirement
    private ModelSelector modelSelector;

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

    public Result<? extends ProjectDependencyGraph> build(MavenSession session) {
        final MavenExecutionRequest request = session.getRequest();

        // 1. load all models
        final Result<Iterable<? extends Model>> result = modelLoader.loadModules(request.getPom());

        // if we cannot build the model index, do not continue
        if (result.hasErrors()) return error(result.getProblems());

        // 2. index by Id: throws IllegalArgumentException if multiple projects with same ID
        final Map<GA, ? extends Model> srcIndex;
        try {
            srcIndex = Maps.uniqueIndex(result.get(), getId);
        } catch (IllegalArgumentException e) {
            return error(Collections.singleton(new DefaultModelProblem("Duplicate project identifiers: "
                    + e.getMessage(), FATAL, BASE, "", -1, -1, null, e)));
        }

        // 3. determine selected projects based on '--projects'
        final Set<GA> selected = modelSelector.select(new File(request.getBaseDirectory()),
                request.getSelectedProjects(), srcIndex).keySet();

        // TODO load from generation
        final Map<GA, ? extends Model> binIndex = Collections.emptyMap();

        final BuildState state = new BuildState(selected, srcIndex, binIndex, request.getProjectBuildingRequest()
                .setRepositorySession(session.getRepositorySession()));
        for (GA ga : srcIndex.keySet())
            buildProject(ga, state);

        final Result<Iterable<? extends MavenProject>> results = newResultSet(state.projects.values());
        if (results.hasErrors()) return error(results.getProblems());

        return success(newPDG(results.get()));
    }

    // TODO figure out what this is
    static class BuildState {

        // immutable
        final Set<GA> selectedForSrc;
        final Map<GA, ? extends Model> srcIndex;
        final Map<GA, ? extends Model> binIndex;
        final ProjectBuildingRequest request;
        final ExternalProfileActivationContext epac;

        // mutable
        final Map<GA, Result<MavenProject>> projects;
        final Set<GA> building;

        public BuildState(Set<GA> selected, Map<GA, ? extends Model> srcModels, Map<GA, ? extends Model> binModels,
                ProjectBuildingRequest request) {
            this.selectedForSrc = selected;
            this.srcIndex = srcModels;
            this.binIndex = binModels;
            this.request = request;
            this.epac = new DefaultExternalProfileActivationContext(request.getActiveProfileIds(),
                    request.getInactiveProfileIds(), request.getSystemProperties(), request.getUserProperties());

            // / using linked map should topologically-sort projects as they are added
            this.projects = Maps.newLinkedHashMap();
            // using linked set should preserve cycle order
            this.building = Sets.newLinkedHashSet();
        }

    }

    /**
     * Builds nodes using depth-first search traversal. The result
     * 
     * @param id
     * @param model
     * @param state
     * @return
     */
    Result<MavenProject> buildProject(GA id, BuildState state) {
        // if the project is already built, return it
        final Result<MavenProject> existing = state.projects.get(id);
        if (existing != null) return existing;

        /*
         * If we are currently building this project and traverse back to it via some dependency, we must have a cycle.
         * Failing here maintains the invariant that the project graph is a DAG, because any newly added project refers
         * only to projects already in the DAG. In other words, no back-edges are added.
         */
        if (!state.building.add(id))
            throw new IllegalArgumentException("Project dependency cycle detected " + state.building);

        final Result<MavenProject> result;

        final Model srcModel = state.srcIndex.get(id);
        /*
         * If we recurse via a binary project's dependency, the project targeted by the dependency may not be available
         * in source form. For example, say A -> B in source, A -> B -> C in binary, and A is selected. Then building
         * the binary project B will trigger building C, which exists only in binary form.
         */
        if (srcModel == null) {
            // selected IDs are computed from the source projects, so they should never contains binary-only project
            assert !state.selectedForSrc.contains(id);

            final Model binModel = state.binIndex.get(id);

            // this method must only be called for IDs that are either in the source or binary project set
            assert binModel != null;

            final Result<MavenProject> binProject = buildProject(false, id, binModel, state);
            if (binProject.get().hasSourceDependency())
                // TODO strategy: 1. use binary project anyway, 2. fail with no src avail, 3. fail (currently 2)
                result = newResult(binProject, new DefaultModelProblem("Binary project " + id
                        + " refers to a source project, but no source project with same id available to use instead",
                        FATAL, BASE, binProject.get().getModel(), -1, -1, null));
            else
                result = binProject;
        }
        /*
         * If a source project exists, we build it first to determine whether it has source dependencies
         */
        else {
            final Result<MavenProject> srcProject = buildProject(true, id, srcModel, state);
            if (state.selectedForSrc.contains(id)) {
                result = srcProject;
            } else if (srcProject.get().hasSourceDependency()) {
                // log that project in source mode, because it depends on source project
                result = srcProject;
            } else {
                final Model binModel = state.binIndex.get(id);
                if (binModel == null) {
                    // log that project in source, because no binary project exists
                    result = srcProject;
                } else {
                    final Result<MavenProject> binProject = buildProject(false, id, binModel, state);
                    if (binProject.get().hasSourceDependency()) {
                        // log that project in source, because binary project depends on source project
                        // TODO strategy: 1. use binary project anyway, 2. use source project, 3. fail (currently 2)
                        result = srcProject;
                    } else {
                        // log that project in binary, because it does not depend on source projects
                        result = binProject;
                    }
                }
            }
        }

        // at this point all project dependencies are in the graph, so this project can be added as well
        state.projects.put(id, result);

        // the project is unmarked, since it is now part of the graph
        state.building.remove(id);

        return result;
    }

    Result<MavenProject> buildProject(final boolean source, final GA id, final Model model, final BuildState state) {
        final MavenProject project = new MavenProject();
        project.setOriginalModel(model.clone());
        project.setSource(source);

        // 1. activate model -> should likely be moved into model loader, since profiles can add modules
        final Result<ActivatedModel> activation = modelBuilder.activate(model, state.request.getProfiles(), state.epac);
        if (activation.hasErrors()) return error(project, activation.getProblems());

        // 2. build parent models as they are needed to interpolate model
        final Model activeModel = activation.get().getActiveModel();
        final MavenProject parent;
        if (activeModel.getParent() == null) {
            parent = null;
        } else {
            final Result<MavenProject> parentResult = buildDependency(activeModel.getParent(), getParentId, state);
            if (parentResult == null)
                parent = null;
            else if (parentResult.hasErrors())
                return error(parentResult.getProblems()); // TODO don't return parent problem
            else
                parent = parentResult.get();
        }

        // 3. interpolate model using resolved parents - build effective resolver in the process
        final ModelResolver resolver = newModelResolver(state, activeModel);
        final Parents parents = new ParentsImpl(resolver, parent);
        final Result<InterpolatedModel> interpolationResult = modelBuilder.interpolate(activation.get(), parents,
                state.request.getValidationLevel(), state.request.getBuildStartTime(),
                state.request.getSystemProperties(), state.request.getUserProperties());
        if (interpolationResult.hasErrors())
            return error(project, concat(activation.getProblems(), interpolationResult.getProblems()));
        addRepositories(resolver, interpolationResult.get().getInterpolatedModel().getRepositories(), true);

        // 4. now resolve imports, so we can build the effective model
        final Result<Iterable<? extends MavenProject>> imports = buildDependencies(filterPomImports(interpolationResult
                .get().getInterpolatedModel().getDependencyManagement().getDependencies()), getDependencyId, state);
        if (imports.hasErrors()) return error(project, imports.getProblems());
        project.setProjectImports(imports.get());

        // 5. enable project (extensions etc)
        final Result<Model> effective = modelBuilder.enable(interpolationResult.get(), state.request
                .getValidationLevel(), source, new DefaultModelBuildingListener(project, projectBuildingHelper,
                state.request));
        if (effective.hasErrors()) return error(project, effective.getProblems());
        project.setModel(effective.get());

        // now that effective model is built, resolve dependencies (incl plugins)
        final Result<Iterable<? extends MavenProject>> plugins = buildDependencies(effective.get().getBuild()
                .getPlugins(), getPluginId, state);
        if (plugins.hasErrors()) return error(project, plugins.getProblems());
        project.setProjectPlugins(plugins.get());

        final Result<Iterable<? extends MavenProject>> dependencies = buildDependencies(effective.get()
                .getDependencies(), getDependencyId, state);
        if (dependencies.hasErrors()) return error(project, dependencies.getProblems());
        project.setProjectDependencies(dependencies.get());

        // TODO initialize maven project

        return Result.newResult(project,
                concat(activation.getProblems(), interpolationResult.getProblems(), effective.getProblems()));
    }

    <T> Result<Iterable<? extends MavenProject>> buildDependencies(Iterable<T> dependencies,
            final Function<T, GA> getId, final BuildState state) {
        return newResultSet(newArrayList(filter(transform(dependencies, new Function<T, Result<MavenProject>>() {
            @Override
            public Result<MavenProject> apply(T ref) {
                return buildDependency(ref, getId, state);
            }
        }), notNull())));
    }

    <T> Result<MavenProject> buildDependency(T ref, Function<T, GA> getId, BuildState state) {
        final GA id = getId.apply(ref);
        return state.srcIndex.containsKey(id) || state.binIndex.containsKey(id) ? buildProject(id, state) : null;
    }

    final class ParentsImpl implements Parents {
        private final ModelResolver resolver;
        private final MavenProject parent;

        ParentsImpl(ModelResolver resolver, MavenProject parent) {
            this.resolver = resolver;
            this.parent = parent;
        }

        @Override
        public Result<? extends Iterable<? extends ActivatedModel>> traverse(Visitor v) {
            final List<Result<ActivatedModel>> results = newArrayList();
            final Parent superModelRef = new Parent();
            MavenProject parentProject = parent;
            Parent parentRef = parent == null ? superModelRef : parent.getOriginalModel().getParent();
            while (parentRef != null) {
                final Result<Model> result;
                if (parentRef == superModelRef)
                    result = success(superPomProvider.getSuperModel("4.0.0").clone());
                else if (parentProject != null)
                    result = success(parentProject.getOriginalModel());
                else
                    result = resolve(resolver, parentRef.getGroupId(), parentRef.getArtifactId(),
                            parentRef.getVersion());

                final Result<ActivatedModel> activatedModel = v.visit(result);
                results.add(activatedModel);
                if (activatedModel.hasErrors()) return newResultSet(results);

                final Model activeModel = activatedModel.get().getActiveModel();
                addRepositories(resolver, activeModel.getRepositories());

                if (parentRef == superModelRef)
                    parentRef = null;
                else
                    parentRef = activeModel.getParent() == null ? superModelRef : activeModel.getParent();
                if (parentProject != null) parentProject = parentProject.getParent();
            }
            return newResultSet(results);
        }
    }

    // resolution-related methods

    Result<Model> resolve(ModelResolver resolver, String groupId, String artifactId, String version) {
        try {
            final ModelSource ms = resolver.resolveModel(groupId, artifactId, version);
            // TODO cache
            // TODO parent uses different validation level
            return modelBuilder.load(ms, VALIDATION_LEVEL_STRICT, true);
        } catch (UnresolvableModelException e) {
            final ModelProblem problem = new DefaultModelProblem("Failed to resolve reference",
                    ModelProblem.Severity.FATAL, Version.BASE, "", -1, -1, groupId + ":" + artifactId + ":" + version,
                    e);
            return newResult(null, Collections.singleton(problem));
        }
    }

    private void addRepositories(ModelResolver resolver, Iterable<? extends Repository> repositories) {
        addRepositories(resolver, repositories, false);
    }

    private void addRepositories(ModelResolver resolver, Iterable<? extends Repository> repositories, boolean replace) {
        for (Repository repository : repositories) {
            try {
                resolver.addRepository(repository, replace);
            } catch (InvalidRepositoryException e) {
                // TODO handle errors
                // problems.add(new ModelProblemCollectorRequest(Severity.ERROR, Version.BASE)
                // .setMessage("Invalid repository " + repository.getId() + ": " + e.getMessage())
                // .setLocation(repository.getLocation("")).setException(e));
            }
        }
    }

    private ModelResolver newModelResolver(BuildState state, Model model) {
        final ModelResolver resolver = newModelResolver(state);
        addRepositories(resolver, model.getRepositories());
        return resolver;
    }

    private ModelResolver newModelResolver(BuildState state) {
        // TODO child used to be project building request
        final RequestTrace trace = RequestTrace.newChild(null, state.request);
        final RepositorySystemSession session = LegacyLocalRepositoryManager.overlay(
                state.request.getLocalRepository(), state.request.getRepositorySession(), repoSystem);
        final List<RemoteRepository> repositories = RepositoryUtils.toRepos(state.request.getRemoteRepositories());
        return new ProjectModelResolver(session, trace, repoSystem, repositoryManager, repositories,
                state.request.getRepositoryMerging(), null);
    }

    static Iterable<Dependency> filterPomImports(Iterable<Dependency> dependencies) {
        return filter(dependencies, and(compose(equalTo("pom"), getType), compose(equalTo("import"), getScope)));
    }

    static final Function<Dependency, String> getType = new Function<Dependency, String>() {
        @Override
        public String apply(Dependency input) {
            return input.getType();
        }
    };

    static final Function<Dependency, String> getScope = new Function<Dependency, String>() {
        @Override
        public String apply(Dependency input) {
            return input.getScope();
        }
    };

}