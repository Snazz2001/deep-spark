/*
 * Copyright 2014, Stratio.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.deep.cassandra.cql;

import static com.stratio.deep.cassandra.cql.CassandraClientProvider.trySessionForLocation;
import static com.stratio.deep.cassandra.util.CassandraUtils.isFilterdByKey;
import static com.stratio.deep.cassandra.util.CassandraUtils.isTokenIncludedInRange;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.CompositeType;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Token;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.ColumnMetadata;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SimpleStatement;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.TableMetadata;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.stratio.deep.cassandra.config.CassandraDeepJobConfig;
import com.stratio.deep.cassandra.entity.CellValidator;
import com.stratio.deep.cassandra.filter.value.EqualsInValue;
import com.stratio.deep.cassandra.util.CassandraUtils;
import com.stratio.deep.commons.config.DeepJobConfig;
import com.stratio.deep.commons.exception.DeepGenericException;
import com.stratio.deep.commons.exception.DeepIOException;
import com.stratio.deep.commons.exception.DeepIllegalAccessException;
import com.stratio.deep.commons.filter.Filter;
import com.stratio.deep.commons.impl.DeepPartitionLocationComparator;
import com.stratio.deep.commons.rdd.DeepTokenRange;
import com.stratio.deep.commons.rdd.IDeepRecordReader;
import com.stratio.deep.commons.utils.Pair;
import com.stratio.deep.commons.utils.Utils;

/**
 * Implements a cassandra record reader with pagination capabilities. Does not rely on Cassandra's Hadoop
 * CqlPagingRecordReader.
 * <p/>
 * Pagination is outsourced to Datastax Java Driver.
 *
 * @author Luca Rosellini <luca@strat.io>
 */
public class DeepRecordReader implements IDeepRecordReader {
    /**
     * The constant LOG.
     */
    private static final Logger LOG = LoggerFactory.getLogger(DeepRecordReader.class);

    /**
     * The Split.
     */
    private final DeepTokenRange<?, String> split;
    /**
     * The Row iterator.
     */
    private RowIterator rowIterator;

    /**
     * The Cf name.
     */
    private String cfName;

    /**
     * The Partition bound columns.
     */
    // partition keys -- key aliases
    private final List<BoundColumn> partitionBoundColumns = new ArrayList<>();

    /**
     * The Cluster columns.
     */
    // cluster keys -- column aliases
    private final List<BoundColumn> clusterColumns = new ArrayList<>();

    /**
     * The Columns.
     */
    // cql query select columns
    private String columns;

    /**
     * The Page size.
     */
    // the number of cql rows per page
    private final int pageSize;

    /**
     * The Partitioner.
     */
    private IPartitioner<?> partitioner;

    /**
     * The Key validator.
     */
    private AbstractType<?> keyValidator;

    /**
     * The Config.
     */
    private final CassandraDeepJobConfig<?> config;

    /**
     * The Session.
     */
    private Session session;


    private boolean filterByKey = false;
    /**
     * public constructor. Takes a list of filters to pass to the underlying data stores.
     *
     * @param config the deep configuration object.
     * @param split  the token range on which the new reader will be based.
     */
    public DeepRecordReader(DeepJobConfig<?, ?> config, DeepTokenRange<?, String> split) {
        this.config = (CassandraDeepJobConfig<?>) config;
        this.split = split;
        this.pageSize = ((CassandraDeepJobConfig<?>) config).getPageSize();
        initialize();
    }

    /**
     * Initialized this object.
     * <p>
     * Creates a new client and row iterator.
     * </p>
     */
    private void initialize() {
        cfName = config.getTable();

        if (!ArrayUtils.isEmpty(config.getInputColumns())) {
            columns = StringUtils.join(config.getInputColumns(), ",");
        }

        partitioner = Utils.newTypeInstance(config.getPartitionerClassName(), IPartitioner.class);

        try {
            session = createConnection();

            retrieveKeys();
        } catch (Exception e) {
            throw new DeepIOException(e);
        }

        rowIterator = new RowIterator();
    }

