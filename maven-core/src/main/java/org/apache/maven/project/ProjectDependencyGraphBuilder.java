package org.apache.maven.project;

import static com.google.common.base.Predicates.and;
import static com.google.common.base.Predicates.compose;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.transform;
import static java.util.Collections.singleton;
import static org.apache.maven.RepositoryUtils.toRepos;
import static org.apache.maven.artifact.repository.LegacyLocalRepositoryManager.overlay;
import static org.apache.maven.model.building.ModelProblem.Severity.FATAL;
import static org.apache.maven.model.building.ModelProblem.Version.BASE;
import static org.apache.maven.model.building.Result.addProblem;
import static org.apache.maven.model.building.Result.error;
import static org.apache.maven.model.building.Result.newResult;
import static org.apache.maven.model.building.Result.newResultSet;
import static org.apache.maven.model.building.Result.success;
import static org.apache.maven.project.DefaultProjectDependencyGraph.newPDG;
import static org.apache.maven.project.GA.getDependencyId;
import static org.apache.maven.project.GA.getId;
import static org.apache.maven.project.GA.getPluginId;
import static org.eclipse.aether.RequestTrace.newChild;

import java.io.File;
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
import org.apache.maven.model.building.DefaultModelProblem;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.Result;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.model.resolution.WorkspaceResolver;
import org.apache.maven.model.superpom.SuperPomProvider;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.impl.RemoteRepositoryManager;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
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
 * <li>Should project selector be applied on interpolated model?
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

    @Requirement
    private MavenRepositorySystem repositorySystem;

    public Result<? extends ProjectDependencyGraph> build(MavenSession session) {
        final MavenExecutionRequest request = session.getRequest();

        // 1. load all models
        final Result<Iterable<Model>> result = modelLoader.loadModules(request.getPom());

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

        final Projects projects = new Projects(selected, srcIndex, binIndex, request.getProjectBuildingRequest()
                .setRepositorySession(session.getRepositorySession()));
        for (GA ga : srcIndex.keySet())
            buildProject(ga, projects);

        final Result<Iterable<MavenProject>> results = newResultSet(projects.completed.values());
        if (results.hasErrors()) return error(results.getProblems());

        return success(newPDG(results.get()), result.getProblems());
    }

    class Projects implements WorkspaceResolver, Predicate<GA> {

        // immutable
        final Set<GA> selectedForSrc;
        final Map<GA, ? extends Model> srcIndex;
        final Map<GA, ? extends Model> binIndex;
        final ProjectBuildingRequest request;

        // mutable
        final Map<GA, Result<MavenProject>> completed;
        final Set<GA> building;

        public Projects(Set<GA> selected, Map<GA, ? extends Model> srcModels, Map<GA, ? extends Model> binModels,
                ProjectBuildingRequest request) {
            this.selectedForSrc = selected;
            this.srcIndex = srcModels;
            this.binIndex = binModels;
            this.request = request;

            // / using linked map should topologically-sort projects as they are added
            this.completed = Maps.newLinkedHashMap();
            // using linked set should preserve cycle order
            this.building = Sets.newLinkedHashSet();
        }

        @Override
        public Model resolveRawModel(String groupId, String artifactId, String version)
                throws UnresolvableModelException {
            final MavenProject project = resolve(groupId, artifactId, version);
            return project == null ? null : project.getOriginalModel();
        }

        @Override
        public Model resolveEffectiveModel(String groupId, String artifactId, String version)
                throws UnresolvableModelException {
            final MavenProject project = resolve(groupId, artifactId, version);
            return project == null ? null : project.getModel();
        }

        private MavenProject resolve(String groupId, String artifactId, String version)
                throws UnresolvableModelException {
            final GA id = new GA(groupId, artifactId);
            if (!isProject(id)) return null;
            final Result<MavenProject> project = completed.get(id);
            if (project == null)
                throw new RuntimeException("Assertion violated: project " + id + " has not completed build");
            if (project.hasErrors())
                throw new UnresolvableModelException("Parent failed to build", groupId, artifactId, version);
            // TODO validate version
            return project.get();
        }

        boolean isProject(GA id) {
            return srcIndex.containsKey(id) || binIndex.containsKey(id);
        }

        @Override
        public boolean apply(GA id) {
            return isProject(id);
        }

        ModelBuildingRequest newModelBuildingRequest(MavenProject project) {
            final ModelBuildingRequest mbr = new DefaultModelBuildingRequest();
            mbr.setValidationLevel(request.getValidationLevel());
            mbr.setProcessPlugins(request.isProcessPlugins());
            mbr.setProfiles(request.getProfiles());
            mbr.setActiveProfileIds(request.getActiveProfileIds());
            mbr.setInactiveProfileIds(request.getInactiveProfileIds());
            mbr.setSystemProperties(request.getSystemProperties());
            mbr.setUserProperties(request.getUserProperties());
            mbr.setBuildStartTime(request.getBuildStartTime());
            mbr.setWorkspaceResolver(this);
            mbr.setTwoPhaseBuilding(true);
            mbr.setProcessPlugins(true);
            mbr.setModelResolver(new ProjectModelResolver(overlay(request.getLocalRepository(),
                    request.getRepositorySession(), repoSystem), newChild(null, request).newChild(mbr), repoSystem,
                    repositoryManager, toRepos(request.getRemoteRepositories()), request.getRepositoryMerging(), null));
            mbr.setModelBuildingListener(new DefaultModelBuildingListener(project, projectBuildingHelper, request));
            return mbr;
        }

    }

    /**
     * Builds nodes using depth-first search traversal. The result
     * 
     * @param id
     * @param model
     * @param projects
     * @return
     */
    Result<MavenProject> buildProject(GA id, Projects projects) {
        // if the project is already built, return it
        final Result<MavenProject> existing = projects.completed.get(id);
        if (existing != null) return existing;

        /*
         * If we are currently building this project and traverse back to it via some dependency, we must have a cycle.
         * Failing here maintains the invariant that the project graph is a DAG, because any newly added project refers
         * only to projects already in the DAG. In other words, no back-edges are added.
         */
        if (!projects.building.add(id))
            throw new IllegalArgumentException("Project dependency cycle detected " + projects.building);

        final Result<MavenProject> result;

        final Model srcModel = projects.srcIndex.get(id);
        /*
         * If we recurse via a binary project's dependency, the project targeted by the dependency may not be available
         * in source form. For example, say A -> B in source, A -> B -> C in binary, and A is selected. Then building
         * the binary project B will trigger building C, which exists only in binary form.
         */
        if (srcModel == null) {
            // selected IDs are computed from the source projects, so they should never contains binary-only project
            assert !projects.selectedForSrc.contains(id);

            final Model binModel = projects.binIndex.get(id);

            // this method must only be called for IDs that are either in the source or binary project set
            assert binModel != null;

            final Result<MavenProject> binProject = buildProject(false, binModel, projects);
            if (binProject.get().hasSourceDependency())
                // TODO strategy: 1. use binary project anyway, 2. fail with no src avail, 3. fail (currently 2)
                result = addProblem(binProject, new DefaultModelProblem("Binary project " + id
                        + " refers to a source project, but no source project with same id available to use instead",
                        FATAL, BASE, binProject.get().getModel(), -1, -1, null));
            else
                result = binProject;
        }
        /*
         * If a source project exists, we build it first to determine whether it has source dependencies
         */
        else {
            final Result<MavenProject> srcProject = buildProject(true, srcModel, projects);
            if (projects.selectedForSrc.contains(id)) {
                result = srcProject;
            } else if (srcProject.get().hasSourceDependency()) {
                // log that project in source mode, because it depends on source project
                result = srcProject;
            } else {
                final Model binModel = projects.binIndex.get(id);
                if (binModel == null) {
                    // log that project in source, because no binary project exists
                    result = srcProject;
                } else {
                    final Result<MavenProject> binProject = buildProject(false, binModel, projects);
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
        projects.completed.put(id, result);

        // the project is unmarked, since it is now part of the graph
        projects.building.remove(id);

        return result;
    }

    // returns partially applied function
    Function<GA, Result<MavenProject>> buildProject(final Projects projects) {
        return new Function<GA, Result<MavenProject>>() {
            @Override
            public Result<MavenProject> apply(GA id) {
                return buildProject(id, projects);
            }
        };
    }

    Result<MavenProject> buildProject(final boolean source, final Model model, final Projects projects) {
        final MavenProject project = new MavenProject();
        project.setOriginalModel(model);
        project.setFile(model.getPomFile());
        project.setSource(source);

        // 1. resolve parents (needed during first phase of build)
        final Parent parent = model.getParent();
        if (parent != null) {
            final GA pid = getId(parent);
            if (projects.isProject(pid)) {
                final Result<MavenProject> result = buildProject(pid, projects);
                if (result.hasErrors())
                    return error(project, singleton(new DefaultModelProblem("Failed to resolve parent", FATAL, BASE,
                            model, -1, -1, null)));
                project.setParent(result.get());
                project.setParentFile(result.get().getFile());
            }
        }

        // 2. perform first phase of build to assemble and interpolate model
        final ModelBuildingRequest request = projects.newModelBuildingRequest(project);
        final ModelBuildingResult result;
        try {
            result = modelBuilder.build(request);
        } catch (ModelBuildingException e) {
            return error(project, e.getProblems());
        }

        // 3. resolve imports (needed during second phase of build)
        final Result<Iterable<MavenProject>> imports = buildDependencies(getImports(result.getEffectiveModel()),
                getDependencyId, projects);
        if (imports.hasErrors()) return error(project, concat(result.getProblems(), imports.getProblems()));

        // 4. perform second phase of build to compute effective model
        try {
            modelBuilder.build(request, result);
        } catch (ModelBuildingException e) {
            return error(project, e.getProblems());
        }
        final Model effectiveModel = result.getEffectiveModel();
        project.setModel(effectiveModel);

        // 5. resolve plugins
        final Result<Iterable<MavenProject>> plugins = buildDependencies(effectiveModel.getBuild().getPlugins(),
                getPluginId, projects);
        if (plugins.hasErrors()) return error(project, plugins.getProblems());
        project.setProjectPlugins(plugins.get());

        // 6. resolve dependencies
        final Result<Iterable<MavenProject>> dependencies = buildDependencies(effectiveModel.getDependencies(),
                getDependencyId, projects);
        if (dependencies.hasErrors()) return error(project, dependencies.getProblems());
        project.setProjectDependencies(dependencies.get());

        initProject(project, result, projects.request);

        return newResult(project, concat(result.getProblems(), plugins.getProblems(), dependencies.getProblems()));
    }

    <T> Result<Iterable<MavenProject>> buildDependencies(Iterable<T> deps, Function<T, GA> getId, Projects projects) {
        return newResultSet(newArrayList(transform(filter(transform(deps, getId), projects), buildProject(projects))));
    }

    static Iterable<Dependency> getImports(Model model) {
        return filter(model.getDependencyManagement().getDependencies(),
                and(compose(equalTo("pom"), new Function<Dependency, String>() {
                    @Override
                    public String apply(Dependency input) {
                        return input.getType();
                    }
                }), compose(equalTo("import"), new Function<Dependency, String>() {
                    @Override
                    public String apply(Dependency input) {
                        return input.getScope();
                    }
                })));
    }

    // TODO mostly copied from {@link DefaulProjectBuilder}
    @SuppressWarnings("deprecation")
    private void initProject(MavenProject project, ModelBuildingResult result,
            ProjectBuildingRequest projectBuildingRequest) {
        // TODO set collected projects and execution root?
        // TODO inject maven project for external parent?

        project.setArtifact(repositorySystem.createArtifact(project.getGroupId(), project.getArtifactId(),
                project.getVersion(), null, project.getPackaging()));

        final Build build = project.getBuild();
        project.addScriptSourceRoot(build.getScriptSourceDirectory());
        project.addCompileSourceRoot(build.getSourceDirectory());
        project.addTestCompileSourceRoot(build.getTestSourceDirectory());

        List<Profile> activeProfiles = new ArrayList<Profile>();
        activeProfiles.addAll(result.getActivePomProfiles(result.getModelIds().get(0)));
        activeProfiles.addAll(result.getActiveExternalProfiles());
        project.setActiveProfiles(activeProfiles);

        project.setInjectedProfileIds("external", getProfileIds(result.getActiveExternalProfiles()));
        for (String modelId : result.getModelIds()) {
            project.setInjectedProfileIds(modelId, getProfileIds(result.getActivePomProfiles(modelId)));
        }

        //
        // All the parts that were taken out of MavenProject for Maven 4.0.0
        //

        project.setProjectBuildingRequest(projectBuildingRequest);

        // pluginArtifacts
        Set<Artifact> pluginArtifacts = new HashSet<Artifact>();
        for (Plugin plugin : project.getBuildPlugins()) {
            Artifact artifact = repositorySystem.createPluginArtifact(plugin);
            if (artifact != null) {
                pluginArtifacts.add(artifact);
            }
        }
        project.setPluginArtifacts(pluginArtifacts);

        // reportArtifacts
        Set<Artifact> reportArtifacts = new HashSet<Artifact>();
        for (ReportPlugin report : project.getReportPlugins()) {
            Plugin pp = new Plugin();
            pp.setGroupId(report.getGroupId());
            pp.setArtifactId(report.getArtifactId());
            pp.setVersion(report.getVersion());

            Artifact artifact = repositorySystem.createPluginArtifact(pp);

            if (artifact != null) {
                reportArtifacts.add(artifact);
            }
        }
        project.setReportArtifacts(reportArtifacts);

        // extensionArtifacts
        Set<Artifact> extensionArtifacts = new HashSet<Artifact>();
        List<Extension> extensions = project.getBuildExtensions();
        if (extensions != null) {
            for (Extension ext : extensions) {
                String version;
                if (StringUtils.isEmpty(ext.getVersion())) {
                    version = "RELEASE";
                } else {
                    version = ext.getVersion();
                }

                Artifact artifact = repositorySystem.createArtifact(ext.getGroupId(), ext.getArtifactId(), version,
                        null, "jar");

                if (artifact != null) {
                    extensionArtifacts.add(artifact);
                }
            }
        }
        project.setExtensionArtifacts(extensionArtifacts);

        // managedVersionMap
        Map<String, Artifact> map = null;
        if (repositorySystem != null) {
            List<Dependency> deps;
            DependencyManagement dependencyManagement = project.getDependencyManagement();
            if ((dependencyManagement != null) && ((deps = dependencyManagement.getDependencies()) != null)
                    && (deps.size() > 0)) {
                map = new HashMap<String, Artifact>();
                for (Dependency d : dependencyManagement.getDependencies()) {
                    Artifact artifact = repositorySystem.createDependencyArtifact(d);

                    if (artifact == null) {
                        map = Collections.emptyMap();
                    }

                    map.put(d.getManagementKey(), artifact);
                }
            } else {
                map = Collections.emptyMap();
            }
        }
        project.setManagedVersionMap(map);

        // release artifact repository
        if (project.getDistributionManagement() != null && project.getDistributionManagement().getRepository() != null) {
            try {
                DeploymentRepository r = project.getDistributionManagement().getRepository();
                if (!StringUtils.isEmpty(r.getId()) && !StringUtils.isEmpty(r.getUrl())) {
                    ArtifactRepository repo = MavenRepositorySystem.buildArtifactRepository(project
                            .getDistributionManagement().getRepository());
                    repositorySystem.injectProxy(projectBuildingRequest.getRepositorySession(), Arrays.asList(repo));
                    repositorySystem.injectAuthentication(projectBuildingRequest.getRepositorySession(),
                            Arrays.asList(repo));
                    project.setReleaseArtifactRepository(repo);
                }
            } catch (InvalidRepositoryException e) {
                throw new IllegalStateException("Failed to create release distribution repository for "
                        + project.getId(), e);
            }
        }

        // snapshot artifact repository
        if (project.getDistributionManagement() != null
                && project.getDistributionManagement().getSnapshotRepository() != null) {
            try {
                DeploymentRepository r = project.getDistributionManagement().getSnapshotRepository();
                if (!StringUtils.isEmpty(r.getId()) && !StringUtils.isEmpty(r.getUrl())) {
                    ArtifactRepository repo = MavenRepositorySystem.buildArtifactRepository(project
                            .getDistributionManagement().getSnapshotRepository());
                    repositorySystem.injectProxy(projectBuildingRequest.getRepositorySession(), Arrays.asList(repo));
                    repositorySystem.injectAuthentication(projectBuildingRequest.getRepositorySession(),
                            Arrays.asList(repo));
                    project.setSnapshotArtifactRepository(repo);
                }
            } catch (InvalidRepositoryException e) {
                throw new IllegalStateException("Failed to create snapshot distribution repository for "
                        + project.getId(), e);
            }
        }
    }

    private List<String> getProfileIds(List<Profile> profiles) {
        return transform(profiles, new Function<Profile, String>() {
            @Override
            public String apply(Profile input) {
                return input.getId();
            }
        });
    }

}