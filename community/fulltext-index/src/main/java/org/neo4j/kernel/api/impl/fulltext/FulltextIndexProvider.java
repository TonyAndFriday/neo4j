/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.impl.fulltext;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.Analyzer;

import java.io.IOException;
import java.util.stream.Stream;

import org.neo4j.configuration.Config;
import org.neo4j.graphdb.schema.AnalyzerProvider;
import org.neo4j.internal.kernel.api.InternalIndexState;
import org.neo4j.internal.schema.IndexCapability;
import org.neo4j.internal.schema.IndexConfig;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.IndexPrototype;
import org.neo4j.internal.schema.IndexProviderDescriptor;
import org.neo4j.internal.schema.IndexType;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.memory.ByteBufferFactory;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.api.impl.index.DatabaseIndex;
import org.neo4j.kernel.api.impl.index.storage.DirectoryFactory;
import org.neo4j.kernel.api.impl.index.storage.IndexStorageFactory;
import org.neo4j.kernel.api.impl.index.storage.PartitionedIndexStorage;
import org.neo4j.kernel.api.impl.schema.LuceneSchemaIndexBuilder;
import org.neo4j.kernel.api.impl.schema.SchemaIndex;
import org.neo4j.kernel.api.index.IndexAccessor;
import org.neo4j.kernel.api.index.IndexDirectoryStructure;
import org.neo4j.kernel.api.index.IndexPopulator;
import org.neo4j.kernel.api.index.IndexProvider;
import org.neo4j.kernel.impl.api.index.IndexSamplingConfig;
import org.neo4j.logging.Log;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.service.Services;
import org.neo4j.storageengine.api.StorageEngineFactory;
import org.neo4j.storageengine.migration.SchemaIndexMigrator;
import org.neo4j.storageengine.migration.StoreMigrationParticipant;
import org.neo4j.token.NonTransactionalTokenNameLookup;
import org.neo4j.token.TokenHolders;
import org.neo4j.token.api.NamedToken;
import org.neo4j.token.api.TokenHolder;
import org.neo4j.token.api.TokenNotFoundException;
import org.neo4j.values.storable.TextValue;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.ValueGroup;
import org.neo4j.values.storable.Values;

import static org.apache.commons.lang3.StringUtils.defaultIfEmpty;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexSettings.createAnalyzer;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexSettings.createPropertyNames;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexSettings.isEventuallyConsistent;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexSettingsKeys.ANALYZER;

public class FulltextIndexProvider extends IndexProvider implements FulltextAdapter
{
    private final FileSystemAbstraction fileSystem;
    private final Config config;
    private final TokenHolders tokenHolders;
    private final boolean isSingleInstance;
    private final String defaultAnalyzerName;
    private final boolean defaultEventuallyConsistentSetting;
    private final Log log;
    private final IndexUpdateSink indexUpdateSink;
    private final IndexStorageFactory indexStorageFactory;

    public FulltextIndexProvider( IndexProviderDescriptor descriptor, IndexDirectoryStructure.Factory directoryStructureFactory,
            FileSystemAbstraction fileSystem, Config config, TokenHolders tokenHolders, DirectoryFactory directoryFactory, boolean isSingleInstance,
            JobScheduler scheduler, Log log )
    {
        super( descriptor, directoryStructureFactory );
        this.fileSystem = fileSystem;
        this.config = config;
        this.tokenHolders = tokenHolders;
        this.isSingleInstance = isSingleInstance;
        this.log = log;

        defaultAnalyzerName = config.get( FulltextSettings.fulltext_default_analyzer );
        defaultEventuallyConsistentSetting = config.get( FulltextSettings.eventually_consistent );
        indexUpdateSink = new IndexUpdateSink( scheduler, config.get( FulltextSettings.eventually_consistent_index_update_queue_max_length ) );
        indexStorageFactory = buildIndexStorageFactory( fileSystem, directoryFactory );
    }

    private IndexStorageFactory buildIndexStorageFactory( FileSystemAbstraction fileSystem, DirectoryFactory directoryFactory )
    {
        return new IndexStorageFactory( directoryFactory, fileSystem, directoryStructure() );
    }

