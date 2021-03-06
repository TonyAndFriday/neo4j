/*
 * Copyright (c) 2002-2020 "Neo4j,"
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
package org.neo4j.cypher.result;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.neo4j.kernel.impl.query.QuerySubscriber;
import org.neo4j.values.AnyValue;

/**
 * This class is just a stepping stone and should be removed soon. It is merely a stepping stone in order to get all the
 * pieces of reactive results in the code. Implements {@link org.neo4j.kernel.impl.query.QuerySubscription} by simply reading
 * the entire result in to memory and serves it in chunks as demanded by the client.
 */
public abstract class NaiveQuerySubscription extends EagerQuerySubscription implements VisitableRuntimeResult,
        QueryResult.QueryResultVisitor<Exception>
{
    private List<AnyValue[]> materializedResult;

    protected NaiveQuerySubscription( QuerySubscriber subscriber )
    {
        super( subscriber );
    }

    @Override
    protected void streamRecordToSubscriber( int servedRecords ) throws Exception
    {
        AnyValue[] current = materializedResult.get( servedRecords );
        for ( int i = 0; i < current.length; i++ )
        {
            subscriber.onField( i, current[i] );
        }
    }

    @Override
    public void request( long numberOfRecords ) throws Exception
    {
        //The client asked for all the results, no need to materialize in an intermediate
        //collection, just stream it directly back
        if ( numberOfRecords == Long.MAX_VALUE )
        {
            streamResultsDirectly();
        }
        else
        {
            super.request( numberOfRecords );
        }
    }

    @Override
    protected int resultSize()
    {
        return materializedResult != null ? materializedResult.size() : -1;
    }

    @Override
    protected void materializeIfNecessary() throws Exception
    {
        if ( materializedResult == null )
        {
            materializedResult = new ArrayList<>();
            try
            {
                accept( record -> {
                    AnyValue[] fields = record.fields();
                    materializedResult.add( Arrays.copyOf( fields, fields.length ) );
                    record.release();
                    return true;
                } );
            }
            catch ( Exception t )
            {
                //an error occurred, there might still be some data to feed to the user before failing
                error = t;
            }

            //only call onResult first time
            subscriber.onResult( fieldNames().length );
        }
    }

    private void streamResultsDirectly() throws Exception
    {
        //only call onResult first time
        int length = fieldNames().length;
        subscriber.onResult( length );
        try
        {
            accept( this );
        }
        catch ( Exception t )
        {
            this.error = t;
        }
    }

    @Override
    public boolean visit( QueryResult.Record record ) throws Exception
    {
        subscriber.onRecord();
        AnyValue[] fields = record.fields();
        int fieldsLength = fields.length;
        for ( int i = 0; i < fieldsLength; i++ )
        {
            subscriber.onField( i, fields[i] );
        }
        subscriber.onRecordCompleted();
        record.release();
        return true;
    }
}
