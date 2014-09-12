package org.apache.maven.project;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;

import java.util.List;
import java.util.Map;

import org.apache.maven.execution.ProjectDependencyGraph;

class DefaultProjectDependencyGraph
    implements ProjectDependencyGraph
{

    static ProjectDependencyGraph newPDG( Iterable<? extends MavenProject> sortedProjects )
    {
        final List<MavenProject> projects = newArrayList();
        final Map<MavenProject, List<MavenProject>> dependents = newHashMap();
        for ( MavenProject project : sortedProjects )
        {
            projects.add( project );
            for ( MavenProject dependency : project.getAllProjectDependencies() )
            {
                List<MavenProject> l = dependents.get( dependency );
                if ( l == null )
                    dependents.put( dependency, l = newArrayList() );
                l.add( project );
            }
        }
        return new DefaultProjectDependencyGraph( projects, dependents );
    }

    private final List<MavenProject> sortedProjects;

    private final Map<MavenProject, List<MavenProject>> dependents;

    DefaultProjectDependencyGraph( List<MavenProject> sortedProjects, Map<MavenProject, List<MavenProject>> dependents )
    {
        this.sortedProjects = sortedProjects;
        this.dependents = dependents;
    }

    @Override
    public List<MavenProject> getSortedProjects()
    {
        return sortedProjects;
    }

    @Override
    public List<MavenProject> getDownstreamProjects( MavenProject project, boolean transitive )
    {
        // TODO transitive
        return dependents.get( project );
    }

    @Override
    public List<MavenProject> getUpstreamProjects( MavenProject project, boolean transitive )
    {
        // TODO transitive
        return newArrayList( project.getAllProjectDependencies() );
    }

}
