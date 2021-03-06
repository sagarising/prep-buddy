package com.thoughtworks.datacommons.prepbuddy.rdds

import java.util.UUID.randomUUID

import com.thoughtworks.datacommons.prepbuddy.clusterers._
import com.thoughtworks.datacommons.prepbuddy.exceptions.{ApplicationException, ErrorMessages}
import com.thoughtworks.datacommons.prepbuddy.imputations.ImputationStrategy
import com.thoughtworks.datacommons.prepbuddy.normalizers.NormalizationStrategy
import com.thoughtworks.datacommons.prepbuddy.smoothers.SmoothingMethod
import com.thoughtworks.datacommons.prepbuddy.transformations.GenericTransformation
import com.thoughtworks.datacommons.prepbuddy.types.{CSV, FileType}
import com.thoughtworks.datacommons.prepbuddy.utils.{PivotTable, RowRecord}
import org.apache.spark.rdd.RDD

import scala.reflect.ClassTag

class TransformableRDD(parent: RDD[String], fileType: FileType = CSV) extends AbstractRDD(parent, fileType) {

    private var schema: Map[String, Int] = _

    private def getFileType: FileType = fileType

    /**
      * Need to be set to use column name while calling other operations instead of column index
      *
      * @param schema Map between column name and column index
      * @return
      */
    def useSchema(schema: Map[String, Int]): TransformableRDD = {
        this.schema = schema
        this
    }

    private def getIndex(columnNames: String*): List[Int] = {
        if (schema == null) throw new ApplicationException(ErrorMessages.SCHEMA_NOT_SET)
        columnNames.map(schema.getOrElse(_, -1)).toList
    }

    /**
      * Returns a new RDD containing smoothed values of @columnIndex using @smoothingMethod
      *
      * @param columnIndex     Column Index
      * @param smoothingMethod Method that will be used for smoothing of the data
      * @return RDD<Double>
      */
    def smooth(columnIndex: Int, smoothingMethod: SmoothingMethod): RDD[Double] = {
        validateColumnIndex(columnIndex)
        validateNumericColumn(columnIndex)
        val cleanRDD: TransformableRDD = removeRows(!_.isNumberAt(columnIndex))
        val columnDataset: RDD[String] = cleanRDD.select(columnIndex)
        smoothingMethod.smooth(columnDataset)
    }

    /**
      * Returns a new TransformableRDD containing only the elements that satisfy the matchInDictionary.
      *
      * @param predicate A matchInDictionary function, which gives bool value for every row.
      * @return TransformableRDD
      */
    def removeRows(predicate: (RowRecord) => Boolean): TransformableRDD = {
        val filteredRDD = filter((record: String) => {
            val rowRecord = fileType.parse(record)
            !predicate(rowRecord)
        })
        new TransformableRDD(filteredRDD, fileType)
    }

    /**
      * Returns a new TransformableRDD by replacing the @cluster's text with specified @newValue
      *
      * @param cluster     Cluster of similar values to be replaced
      * @param newValue    Value that will be used to replace all the cluster value
      * @param columnIndex Column index
      * @return TransformableRDD
      */
    def replaceValues(cluster: Cluster, newValue: String, columnIndex: Int): TransformableRDD = {
        val mapped: RDD[String] = map((row) => {
            var rowRecord: RowRecord = fileType.parse(row)
            val value: String = rowRecord(columnIndex)
            if (cluster.containsValue(value)) rowRecord = rowRecord.replace(columnIndex, newValue)
            fileType.join(rowRecord)
        })
        new TransformableRDD(mapped, fileType)
    }

    /**
      * Returns a new TransformableRDD by applying the function on all rows marked as @flag
      *
      * @param symbol            Symbol that has been used for flagging.
      * @param symbolColumnIndex Symbol column index
      * @param mapFunction       map function
      * @return TransformableRDD
      */
    def mapByFlag(symbol: String, symbolColumnIndex: Int, mapFunction: (String) => String): TransformableRDD = {
        val mappedRDD: RDD[String] = map((record) => {
            val rowRecord: RowRecord = fileType.parse(record)
            val symbolColumn: String = rowRecord(symbolColumnIndex)
            if (symbolColumn.equals(symbol)) mapFunction(record) else record
        })
        new TransformableRDD(mappedRDD, fileType)
    }