    /**
     * Creates a new connection. Reuses a cached connection if possible.
     *
     * @return the new session
     */
    private Session createConnection() {

        /* reorder locations */
        List<String> locations = Lists.newArrayList(split.getReplicas());
        Collections.sort(locations, new DeepPartitionLocationComparator());

        Exception lastException = null;

        LOG.debug("createConnection: " + locations);
        for (String location : locations) {

            try {
                return trySessionForLocation(location, config, false).left;
            } catch (Exception e) {
                LOG.error("Could not get connection for: {}, replicas: {}", location, locations);
                lastException = e;
            }
        }

        throw new DeepIOException(lastException);
    }

    /**
     * Closes this input reader object.
     */
    @Override
    public void close() {
        /* dummy close method, no need to close any resource here */
    }

    /**
     * Creates a new empty LinkedHashMap.
     *
     * @return the map of associations between row column names and their values.
     */
    public Map<String, ByteBuffer> createEmptyMap() {
        return new LinkedHashMap<String, ByteBuffer>();
    }

    /**
     * CQL row iterator
     */
    class RowIterator extends AbstractIterator<Pair<Map<String, ByteBuffer>, Map<String, ByteBuffer>>> {
        /**
         * The Rows.
         */
        private Iterator<Row> rows;
        /**
         * The Partition key string.
         */
        private String partitionKeyString; // keys in <key1>, <key2>, <key3> string format
        /**
         * The Partition key markers.
         */
        private String partitionKeyMarkers; // question marks in ? , ? , ? format which matches the number of keys

        /**
         * Default constructor.
         */
        public RowIterator() {
            // initial page
            executeQuery();
        }

