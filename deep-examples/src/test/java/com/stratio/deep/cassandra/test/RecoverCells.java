package com.stratio.deep.cassandra.test;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;
import org.apache.spark.rdd.RDD;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.stratio.deep.cassandra.extractor.CassandraCellExtractor;
import com.stratio.deep.commons.config.DeepJobConfig;
import com.stratio.deep.commons.config.ExtractorConfig;
import com.stratio.deep.commons.entity.Cells;
import com.stratio.deep.commons.extractor.server.ExtractorServer;
import com.stratio.deep.commons.extractor.utils.ExtractorConstants;
import com.stratio.deep.core.context.DeepSparkContext;
import com.stratio.deep.utils.ContextProperties;

/**
 * Created by dgomez on 28/08/14.
 */
@org.testng.annotations.Test
public class RecoverCells {

    private static final Logger LOG = Logger.getLogger(RecoverCells.class);

    /* used to perform external tests */

    private final String job = "java:aggregatingData";

    private final String KEYSPACENAME = "test";
    private final String TABLENAME = "tweets";
    private final String CQLPORT = "9042";
    private final String RPCPORT = "9160";
    private final String HOST = "127.0.0.1";

    /**
     *
     *
     *
     */
    @BeforeMethod
    public void createExtractorServer() {

        ExtractorServer.initExtractorServer();
    }

    /**
     * This is the method called by both main and tests.
     * 
     */
    @Test
    public void recoverCells() {

        // Creating the Deep Context where args are Spark Master and Job Name
        ContextProperties p = new ContextProperties(null);
        DeepSparkContext deepContext = new DeepSparkContext(p.getCluster(), job, p.getSparkHome(), p.getJars());

        // Creating a configuration for the Extractor and initialize it
        DeepJobConfig<Cells> config = new DeepJobConfig<>(new ExtractorConfig(Cells.class));

        config.getExtractorConfiguration().setExtractorImplClass(CassandraCellExtractor.class);
        config.setEntityClass(Cells.class);

        Map<String, Serializable> values = new HashMap<>();
        values.put(ExtractorConstants.KEYSPACE, KEYSPACENAME);
        values.put(ExtractorConstants.TABLE, TABLENAME);
        values.put(ExtractorConstants.CQLPORT, CQLPORT);
        values.put(ExtractorConstants.RPCPORT, RPCPORT);
        values.put(ExtractorConstants.HOST, HOST);

        config.setValues(values);

        // Creating the RDD
        RDD<Cells> rdd = deepContext.createRDD(config);
        LOG.info("count: " + rdd.count());
        LOG.info("first: " + rdd.first());

        // close
        ExtractorServer.close();
        deepContext.stop();
    }

}
