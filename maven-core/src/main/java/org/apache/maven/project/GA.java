package org.apache.maven.project;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Extension;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;

import com.google.common.base.Function;

/**
 * Unique version-less identifier of a maven project.
 *
 * @author bbusjaeger
 */
class GA
{

    static final Function<Model, GA> getId = new Function<Model, GA>()
    {
        @Override
        public GA apply( Model model )
        {
            return getId( model );
        }
    };

    static final Function<Parent, GA> getParentId = new Function<Parent, GA>()
    {
        @Override
        public GA apply( Parent parent )
        {
            return getId( parent );
        }
    };

    static final Function<Dependency, GA> getDependencyId = new Function<Dependency, GA>()
    {
        @Override
        public GA apply( Dependency dependency )
        {
            return getId( dependency );
        }
    };

    static final Function<Plugin, GA> getPluginId = new Function<Plugin, GA>()
    {
        @Override
        public GA apply( Plugin plugin )
        {
            return getId( plugin );
        }
    };

    static final Function<Extension, GA> getExtensionId = new Function<Extension, GA>()
    {
        @Override
        public GA apply( Extension plugin )
        {
            return getId( plugin );
        }
    };

    static GA getId( Parent parent )
    {
        return new GA( parent.getGroupId(), parent.getArtifactId() );
    }

    static GA getId( Dependency dependency )
    {
        return new GA( dependency.getGroupId(), dependency.getArtifactId() );
    }

    static GA getId( Model model )
    {
        return new GA( getGroupId( model ), model.getArtifactId() );
    }

    static GA getId( Plugin plugin )
    {
        return new GA( plugin.getGroupId(), plugin.getArtifactId() );
    }

    static GA getId( Extension extension )
    {
        return new GA( extension.getGroupId(), extension.getArtifactId() );
    }

    static String getGroupId( Model model )
    {
        if ( model.getGroupId() == null )
            return model.getParent() == null ? null : model.getParent().getGroupId();
        else
            return model.getGroupId();
    }

    private final String groupId;

    private final String artifactId;

    GA( String groupId, String artifactId )
    {
        if ( groupId == null || artifactId == null )
            throw new NullPointerException();
        this.groupId = groupId;
        this.artifactId = artifactId;
    }

    public String getGroupId()
    {
        return groupId;
    }

    public String getArtifactId()
    {
        return artifactId;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + artifactId.hashCode();
        result = prime * result + groupId.hashCode();
        return result;
    }

    @Override
    public boolean equals( Object obj )
    {
        if ( this == obj )
            return true;
        if ( obj == null || getClass() != obj.getClass() )
            return false;
        GA other = (GA) obj;
        return artifactId.equals( other.artifactId ) && groupId.equals( other.groupId );
    }

    @Override
    public String toString()
    {
        return groupId + ':' + artifactId;
    }
}