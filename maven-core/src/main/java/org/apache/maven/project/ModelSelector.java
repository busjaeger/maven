package org.apache.maven.project;

import static com.google.common.base.Predicates.or;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Maps.filterValues;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Model;
import org.codehaus.plexus.component.annotations.Component;

import com.google.common.base.Function;
import com.google.common.base.Predicate;

/**
 * Reduces the set of source projects to the ones selected by the user
 * 
 * @author bbusjaeger
 */
@Component(role = ModelSelector.class)
public class ModelSelector
{

    static interface Selector
        extends Predicate<Model>
    {
        @Override
        public boolean apply( Model model );
    }

    static class ArtifactIdSelector
        implements Selector
    {
        private final String artifactId;

        ArtifactIdSelector( String artifactId )
        {
            assert artifactId != null;
            this.artifactId = artifactId;
        }

        @Override
        public boolean apply( Model model )
        {
            return artifactId.equals( model.getArtifactId() );
        }

        @Override
        public int hashCode()
        {
            return artifactId.hashCode();
        }

        @Override
        public boolean equals( Object obj )
        {
            if ( this == obj )
                return true;
            if ( obj == null || getClass() != obj.getClass() )
                return false;
            return ( (ArtifactIdSelector) obj ).artifactId.equals( artifactId );
        }

    }

    static class IdSelector
        extends ArtifactIdSelector
    {
        private final String groupId;

        public IdSelector( String artifactId, String groupId )
        {
            super( artifactId );
            this.groupId = groupId;
        }

        @Override
        public boolean apply( Model model )
        {
            return super.apply( model ) && groupId.equals( model.getGroupId() );
        }

        @Override
        public int hashCode()
        {
            return 31 * super.hashCode() + groupId.hashCode();
        }

        @Override
        public boolean equals( Object obj )
        {
            if ( this == obj )
                return true;
            return super.equals( obj ) && ( (IdSelector) obj ).groupId.equals( groupId );
        }

    }

    static abstract class FileSelector
        implements Selector
    {
        protected final File file;

        public FileSelector( File file )
        {
            this.file = file;
        }

        @Override
        public int hashCode()
        {
            return file.hashCode();
        }

        @Override
        public boolean equals( Object obj )
        {
            if ( this == obj )
                return true;
            if ( obj == null || getClass() != obj.getClass() )
                return false;
            return ( (FileSelector) obj ).file.equals( file );
        }
    }

    static class ProjectFileSelector
        extends FileSelector
    {

        public ProjectFileSelector( File file )
        {
            super( file );
        }

        @Override
        public boolean apply( Model model )
        {
            return file.equals( model.getPomFile() );
        }

    }

    static class ProjectDirSelector
        extends FileSelector
    {

        public ProjectDirSelector( File file )
        {
            super( file );
        }

        @Override
        public boolean apply( Model model )
        {
            return file.equals( model.getPomFile().getParentFile() );
        }
    }

    static Selector parseSelector( String string, File baseDir )
    {
        final Selector selector;
        final int idx = string.indexOf( ':' );
        if ( idx >= 0 )
        {
            final String groupId = string.substring( 0, idx );
            final String artifactId = string.substring( idx + 1, string.length() );
            if ( artifactId.indexOf( ':' ) >= 0 )
                throw new IllegalArgumentException( "invalid selector " + string + ": contains more than one ':'" );
            if ( artifactId.length() == 0 )
                throw new IllegalArgumentException( "invalid selector " + string + ": artifactId missing" );
            selector = groupId.length() == 0 ? new ArtifactIdSelector( artifactId ) : new IdSelector( artifactId,
                                                                                                      groupId );
        }
        else
        {
            final File file = new File( new File( baseDir, string ).toURI().normalize() );
            if ( file.isFile() )
                selector = new ProjectFileSelector( file );
            else if ( file.isDirectory() )
                selector = new ProjectDirSelector( file );
            else
                throw new IllegalArgumentException( "invalid selector " + string + ": no file or directory at " + file );
        }
        return selector;
    }

    static Function<String, Selector> parseSelectorClosure( final File baseDir )
    {
        return new Function<String, Selector>()
        {
            @Override
            public Selector apply( String selector )
            {
                return parseSelector( selector, baseDir );
            }
        };
    }

    // TODO fail if any predicate doesn't match anything?
    public Map<GA, ? extends Model> select( final File baseDir, final List<String> selectors,
                                            final Map<GA, ? extends Model> models )
    {
        return filterValues( models, or( transform( selectors, parseSelectorClosure( baseDir ) ) ) );
    }

}
