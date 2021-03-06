// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import com.microsoft.ml.spark.schema.SchemaConstants
import org.apache.hadoop.fs.Path
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.sql.{DataFrame, Dataset, Row, SaveMode}
import org.apache.spark.ml._
import org.apache.spark.ml.param.{Param, ParamMap, TransformerArrayParam}
import org.apache.spark.ml.util._
import org.apache.spark.sql.types._

import scala.collection.mutable.ListBuffer

object FindBestModel extends DefaultParamsReadable[FindBestModel] {
  val modelNameCol = "model_name"
  val metricsCol = "metric"
  val paramsCol = "parameters"
}

/**
  * Evaluates and chooses the best model from a list of models.
  */
class FindBestModel(override val uid: String) extends Estimator[BestModel] with MMLParams {

  def this() = this(Identifiable.randomUID("FindBestModel"))
  val models: TransformerArrayParam = new TransformerArrayParam(this, "models", "List of models to be evaluated")

  def getModels: Array[Transformer] = $(models)

  /** @group setParam **/
  def setModels(value: Array[Transformer]): this.type = set(models, value)

  /** @group setParam **/
  val evaluationMetric: Param[String] = StringParam(this, "evaluationMetric", "Metric to evaluate models with",
    (s: String) => Seq(ComputeModelStatistics.MseSparkMetric,
    ComputeModelStatistics.RmseSparkMetric,
    ComputeModelStatistics.R2SparkMetric,
    ComputeModelStatistics.MaeSparkMetric,
    ComputeModelStatistics.AccuracySparkMetric,
    ComputeModelStatistics.PrecisionSparkMetric,
    ComputeModelStatistics.RecallSparkMetric,
    ComputeModelStatistics.AucSparkMetric) contains s)

  // Set default evaluation metric to accuracy
  setDefault(evaluationMetric -> ComputeModelStatistics.AccuracySparkMetric)

  def getEvaluationMetric: String = $(evaluationMetric)

  /** @group setParam **/
  def setEvaluationMetric(value: String): this.type = set(evaluationMetric, value)

  var selectedModel: Transformer = null

  var selectedScoredDataset: Dataset[_] = null

  var selectedROCCurve: DataFrame = null

  var selectedBestModelMetrics: Dataset[_] = null

