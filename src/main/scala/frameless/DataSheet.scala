package frameless

import org.apache.spark.api.java.JavaRDD
import org.apache.spark.api.java.function.{ Function => JFunction }
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{ Column, DataFrame, GroupedData, Row, SaveMode, SQLContext }
import org.apache.spark.sql.types.StructType
import org.apache.spark.storage.StorageLevel

import scala.collection.JavaConverters.{ asScalaBufferConverter, seqAsJavaListConverter }
import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import shapeless.{ ::, Generic, HList, HNil, LabelledGeneric, Nat, SingletonProductArgs, Witness }
import shapeless.ops.hlist.{ IsHCons, Length, Prepend, ToList, ZipWithKeys }
import shapeless.ops.record.{ Keys, Renamer, SelectAll, Selector, Values }
import shapeless.ops.traversable.FromTraversable
import shapeless.syntax.std.traversable.traversableOps

/** Wrapper around [[org.apache.spark.sql.DataFrame]] where the type parameter tracks the schema.
  *
  * All heavy-lifting is still being done by the backing DataFrame so this API will more or less
  * be 1-to-1 with that of the DataFrame's.
  */
final class DataSheet[Schema <: HList] private(val dataFrame: DataFrame) extends Serializable with SingletonProductArgs {
  import DataSheet._

  def as(alias: Symbol): DataSheet[Schema] = DataSheet(dataFrame.as(alias))

  def as(alias: String): DataSheet[Schema] = DataSheet(dataFrame.as(alias))

  def cache(): this.type = {
    dataFrame.cache()
    this
  }

  def count(): Long = dataFrame.count()

  def distinct: DataSheet[Schema] = DataSheet(dataFrame.distinct)

  def except(other: DataSheet[Schema]): DataSheet[Schema] =
    DataSheet(dataFrame.except(other.dataFrame))

  def explain(): Unit = dataFrame.explain()

  def explain(extended: Boolean): Unit = dataFrame.explain(extended)

  def intersect(other: DataSheet[Schema]): DataSheet[Schema] =
    DataSheet(dataFrame.intersect(other.dataFrame))

  def isLocal: Boolean = dataFrame.isLocal

  def join[OtherSchema <: HList, NewSchema <: HList](right: DataSheet[OtherSchema])(
                                                     implicit P: Prepend.Aux[Schema, OtherSchema, NewSchema]): DataSheet[NewSchema] =
    DataSheet(dataFrame.join(right.dataFrame))

  def limit(n: Int): DataSheet[Schema] = DataSheet(dataFrame.limit(n))

  def persist(newLevel: StorageLevel): this.type = {
    dataFrame.persist(newLevel)
    this
  }

  def persist(): this.type = {
    dataFrame.persist()
    this
  }

  def printSchema(): Unit = dataFrame.printSchema()

  val queryExecution = dataFrame.queryExecution

  def registerTempTable(tableName: String): Unit = dataFrame.registerTempTable(tableName)

  def repartition(numPartitions: Int): DataSheet[Schema] = DataSheet(dataFrame.repartition(numPartitions))

  def sample(withReplacement: Boolean, fraction: Double): DataSheet[Schema] =
    DataSheet(dataFrame.sample(withReplacement, fraction))

  def sample(withReplacement: Boolean, fraction: Double, seed: Long): DataSheet[Schema] =
    DataSheet(dataFrame.sample(withReplacement, fraction, seed))

  def save(source: String, mode: SaveMode, options: Map[String, String]): Unit = dataFrame.save(source, mode, options)

  def save(path: String, source: String, mode: SaveMode): Unit = dataFrame.save(path, source, mode)

  def save(path: String, source: String): Unit = dataFrame.save(path, source)

  def save(path: String, mode: SaveMode): Unit = dataFrame.save(path, mode)

  def save(path: String): Unit = dataFrame.save(path)

  def saveAsParquetFile(path: String): Unit = dataFrame.saveAsParquetFile(path)

  def saveAsTable(tableName: String, source: String, mode: SaveMode, options: Map[String, String]): Unit =
    dataFrame.saveAsTable(tableName, source, mode, options)

  def saveAsTable(tableName: String, source: String, mode: SaveMode): Unit =
    dataFrame.saveAsTable(tableName, source, mode)

  def saveAsTable(tableName: String, source: String): Unit =
    dataFrame.saveAsTable(tableName, source)

  def saveAsTable(tableName: String, mode: SaveMode): Unit =
    dataFrame.saveAsTable(tableName, mode)

  def saveAsTable(tableName: String): Unit =
    dataFrame.saveAsTable(tableName)

