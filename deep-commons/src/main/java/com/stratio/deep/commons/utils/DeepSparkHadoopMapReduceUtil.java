package com.stratio.deep.commons.utils;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.log4j.Logger;

import com.stratio.deep.commons.exception.DeepInstantiationException;

/**
 * Created by rcrespo on 22/08/14.
 */
public class DeepSparkHadoopMapReduceUtil {

    private static final Logger LOG = Logger.getLogger(DeepSparkHadoopMapReduceUtil.class);

    public static JobContext newJobContext(Configuration conf, JobID jobId) {
        try {
            Class clazz = firstAvailableClass(
                    "org.apache.hadoop.mapreduce.task.JobContextImpl", // hadoop2, hadoop2-yarn
                    "org.apache.hadoop.mapreduce.JobContext"); // hadoop1

            Constructor constructor = clazz.getDeclaredConstructor(Configuration.class, JobID.class);

            return (JobContext) constructor.newInstance(conf, jobId);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            LOG.error("to use hadoop classes, we need them in the classpath " + e.getMessage());
            throw new DeepInstantiationException("to use hadoop classes, we need them in the classpath", e);
        }
    }

    public static TaskAttemptContext newTaskAttemptContext(Configuration conf, TaskAttemptID attemptId) {
        try {
            Class clazz = firstAvailableClass(
                    "org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl", // hadoop2, hadoop2-yarn
                    "org.apache.hadoop.mapreduce.TaskAttemptContext"); // hadoop1
            Constructor constructor = clazz.getDeclaredConstructor(Configuration.class, TaskAttemptID.class);
            return (TaskAttemptContext) constructor.newInstance(conf, attemptId);

        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            LOG.error("to use hadoop classes, we need them in the classpath " + e.getMessage());
            throw new DeepInstantiationException("to use hadoop classes, we need them in the classpath", e);
        }
    }

    public static TaskAttemptID newTaskAttemptID(String jtIdentifier, int jobId, boolean isMap, int taskId,
            int attemptId) {
        Class clazz = null;
        try {
            clazz = Class.forName("org.apache.hadoop.mapreduce.TaskAttemptID");
            Constructor constructor = clazz
                    .getDeclaredConstructor(String.class, int.class, boolean.class, int.class, int.class);
            return (TaskAttemptID) constructor.newInstance(jtIdentifier, jobId, isMap, taskId, attemptId);
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException
                | InvocationTargetException e) {

            try {

                TaskType type = null;
                if (isMap) {
                    type = TaskType.MAP;
                } else {
                    type = TaskType.REDUCE;
                }

                Constructor constructor = clazz.getDeclaredConstructor(String.class, int.class, TaskType.class,
                        int.class, int.class);
                return (TaskAttemptID) constructor.newInstance(jtIdentifier, jobId, type, taskId,
                        attemptId);
            } catch (NoSuchMethodException | InstantiationException | IllegalAccessException
                    | InvocationTargetException e1) {
                LOG.error("to use hadoop classes, we need them in the classpath " + e.getMessage());
                throw new DeepInstantiationException("to use hadoop classes, we need them in the classpath", e1);
            }
        }
    }

    private static Class firstAvailableClass(String first, String second) {

        try {
            return Class.forName(first);
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName(second);
            } catch (ClassNotFoundException e1) {
                return null;
            }
        }
    }

}