        /**
         * Is column wanted.
         *
         * @param columnName the column name
         * @return the boolean
         */
        private boolean isColumnWanted(String columnName) {
            return ArrayUtils.isEmpty(config.getInputColumns()) ||
                    ArrayUtils.contains(config.getInputColumns(), columnName);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected Pair<Map<String, ByteBuffer>, Map<String, ByteBuffer>> computeNext() {
            if (rows == null || !rows.hasNext()) {
                return endOfData();
            }

            Map<String, ByteBuffer> valueColumns = createEmptyMap();
            Map<String, ByteBuffer> keyColumns = createEmptyMap();

            initColumns(valueColumns, keyColumns);

            return Pair.create(keyColumns, valueColumns);
        }

        /**
         * Init columns.
         *
         * @param valueColumns the value columns
         * @param keyColumns   the key columns
         */
        private void initColumns(Map<String, ByteBuffer> valueColumns, Map<String, ByteBuffer> keyColumns) {
            Row row = rows.next();
            TableMetadata tableMetadata = config.fetchTableMetadata();

            List<ColumnMetadata> partitionKeys = tableMetadata.getPartitionKey();
            List<ColumnMetadata> clusteringKeys = tableMetadata.getClusteringColumns();
            List<ColumnMetadata> allColumns = tableMetadata.getColumns();

            for (ColumnMetadata key : partitionKeys) {
                String columnName = key.getName();
                ByteBuffer bb = row.getBytesUnsafe(columnName);
                keyColumns.put(columnName, bb);
            }
            for (ColumnMetadata key : clusteringKeys) {
                String columnName = key.getName();
                ByteBuffer bb = row.getBytesUnsafe(columnName);
                keyColumns.put(columnName, bb);
            }
            for (ColumnMetadata key : allColumns) {
                String columnName = key.getName();
                if (keyColumns.containsKey(columnName) || !isColumnWanted(columnName)) {
                    continue;
                }

                ByteBuffer bb = row.getBytesUnsafe(columnName);
                valueColumns.put(columnName, bb);
            }
        }

        /**
         * serialize the prepared query, pair.left is query id, pair.right is query
         *
         * @return the string
         */
        //TODO: return id column
        private String composeQuery() {
            String generatedColumns = columns;
            if (generatedColumns == null) {
                generatedColumns = "*";
            } else {
                // add keys in the front in order
                String partitionKey = keyString(partitionBoundColumns);
                String clusterKey = keyString(clusterColumns);

                generatedColumns = withoutKeyColumns(generatedColumns);
                generatedColumns = (generatedColumns != null ? "," + generatedColumns : "");

                generatedColumns = StringUtils.isEmpty(clusterKey)
                        ? partitionKey + generatedColumns
                        : partitionKey + "," + clusterKey + generatedColumns;
            }

            EqualsInValue equalsInValue = config.getEqualsInValue();
            String generatedQuery = null;
            // Checking whether the job is a EQUALS_IN special query or not
            if (equalsInValue == null) {
                String whereClause = whereClause();
                generatedQuery = String.format("SELECT %s FROM %s%s ALLOW FILTERING",
                        generatedColumns, Utils.quote(cfName), whereClause);
            } else {
                // partitioner.getToken(getPartitionKey(equalsInValue));
                String equalsInClause = equalsInWhereClause(equalsInValue);
                generatedQuery = String.format("SELECT %s FROM %s %s",
                        generatedColumns, Utils.quote(cfName), equalsInClause);
            }
            return generatedQuery;
        }

        /**
         * Prepares a Cassandra statement before being executed
         *
         * @return statement
         */
        private Statement prepareStatement() {

            String query = composeQuery();

            EqualsInValue equalsInValue = config.getEqualsInValue();

            Object[] values = null;
            if (equalsInValue == null) {
                List<Object> bindValues = preparedQueryBindValues();
                assert bindValues != null;

                values = bindValues.toArray(new Object[bindValues.size()]);
                LOG.debug("query: " + query + "; values: " + Arrays.toString(values));
            } else {
                values = new Object[equalsInValue.getEqualsList().size() + 1];
                for (int i = 0; i < equalsInValue.getEqualsList().size(); i++) {
                    values[i] = equalsInValue.getEqualsList().get(i).right;
                }

                values[values.length - 1] = filterSplits(equalsInValue);
                if (values[values.length - 1] == null) {
                    return null;
                }

                LOG.debug("query: " + query + "; values: " + Arrays.toString(values));
            }

            Statement stmt = new SimpleStatement(query, values);
            stmt.setFetchSize(pageSize);

            return stmt;
        }

        /**
         * Filter splits.
         *
         * @param equalsInValue the equals in value
         * @return the list
         */
        private List<Serializable> filterSplits(EqualsInValue equalsInValue) {

            List<Serializable> filteredInValues = new ArrayList<>();
            for (Serializable value : equalsInValue.getInValues()) {
                Token<Comparable> token = partitioner.getToken(getPartitionKey(
                        equalsInValue.getEqualsList(),
                        value));

                if (isTokenIncludedInRange(split, token)) {
                    filteredInValues.add(value);
                }
            }

            if (filteredInValues.isEmpty()) {
                return null;
            }

            return filteredInValues;
        }

        /**
         * Retrieve the column name for the lucene indexes. Null if there is no lucene index.
         *
         * @return Lucene index; null, if doesn't exist.
         */
        private String getLuceneIndex() {
            String indexName = "";

            TableMetadata tableMetadata = config.fetchTableMetadata();
            List<ColumnMetadata> columns = tableMetadata.getColumns();
            for (ColumnMetadata column : columns) {
                if (column.getIndex() != null) {
                    if (column.getIndex().isCustomIndex()) {
                        indexName = column.getName();
                    }
                }
            }
            return indexName;
        }

        /**
         * remove key columns from the column string
         *
         * @param columnString the column string
         * @return the string
         */
        private String withoutKeyColumns(String columnString) {
            Set<String> keyNames = new HashSet<>();
            for (BoundColumn column : Iterables.concat(partitionBoundColumns, clusterColumns)) {
                keyNames.add(column.name);
            }

            String[] cols = columnString.split(",");
            String result = null;
            for (String column : cols) {
                String trimmed = column.trim();
                if (keyNames.contains(trimmed)) {
                    continue;
                }

                String quoted = quote(trimmed);
                result = result == null ? quoted : result + "," + quoted;
            }
            return result;
        }

        /**
         * serialize the where clause
         *
         * @return the string
         */
        private String whereClause() {
            if (partitionKeyString == null) {
                partitionKeyString = keyString(partitionBoundColumns);
            }

            if (partitionKeyMarkers == null) {
                partitionKeyMarkers = partitionKeyMarkers();
            }
            // initial
            // query token(k) >= start_token and token(k) <= end_token

            filterByKey = isFilterdByKey(config.getFilters(), partitionKeyString);

            String filterGenerator = CassandraUtils.additionalFilterGenerator(config.getAdditionalFilters(),
                    config.getFilters(), getLuceneIndex());

            StringBuffer sb = new StringBuffer();

            sb.append(" WHERE ");
            if(filterByKey){
                filterGenerator = filterGenerator.substring(4);
            }else{
                sb.append(String.format(" token(%s) > ? AND token(%s) <= ?", partitionKeyString,
                        partitionKeyString));
            }
            sb.append(filterGenerator);

            return sb.toString();
        }

        /**
         * Generates the special equals_in clause
         *
         * @param equalsInValue the equals in value
         * @return Returns the equals in clause
         */
        private String equalsInWhereClause(EqualsInValue equalsInValue) {

            StringBuffer sb = new StringBuffer();
            sb.append("WHERE ");
            for (int i = 0; i < equalsInValue.getEqualsList().size(); i++) {
                sb.append(equalsInValue.getEqualsList().get(i).left).append(" = ? AND ");
            }
            sb.append(equalsInValue.getInField()).append(" IN ?");

            return sb.toString();
        }

        /**
         * serialize the partition key string in format of <key1>, <key2>, <key3>
         *
         * @param columns the columns
         * @return the string
         */
        private String keyString(List<BoundColumn> columns) {
            String result = null;
            for (BoundColumn column : columns) {
                result = result == null ? quote(column.name) : result + "," + quote(column.name);
            }

            return result == null ? "" : result;
        }

        /**
         * serialize the question marks for partition key string in format of ?, ? , ?
         *
         * @return the string
         */
        private String partitionKeyMarkers() {
            String result = null;
            for (BoundColumn partitionBoundColumn : partitionBoundColumns) {
                result = result == null ? "?" : result + ",?";
            }

            return result;
        }

        /**
         * serialize the query binding variables, pair.left is query id, pair.right is the binding variables
         *
         * @return the list
         */
        private List<Object> preparedQueryBindValues() {
            List<Object> values = new LinkedList<>();

            if(!filterByKey){
                Object startToken = split.getStartToken();
                Object endToken = split.getEndToken();

                values.add(startToken);
                values.add(endToken);
            }

            return values;
        }

        /**
         * Quoting for working with uppercase
         *
         * @param identifier the identifier
         * @return the string
         */
        private String quote(String identifier) {
            return "\"" + identifier.replaceAll("\"", "\"\"") + "\"";
        }

        /**
         * execute the prepared query
         */
        private void executeQuery() {

            Statement stmt = prepareStatement();

            if (stmt != null) {
                rows = null;
                int retries = 0;
                Exception exception = null;

                // only try three times for TimedOutException and UnavailableException
                while (retries < 3) {
                    try {
                        ResultSet resultSet = session.execute(stmt);

                        if (resultSet != null) {
                            rows = resultSet.iterator();
                        }
                        return;
                    } catch (NoHostAvailableException e) {
                        LOG.error("Could not connect to ");
                        exception = e;

                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e1) {
                            LOG.error("sleep exception", e1);
                        }

                        ++retries;

                    } catch (Exception e) {
                        throw new DeepIOException(e);
                    }
                }

                if (exception != null) {
                    throw new DeepIOException(exception);
                }
            }
        }
    }