  /**
    *
    * @param dataset - The input dataset, to be fitted
    * @return The Model that results from the fitting
    */
  override def fit(dataset: Dataset[_]): BestModel = {
    // Staging
    val trainedModels = getModels
    if (trainedModels.isEmpty) {
      throw new Exception("No trained models to evaluate.")
    }
    // Find type of trained models
    def modelTypeDiscriminant(model: Transformer):String = {
      model match {
        case reg: TrainedRegressorModel => SchemaConstants.RegressionKind
        case cls: TrainedClassifierModel => SchemaConstants.ClassificationKind
        case evm: BestModel => modelTypeDiscriminant(evm.getBestModel)
        case _ => throw new Exception("Model type not supported for evaluation")
      }
    }
    val modelType = modelTypeDiscriminant(trainedModels(0))
    val evaluator = new ComputeModelStatistics()
    evaluator.set(evaluator.evaluationMetric, getEvaluationMetric)

    var bestMetric: Double = Double.NaN
    // Setup to store metrics and model name data for model metrics table
    val modelMetrics = ListBuffer[Double]()
    val models = ListBuffer[String]()
    val parameters = ListBuffer[String]()

    // TODO: Add the other metrics
    // TODO: Check metrics per model
    val chooseHighest = (current: Double, best: Double) => { current > best }
    val chooseLowest = (current: Double, best: Double) => { current < best }
    val (evaluationMetricColumnName, operator): (String, (Double, Double) => Boolean) = modelType match {
      case SchemaConstants.RegressionKind => getEvaluationMetric match {
        case ComputeModelStatistics.MseSparkMetric  => (ComputeModelStatistics.MseColumnName,  chooseLowest)
        case ComputeModelStatistics.RmseSparkMetric => (ComputeModelStatistics.RmseColumnName, chooseLowest)
        case ComputeModelStatistics.R2SparkMetric   => (ComputeModelStatistics.R2ColumnName,   chooseHighest)
        case ComputeModelStatistics.MaeSparkMetric  => (ComputeModelStatistics.MaeColumnName,  chooseLowest)
        case _ => throw new Exception("Metric is not supported for regressors")
      }
      case SchemaConstants.ClassificationKind => getEvaluationMetric match {
        case ComputeModelStatistics.AucSparkMetric       => (ComputeModelStatistics.AucColumnName, chooseHighest)
        case ComputeModelStatistics.PrecisionSparkMetric => (ComputeModelStatistics.PrecisionColumnName, chooseHighest)
        case ComputeModelStatistics.RecallSparkMetric    => (ComputeModelStatistics.RecallColumnName, chooseHighest)
        case ComputeModelStatistics.AccuracySparkMetric  => (ComputeModelStatistics.AccuracyColumnName, chooseHighest)
        case _ => throw new Exception("Metric is not supported for classifiers")
      }
      case _ => throw new Exception("Model type not supported for evaluation")
    }

    val compareModels = (model: Transformer, metrics: DataFrame, scoredDataset: Dataset[_]) => {
      val currentMetric = metrics.select(evaluationMetricColumnName).first()(0).toString.toDouble
      modelMetrics += currentMetric
      models += model.uid
      def getModelParams(model: Transformer): ParamMap = {
        model match {
          case reg: TrainedRegressorModel => reg.getParamMap
          case cls: TrainedClassifierModel => cls.getParamMap
          case evm: BestModel => getModelParams(evm.getBestModel)
          case _ => throw new Exception("Model type not supported for evaluation")
        }
      }
      parameters += getModelParams(model).toSeq.map { case pv => s"${pv.param.name}: ${pv.value}" }.mkString(", ")
      if (bestMetric.isNaN || operator(currentMetric, bestMetric)) {
        bestMetric = currentMetric
        selectedModel = model
        selectedScoredDataset = scoredDataset
      }
    }

    for (trainedModel <- trainedModels) {
      // Check that models are consistent
      if (modelTypeDiscriminant(trainedModel) != modelType) {
        throw new Exception("Models are inconsistent. Please evaluate only regressors or classifiers.")
      }
      val df = trainedModel.transform(dataset)
      val metrics = evaluator.transform(df)
      compareModels(trainedModel, metrics, df)
    }

    // compute ROC curve
    evaluator.set(evaluator.evaluationMetric, ComputeModelStatistics.AllSparkMetrics)
    selectedBestModelMetrics = evaluator.transform(selectedScoredDataset)
    selectedROCCurve = evaluator.rocCurve

    val spark = dataset.sparkSession
    val allModelMetricsSchema = StructType(Seq(StructField(FindBestModel.modelNameCol, StringType, true),
      StructField(FindBestModel.metricsCol, DoubleType, true),
      StructField(FindBestModel.paramsCol, StringType, true)))
    var allModelMetrics = spark.createDataFrame(spark.sparkContext.parallelize(models.zip(modelMetrics).zip(parameters)
        .map(mmp => Row(mmp._1._1, mmp._1._2, mmp._2))), allModelMetricsSchema)
    new BestModel(uid,
      selectedModel,
      selectedScoredDataset,
      selectedROCCurve,
      selectedBestModelMetrics,
      allModelMetrics)
  }

  // Choose a random model as we don't know which one will be chosen yet - all will transform schema in same way
  def transformSchema(schema: StructType): StructType = getModels(0).transformSchema(schema)

  def copy(extra: ParamMap): FindBestModel = defaultCopy(extra)

}

/**
  * Model produced by [[FindBestModel]].
  */
class BestModel(val uid: String,
                val model: Transformer,
                val scoredDataset: Dataset[_],
                val rocCurve: DataFrame,
                val bestModelMetrics: Dataset[_],
                val allModelMetrics: Dataset[_])
    extends Model[BestModel] with MLWritable {

  override def write: MLWriter = new BestModel.EvaluateModelWriter(uid,
    new Pipeline().setStages(Array(model)).fit(scoredDataset),
    scoredDataset,
    rocCurve,
    bestModelMetrics,
    allModelMetrics)

  override def copy(extra: ParamMap): BestModel =
    new BestModel(uid, model.copy(extra), scoredDataset, rocCurve, bestModelMetrics, allModelMetrics)

  override def transform(dataset: Dataset[_]): DataFrame = model.transform(dataset)

  /**
    * The best model found during evaluation.
    * @return The best model.
    */
  def getBestModel: Transformer = model

  /**
    * Gets the scored dataset.
    * @return The scored dataset for the best model.
    */
  def getScoredDataset: Dataset[_] = scoredDataset

  /**
    * Gets the ROC curve with TPR, FPR.
    * @return The evaluation results.
    */
  def getEvaluationResults: Dataset[_] = rocCurve

  /**
    * Gets all of the best model metrics results from the evaluator.
    * @return All of the best model metrics results.
    */
  def getBestModelMetrics: Dataset[_] = bestModelMetrics

  /**
    * Gets a table of metrics from all models compared from the evaluation comparison.
    * @return The model metrics results from all models.
    */
  def getAllModelMetrics: Dataset[_] = allModelMetrics

  @DeveloperApi
  override def transformSchema(schema: StructType): StructType = model.transformSchema(schema)

}