  def show(): Unit = dataFrame.show()

  def show(numRows: Int): Unit = dataFrame.show(numRows)

  val sqlContext: SQLContext = dataFrame.sqlContext

  override def toString(): String = s"DataSheet:\n${dataFrame.toString}"

  def unionAll(other: DataSheet[Schema]): DataSheet[Schema] =
    DataSheet(dataFrame.unionAll(other.dataFrame))

  def unpersist(): this.type = {
    dataFrame.unpersist()
    this
  }

  def unpersist(blocking: Boolean): this.type = {
    dataFrame.unpersist(blocking)
    this
  }

  /////////////////////////

  /** Proxy that allows getting contents as a [[scala.Product]] type.
    *
    * Example usage:
    * {{{
    * case class Foo(x: Int, y: Double)
    *
    * val dataSheet = ...
    *
    * // Assuming dataSheet schema matches Foo
    * dataSheet.get[Foo].head(10): Array[Foo]
    * }}}
    */
  def get[P <: Product]: GetProxy[P] = new GetProxy[P]

  final class GetProxy[P <: Product] private[frameless] {
    def collect[V <: HList]()(implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V],
                              P: ClassTag[P], V: FromTraversable[V]): Array[P] =
      dataFrame.collect().map(unsafeRowToProduct[P, V])

    def collectAsScalaList[V <: HList]()(implicit Val: Values.Aux[Schema, V],Gen: Generic.Aux[P, V],
                                         V: FromTraversable[V]): List[P] =
      dataFrame.collectAsList().asScala.toList.map(unsafeRowToProduct[P, V])

