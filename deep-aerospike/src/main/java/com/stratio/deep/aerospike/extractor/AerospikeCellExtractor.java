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
package com.stratio.deep.aerospike.extractor;

import java.lang.reflect.InvocationTargetException;

import com.stratio.deep.commons.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aerospike.hadoop.mapreduce.AerospikeKey;
import com.aerospike.hadoop.mapreduce.AerospikeRecord;
import com.stratio.deep.aerospike.config.AerospikeDeepJobConfig;
import com.stratio.deep.aerospike.utils.UtilAerospike;
import com.stratio.deep.commons.config.DeepJobConfig;
import com.stratio.deep.commons.entity.Cells;
import com.stratio.deep.commons.exception.DeepTransformException;

import scala.Tuple2;

/**
 * Cell RDD to interact with Aerospike.
 */
public class AerospikeCellExtractor extends AerospikeExtractor<Cells> {

    private static final Logger LOG = LoggerFactory.getLogger(AerospikeCellExtractor.class);
    private static final long serialVersionUID = 6437435944817212233L;

    /**
     * Public constructor for AerospikeCellExtractor
     */
    public AerospikeCellExtractor() {
        this(Cells.class);
    }

    public AerospikeCellExtractor(Class<Cells> entityClass) {
        super();
        this.deepJobConfig = new AerospikeDeepJobConfig(entityClass);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Cells transformElement(Tuple2<AerospikeKey, AerospikeRecord> tuple,
                                  DeepJobConfig<Cells, ? extends DeepJobConfig> config) {
        try {
            return UtilAerospike.getCellFromAerospikeRecord(tuple._1(), tuple._2(), (AerospikeDeepJobConfig) deepJobConfig);
        } catch (Exception e) {
            LOG.error("Cannot convert AerospikeRecord: ", e);
            throw new DeepTransformException("Could not transform from AerospikeRecord to Cell " + e.getMessage(), e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Tuple2<Object, AerospikeRecord> transformElement(Cells cells) {
        try {
            Pair<Object, AerospikeRecord> pair = UtilAerospike.getAerospikeRecordFromCell(cells);
            return new Tuple2<>(pair.left, pair.right);
        } catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
            LOG.error("Cannot transform Cells into AerospikeRecord", e.getMessage(), e);
            throw new DeepTransformException(e.getMessage(), e);
        }
    }

}
