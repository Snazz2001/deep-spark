First Steps with Stratio Deep and Aerospike
===========================================

StratioDeep-Aerospike is an integration layer between Spark, a distributed computing framework and Aerospike, 
a flash-optimized, in-memory, NoSQL database. [Aerospike](http://www.aerospike.com/ "Aerospike website") provides a key/value storage system. [Spark](http://spark.incubator.apache.org/ "Spark website") 
is a 
fast and general-purpose cluster computing system that can run applications up to 100 times faster than Hadoop. 
It processes data using Resilient Distributed Datasets (RDDs) allowing storage of intermediate results in memory 
for future processing reuse. Spark applications are written in 
[Scala](http://www.scala-lang.org/ "The Scala programming language site"), a popular functional language for 
the Java Virtual Machine (JVM). Integrating Aerospike and Spark gives us a system that combines the best of both 
worlds opening to Aerospike the possibility of solving a wide range of new use cases.

Summary
=======

This tutorial shows how Stratio Deep can be used to perform simple to complex queries and calculations on data 
stored in Aerospike. You will learn:

-   How to use the Stratio Deep interactive shell.
-   How to create a RDD from Aerospike and perform operations on the data.
-   How to write data from a RDD to Aerospike.

Table of Contents
=================

-   [Summary](#summary)
-   [Before you start](#before-you-start)
    -   [Prerequisites](#prerequisites)
-   [Loading the dataset](#loading-the-dataset)
-   [Using the Stratio Deep Shell](#using-the-stratio-deep-shell)
    -   [Step 1: Creating a RDD](#step-1-creating-a-rdd)
    -   [Step 2: Word Count](#step-2-word-count)
    -   [Step 3: Writing the results to Aerospike](#step-3-writing-the-results-to-aerospike)
-   [Where to go from here](#where-to-go-from-here)

Before you start
================

Prerequisites
-------------

-   Aerospike and Stratio Deep: see [Getting Started](/getting-started.md "Getting Started") for installation instructions
-   Basic knowledge of SQL, Java and/or Scala
-	Some input data loaded into Aerospike.

Loading the dataset
-------------------

First of all, you need to create the Aerospike namespace used in the example. We will use a "test" namespace. Open the
aerospike configuration file (usually /etc/aerospike.conf) and add the namespace configuration:

```shell-session
namespace test {
        replication-factor 2
        memory-size 2G
        default-ttl 5d # 5 days, use 0 to never expire/evict.

        storage-engine device {
                file /opt/aerospike/data/test.dat
                filesize 5G
                data-in-memory true # Store data in memory in addition to file.
        }
}
```

The data can be loaded using the Aerospike shell. First of all, enter the shell with "aql" command.

```shell-session
$ aql
```

That will produce the Aerospike shell:

```shell-session
Aerospike Query
Copyright 2013 Aerospike. All rights reserved.

aql>
```

Now, insert some test data:

```shell-session
aql> INSERT INTO test.input (PK, id, message, number) VALUES ('1', '1', 'message test 1', 1)
aql> INSERT INTO test.input (PK, id, message, number) VALUES ('2', '2', 'message test 2', 2)
```

From the same Aerospike shell, check that there are 2 rows in the “input” table:

```shell-session
> select * from test.input;
```

Using the Stratio Deep Shell
============================

The Stratio Deep shell provides a Scala interpreter that allows interactive calculations on Aerospike RDDs. In 
this section, you are going to learn how to create RDDs of the Aerospike dataset we imported in the previous 
section and how to make basic operations on them. Start the shell:

```shell-session
$ stratio-deep-shell
```

A welcome screen will be displayed (figure 1).

![Stratio Deep shell Welcome Screen](http://www.openstratio.org/wp-content/uploads/2014/01/stratio-deep-shell-WelcomeScreen.png)  
Figure 1: The Stratio Deep shell welcome screen

Step 1: Creating a RDD
----------------------

When using the Stratio Deep shell, a deepContext object has been created already and is available for use.
The deepContext is created from the SparkContext and tells Stratio Deep how to access the cluster. However
the RDD needs more information to access Aerospike data such as the namespace and set names. By default,
the RDD will try to connect to “localhost” on port 3000, this can be overridden by setting the host and
port properties of the configuration object: Define a configuration object for the RDD that contains the
connection string for Aerospike, namely the database and the collection name:

```shell-session
val inputConfigEntity: AerospikeDeepJobConfig[MessageTestEntity] = 
AerospikeConfigFactory.createAerospike(classOf[MessageTestEntity]).host("localhost").port(3000).namespace("test").set("input").initialize
```

Create a RDD in the Deep context using the configuration object:

```shell-session
scala> val inputRDDEntity: RDD[MessageTestEntity] = deepContext.createJavaRDD(inputConfigEntity)
```

Step 2: Word Count
------------------

We create a JavaRDD&lt;String> from the MessageTestEntity

```shell-session
scala> val words: RDD[String] = inputRDDEntity flatMap {
      e: MessageTestEntity => (for (message <- e.getMessage) yield message.split(" ")).flatten
    }
```

Now we make a JavaPairRDD&lt;String, Integer>, counting one unit for each word

```shell-session
scala> val wordCount : RDD[(String, Long)] = words map { s:String => (s,1) }
```

We group by word

```shell-session
scala> val wordCountReduced  = wordCount reduceByKey { (a,b) => a + b }
```

Create a new WordCount Object from

```shell-session
scala> val outputRDD = wordCountReduced map { e:(String, Long) => new WordCount(e._1, e._2) }
```

Step 3: Writing the results to Aerospike
--------------------------------------

From the previous step we have a RDD object “outputRDD” that contains pairs of word (String)
and the number of occurrences (Integer). To write this result to the output collection, we will need
a configuration that binds the RDD to the given collection and then writes its contents to Aerospike 
using that configuration:

```shell-session
scala> val outputConfigEntity: AerospikeDeepJobConfig[WordCount] = AerospikeConfigFactory.createAerospike(classOf[WordCount]).host("localhost").
port(3000).namespace("test").set("input").initialize
```

Then write the outRDD to Aerospike:

```shell-session
scala>DeepSparkContext.saveRDD(outputRDD, outputConfigEntity)
```

To check that the data has been correctly written to Aerospike, open an Aerospike shell and look at the contents 
of the “output” collection:

```shell-session
$ aql
aql> select * from test.output
```

Where to go from here
=====================

Congratulations! You have completed the “First steps with Stratio Deep” tutorial. If you want to learn more, 
we recommend the “[Writing and Running a Basic Application](t40-basic-application.md "Writing and Running a Basic Application")” tutorial.