object BestModel extends MLReadable[BestModel] {

  private val modelPart = "model"
  private val scoredDatasetPart = "scoredDataset"
  private val rocCurvePart = "rocCurve"
  private val bestModelMetricsPart = "bestModelMetrics"
  private val allModelMetricsPart = "allModelMetrics"
  private val dataPart = "data"

  override def read: MLReader[BestModel] = new BestModelReader

  override def load(path: String): BestModel = super.load(path)

  /** [[MLWriter]] instance for [[BestModel]] */
  private[BestModel]
  class EvaluateModelWriter(val uid: String,
                            val model: PipelineModel,
                            val scoredDataset: Dataset[_],
                            val rocCurve: DataFrame,
                            val bestModelMetrics: Dataset[_],
                            val allModelMetrics: Dataset[_])
    extends MLWriter {
    private case class Data(uid: String)

    override protected def saveImpl(path: String): Unit = {
      val overwrite = this.shouldOverwrite
      val qualPath = PipelineUtilities.makeQualifiedPath(sc, path)
      // Required in order to allow this to be part of an ML pipeline
      PipelineUtilities.saveMetadata(uid,
        BestModel.getClass.getName.replace("$", ""),
        new Path(path, "metadata").toString,
        sc,
        overwrite)

      // save the model
      val modelPath = new Path(qualPath, modelPart).toString
      val modelWriter =
        if (overwrite) model.write.overwrite()
        else model.write
      modelWriter.save(modelPath)

      val saveMode =
        if (overwrite) SaveMode.Overwrite
        else SaveMode.ErrorIfExists

      // save the scored dataset
      val scoredDatasetPath = new Path(qualPath, scoredDatasetPart).toString
      scoredDataset.write.mode(saveMode).parquet(scoredDatasetPath)

      // save the roc curve
      val rocCurvePath = new Path(qualPath, rocCurvePart).toString
      rocCurve.write.mode(saveMode).parquet(rocCurvePath)

      // save the best model metrics
      val bestModelMetricsPath = new Path(qualPath, bestModelMetricsPart).toString
      bestModelMetrics.write.mode(saveMode).parquet(bestModelMetricsPath)

      // save all model metrics
      val allModelMetricsPath = new Path(qualPath, allModelMetricsPart).toString
      allModelMetrics.write.mode(saveMode).parquet(allModelMetricsPath)

      // save model data
      val data = Data(uid)
      val dataPath = new Path(qualPath, dataPart).toString
      sparkSession.createDataFrame(Seq(data)).repartition(1).write.mode(saveMode).parquet(dataPath)
    }
  }

  private class BestModelReader
    extends MLReader[BestModel] {

    override def load(path: String): BestModel = {
      val qualPath = PipelineUtilities.makeQualifiedPath(sc, path)
      // load the uid, label column and model name
      val dataPath = new Path(qualPath, dataPart).toString
      val data = sparkSession.read.format("parquet").load(dataPath)
      val Row(uid: String) = data.select("uid").head()

      // retrieve the underlying model
      val modelPath = new Path(qualPath, modelPart).toString
      val model = PipelineModel.load(modelPath)

      // retrieve the scored dataset
      val scoredDatasetPath = new Path(qualPath, scoredDatasetPart).toString
      val scoredDataset = sparkSession.read.parquet(scoredDatasetPath)

      // retrieve the roc curve
      val rocCurvePath = new Path(qualPath, rocCurvePart).toString
      val rocCurve = sparkSession.read.parquet(rocCurvePath)

      // retrieve the best model metrics
      val bestModelMetricsPath = new Path(qualPath, bestModelMetricsPart).toString
      val bestModelMetrics = sparkSession.read.parquet(bestModelMetricsPath)

      // retrieve all model metrics
      val allModelMetricsPath = new Path(qualPath, allModelMetricsPart).toString
      val allModelMetrics = sparkSession.read.parquet(allModelMetricsPath)

      new BestModel(uid, model.stages(0), scoredDataset, rocCurve, bestModelMetrics, allModelMetrics)
    }
  }

}