    private boolean indexIsOnline( PartitionedIndexStorage indexStorage, IndexDescriptor descriptor ) throws IOException
    {
        try ( SchemaIndex index = LuceneSchemaIndexBuilder.create( descriptor, config ).withIndexStorage( indexStorage ).build() )
        {
            if ( index.exists() )
            {
                index.open();
                return index.isOnline();
            }
            return false;
        }
    }

    private PartitionedIndexStorage getIndexStorage( long indexId )
    {
        return indexStorageFactory.indexStorageOf( indexId );
    }

    @Override
    public void shutdown() throws Exception
    {
        // Closing the index storage factory also closes all Lucene Directory instances.
        // This has to be done at shutdown, which happens after all of the index accessors have been closed, and thus committed any pent up changes.
        indexStorageFactory.close();
    }

    @Override
    public IndexDescriptor completeConfiguration( IndexDescriptor index )
    {
        IndexConfig indexConfig = index.getIndexConfig();
        indexConfig = addMissingDefaultIndexConfig( indexConfig );
        index = index.withIndexConfig( indexConfig );
        if ( index.getCapability().equals( IndexCapability.NO_CAPABILITY ) )
        {
            index = index.withIndexCapability( getCapability( index ) );
        }
        return index;
    }

    private IndexCapability getCapability( IndexDescriptor index )
    {
        return new FulltextIndexCapability( isEventuallyConsistent( index ) );
    }

    @Override
    public String getPopulationFailure( IndexDescriptor descriptor )
    {
        return defaultIfEmpty( getIndexStorage( descriptor.getId() ).getStoredIndexFailure(), StringUtils.EMPTY );
    }

    @Override
    public InternalIndexState getInitialState( IndexDescriptor descriptor )
    {
        PartitionedIndexStorage indexStorage = getIndexStorage( descriptor.getId() );
        String failure = indexStorage.getStoredIndexFailure();
        if ( failure != null )
        {
            return InternalIndexState.FAILED;
        }
        try
        {
            return indexIsOnline( indexStorage, descriptor ) ? InternalIndexState.ONLINE : InternalIndexState.POPULATING;
        }
        catch ( IOException e )
        {
            return InternalIndexState.POPULATING;
        }
    }

    @Override
    public IndexPopulator getPopulator( IndexDescriptor descriptor, IndexSamplingConfig samplingConfig,
            ByteBufferFactory bufferFactory )
    {
        PartitionedIndexStorage indexStorage = getIndexStorage( descriptor.getId() );
        NonTransactionalTokenNameLookup tokenNameLookup = new NonTransactionalTokenNameLookup( tokenHolders );
        Analyzer analyzer = createAnalyzer( descriptor, tokenNameLookup );
        String[] propertyNames = createPropertyNames( descriptor, tokenNameLookup );
        DatabaseIndex<FulltextIndexReader> fulltextIndex = FulltextIndexBuilder
                .create( descriptor, config, tokenHolders.propertyKeyTokens(), analyzer, propertyNames )
                .withFileSystem( fileSystem )
                .withOperationalMode( isSingleInstance )
                .withIndexStorage( indexStorage )
                .withPopulatingMode( true )
                .build();
        if ( fulltextIndex.isReadOnly() )
        {
            throw new UnsupportedOperationException( "Can't create populator for read only index" );
        }
        log.debug( "Creating populator for fulltext schema index: %s", descriptor );
        return new FulltextIndexPopulator( descriptor, fulltextIndex, propertyNames );
    }