    /**
      * Returns a new TransformableRDD that contains records flagged by @symbol
      * based on the evaluation of @markerPredicate
      *
      * @param symbol          Symbol that will be used to flag
      * @param markerPredicate A matchInDictionary which will determine whether to flag a row or not
      * @return TransformableRDD
      */
    def flag(symbol: String, markerPredicate: (RowRecord) => Boolean): TransformableRDD = {
        val flagged: RDD[String] = map((record) => {
            var newRow: String = fileType.appendDelimiter(record)
            val rowRecord: RowRecord = fileType.parse(record)
            if (markerPredicate(rowRecord)) newRow += symbol
            newRow
        })
        new TransformableRDD(flagged, fileType)
    }

    /**
      * Returns number of column in this rdd
      *
      * @return int
      */
    def numberOfColumns(): Int = columnLength


    /**
      * Returns Clusters that has all cluster of text of @columnIndex according to @algorithm
      *
      * @param columnIndex         Column Index
      * @param clusteringAlgorithm Algorithm to be used to form clusters
      * @return Clusters
      */
    def clusters(columnIndex: Int, clusteringAlgorithm: ClusteringAlgorithm): Clusters = {
        validateColumnIndex(columnIndex)
        val textFacets: TextFacets = listFacets(columnIndex)
        val rdd: RDD[(String, Int)] = textFacets.rdd
        val tuples: Array[(String, Int)] = rdd.collect

        clusteringAlgorithm.getClusters(tuples)
    }

    /**
      * Returns Clusters that has all cluster of text of @columnName according to @algorithm only when schema is set
      *
      * @param columnName          Column Name
      * @param clusteringAlgorithm Algorithm to be used to form clusters
      * @return Clusters
      */
    def clusters(columnName: String, clusteringAlgorithm: ClusteringAlgorithm): Clusters = {
        clusters(getIndex(columnName).head, clusteringAlgorithm)
    }

    /**
      * Returns a new TextFacet containing the facets of @columnIndexes
      *
      * @param columnIndexes List of column index
      * @return TextFacets
      */
    def listFacets(columnIndexes: List[Int]): TextFacets = {
        val columnValuePair: RDD[(String, Int)] = select(columnIndexes).map((_, 1))
        val facets: RDD[(String, Int)] = columnValuePair.reduceByKey(_ + _)
        new TextFacets(facets)
    }

    /**
      * Returns a new TextFacet containing the facets of @columnNames
      *
      * @param columnNames List of column names
      * @return TextFacets
      */
    def listFacets[X: ClassTag](columnNames: List[String]): TextFacets = listFacets(getIndex(columnNames: _*))

    /**
      * Returns a new TextFacet containing the cardinal values of @columnIndex
      *
      * @param columnIndex index of the column
      * @return TextFacets
      */
    def listFacets(columnIndex: Int): TextFacets = listFacets(List(columnIndex))

    /**
      * Returns a new TextFacet containing the cardinal values of @columnName when Schema is set
      *
      * @param columnName Name of the column
      * @return TextFacets
      */
    def listFacets(columnName: String): TextFacets = listFacets(getIndex(columnName).head)

    /**
      * Returns a RDD of double which is a product of the values in @firstColumn and @secondColumn
      *
      * @param firstColumn  First Column Index
      * @param secondColumn Second Column Index
      * @return RDD[Double]
      */
    def multiplyColumns(firstColumn: Int, secondColumn: Int): RDD[Double] = {
        validateColumnIndex(firstColumn :: secondColumn :: Nil)
        val rddOfNumbers: TransformableRDD = removeRows((record) => {
            !record.isNumberAt(firstColumn) || !record.isNumberAt(secondColumn)
        })
        rddOfNumbers.map((row) => {
            val firstColumnValue: String = fileType.parse(row)(firstColumn)
            val secondColumnValue: String = fileType.parse(row)(secondColumn)
            firstColumnValue.toDouble * secondColumnValue.toDouble
        })
    }

    /**
      * Generates a PivotTable by pivoting data in the pivotalColumn
      *
      * @param pivotalColumn            Pivotal Column
      * @param independentColumnIndexes Independent Column Indexes
      * @return PivotTable
      */
    def pivotByCount(pivotalColumn: Int, independentColumnIndexes: Seq[Int]): PivotTable[Integer] = {
        validateColumnIndex(independentColumnIndexes.+:(pivotalColumn).toList)
        val table: PivotTable[Integer] = new PivotTable[Integer](0)
        independentColumnIndexes.foreach((index) => {
            val facets: TextFacets = listFacets(pivotalColumn :: index :: Nil)
            val tuples = facets.rdd.collect()
            tuples.foreach((tuple) => {
                val split: RowRecord = fileType.parse(tuple._1)
                table.addEntry(split(0), split(1), tuple._2)
            })
        })
        table
    }