    /**
     * retrieve the partition keys and cluster keys from system.schema_columnfamilies table
     */
    //TODO check this
    private void retrieveKeys() {
        TableMetadata tableMetadata = config.fetchTableMetadata();

        List<ColumnMetadata> partitionKeys = tableMetadata.getPartitionKey();
        List<ColumnMetadata> clusteringKeys = tableMetadata.getClusteringColumns();

        List<AbstractType<?>> types = new ArrayList<>();

        for (ColumnMetadata key : partitionKeys) {
            String columnName = key.getName();
            BoundColumn boundColumn = new BoundColumn(columnName);
            boundColumn.validator = CellValidator.cellValidator(key.getType()).getAbstractType();
            partitionBoundColumns.add(boundColumn);
            types.add(boundColumn.validator);
        }
        for (ColumnMetadata key : clusteringKeys) {
            String columnName = key.getName();
            BoundColumn boundColumn = new BoundColumn(columnName);
            boundColumn.validator = CellValidator.cellValidator(key.getType()).getAbstractType();
            clusterColumns.add(boundColumn);
        }

        if (types.size() > 1) {
            keyValidator = CompositeType.getInstance(types);
        } else if (types.size() == 1) {
            keyValidator = types.get(0);
        } else {
            throw new DeepGenericException("Cannot determine if keyvalidator is composed or not, " +
                    "partitionKeys: " + partitionKeys);
        }
    }