    @Override
    public IndexAccessor getOnlineAccessor( IndexDescriptor index, IndexSamplingConfig samplingConfig ) throws IOException
    {
        PartitionedIndexStorage indexStorage = getIndexStorage( index.getId() );
        NonTransactionalTokenNameLookup tokenNameLookup = new NonTransactionalTokenNameLookup( tokenHolders );
        Analyzer analyzer = createAnalyzer( index, tokenNameLookup );
        String[] propertyNames = createPropertyNames( index, tokenNameLookup );
        FulltextIndexBuilder fulltextIndexBuilder = FulltextIndexBuilder
                .create( index, config, tokenHolders.propertyKeyTokens(), analyzer, propertyNames )
                .withFileSystem( fileSystem )
                .withOperationalMode( isSingleInstance )
                .withIndexStorage( indexStorage )
                .withPopulatingMode( false );
        if ( isEventuallyConsistent( index ) )
        {
            fulltextIndexBuilder = fulltextIndexBuilder.withIndexUpdateSink( indexUpdateSink );
        }
        DatabaseIndex<FulltextIndexReader> fulltextIndex = fulltextIndexBuilder.build();
        fulltextIndex.open();

        FulltextIndexAccessor accessor = new FulltextIndexAccessor( indexUpdateSink, fulltextIndex, index, propertyNames );
        log.debug( "Created online accessor for fulltext schema index %s: %s", index, accessor );
        return accessor;
    }

    @Override
    public StoreMigrationParticipant storeMigrationParticipant( final FileSystemAbstraction fs, PageCache pageCache, StorageEngineFactory storageEngineFactory )
    {
        return new SchemaIndexMigrator( "Fulltext indexes",  fs, this.directoryStructure(), storageEngineFactory );
    }

    @Override
    public void validatePrototype( IndexPrototype prototype )
    {
        String providerName = getProviderDescriptor().name();
        if ( prototype.getIndexType() != IndexType.FULLTEXT )
        {
            throw new IllegalArgumentException( "The '" + providerName + "' index provider only supports FULLTEXT index types: " + prototype );
        }
        if ( !prototype.schema().isFulltextSchemaDescriptor() )
        {
            throw new IllegalArgumentException( "The " + prototype.schema() + " index schema is not a full-text index schema, " +
                    "which it is required to be for the '" + providerName + "' index provider to be able to create an index." );
        }
        Value value = prototype.getIndexConfig().get( ANALYZER );
        if ( value != null )
        {
            if ( value.valueGroup() == ValueGroup.TEXT )
            {
                String analyzerName = ((TextValue) value).stringValue();
                if ( listAvailableAnalyzers().noneMatch( analyzer -> analyzer.getName().equals( analyzerName ) ) )
                {
                    throw new IllegalArgumentException( "No such full-text analyzer: '" + analyzerName + "'." );
                }
            }
            else
            {
                throw new IllegalArgumentException( "Wrong index setting value type for fulltext analyzer: '" + value + "'." );
            }
        }

        TokenHolder propertyKeyTokens = tokenHolders.propertyKeyTokens();
        for ( int propertyId : prototype.schema().getPropertyIds() )
        {
            try
            {
                NamedToken token = propertyKeyTokens.getTokenById( propertyId );
                if ( token.name().equals( LuceneFulltextDocumentStructure.FIELD_ENTITY_ID ) )
                {
                    throw new IllegalArgumentException( "Unable to index the property, the name is reserved for internal use " +
                            LuceneFulltextDocumentStructure.FIELD_ENTITY_ID );
                }
            }
            catch ( TokenNotFoundException e )
            {
                throw new IllegalArgumentException( "Schema references non-existing property key token id: " + propertyId + ".", e );
            }
        }
    }

    private IndexConfig addMissingDefaultIndexConfig( IndexConfig indexConfig )
    {
        indexConfig = indexConfig.withIfAbsent( ANALYZER, Values.stringValue( defaultAnalyzerName ) );
        indexConfig = indexConfig.withIfAbsent( FulltextIndexSettingsKeys.EVENTUALLY_CONSISTENT, Values.booleanValue( defaultEventuallyConsistentSetting ) );
        return indexConfig;
    }

    @Override
    public void awaitRefresh()
    {
        indexUpdateSink.awaitUpdateApplication();
    }

    @Override
    public Stream<AnalyzerProvider> listAvailableAnalyzers()
    {
        return Services.loadAll( AnalyzerProvider.class ).stream();
    }
}
