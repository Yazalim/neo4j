/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.kernel.impl.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.neo4j.graphdb.TransactionFailureException;
import org.neo4j.helpers.Function;
import org.neo4j.helpers.Predicate;
import org.neo4j.helpers.Predicates;
import org.neo4j.helpers.collection.IteratorUtil;
import org.neo4j.kernel.api.EntityType;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.constraints.UniquenessConstraint;
import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.api.exceptions.LabelNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.PropertyKeyIdNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.RelationshipTypeIdNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.schema.SchemaRuleNotFoundException;
import org.neo4j.kernel.api.exceptions.schema.TooManyLabelsException;
import org.neo4j.kernel.api.index.InternalIndexState;
import org.neo4j.kernel.api.operations.AuxiliaryStoreOperations;
import org.neo4j.kernel.api.operations.EntityReadOperations;
import org.neo4j.kernel.api.operations.EntityWriteOperations;
import org.neo4j.kernel.api.operations.KeyReadOperations;
import org.neo4j.kernel.api.operations.KeyWriteOperations;
import org.neo4j.kernel.api.operations.SchemaReadOperations;
import org.neo4j.kernel.api.properties.Property;
import org.neo4j.kernel.api.properties.SafeProperty;
import org.neo4j.kernel.impl.api.index.IndexDescriptor;
import org.neo4j.kernel.impl.api.index.IndexingService;
import org.neo4j.kernel.impl.core.LabelTokenHolder;
import org.neo4j.kernel.impl.core.PropertyKeyTokenHolder;
import org.neo4j.kernel.impl.core.RelationshipTypeTokenHolder;
import org.neo4j.kernel.impl.core.Token;
import org.neo4j.kernel.impl.core.TokenHolder;
import org.neo4j.kernel.impl.core.TokenNotFoundException;
import org.neo4j.kernel.impl.nioneo.store.IndexRule;
import org.neo4j.kernel.impl.nioneo.store.InvalidRecordException;
import org.neo4j.kernel.impl.nioneo.store.NeoStore;
import org.neo4j.kernel.impl.nioneo.store.NodeStore;
import org.neo4j.kernel.impl.nioneo.store.PrimitiveRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyBlock;
import org.neo4j.kernel.impl.nioneo.store.PropertyRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyStore;
import org.neo4j.kernel.impl.nioneo.store.RelationshipStore;
import org.neo4j.kernel.impl.nioneo.store.SchemaRule;
import org.neo4j.kernel.impl.nioneo.store.UnderlyingStorageException;
import org.neo4j.kernel.impl.nioneo.store.UniquenessConstraintRule;
import org.neo4j.kernel.impl.persistence.PersistenceManager;

import static org.neo4j.helpers.collection.Iterables.filter;
import static org.neo4j.helpers.collection.Iterables.map;
import static org.neo4j.helpers.collection.IteratorUtil.asPrimitiveIterator;
import static org.neo4j.helpers.collection.IteratorUtil.contains;
import static org.neo4j.helpers.collection.IteratorUtil.emptyPrimitiveLongIterator;
import static org.neo4j.kernel.impl.nioneo.store.labels.NodeLabelsField.parseLabelsField;

/**
 * This layer interacts with committed data. It currently delegates to several of the older XXXManager-type classes.
 * This should be refactored to use a cleaner read-only interface.
 *
 * Also, caching currently lives above this layer, but it really should live *inside* the read-only abstraction that
 * this
 * thing takes.
 *
 * Cache reading and invalidation is not the concern of this part of the system, that is an optimization on top of the
 * committed data in the database, and should as such live under that abstraction.
 */
public class StoreStatementOperations implements
    KeyReadOperations,
    KeyWriteOperations,
    EntityReadOperations,
    EntityWriteOperations,
    SchemaReadOperations,
    AuxiliaryStoreOperations

