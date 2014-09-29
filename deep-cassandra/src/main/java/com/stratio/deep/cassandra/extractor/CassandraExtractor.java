/*
 * Copyright 2014, Stratio.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.stratio.deep.cassandra.extractor;

import static com.stratio.deep.commons.utils.Constants.SPARK_RDD_ID;
import static scala.collection.JavaConversions.asScalaBuffer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import org.apache.spark.Partition;

import scala.Tuple2;
import scala.collection.Seq;

import com.stratio.deep.cassandra.config.GenericDeepJobConfig;
import com.stratio.deep.cassandra.config.ICassandraDeepJobConfig;
import com.stratio.deep.cassandra.cql.DeepCqlRecordWriter;
import com.stratio.deep.cassandra.cql.DeepRecordReader;
import com.stratio.deep.cassandra.cql.RangeUtils;
import com.stratio.deep.commons.config.DeepJobConfig;
import com.stratio.deep.commons.config.IDeepJobConfig;
import com.stratio.deep.commons.entity.Cells;
import com.stratio.deep.commons.functions.AbstractSerializableFunction;
import com.stratio.deep.commons.impl.DeepPartition;
import com.stratio.deep.commons.rdd.DeepTokenRange;
import com.stratio.deep.commons.rdd.IDeepRecordReader;
import com.stratio.deep.commons.rdd.IExtractor;
import com.stratio.deep.commons.utils.Pair;

/**
 * Base class that abstracts the complexity of interacting with the Cassandra Datastore.<br/>
 * Implementors should only provide a way to convert an object of type T to a
 * {@link com.stratio.deep.commons.entity.Cells} element.
 */
public abstract class CassandraExtractor<T> implements IExtractor<T> {

    protected transient IDeepRecordReader<Pair<Map<String, ByteBuffer>, Map<String, ByteBuffer>>> recordReader;

    protected transient DeepCqlRecordWriter writer;

    protected ICassandraDeepJobConfig<T> cassandraJobConfig;

    protected transient AbstractSerializableFunction transformer;

    @Override
    public boolean hasNext() {
        return recordReader.hasNext();
    }

    @Override
    public T next() {
        return transformElement(recordReader.next(), cassandraJobConfig);
    }

    @Override
    public void close() {
        if (recordReader != null) {
            recordReader.close();
        }

        if (writer != null) {
            writer.close();
        }

    }

    @Override
    public void initIterator(final Partition dp,
            DeepJobConfig<T> config) {
        this.cassandraJobConfig = initCustomConfig(config);
        recordReader = initRecordReader((DeepPartition) dp, cassandraJobConfig);
    }

    private ICassandraDeepJobConfig<T> initCustomConfig(DeepJobConfig<T> config) {
        return cassandraJobConfig.initialize(config);
    }

    public abstract T transformElement(
            Pair<Map<String, ByteBuffer>, Map<String, ByteBuffer>> elem,
            IDeepJobConfig<T, ? extends IDeepJobConfig<?, ?>> config);

    public abstract Class getConfigClass();

    /**
     * Returns the partitions on which this RDD depends on.
     * <p/>
     * Uses the underlying CqlPagingInputFormat in order to retrieve the splits.
     * <p/>
     * The number of splits, and hence the number of partitions equals to the number of tokens configured in
     * cassandra.yaml + 1.
     */
    @Override
    public Partition[] getPartitions(DeepJobConfig<T> config) {

        int id = Integer.parseInt(config.getValues().get(SPARK_RDD_ID).toString());
        ICassandraDeepJobConfig<T> cellDeepJobConfig = initCustomConfig(config);

        List<DeepTokenRange> underlyingInputSplits = RangeUtils.getSplits(cellDeepJobConfig);

        Partition[] partitions = new DeepPartition[underlyingInputSplits.size()];

        int i = 0;

        for (DeepTokenRange split : underlyingInputSplits) {
            partitions[i] = new DeepPartition(id, i, split);

            // log().debug("Detected partition: " + partitions[i]);
            ++i;
        }

        return partitions;
    }

    /**
     * Returns a list of hosts on which the given split resides.
     */
    public Seq<String> getPreferredLocations(DeepTokenRange tokenRange) {

        List<String> locations = tokenRange.getReplicas();
        // log().debug("getPreferredLocations: " + p);

        return asScalaBuffer(locations);
    }

    /**
     * Instantiates a new deep record reader object associated to the provided partition.
     * 
     * @param dp
     *            a spark deep partition
     * @return the deep record reader associated to the provided partition.
     */
    private IDeepRecordReader initRecordReader(final DeepPartition dp,
            IDeepJobConfig<T, ? extends IDeepJobConfig<?, ?>> config) {

        IDeepRecordReader recordReader = new DeepRecordReader(config, dp.splitWrapper());

        return recordReader;

    }

    @Override
    public void initSave(DeepJobConfig<T> config, T first) {

        cassandraJobConfig = cassandraJobConfig.initialize(config);
        ((GenericDeepJobConfig) cassandraJobConfig).createOutputTableIfNeeded((Tuple2<Cells, Cells>) transformer
                .apply(first));
        writer = new DeepCqlRecordWriter(cassandraJobConfig);
    }

    @Override
    public void saveRDD(T t) {
        Tuple2<Cells, Cells> tuple = (Tuple2<Cells, Cells>) transformer.apply(t);
        writer.write(tuple._1(), tuple._2());
    }

}