    /**
      * Returns a TransformableRDD by splitting the @column according to the specified lengths
      *
      * @param column       Column index of the value to be split
      * @param fieldLengths List of integers specifying the number of character each split value will contains
      * @param retainColumn false when you want to remove the column value at @column in the result TransformableRDD
      * @return TransformableRDD
      */
    def splitByFieldLength(column: Int, fieldLengths: List[Int], retainColumn: Boolean = false): TransformableRDD = {
        validateColumnIndex(column)

        def splitString(value: String): Array[String] = {
            var result = Array.empty[String]
            var columnValue = value
            for (length <- fieldLengths) {
                val splitValue: String = {
                    if (columnValue.length >= length) columnValue.take(length) else columnValue
                }.mkString
                result = result.:+(splitValue)
                columnValue = columnValue.drop(length)
            }
            result
        }

        val transformed: RDD[String] = map((record) => {
            val rowRecord: RowRecord = fileType.parse(record)
            val splitValue: Array[String] = splitString(rowRecord(column))
            val result: RowRecord = arrangeRecords(rowRecord, column :: Nil, splitValue, retainColumn)
            fileType.join(result)
        })
        new TransformableRDD(transformed, fileType)
    }

    /**
      * Returns a new TransformableRDD by splitting the @column by the delimiter provided
      *
      * @param column       Column index of the value to be split
      * @param delimiter    delimiter or regEx that will be used to split the value @column
      * @param retainColumn false when you want to remove the column value at @column in the result TransformableRDD
      * @param maxSplit     Maximum number of split to be added to the result TransformableRDD
      * @return TransformableRDD
      */
    def splitByDelimiter(column: Int, delimiter: String, retainColumn: Boolean = false, maxSplit: Int = -1):
    TransformableRDD = {
        validateColumnIndex(column)
        val transformed: RDD[String] = map((record) => {
            val rowRecord: RowRecord = fileType.parse(record)
            val splitValue: Array[String] = rowRecord(column).split(delimiter, maxSplit)
            val result: RowRecord = arrangeRecords(rowRecord, List(column), splitValue, retainColumn)
            fileType.join(result)
        })
        new TransformableRDD(transformed, fileType)
    }

    private def arrangeRecords(rowRecord: RowRecord, cols: List[Int], result: Array[String], retainColumn: Boolean) = {
        if (retainColumn) rowRecord.appendColumns(result) else rowRecord.valuesNotAt(cols).appendColumns(result)
    }

    /**
      * Returns a new TransformableRDD by merging 2 or more columns together
      *
      * @param columns       List of columns to be merged
      * @param separator     Separator to be used to separate the merge value
      * @param retainColumns false when you want to remove the column value at @column in the result TransformableRDD
      * @return TransformableRDD
      */
    def mergeColumns(columns: List[Int], separator: String = " ", retainColumns: Boolean = false): TransformableRDD = {
        validateColumnIndex(columns)
        val transformedRDD: RDD[String] = map((record) => {
            val rowRecord: RowRecord = fileType.parse(record)
            val mergedValue: String = rowRecord(columns: _*).mkString(separator)
            val result: RowRecord = arrangeRecords(rowRecord, columns, Array(mergedValue), retainColumns)
            fileType.join(result)
        })
        new TransformableRDD(transformedRDD, fileType)
    }


    /**
      * Returns a new TransformableRDD by normalizing values of the given column using different Normalizers
      *
      * @param columnIndex Column Index
      * @param normalizer  Normalization Strategy
      * @return TransformableRDD
      */
    def normalize(columnIndex: Int, normalizer: NormalizationStrategy): TransformableRDD = {
        validateColumnIndex(columnIndex)
        normalizer.prepare(this, columnIndex)
        val rdd: RDD[String] = map((record) => {
            val columns: RowRecord = fileType.parse(record)
            val normalizedColumn = normalizer.normalize(columns(columnIndex))
            val normalizedRecord: RowRecord = columns.replace(columnIndex, normalizedColumn)
            fileType.join(normalizedRecord)
        })
        new TransformableRDD(rdd, fileType).useSchema(schema)
    }

    /**
      * Returns a new TransformableRDD by normalizing values of the given column using different Normalizers
      *
      * @param columnName Column Name
      * @param normalizer Normalization Strategy
      * @return TransformableRDD
      */
    def normalize(columnName: String, normalizer: NormalizationStrategy): TransformableRDD = {
        normalize(getIndex(columnName).head, normalizer)
    }