{
    private static final Function<UniquenessConstraintRule, UniquenessConstraint> UNIQUENESS_CONSTRAINT_TO_RULE =
            new Function<UniquenessConstraintRule, UniquenessConstraint>()
    {
        @Override
        public UniquenessConstraint apply( UniquenessConstraintRule rule )
        {
            // We can use propertyKeyId straight up here, without reading from the record, since we have
            // verified that it has that propertyKeyId in the predicate. And since we currently only support
            // uniqueness on single properties, there is nothing else to pass in to UniquenessConstraint.
            return new UniquenessConstraint( rule.getLabel(), rule.getPropertyKey() );
        }
    };
    
    private final PropertyKeyTokenHolder propertyKeyTokenHolder;
    private final LabelTokenHolder labelTokenHolder;
    private final NeoStore neoStore;
    private final IndexingService indexService;
    private final NodeStore nodeStore;
    private final RelationshipStore relationshipStore;
    private final PropertyStore propertyStore;
    private final RelationshipTypeTokenHolder relationshipTypeTokenHolder;
    private final SchemaStorage schemaStorage;
    
    // TODO this is here since the move of properties from Primitive and friends to the Kernel API.
    // ideally we'd have StateHandlingStatementContext not delegate setProperty to this StoreStatementContext,
    // but talk to use before commit instead.
    private final PersistenceManager persistenceManager;

    public StoreStatementOperations( PropertyKeyTokenHolder propertyKeyTokenHolder, LabelTokenHolder labelTokenHolder,
                                     RelationshipTypeTokenHolder relationshipTypeTokenHolder,
                                     SchemaStorage schemaStorage, NeoStore neoStore,
                                     PersistenceManager persistenceManager,
                                     IndexingService indexService )
    {
        this.relationshipTypeTokenHolder = relationshipTypeTokenHolder;
        this.schemaStorage = schemaStorage;
        assert neoStore != null : "No neoStore provided";

        this.indexService = indexService;
        this.propertyKeyTokenHolder = propertyKeyTokenHolder;
        this.labelTokenHolder = labelTokenHolder;
        this.neoStore = neoStore;
        this.nodeStore = neoStore.getNodeStore();
        this.relationshipStore = neoStore.getRelationshipStore();
        this.propertyStore = neoStore.getPropertyStore();
        this.persistenceManager = persistenceManager;
    }

    private UnsupportedOperationException shouldNotManipulateStoreDirectly()
    {
        throw new UnsupportedOperationException(
                "The storage layer can not be written to directly, you have to go through a transaction." );
    }
    
    private UnsupportedOperationException shouldNotHaveReachedAllTheWayHere()
    {
        throw new UnsupportedOperationException(
                "This call should not reach all the way here" );
    }
    
    private UnsupportedOperationException shouldCallAuxiliaryInstead()
    {
        return new UnsupportedOperationException(
                "This shouldn't be called directly, but instead to an appropriate method in the " +
                        AuxiliaryStoreOperations.class.getSimpleName() + " interface" );
    }
    
    @Override
    public long labelGetOrCreateForName( Statement state, String label ) throws TooManyLabelsException
    {
        try
        {
            return labelTokenHolder.getOrCreateId( label );
        }
        catch ( TransactionFailureException e )
        {
            // Temporary workaround for the property store based label
            // implementation. Actual
            // implementation should not depend on internal kernel exception
            // messages like this.
            if ( e.getCause() instanceof UnderlyingStorageException
                 && e.getCause().getMessage().equals( "Id capacity exceeded" ) )
            {
                throw new TooManyLabelsException( e );
            }
            else
            {
                throw e;
            }
        }
    }

    @Override
    public long labelGetForName( Statement state, String label )
    {
        int id = labelTokenHolder.getIdByName( label );

        if(id == TokenHolder.NO_ID)
        {
            return NO_SUCH_LABEL;
        }
        return id;
    }

    @Override
    public boolean nodeHasLabel( Statement state, long nodeId, long labelId )
    {
        try
        {
            return contains( nodeGetLabels( state, nodeId ), labelId );
        }
        catch ( InvalidRecordException e )
        {
            return false;
        }
    }

    @Override
    public PrimitiveLongIterator nodeGetLabels( Statement state, long nodeId )
    {
        try
        {
            return asPrimitiveIterator( parseLabelsField( nodeStore.getRecord( nodeId ) ).get( nodeStore ) );
        }
        catch ( InvalidRecordException e )
        {   // TODO Might hide invalid dynamic record problem. It's here because this method
            // might get called with a nodeId that doesn't exist.
            return emptyPrimitiveLongIterator();
        }
    }

    @Override
    public String labelGetName( Statement state, long labelId ) throws LabelNotFoundKernelException
    {
        try
        {
            return labelTokenHolder.getTokenById( (int) labelId ).name();
        }
        catch ( TokenNotFoundException e )
        {
            throw new LabelNotFoundKernelException( "Label by id " + labelId, e );
        }
    }

    @Override
    public PrimitiveLongIterator nodesGetForLabel( Statement state, final long labelId )
    {
        return state.getLabelScanReader().nodesWithLabel( labelId );
    }

    @Override
    public Iterator<Token> labelsGetAllTokens( Statement state )
    {
        return labelTokenHolder.getAllTokens().iterator();
    }

    @Override
    public long relationshipTypeGetForName( Statement state, String relationshipType )
    {
        return relationshipTypeTokenHolder.getIdByName( relationshipType );
    }

    @Override
    public String relationshipTypeGetName( Statement state, long relationshipTypeId )
            throws RelationshipTypeIdNotFoundKernelException
    {
        try
        {
            return ((Token)relationshipTypeTokenHolder.getTokenById( (int)relationshipTypeId )).name();
        }
        catch ( TokenNotFoundException e )
        {
            throw new RelationshipTypeIdNotFoundKernelException( relationshipTypeId, e );
        }
    }

    @Override
    public IndexDescriptor indexesGetForLabelAndPropertyKey( Statement state, final long labelId, final long propertyKey )
            throws SchemaRuleNotFoundException
    {
        return descriptor( schemaStorage.indexRule( labelId, propertyKey ) );
    }

    private static IndexDescriptor descriptor( IndexRule ruleRecord )
    {
        return new IndexDescriptor( ruleRecord.getLabel(), ruleRecord.getPropertyKey() );
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetForLabel( Statement state, final long labelId )
    {
        return getIndexDescriptorsFor( indexRules( labelId ) );
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetAll( Statement state )
    {
        return getIndexDescriptorsFor( INDEX_RULES );
    }

    @Override
    public Iterator<IndexDescriptor> uniqueIndexesGetForLabel( Statement state, final long labelId )
    {
        return getIndexDescriptorsFor( constraintIndexRules( labelId ) );
    }

    @Override
    public Iterator<IndexDescriptor> uniqueIndexesGetAll( Statement state )
    {
        return getIndexDescriptorsFor( CONSTRAINT_INDEX_RULES );
    }

    private static Predicate<SchemaRule> indexRules( final long labelId )
    {
        return new Predicate<SchemaRule>()
        {
            @Override
            public boolean accept( SchemaRule rule )
            {
                return rule.getLabel() == labelId && rule.getKind() == SchemaRule.Kind.INDEX_RULE;
            }
        };
    }

    private static Predicate<SchemaRule> constraintIndexRules( final long labelId )
    {
        return new Predicate<SchemaRule>()
        {
            @Override
            public boolean accept( SchemaRule rule )
            {
                return rule.getLabel() == labelId && rule.getKind() == SchemaRule.Kind.CONSTRAINT_INDEX_RULE;
            }
        };
    }

    private static final Predicate<SchemaRule> INDEX_RULES = new Predicate<SchemaRule>()
    {
        @Override
        public boolean accept( SchemaRule rule )
        {
            return rule.getKind() == SchemaRule.Kind.INDEX_RULE;
        }
    }, CONSTRAINT_INDEX_RULES = new Predicate<SchemaRule>()
    {
        @Override
        public boolean accept( SchemaRule rule )
        {
            return rule.getKind() == SchemaRule.Kind.CONSTRAINT_INDEX_RULE;
        }
    };

    private Iterator<IndexDescriptor> getIndexDescriptorsFor( Predicate<SchemaRule> filter )
    {
        Iterator<SchemaRule> filtered = filter( filter, neoStore.getSchemaStore().loadAllSchemaRules() );

        return map( new Function<SchemaRule, IndexDescriptor>()
        {
            @Override
            public IndexDescriptor apply( SchemaRule from )
            {
                return descriptor( (IndexRule) from );
            }
        }, filtered );
    }

    @Override
    public Long indexGetOwningUniquenessConstraintId( Statement state, IndexDescriptor index )
            throws SchemaRuleNotFoundException
    {
        return schemaStorage.indexRule( index.getLabelId(), index.getPropertyKeyId() ).getOwningConstraint();
    }

    @Override
    public long indexGetCommittedId( Statement state, IndexDescriptor index ) throws SchemaRuleNotFoundException
    {
        return schemaStorage.indexRule( index.getLabelId(), index.getPropertyKeyId() ).getId();
    }

    @Override
    public InternalIndexState indexGetState( Statement state, IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        return indexService.getProxyForRule( indexId( descriptor ) ).getState();
    }
    
    @Override
    public String indexGetFailure( Statement state, IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        return indexService.getProxyForRule( indexId( descriptor ) ).getPopulationFailure().asString();
    }

    private long indexId( IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        try
        {
            return schemaStorage.indexRule( descriptor.getLabelId(), descriptor.getPropertyKeyId() ).getId();
        }
        catch ( SchemaRuleNotFoundException e )
        {
            throw new IndexNotFoundKernelException( e.getMessage(), e );
        }
    }

    @Override
    public Iterator<UniquenessConstraint> constraintsGetForLabelAndPropertyKey( Statement state,
            long labelId, final long propertyKeyId )
    {
        return schemaStorage.schemaRules( UNIQUENESS_CONSTRAINT_TO_RULE, UniquenessConstraintRule.class,
                                          labelId, new Predicate<UniquenessConstraintRule>()
        {
            @Override
            public boolean accept( UniquenessConstraintRule rule )
            {
                return rule.containsPropertyKeyId( propertyKeyId );
            }
        }
        );
    }

    @Override
    public Iterator<UniquenessConstraint> constraintsGetForLabel( Statement state, long labelId )
    {
        return schemaStorage.schemaRules( UNIQUENESS_CONSTRAINT_TO_RULE, UniquenessConstraintRule.class,
                                          labelId, Predicates.<UniquenessConstraintRule>TRUE() );
    }

    @Override
    public Iterator<UniquenessConstraint> constraintsGetAll( Statement state )
    {
        return schemaStorage.schemaRules( UNIQUENESS_CONSTRAINT_TO_RULE, SchemaRule.Kind.UNIQUENESS_CONSTRAINT,
                Predicates.<UniquenessConstraintRule>TRUE() );
    }

    @Override
    public long propertyKeyGetOrCreateForName( Statement state, String propertyKey )
    {
        return propertyKeyTokenHolder.getOrCreateId( propertyKey );
    }

    @Override
    public long relationshipTypeGetOrCreateForName( Statement state, String relationshipType )
    {
        return relationshipTypeTokenHolder.getOrCreateId( relationshipType );
    }

    @Override
    public long propertyKeyGetForName( Statement state, String propertyKey )
    {
        int id = propertyKeyTokenHolder.getIdByName( propertyKey );
        if(id == TokenHolder.NO_ID)
        {
            return NO_SUCH_PROPERTY_KEY;
        }
        return id;
    }

    @Override
    public String propertyKeyGetName( Statement state, long propertyKeyId )
            throws PropertyKeyIdNotFoundKernelException
    {
        try
        {
            return propertyKeyTokenHolder.getTokenById( (int) propertyKeyId ).name();
        }
        catch ( TokenNotFoundException e )
        {
            throw new PropertyKeyIdNotFoundKernelException( propertyKeyId, e );
        }
    }
    
    @Override
    public Iterator<SafeProperty> nodeGetAllProperties( Statement state, long nodeId ) throws EntityNotFoundException
    {
        try
        {
            return loadAllPropertiesOf( nodeStore.getRecord( nodeId ) );
        }
        catch ( InvalidRecordException e )
        {
            throw new EntityNotFoundException( EntityType.NODE, nodeId, e );
        }
    }

    @Override
    public Iterator<SafeProperty> relationshipGetAllProperties( Statement state, long relationshipId ) throws EntityNotFoundException
    {
        try
        {
            return loadAllPropertiesOf( relationshipStore.getRecord( relationshipId ) );
        }
        catch ( InvalidRecordException e )
        {
            throw new EntityNotFoundException( EntityType.RELATIONSHIP, relationshipId, e );
        }
    }
    
    @Override
    public Iterator<SafeProperty> graphGetAllProperties( Statement state )
    {
        return loadAllPropertiesOf( neoStore.asRecord() );
    }

    private Iterator<SafeProperty> loadAllPropertiesOf( PrimitiveRecord primitiveRecord )
    {
        Collection<PropertyRecord> records = propertyStore.getPropertyRecordChain( primitiveRecord.getNextProp() );
        if ( null == records )
        {
            return IteratorUtil.emptyIterator();
        }
        List<SafeProperty> properties = new ArrayList<>();
        for ( PropertyRecord record : records )
        {
            for ( PropertyBlock block : record.getPropertyBlocks() )
            {
                properties.add( block.getType().readProperty( block.getKeyIndexId(), block, propertyStore ) );
            }
        }
        return properties.iterator();
    }

    @Override
    public PrimitiveLongIterator nodesGetFromIndexLookup( Statement state, IndexDescriptor index, Object value )
            throws IndexNotFoundKernelException
    {
        return state.getIndexReader( indexId( index ) ).lookup( value );
    }

    @Override
    public void nodeAddStoreProperty( long nodeId, SafeProperty property )

    {
        persistenceManager.nodeAddProperty( nodeId, (int) property.propertyKeyId(), property.value() );
    }

    @Override
    public void relationshipAddStoreProperty( long relationshipId, SafeProperty property )
    {
        persistenceManager.relAddProperty( relationshipId, (int) property.propertyKeyId(), property.value() );
    }
    
    @Override
    public void graphAddStoreProperty( SafeProperty property )
    {
        persistenceManager.graphAddProperty( (int) property.propertyKeyId(), property.value() );
    }

    @Override
    public void nodeChangeStoreProperty( long nodeId, SafeProperty previousProperty, SafeProperty property )
    {
        // TODO this should change. We don't have the property record id here, so we PersistenceManager
        // has been changed to only accept the property key and it will find it among the property records
        // on demand. This change was made instead of cramming in record id into the Property objects,
        persistenceManager.nodeChangeProperty( nodeId, (int) property.propertyKeyId(), property.value() );
    }

    @Override
    public void relationshipChangeStoreProperty( long relationshipId, SafeProperty previousProperty, SafeProperty property )
    {
        // TODO this should change. We don't have the property record id here, so we PersistenceManager
        // has been changed to only accept the property key and it will find it among the property records
        // on demand. This change was made instead of cramming in record id into the Property objects,
        persistenceManager.relChangeProperty( relationshipId,
                                              (int) property.propertyKeyId(), property.value() );
    }
    
    @Override
    public void graphChangeStoreProperty( SafeProperty previousProperty, SafeProperty property )
    {
        // TODO this should change. We don't have the property record id here, so we PersistenceManager
        // has been changed to only accept the property key and it will find it among the property records
        // on demand. This change was made instead of cramming in record id into the Property objects,
        persistenceManager.graphChangeProperty( (int) property.propertyKeyId(), property.value() );
    }

    @Override
    public void nodeRemoveStoreProperty( long nodeId, SafeProperty property )
    {
        // TODO this should change. We don't have the property record id here, so we PersistenceManager
        // has been changed to only accept the property key and it will find it among the property records
        // on demand. This change was made instead of cramming in record id into the Property objects,
        persistenceManager.nodeRemoveProperty( nodeId, (int) property.propertyKeyId() );
    }

    @Override
    public void relationshipRemoveStoreProperty( long relationshipId, SafeProperty property )
    {
        // TODO this should change. We don't have the property record id here, so we PersistenceManager
        // has been changed to only accept the property key and it will find it among the property records
        // on demand. This change was made instead of cramming in record id into the Property objects,
        persistenceManager.relRemoveProperty( relationshipId, (int) property.propertyKeyId() );
    }
    
    @Override
    public void graphRemoveStoreProperty( SafeProperty property )
    {
        // TODO this should change. We don't have the property record id here, so we PersistenceManager
        // has been changed to only accept the property key and it will find it among the property records
        // on demand. This change was made instead of cramming in record id into the Property objects,
        persistenceManager.graphRemoveProperty( (int) property.propertyKeyId() );
    }

    @Override
    public Property nodeSetProperty( Statement state, long nodeId, SafeProperty property )
    {
        throw shouldCallAuxiliaryInstead();
    }

    @Override
    public Property relationshipSetProperty( Statement state, long relationshipId, SafeProperty property )
    {
        throw shouldCallAuxiliaryInstead();
    }
    
    @Override
    public Property graphSetProperty( Statement state, SafeProperty property )
    {
        throw shouldCallAuxiliaryInstead();
    }
    
    @Override
    public Property nodeRemoveProperty( Statement state, long nodeId, long propertyKeyId )
    {
        throw shouldCallAuxiliaryInstead();
    }

    @Override
    public Property relationshipRemoveProperty( Statement state, long relationshipId, long propertyKeyId )
    {
        throw shouldCallAuxiliaryInstead();
    }
    
    @Override
    public Property graphRemoveProperty( Statement state, long propertyKeyId )
    {
        throw shouldCallAuxiliaryInstead();
    }
    
    @Override
    public void nodeDelete( Statement state, long nodeId )
    {
        throw shouldCallAuxiliaryInstead();
    }
    
    @Override
    public void relationshipDelete( Statement state, long relationshipId )
    {
        throw shouldCallAuxiliaryInstead();
    }
    
    @Override
    public void nodeDelete( long nodeId )
    {
        throw shouldNotManipulateStoreDirectly();
    }

    @Override
    public void relationshipDelete( long relationshipId )
    {
        throw shouldNotManipulateStoreDirectly();
    }

    @Override
    public boolean nodeAddLabel( Statement state, long nodeId, long labelId ) throws EntityNotFoundException
    {
        throw shouldNotManipulateStoreDirectly();
    }

    @Override
    public boolean nodeRemoveLabel( Statement state, long nodeId, long labelId ) throws EntityNotFoundException
    {
        throw shouldNotManipulateStoreDirectly();
    }

    @Override
    public Property nodeGetProperty( Statement state, long nodeId, long propertyKeyId )
            throws EntityNotFoundException
    {
        throw shouldNotHaveReachedAllTheWayHere();
    }

    @Override
    public Property relationshipGetProperty( Statement state, long relationshipId, long propertyKeyId )
            throws EntityNotFoundException
    {
        throw shouldNotHaveReachedAllTheWayHere();
    }

    @Override
    public Property graphGetProperty( Statement state, long propertyKeyId )
    {
        throw shouldNotHaveReachedAllTheWayHere();
    }

    @Override
    public PrimitiveLongIterator nodeGetPropertyKeys( Statement state, long nodeId ) throws EntityNotFoundException
    {
        throw shouldNotHaveReachedAllTheWayHere();
    }

    @Override
    public PrimitiveLongIterator relationshipGetPropertyKeys( Statement state, long relationshipId ) throws EntityNotFoundException
    {
        throw shouldNotHaveReachedAllTheWayHere();
    }

    @Override
    public PrimitiveLongIterator graphGetPropertyKeys( Statement state )
    {
        throw shouldNotHaveReachedAllTheWayHere();
    }
}
