package org.deeplearning4j.spark.impl.layer;

import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.mllib.linalg.Matrix;
import org.apache.spark.mllib.linalg.Vector;
import org.apache.spark.mllib.regression.LabeledPoint;
import org.canova.api.records.reader.RecordReader;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.spark.canova.RDDMiniBatches;
import org.deeplearning4j.spark.canova.RecordReaderFunction;
import org.deeplearning4j.spark.util.MLLibUtil;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;

import java.io.Serializable;

/**
 * Master class for org.deeplearning4j.spark
 * layers
 * @author Adam Gibson
 */
public class SparkDl4jLayer implements Serializable {

    private transient SparkContext sparkContext;
    private transient JavaSparkContext sc;
    private NeuralNetConfiguration conf;
    private Layer layer;


    public SparkDl4jLayer(SparkContext sparkContext, NeuralNetConfiguration conf) {
        this.sparkContext = sparkContext;
        this.conf = conf.clone();
        sc = new JavaSparkContext(this.sparkContext);
    }

    public SparkDl4jLayer(JavaSparkContext sc, NeuralNetConfiguration conf) {
        this.sc = sc;
        this.conf = conf.clone();
    }

    /**
     * Fit the layer based on the specified org.deeplearning4j.spark context text file
     * @param path the path to the text file
     * @param labelIndex the index of the label
     * @param recordReader the record reader
     * @return the fit layer
     */
    public Layer fit(String path,int labelIndex,RecordReader recordReader) {
        JavaRDD<String> lines = sc.textFile(path);
        // gotta map this to a Matrix/INDArray
        JavaRDD<DataSet> points = lines.map(new RecordReaderFunction(recordReader
                , labelIndex, conf.getnOut()));
        return fitDataSet(points);

    }

    /**
     * Fit the given rdd given the context.
     * This will convert the labeled points
     * to the internal dl4j format and train the model on that
     * @param sc the org.deeplearning4j.spark context
     * @param rdd the rdd to fitDataSet
     * @return the multi layer network that was fitDataSet
     */
    public Layer fit(JavaSparkContext sc,JavaRDD<LabeledPoint> rdd) {
        return fitDataSet(MLLibUtil.fromLabeledPoint(sc, rdd, conf.getnOut()));
    }

    /**
     * Fit a java rdd of dataset
     * @param rdd the rdd to fit
     * @return the fit layer
     */
    public Layer fitDataSet(JavaRDD<DataSet> rdd) {
        int batchSize = conf.getBatchSize();
        JavaRDD<DataSet> miniBatches = new RDDMiniBatches(batchSize,rdd).miniBatchesJava();
        Layer layer = conf.getLayerFactory().create(conf);
        INDArray params = layer.params();
        int paramsLength = layer.numParams();
        if(params.length() != paramsLength)
            throw new IllegalStateException("Number of params " + paramsLength + " was not equal to " + params.length());
        INDArray newParams = miniBatches.map(new DL4jWorker(conf.toJson(),params)).reduce(new Function2<INDArray, INDArray, INDArray>() {
            @Override
            public INDArray call(INDArray v1, INDArray v2) throws Exception {
                return v1.add(v2);
            }
        }).divi(miniBatches.count());
        layer.setParameters(newParams);
        this.layer = layer;
        return layer;
    }


    /**
     * Predict the given feature matrix
     * @param features the given feature matrix
     * @return the predictions
     */
    public Matrix predict(Matrix features) {
        return MLLibUtil.toMatrix(layer.activate(MLLibUtil.toMatrix(features)));
    }


    /**
     * Predict the given vector
     * @param point the vector to predict
     * @return the predicted vector
     */
    public Vector predict(Vector point) {
        return MLLibUtil.toVector(layer.activate(MLLibUtil.toVector(point)));
    }


    /**
     * Train a multi layer network
     * @param data the data to train on
     * @param conf the configuration of the network
     * @return the fit multi layer network
     */
    public static Layer train(JavaRDD<LabeledPoint> data,NeuralNetConfiguration conf) {
        SparkDl4jLayer multiLayer = new SparkDl4jLayer(data.context(),conf);
        return multiLayer.fit(new JavaSparkContext(data.context()),data);

    }



}
