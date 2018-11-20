package com.tencent.angel.spark.examples.cluster

import com.tencent.angel.ml.math2.vector.{LongDoubleVector, Vector}
import com.tencent.angel.ml.matrix.RowType
import com.tencent.angel.spark.context.PSContext
import com.tencent.angel.spark.ml.core.ArgsUtil
import com.tencent.angel.spark.ml.core.metric.AUC
import com.tencent.angel.spark.ml.online_learning.FTRL
import com.tencent.angel.spark.ml.util.DataLoader
import org.apache.spark.{SparkConf, SparkContext}

object FTRLExample {

  def start(): Unit = {
    val conf = new SparkConf()
    val sc = new SparkContext(conf)
    PSContext.getOrCreate(sc)
  }

  def stop(): Unit = {
    PSContext.stop()
    SparkContext.getOrCreate().stop()
  }

  def main(args: Array[String]): Unit = {
    start()

    val params = ArgsUtil.parse(args)
    val alpha = params.getOrElse("alpha", "2.0").toDouble
    val beta = params.getOrElse("beta", "1.0").toDouble
    val lambda1 = params.getOrElse("lambda1", "0.1").toDouble
    val lambda2 = params.getOrElse("lambda2", "100.0").toDouble
    val dim = params.getOrElse("dim", "149").toLong
    val input = params.getOrElse("input", "data/census/census_148d_train.libsvm")
    val batchSize = params.getOrElse("batchSize", "100").toInt
    val partNum = params.getOrElse("partNum", "10").toInt
    val numEpoch = params.getOrElse("numEpoch", "3").toInt
    val modelPath = params.getOrElse("output", "")

    val opt = new FTRL(lambda1, lambda2, alpha, beta)
    opt.init(dim, RowType.T_DOUBLE_SPARSE_LONGKEY)

    val sc = SparkContext.getOrCreate()
    val data = sc.textFile(input).repartition(partNum)
      .map(s => (DataLoader.parseLongDouble(s, dim), DataLoader.parseLabel(s, false)))
      .map {
        f =>
          f._1.setY(f._2)
          f._1
      }
    val size = data.count()

    for (epoch <- 1 until numEpoch) {
      val totalLoss = data.mapPartitions {
        case iterator =>
          val loss = iterator.map(f => (f.getX, f.getY))
            .sliding(batchSize, batchSize)
            .map(f => opt.optimize(f.toArray, calcGradientLoss)).sum
          Iterator.single(loss)
      }.sum()

      val scores = data.mapPartitions {
        case iterator =>
          opt.predict(iterator.toArray).iterator}
      val auc = new AUC().calculate(scores)

      println(s"epoch=$epoch loss=${totalLoss / size} auc=$auc")
    }
    stop()
  }

  private def calcGradientLoss(w: LongDoubleVector, label: Double, feature: Vector): (LongDoubleVector, Double) = {
    val margin = -w.dot(feature)
    val gradientMultiplier = 1.0 / (1.0 + math.exp(margin)) - label
    val grad = feature.mul(gradientMultiplier).asInstanceOf[LongDoubleVector]

    val loss = if (label > 0) {
      math.log1p(math.exp(margin))
    } else {
      math.log1p(math.exp(margin)) - margin
    }

    (grad, loss)
  }
}