    def collectAsList[V <: HList]()(implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V],
                                    V: FromTraversable[V]): java.util.List[P] =
      collectAsScalaList[V].asJava

    def first[V <: HList]()(implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V], V: FromTraversable[V]): P =
      unsafeRowToProduct(dataFrame.first())

    def head[V <: HList]()(implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V], V: FromTraversable[V]): P =
      unsafeRowToProduct(dataFrame.head())

    def head[V <: HList](n: Int)(implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V],
                                 P: ClassTag[P], V: FromTraversable[V]): Array[P] =
      dataFrame.head(n).map(unsafeRowToProduct[P, V])

    def javaRDD[V <: HList](implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V],
                            V: FromTraversable[V]): JavaRDD[P] = {
      val f = new JFunction[Row, P] { def call(v1: Row): P = unsafeRowToProduct(v1) }
      dataFrame.javaRDD.map(f)
    }

    def rdd[V <: HList](implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V],
                        P: ClassTag[P], V: FromTraversable[V]): RDD[P] =
      dataFrame.rdd.map(unsafeRowToProduct[P, V])

    def take[V <: HList](n: Int)(implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V],
                                 P: ClassTag[P], V: FromTraversable[V]): Array[P] =
      dataFrame.take(n).map(unsafeRowToProduct[P, V])
  }

  def flatMap[K <: HList, V <: HList, R](f: Schema => TraversableOnce[R])(
                                         implicit Key: Keys.Aux[Schema, K], Val: Values.Aux[Schema, V],
                                         ZWK: ZipWithKeys.Aux[K, V, Schema], R: ClassTag[R], V: FromTraversable[V]): RDD[R] =
    dataFrame.flatMap(f.compose(unsafeRowToRecord[Schema, K, V]))

  def foreach[K <: HList, V <: HList](f: Schema => Unit)(
                                      implicit Key: Keys.Aux[Schema, K], Val: Values.Aux[Schema, V],
                                      ZWK: ZipWithKeys.Aux[K, V, Schema], V: FromTraversable[V]): Unit =
    dataFrame.foreach(f.compose(unsafeRowToRecord[Schema, K, V]))

  def foreachPartition[K <: HList, V <: HList](f: Iterator[Schema] => Unit)(
                                               implicit Key: Keys.Aux[Schema, K], Val: Values.Aux[Schema, V],
                                               ZWK: ZipWithKeys.Aux[K, V, Schema], V: FromTraversable[V]): Unit =
    dataFrame.foreachPartition(f.compose(_.map(unsafeRowToRecord[Schema, K, V])))

  def map[K <: HList, V <: HList, R](f: Schema => R)(
                                     implicit Key: Keys.Aux[Schema, K], Val: Values.Aux[Schema, V],
                                     ZWK: ZipWithKeys.Aux[K, V, Schema], R: ClassTag[R], V: FromTraversable[V]): RDD[R] =
    dataFrame.map(f.compose(unsafeRowToRecord[Schema, K, V]))

  def mapPartitions[K <: HList, V <: HList, R](f: Iterator[Schema] => Iterator[R])(
                                               implicit Key: Keys.Aux[Schema, K], Val: Values.Aux[Schema, V],
                                               ZWK: ZipWithKeys.Aux[K, V, Schema], R: ClassTag[R], V: FromTraversable[V]): RDD[R] =
    dataFrame.mapPartitions(f.compose(_.map(unsafeRowToRecord[Schema, K, V])))

  /////////////////////////

  def createJDBCTable(url: String, table: String, allowExisting: Boolean): Unit =
    dataFrame.createJDBCTable(url, table, allowExisting)

  def columns: Array[String] = dataFrame.columns

  def dtypes: Array[(String, String)] = dataFrame.dtypes

  def insertInto(tableName: String): Unit = dataFrame.insertInto(tableName)

  def insertInto(tableName: String, overwrite: Boolean): Unit = dataFrame.insertInto(tableName, overwrite)

  def insertIntoJDBC(url: String, table: String, overwrite: Boolean): Unit =
    dataFrame.insertIntoJDBC(url, table, overwrite)

  def schema: StructType = dataFrame.schema

  def toDFProduct[V <: HList, L <: HList, N <: Nat, NewSchema <: HList](
      colNames: L)(
      implicit SchemaLen: Length.Aux[Schema, N],
      LLen: Length.Aux[L, N],
      ToList: ToList[L, Symbol],
      Val: Values.Aux[Schema, V],
      ZWK: ZipWithKeys.Aux[L, V, NewSchema]): DataSheet[NewSchema] =
    DataSheet(dataFrame.toDF(colNames.toList.map(_.name): _*))

  def toJSON: RDD[String] = dataFrame.toJSON

  def withColumnRenamed(existingName: Witness.Lt[Symbol], newName: Witness.Lt[Symbol])(
                        implicit Rename: Renamer[Schema, existingName.T, newName.T]): DataSheet[Rename.Out] =
    DataSheet(dataFrame.withColumnRenamed(existingName.value.name, newName.value.name))

  /////////////////////////

  // Product Args
  def agg(expr: Column, exprs: Column*): DataFrame = ???

  def apply[Out](colName: Witness.Lt[Symbol])(
                 implicit Select: Selector.Aux[Schema, colName.T, Out]): DataColumn[colName.T, Out] =
    DataColumn(dataFrame(colName.value.name))

  def col[Out](colName: Witness.Lt[Symbol])(
               implicit Select: Selector.Aux[Schema, colName.T, Out]):DataColumn[colName.T, Out] =
    DataColumn(dataFrame.col(colName.value.name))

  def explode[A : TypeTag, Out, K <: HList, V <: HList, NewKeys <: HList, NewValues <: HList, NewSchema <: HList](
      inputColumn: Witness.Lt[Symbol], outputColumn: Witness.Lt[Symbol])(
      f: Out => TraversableOnce[A])(
      implicit Select: Selector.Aux[Schema, inputColumn.T, Out],
      Key: Keys.Aux[Schema, K],
      Val: Values.Aux[Schema, V],
      P1: Prepend.Aux[K, outputColumn.T :: HNil, NewKeys],
      P2: Prepend.Aux[V, Out :: HNil, NewValues],
      ZWK: ZipWithKeys.Aux[NewKeys, NewValues, NewSchema]): DataSheet[NewSchema] =
    DataSheet(dataFrame.explode(inputColumn.value.name, outputColumn.value.name)(f))

  // Product Args
  def explode[A <: Product : TypeTag](input: Column*)(f: Row => TraversableOnce[A]): DataFrame = ???

  def filter(condition: DataColumn[_, Boolean]): DataSheet[Schema] = DataSheet(dataFrame.filter(condition.column))

  // Product Args
  def groupBy(cols: Column*): GroupedData = ???

  def groupByProduct[L <: HList](cols: L): GroupedData = ???

  def innerJoin[OtherSchema <: HList, NewSchema <: HList](right: DataSheet[OtherSchema], joinExprs: DataColumn[_, Boolean])(
                                                          implicit P: Prepend.Aux[Schema, OtherSchema, NewSchema]): DataSheet[NewSchema] =
    DataSheet(dataFrame.join(right.dataFrame, joinExprs.column, "inner"))

  def outerJoin[OtherSchema <: HList, NewSchema <: HList](right: DataSheet[OtherSchema], joinExprs: DataColumn[_, Boolean])(
                                                          implicit P: Prepend.Aux[Schema, OtherSchema, NewSchema]): DataSheet[NewSchema] =
    DataSheet(dataFrame.join(right.dataFrame, joinExprs.column, "outer"))

  def leftOuterJoin[OtherSchema <: HList, NewSchema <: HList](right: DataSheet[OtherSchema], joinExprs: DataColumn[_, Boolean])(
                                                              implicit P: Prepend.Aux[Schema, OtherSchema, NewSchema]): DataSheet[NewSchema] =
    DataSheet(dataFrame.join(right.dataFrame, joinExprs.column, "left_outer"))

  def rightOuterJoin[OtherSchema <: HList, NewSchema <: HList](right: DataSheet[OtherSchema], joinExprs: DataColumn[_, Boolean])(
                                                               implicit P: Prepend.Aux[Schema, OtherSchema, NewSchema]): DataSheet[NewSchema] =
    DataSheet(dataFrame.join(right.dataFrame, joinExprs.column, "right_outer"))

  def semiJoin[OtherSchema <: HList, NewSchema <: HList](right: DataSheet[OtherSchema], joinExprs: DataColumn[_, Boolean])(
                                                         implicit P: Prepend.Aux[Schema, OtherSchema, NewSchema]): DataSheet[NewSchema] =
    DataSheet(dataFrame.join(right.dataFrame, joinExprs.column, "semijoin"))

  def join[OtherSchema <: HList, NewSchema <: HList](right: DataSheet[OtherSchema], joinExprs: DataColumn[_, Boolean])(
                                                     implicit P: Prepend.Aux[Schema, OtherSchema, NewSchema]): DataSheet[NewSchema] =
    DataSheet(dataFrame.join(right.dataFrame, joinExprs.column))

  // Product Args
  def orderBy(sortExprs: Column*): DataFrame = ???

  def orderByProduct[L <: HList](sortCols: L): DataFrame = ???

  // Product Args
  // def select(cols: Column*): DataFrame = ???

  def selectProduct[L <: HList, S <: HList, NewSchema <: HList, H, T <: HList](
      cols: L)(
      implicit Cons: IsHCons.Aux[L, H, T],
      Sel: SelectAll.Aux[Schema, L, S],
      ZWK: ZipWithKeys.Aux[L, S, NewSchema],
      HSym : H <:< Symbol,
      ToList: ToList[T, Symbol]): DataSheet[NewSchema] = {

    DataSheet(dataFrame.select(cols.head.name, cols.tail.toList.map(_.name): _*))
  }

  // Product Args
  def sort(sortExprs: Column*): DataFrame = ???

  def sortProduct[L <: HList](sortCols: L): DataFrame = ???

  def where(condition: DataColumn[_, Boolean]): DataSheet[Schema] = DataSheet(dataFrame.where(condition.column))

  def withColumn[A, K <: HList, V <: HList, NewKeys <: HList, NewValues <: HList, NewSchema <: HList](
      colName: Witness.Lt[Symbol], col: DataColumn[_, A])(
      implicit Key: Keys.Aux[Schema, K],
      Val: Values.Aux[Schema, V],
      P1: Prepend.Aux[K, colName.T :: HNil, NewKeys],
      P2: Prepend.Aux[V, A :: HNil, NewValues],
      ZWK: ZipWithKeys.Aux[NewKeys, NewValues, NewSchema]): DataSheet[NewSchema] =
    DataSheet(dataFrame.withColumn(colName.value.name, col.as(colName).column))
}

object DataSheet {
  private def apply[Schema <: HList](dataFrame: DataFrame): DataSheet[Schema] =
    new DataSheet[Schema](dataFrame)

  private def unsafeRowToHList[L <: HList : FromTraversable](row: Row): L =
    row.toSeq.toHList[L].get

  private def unsafeRowToProduct[P <: Product, L <: HList](row: Row)(
                                                           implicit Gen: Generic.Aux[P, L], L: FromTraversable[L]): P =
    Gen.from(unsafeRowToHList(row))

  private def unsafeRowToRecord[R <: HList, K <: HList, V <: HList](row: Row)(
                                                                    implicit Key: Keys.Aux[R, K], Val: Values.Aux[R, V],
                                                                    ZWK: ZipWithKeys.Aux[K, V, R], V: FromTraversable[V]): R =
    unsafeRowToHList[V](row).zipWithKeys(Key())

  def fromRDD[P <: Product : TypeTag, Schema <: HList](rdd: RDD[P])(
                                                       implicit Gen: LabelledGeneric.Aux[P, Schema]): DataSheet[Schema] =
    DataSheet(new SQLContext(rdd.sparkContext).implicits.rddToDataFrameHolder(rdd).toDF())
}