    /**
      * Returns a TransformableRDD by imputing missing values and @missingHints of the @columnIndex using the @strategy
      *
      * @param columnIndex  Column Index
      * @param strategy     Imputation Strategy
      * @param missingHints List of Strings that may mean empty
      * @return TransformableRDD
      */
    def impute(columnIndex: Int, strategy: ImputationStrategy, missingHints: List[String]): TransformableRDD = {
        validateColumnIndex(columnIndex)
        strategy.prepareSubstitute(this, columnIndex)
        val transformed: RDD[String] = map((record) => {
            val rowRecord: RowRecord = fileType.parse(record)
            var replacementValue: String = rowRecord(columnIndex)
            if (replacementValue == null || replacementValue.isEmpty || missingHints.contains(replacementValue)) {
                replacementValue = strategy.handleMissingData(rowRecord)
            }

            fileType.join(rowRecord.replace(columnIndex, replacementValue))
        })

        new TransformableRDD(transformed, fileType)
    }

    /**
      * Returns a TransformableRDD by imputing missing values @columnName with help of @missingHints by using the @strategy
      *
      * @param columnName   Column name
      * @param strategy     Imputation Strategy
      * @param missingHints List of Strings that may mean empty
      * @return TransformableRDD
      */
    def impute(columnName: String, strategy: ImputationStrategy, missingHints: List[String]): TransformableRDD = {
        impute(getIndex(columnName).head, strategy, missingHints)
    }

    /**
      * Returns a new TransformableRDD by imputing missing values @columnName using the @strategy
      *
      * @param columnName Column name
      * @param strategy   Imputation strategy
      * @return TransformableRDD
      */
    def impute(columnName: String, strategy: ImputationStrategy): TransformableRDD = {
        impute(getIndex(columnName).head, strategy)
    }

    /**
      * Returns a new TransformableRDD by imputing missing values of the @columnIndex using the @strategy
      *
      * @param column   Column index
      * @param strategy Imputation strategy
      * @return TransformableRDD
      */
    def impute(column: Int, strategy: ImputationStrategy): TransformableRDD = impute(column, strategy, List.empty)

    /**
      * Returns a new TransformableRDD by dropping the @columnIndex
      *
      * @param columnIndex The column that will be dropped.
      * @return TransformableRDD
      */
    def drop(columnIndex: Int, columnIndexes: Int*): TransformableRDD = {
        val columnsToBeDroped: List[Int] = columnIndexes.+:(columnIndex).toList
        validateColumnIndex(columnsToBeDroped)
        val transformed: RDD[String] = map((record: String) => {
            val rowRecord: RowRecord = fileType.parse(record)
            val resultRecord: RowRecord = rowRecord.valuesNotAt(columnsToBeDroped)
            fileType.join(resultRecord)
        })
        new TransformableRDD(transformed, fileType)
    }

    /**
      * Returns a new RDD containing the duplicate values at the specified column
      *
      * @param columnIndex Column where to look for duplicates
      * @return RDD
      */
    def duplicatesAt(columnIndex: Int): RDD[String] = {
        new TransformableRDD(select(columnIndex), fileType).duplicates().toRDD
    }

    /**
      * Returns a new RDD containing the duplicate values at the specified column
      *
      * @param columnName Column where to look for duplicates
      * @return RDD
      */
    def duplicatesAt(columnName: String): RDD[String] = duplicatesAt(getIndex(columnName).head)

    /**
      * Returns a new TransformableRDD containing only the records which has duplicate in the current data set by
      * considering all the columns as primary key.
      *
      * @return TransformableRDD A new TransformableRDD consisting only the duplicate records.
      */
    def duplicates(): TransformableRDD = duplicates(List.empty[Int])

    /**
      * Returns a new TransformableRDD containing only the records which has duplicate in the current data set by
      * considering the given columns as primary key.
      *
      * @param primaryKeyColumns list of integers specifying the columns that will be combined to create the primary key
      * @return TransformableRDD A new TransformableRDD consisting only the duplicate records.
      */
    def duplicates[X: ClassTag](primaryKeyColumns: List[Int]): TransformableRDD = {
        validateColumnIndex(primaryKeyColumns)
        val fingerprintedRecord: RDD[(Long, String)] = generateFingerprintedRDD(primaryKeyColumns)
        val recordsGroupedByKey: RDD[(Long, List[String])] = fingerprintedRecord.aggregateByKey(List.empty[String])(
            (accumulatorValues, currentValue) => accumulatorValues.::(currentValue),
            (aggregator1, aggregator2) => aggregator1 ::: aggregator2
        )
        val duplicateRecords: RDD[String] = recordsGroupedByKey
            .filter { case (recordKey, allRecords) => allRecords.size != 1 }
            .flatMap(_._2)
        new TransformableRDD(duplicateRecords, fileType).deduplicate()
    }