    /**
     * check whether current row is at the end of range
     *
     * @return the boolean
     */
    private boolean reachEndRange() {
        // current row key
        ByteBuffer rowKey;

        if (keyValidator instanceof CompositeType) {
            ByteBuffer[] keys = new ByteBuffer[partitionBoundColumns.size()];
            for (int i = 0; i < partitionBoundColumns.size(); i++) {
                keys[i] = partitionBoundColumns.get(i).value.duplicate();
            }

            rowKey = CompositeType.build(keys);
        } else {
            rowKey = partitionBoundColumns.get(0).value;
        }

        String endToken = String.valueOf(split.getEndToken());
        String currentToken = partitioner.getToken(rowKey).toString();

        return endToken.equals(currentToken);
    }

    /**
     * The type Bound column.
     */
    private static class BoundColumn implements Serializable {
        /**
         * The Name.
         */
        private final String name;
        /**
         * The Value.
         */
        private ByteBuffer value;
        /**
         * The Validator.
         */
        private AbstractType<?> validator;

        /**
         * Instantiates a new Bound column.
         *
         * @param name the name
         */
        public BoundColumn(String name) {
            this.name = name;
        }
    }

    /**
     * Returns a boolean indicating if the underlying rowIterator has a new element or not. DOES NOT advance the
     * iterator to the next element.
     *
     * @return a boolean indicating if the underlying rowIterator has a new element or not.
     */
    @Override
    public boolean hasNext() {
        return rowIterator.hasNext();
    }

    /**
     * Returns the next element in the underlying rowIterator.
     *
     * @return the next element in the underlying rowIterator.
     */
    @Override
    public Pair<Map<String, ByteBuffer>, Map<String, ByteBuffer>> next() {
        if (!this.hasNext()) {
            throw new DeepIllegalAccessException("DeepRecordReader exhausted");
        }
        return rowIterator.next();
    }

    /**
     * Builds the partition key in {@link ByteBuffer} format for the given values.
     *
     * @param equalsList List of equals field and value pairs.
     * @param inValue    Value for the operator in.
     * @return with the partition key.
     */
    private ByteBuffer getPartitionKey(List<Pair<String, Serializable>> equalsList, Serializable inValue) {

        assert (equalsList.size() + 1) == ((CompositeType) keyValidator).componentsCount();

        ByteBuffer[] serialized = new ByteBuffer[equalsList.size() + 1];
        for (int i = 0; i < equalsList.size(); i++) {
            ByteBuffer buffer = ((AbstractType) keyValidator.getComponents().get(i)).decompose(equalsList.get(i).right);
            serialized[i] = buffer;
        }
        serialized[serialized.length - 1] = ((AbstractType) keyValidator.getComponents().get(serialized.length - 1))
                .decompose(inValue);

        return CompositeType.build(serialized);
    }
}
