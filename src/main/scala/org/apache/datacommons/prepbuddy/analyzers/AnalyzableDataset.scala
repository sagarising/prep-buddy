package org.apache.datacommons.prepbuddy.analyzers

import org.apache.datacommons.prepbuddy.types.FileType
import org.apache.spark.sql.types.{StructField, StructType}
import org.apache.spark.sql.{Dataset, Row, SparkSession}

class AnalyzableDataset(spark: SparkSession, fileName: String, fileType: FileType, header: Boolean = true) {

    private val dataset: Dataset[Row] = spark.read
        .format("com.databricks.spark.csv")
        .option("header", header.toString)
        .option("inferSchema", "true")
        .load(fileName)

    def analyzeColumn(columnName: String, rules: ColumnRules): ColumnProfile = {
        new ColumnProfile()
    }

    def analyzeCompleteness(definition: RowCompletenessRule): RowCompletenessProfile = {
        new RowCompletenessProfile()
    }

    def analyzeSchemaCompliance(expectedSchema: StructType): SchemaComplianceProfile = {
        val actualFields: Array[StructField] = dataset.schema.fields
        val mismatchedFields: Array[(StructField, StructField)] = expectedSchema.fields
            .zip(actualFields)
            .filter(fieldPair => fieldPair._1 != fieldPair._2)

        mismatchedFields.foreach(println)
        new SchemaComplianceProfile(mismatchedFields)
    }
}