    /**
      * Returns a new TransformableRDD containing only the records which has duplicate in the current data set by
      * considering the given columns as primary key.
      *
      * @param primaryKeyColumns list of column names which will be combined to create the primary key
      * @return TransformableRDD A new TransformableRDD consisting duplicate records only.
      */
    def duplicates(primaryKeyColumns: List[String]): TransformableRDD = {
        duplicates(getIndex(primaryKeyColumns: _*))
    }

    /**
      * Returns a new RDD containing the unique elements in the specified column
      *
      * @param columnIndex Column Index
      * @return RDD<String>
      */
    def unique(columnIndex: Int): RDD[String] = new TransformableRDD(select(columnIndex), fileType).deduplicate().toRDD

    /**
      * Returns a new RDD containing the unique elements in the specified column
      *
      * @param columnName Column Name
      * @return RDD<String>
      */
    def unique(columnName: String): RDD[String] = unique(getIndex(columnName).head)

    /**
      * Returns a new TransformableRDD containing unique records of this TransformableRDD by considering
      * the given columns as primary key.
      *
      * @param primaryKeyColumns A list of integers specifying the columns that will be combined to create the primary key
      * @return TransformableRDD A new TransformableRDD consisting unique records.
      */
    def deduplicate(primaryKeyColumns: List[Int]): TransformableRDD = {
        validateColumnIndex(primaryKeyColumns)
        val fingerprintedRDD: RDD[(Long, String)] = generateFingerprintedRDD(primaryKeyColumns)
        val reducedRDD: RDD[(Long, String)] = fingerprintedRDD.reduceByKey((accumulator, record) => record)
        new TransformableRDD(reducedRDD.values, fileType)
    }

    /**
      * Returns a new TransformableRDD containing unique records of this TransformableRDD by considering
      * all the columns as primary key.
      *
      * @return TransformableRDD A new TransformableRDD consisting unique records.
      */
    def deduplicate(): TransformableRDD = deduplicate(List.empty[Int])

    /**
      * Returns a new TransformableRDD containing unique records of this TransformableRDD by considering
      * the given columns as primary key.
      *
      * @param primaryKeyColumns Columns that will be combined to create the primary key
      * @return TransformableRDD A new TransformableRDD consisting unique records.
      */
    def deduplicate[X: ClassTag](primaryKeyColumns: List[String]): TransformableRDD = {
        deduplicate(getIndex(primaryKeyColumns: _*))
    }

    private def generateFingerprintedRDD(primaryKeyColumns: List[Int]): RDD[(Long, String)] = {
        map(record => {
            val rowRecord: RowRecord = fileType.parse(record)
            val fingerprint = rowRecord.fingerprintBy(primaryKeyColumns)
            (fingerprint, record)
        })
    }

    /**
      * Zips the other TransformableRDD with this TransformableRDD and
      * returns a new TransformableRDD with current file format.
      * Both the TransformableRDD must have same number of records
      *
      * @param otherRDD Other TransformableRDD from where the columns will be added to this TransformableRDD
      * @return TransformableRDD
      */
    def addColumnsFrom(otherRDD: TransformableRDD): TransformableRDD = {
        val otherRDDInCurrentFileFormat = {
            if (this.getFileType != otherRDD.getFileType) {
                otherRDD.map(record => {
                    val rowRecord: RowRecord = otherRDD.getFileType.parse(record)
                    fileType.join(rowRecord)
                })
            }
            else {
                otherRDD
            }
        }
        val zippedRecords: RDD[(String, String)] = zip(otherRDDInCurrentFileFormat)
        val recordsWithAddedColumns: RDD[String] = zippedRecords.map(row => fileType.appendDelimiter(row._1) + row._2)

        new TransformableRDD(recordsWithAddedColumns, fileType)
    }

