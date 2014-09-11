package org.apache.maven.project;

import static com.google.common.base.Joiner.on;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newHashSet;
import static java.io.File.separatorChar;
import static org.apache.maven.model.building.ModelBuildingRequest.VALIDATION_LEVEL_STRICT;
import static org.apache.maven.model.building.ModelProblem.Severity.ERROR;
import static org.apache.maven.model.building.ModelProblem.Version.BASE;
import static org.apache.maven.model.building.Result.addProblems;
import static org.apache.maven.model.building.Result.newResultSet;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;

import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelProblem;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.Result;
import org.apache.maven.model.io.ModelParseException;
import org.apache.maven.model.locator.ModelLocator;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;

/**
 * Loads the model for the given pom file and all models that can be located on the local file system by recursively
 * traversing modules references. <br/>
 * Note we could activate profiles for each loaded module to handle the case that profiles add modules. For now I have
 * omitted that part to keep it simple.
 *
 * @author bbusjaeger
 */
@Component(role = ModelLoader.class)
public class ModelLoader {

    @Requirement
    private ModelLocator modelLocator;

    @Requirement
    private ModelBuilder modelBuilder;

    /**
     * Loads the model for the given pom file and all models that can be located on the local file system by recursively
     * traversing parent and modules references.
     * 
     * @param pom
     * @param models
     * @return
     * @throws ModelParseException
     * @throws IOException
     */
    public Result<Iterable<Model>> loadModules(File pom) {
        final Collection<Result<? extends Model>> results = newArrayList();
        final Set<File> visited = newHashSet(pom);
        load(pom, results, visited);
        return newResultSet(results);
    }

    /**
     * Loads models recursively
     *
     * @param pom
     * @param models
     * @param visited
     * @throws ModelParseException
     * @throws IOException
     */
    void load(File pom, Collection<Result<? extends Model>> results, Set<File> visited) {
        // TODO pass in validation level and location tracking
        // TODO activate profiles to allow for module injection
        final Result<? extends Model> result = modelBuilder.buildRawModel(pom, VALIDATION_LEVEL_STRICT, true);

        final Model model = result.get();
        // model completely failed to load, use result
        if (model == null) {
            results.add(result);
        }
        // otherwise, try to traverse modules (even if result has errors)
        else {
            final Collection<ModelProblem> problems = newArrayList();
            for (String module : model.getModules()) {
                final File modulePom = getModulePomFile(pom, module);

                if (modulePom == null) {
                    problems.add(new DefaultModelProblem(
                            "Child module " + modulePom + " of " + pom + " does not exist", ERROR, BASE, model, -1, -1,
                            null));
                    continue;
                }

                if (!visited.add(modulePom)) {
                    problems.add(new DefaultModelProblem("Child module " + modulePom + " of " + pom
                            + " forms aggregation cycle " + on(" -> ").join(visited), ERROR, BASE, model, -1, -1, null));
                    visited.remove(modulePom);
                    continue;
                }

                load(modulePom, results, visited);
            }
            results.add(addProblems(result, problems));
        }
    }

    /**
     * Locates the module pom file for the given module relative to the given pom file. If no pom file can be found,
     * null is returned.
     * 
     * @param pom
     * @param module
     * @return
     */
    File getModulePomFile(File pom, String module) {
        final File moduleFile = new File(pom.getParentFile(), module.replace('\\', separatorChar).replace('/',
                separatorChar));
        return moduleFile.isFile() ? moduleFile
                : (moduleFile.isDirectory() ? modelLocator.locatePom(moduleFile) : null);
    }

}