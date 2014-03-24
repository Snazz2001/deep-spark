/*
Copyright 2014 Stratio.

        Licensed under the Apache License, Version 2.0 (the "License");
        you may not use this file except in compliance with the License.
        You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

        Unless required by applicable law or agreed to in writing, software
        distributed under the License is distributed on an "AS IS" BASIS,
        WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
        See the License for the specific language governing permissions and
        limitations under the License.
*/
package com.stratio.deep.examples.java;

import com.stratio.deep.entity.TweetEntity;
import com.stratio.deep.config.DeepJobConfigFactory;
import com.stratio.deep.config.IDeepJobConfig;
import com.stratio.deep.context.DeepSparkContext;
import com.stratio.deep.rdd.CassandraJavaRDD;
import com.stratio.deep.utils.ContextProperties;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.api.java.function.Function2;
import scala.Tuple2;

import java.util.List;

/**
 * Author: Emmanuelle Raffenne
 * Date..: 13-feb-2014
 */

public class MapReduceJob {

    public static void main(String[] args) {

        String job = "java:mapReduceJob";

        String keyspaceName = "tutorials";
        String tableName = "tweets";

        // Creating the Deep Context where args are Spark Master and Job Name
        ContextProperties p = new ContextProperties();
        DeepSparkContext deepContext = new DeepSparkContext(p.cluster, job, p.sparkHome, p.jarList);

        // Creating a configuration for the RDD and initialize it
        IDeepJobConfig<TweetEntity> config = DeepJobConfigFactory.create(TweetEntity.class)
                .host(p.cassandraHost).rpcPort(p.cassandraPort)
                .keyspace(keyspaceName).table(tableName)
                .initialize();

        // Creating the RDD
        CassandraJavaRDD<TweetEntity> rdd = deepContext.cassandraJavaRDD(config);

        // Map stage: Getting key-value pairs from the RDD
        JavaPairRDD<String, Integer> pairsRDD = rdd.map(new PairFunction<TweetEntity, String, Integer>() {
            @Override
            public Tuple2<String, Integer> call(TweetEntity t) throws Exception {
                return new Tuple2<String,Integer>(t.getAuthor(), 1);
            }
        });

// Reduce stage: counting rows
        JavaPairRDD<String, Integer> counts = pairsRDD.reduceByKey(new Function2<Integer, Integer, Integer>() {
            @Override
            public Integer call(Integer a, Integer b) {
                return a + b;
            }
        });

// Fetching the results
        List<Tuple2<String,Integer>> results = counts.collect();

        for (Tuple2<String,Integer> t: results) {
            System.out.println(t._1() + ": " + t._2().toString());
        }

        System.exit(0);
    }
}