    /**
      * Returns a new TransformableRDD containing values of @columnIndexes
      *
      * @param columnIndexes A number of integer values specifying the columns that will be used to create the new table
      * @return TransformableRDD
      */
    def select[X: ClassTag](columnIndexes: List[Int]): TransformableRDD = {
        validateColumnIndex(columnIndexes)
        val selectedColumnValues: RDD[String] = map((record) => {
            val rowRecord: RowRecord = fileType.parse(record)
            val resultValues: RowRecord = rowRecord(columnIndexes: _*)
            fileType.join(resultValues)
        })

        setSchemaIfRequired(columnIndexes, new TransformableRDD(selectedColumnValues, fileType))
    }

    private def setSchemaIfRequired(columnIndexes: List[Int], rdd: TransformableRDD): TransformableRDD = {
        if (schema == null) {
            new TransformableRDD(rdd, fileType)
        } else {
            val newSchema: Map[String, Int] = columnIndexes.view
                .zipWithIndex
                .map { case (prevIndex, currentIndex) =>
                    val columnName: String = schema.find(_._2 == prevIndex).get._1
                    (columnName, currentIndex)
                }.toMap
            new TransformableRDD(rdd, fileType).useSchema(newSchema)
        }
    }


    /**
      * Returns a new TransformableRDD containing values of @columnNames
      *
      * @param columnNames List of column names to be selected
      * @return TransformableRDD
      */
    def select(columnNames: List[String]): TransformableRDD = select(getIndex(columnNames: _*))

    /**
      * Selects a single column based on the column name only when schema is set
      *
      * @param columnName Name of the column in the Schema
      * @return
      */
    def select(columnName: String): RDD[String] = select(List(columnName)).toRDD

    /**
      * Returns a Transformable RDD by appending a new column using @formula
      *
      * @param formula implementation of GenericTransformation interface
      * @return TransformableRDD
      */

    def appendNewColumn(formula: GenericTransformation): TransformableRDD = {
        val transformedValues: RDD[String] = map(record => {
            val rowRecord: RowRecord = fileType.parse(record)
            val output: Any = formula(rowRecord)
            val newRecord: RowRecord = rowRecord.appendColumns(Array(output.toString))
            fileType.join(newRecord)
        })
        new TransformableRDD(transformedValues, fileType)
    }

    /**
      * Returns a Transformable RDD by removing the outlier records on the basis of interQuartileRange
      *
      * @param columnIndex   of the record on which interQuartileRange will be calculated
      * @param outlierFactor default 1.5 for calculating the threshold
      * @return TransformableRDD
      */

    def removeOutliers(columnIndex: Int, outlierFactor: Double = 1.5): TransformableRDD = {
        validateColumnIndex(columnIndex)
        val numericIndexedRDD: NumericIndexedRDD = new NumericIndexedRDD(toDoubleRDD(columnIndex))
        val iqr = numericIndexedRDD.interQuartileRange
        val lowerThreshold = numericIndexedRDD.firstQuartileValue - (outlierFactor * iqr)
        val maximumThreshold = numericIndexedRDD.thirdQuartileValue + (outlierFactor * iqr)
        removeRows((rowRecord) => {
            (rowRecord(columnIndex).toDouble < lowerThreshold) || (rowRecord(columnIndex).toDouble > maximumThreshold)
        })
    }

    /**
      * Returns a new TransformableRDD after prepending incremental surrogate key to each record
      *
      * @param offset The surrogate keys that are going to be generated are followed by this number
      * @return
      */
    def addSurrogateKey(offset: Long): TransformableRDD = {
        val keyedRecords: RDD[(String, String)] = zipWithIndex()
            .map {
                case (record, index) =>
                    val surrogateKey: Long = index + offset + 1
                    (surrogateKey.toString, record)
            }
        new TransformableRDD(prependSurrogateKeyToRecord(keyedRecords), fileType)
    }

    /**
      * Returns a new TransformableRDD after prepending UUID as surrogate key to each record
      *
      * @return
      */
    def addSurrogateKey(): TransformableRDD = {
        val keyedRecords: RDD[(String, String)] = map((randomUUID().toString, _))
        new TransformableRDD(prependSurrogateKeyToRecord(keyedRecords), fileType)
    }


    private def prependSurrogateKeyToRecord(recordWithKey: RDD[(String, String)]): RDD[String] = {
        recordWithKey.map {
            case (surrogateKey, record) =>
                val rowRecords: RowRecord = fileType.parse(record)
                val recordWithPrependedKey: RowRecord = rowRecords.prependColumns(Array(surrogateKey))
                fileType.join(recordWithPrependedKey)
        }
    }
}