/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sqoop.hive;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Properties;

import org.apache.avro.Schema;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.sqoop.avro.AvroUtil;
import org.apache.sqoop.io.CodecMap;

import org.apache.sqoop.SqoopOptions;
import org.apache.sqoop.manager.ConnManager;
import org.apache.sqoop.util.FileSystemUtil;

import static org.apache.sqoop.mapreduce.parquet.ParquetConstants.SQOOP_PARQUET_AVRO_SCHEMA_KEY;

/**
 * Creates (Hive-specific) SQL DDL statements to create tables to hold data
 * we're importing from another source.
 *
 * After we import the database into HDFS, we can inject it into Hive using
 * the CREATE TABLE and LOAD DATA INPATH statements generated by this object.
 */
public class TableDefWriter {

  public static final Log LOG = LogFactory.getLog(
      TableDefWriter.class.getName());

  private SqoopOptions options;
  private ConnManager connManager;
  private Configuration configuration;
  private String inputTableName;
  private String outputTableName;
  private boolean commentsEnabled;
  private Schema avroSchema;

  /**
   * Creates a new TableDefWriter to generate a Hive CREATE TABLE statement.
   * @param opts program-wide options
   * @param connMgr the connection manager used to describe the table.
   * @param inputTable the name of the table to load.
   * @param outputTable the name of the Hive table to create.
   * @param config the Hadoop configuration to use to connect to the dfs
   * @param withComments if true, then tables will be created with a
   *        timestamp comment.
   */
  public TableDefWriter(final SqoopOptions opts, final ConnManager connMgr,
      final String inputTable, final String outputTable,
      final Configuration config, final boolean withComments) {
    this.options = opts;
    this.connManager = connMgr;
    this.inputTableName = inputTable;
    this.outputTableName = outputTable;
    this.configuration = config;
    this.commentsEnabled = withComments;
  }

  /**
   * Get the column names to import.
   */
  private String [] getColumnNames() {
    if (options.getFileLayout() == SqoopOptions.FileLayout.ParquetFile) {
      return getColumnNamesFromAvroSchema();
    }
    String [] colNames = options.getColumns();
    if (null != colNames) {
      return colNames; // user-specified column names.
    } else if (null != inputTableName) {
      return connManager.getColumnNames(inputTableName);
    } else {
      return connManager.getColumnNamesForQuery(options.getSqlQuery());
    }
  }

  private String[] getColumnNamesFromAvroSchema() {
    List<String> result = new ArrayList<>();

    for (Schema.Field field : getAvroSchema().getFields()) {
      result.add(field.name());
    }

    return result.toArray(new String[result.size()]);
  }

  /**
   * @return the CREATE TABLE statement for the table to load into hive.
   */
  public String getCreateTableStmt() throws IOException {
    resetConnManager();
    Map<String, Integer> columnTypes;
    Properties userMapping = options.getMapColumnHive();
    Boolean isHiveExternalTableSet = !StringUtils.isBlank(options.getHiveExternalTableDir());
    // Get these from the database.
    if (null != inputTableName) {
      columnTypes = connManager.getColumnTypes(inputTableName);
    } else {
      columnTypes = connManager.getColumnTypesForQuery(options.getSqlQuery());
    }

    String [] colNames = getColumnNames();
    Map<String, Schema.Type> columnNameToAvroType = getColumnNameToAvroTypeMapping();
    StringBuilder sb = new StringBuilder();
    if (options.doFailIfHiveTableExists()) {
      if (isHiveExternalTableSet) {
        sb.append("CREATE EXTERNAL TABLE `");
      } else {
        sb.append("CREATE TABLE `");
      }
    } else {
      if (isHiveExternalTableSet) {
        sb.append("CREATE EXTERNAL TABLE IF NOT EXISTS `");
      } else {
        sb.append("CREATE TABLE IF NOT EXISTS `");
      }
    }

    if(options.getHiveDatabaseName() != null) {
      sb.append(options.getHiveDatabaseName()).append("`.`");
    }
    sb.append(outputTableName).append("` ( ");

    // Check that all explicitly mapped columns are present in result set
    for(Object column : userMapping.keySet()) {
      boolean found = false;
      for(String c : colNames) {
        if (c.equals(column)) {
          found = true;
          break;
        }
      }

      if (!found) {
        throw new IllegalArgumentException("No column by the name " + column
                + "found while importing data");
      }
    }

    boolean first = true;
    String partitionKey = options.getHivePartitionKey();
    for (String col : colNames) {
      if (col.equals(partitionKey)) {
        throw new IllegalArgumentException("Partition key " + col + " cannot "
            + "be a column to import.");
      }

      if (!first) {
        sb.append(", ");
      }

      first = false;

      String hiveColType;
      if (options.getFileLayout() == SqoopOptions.FileLayout.TextFile) {
        Integer colType = columnTypes.get(col);
        hiveColType = getHiveColumnTypeForTextTable(userMapping, col, colType);
      } else if (options.getFileLayout() == SqoopOptions.FileLayout.ParquetFile) {
        hiveColType = HiveTypes.toHiveType(columnNameToAvroType.get(col));
      } else {
        throw new RuntimeException("File format is not supported for Hive tables.");
      }

      sb.append('`').append(col).append("` ").append(hiveColType);

    }

    sb.append(") ");

    if (commentsEnabled) {
      DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
      String curDateStr = dateFormat.format(new Date());
      sb.append("COMMENT 'Imported by sqoop on " + curDateStr + "' ");
    }

    if (partitionKey != null) {
      sb.append("PARTITIONED BY (")
        .append(partitionKey)
        .append(" STRING) ");
     }

    if (SqoopOptions.FileLayout.ParquetFile.equals(options.getFileLayout())) {
      sb.append("STORED AS PARQUET");
    } else {
      sb.append("ROW FORMAT DELIMITED FIELDS TERMINATED BY '");
      sb.append(getHiveOctalCharCode((int) options.getOutputFieldDelim()));
      sb.append("' LINES TERMINATED BY '");
      sb.append(getHiveOctalCharCode((int) options.getOutputRecordDelim()));
      String codec = options.getCompressionCodec();
      if (codec != null && (codec.equals(CodecMap.LZOP)
          || codec.equals(CodecMap.getCodecClassName(CodecMap.LZOP)))) {
        sb.append("' STORED AS INPUTFORMAT "
            + "'com.hadoop.mapred.DeprecatedLzoTextInputFormat'");
        sb.append(" OUTPUTFORMAT "
            + "'org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat'");
      } else {
        sb.append("' STORED AS TEXTFILE");
      }
    }

    if (isHiveExternalTableSet) {
      // add location
      sb.append(" LOCATION '"+options.getHiveExternalTableDir()+"'");
    }

    LOG.debug("Create statement: " + sb.toString());
    return sb.toString();
  }

  private Map<String, Schema.Type> getColumnNameToAvroTypeMapping() {
    if (options.getFileLayout() != SqoopOptions.FileLayout.ParquetFile) {
      return Collections.emptyMap();
    }
    Map<String, Schema.Type> result = new HashMap<>();
    Schema avroSchema = getAvroSchema();
    for (Schema.Field field : avroSchema.getFields()) {
      result.put(field.name(), getNonNullAvroType(field.schema()));
    }

    return result;
  }

  private Schema.Type getNonNullAvroType(Schema schema) {
    if (schema.getType() != Schema.Type.UNION) {
      return schema.getType();
    }

    for (Schema subSchema : schema.getTypes()) {
      if (subSchema.getType() != Schema.Type.NULL) {
        return subSchema.getType();
      }
    }

    return null;
  }

  private String getHiveColumnTypeForTextTable(Properties userMapping, String columnName, Integer columnType) throws IOException {
    String hiveColType = userMapping.getProperty(columnName);
    if (hiveColType == null) {
      hiveColType = connManager.toHiveType(inputTableName, columnName, columnType);
    }
    if (null == hiveColType) {
      throw new IOException("Hive does not support the SQL type for column "
          + columnName);
    }

    if (HiveTypes.isHiveTypeImprovised(columnType)) {
      LOG.warn(
          "Column " + columnName + " had to be cast to a less precise type in Hive");
    }
    return hiveColType;
  }

  /**
   * @return the LOAD DATA statement to import the data in HDFS into hive.
   */
  public String getLoadDataStmt() throws IOException {
    Path finalPath = getFinalPath();

    StringBuilder sb = new StringBuilder();
    sb.append("LOAD DATA INPATH '");
    sb.append(finalPath.toString() + "'");
    if (options.doOverwriteHiveTable()) {
      sb.append(" OVERWRITE");
    }
    sb.append(" INTO TABLE `");
    if(options.getHiveDatabaseName() != null) {
      sb.append(options.getHiveDatabaseName()).append("`.`");
    }
    sb.append(outputTableName);
    sb.append('`');

    if (options.getHivePartitionKey() != null) {
      sb.append(" PARTITION (")
        .append(options.getHivePartitionKey())
        .append("='").append(options.getHivePartitionValue())
        .append("')");
    }

    LOG.debug("Load statement: " + sb.toString());
    return sb.toString();
  }

  public Path getFinalPath() throws IOException {
    String warehouseDir = options.getWarehouseDir();
    if (null == warehouseDir) {
      warehouseDir = "";
    } else if (!warehouseDir.endsWith(File.separator)) {
      warehouseDir = warehouseDir + File.separator;
    }

    // Final path is determined in the following order:
    // 1. Use target dir if the user specified.
    // 2. Use input table name.
    String tablePath = null;
    String targetDir = options.getTargetDir();
    if (null != targetDir) {
      tablePath = warehouseDir + targetDir;
    } else {
      tablePath = warehouseDir + inputTableName;
    }
    return FileSystemUtil.makeQualified(new Path(tablePath), configuration);
  }

  /**
   * Return a string identifying the character to use as a delimiter
   * in Hive, in octal representation.
   * Hive can specify delimiter characters in the form '\ooo' where
   * ooo is a three-digit octal number between 000 and 177. Values
   * may not be truncated ('\12' is wrong; '\012' is ok) nor may they
   * be zero-prefixed (e.g., '\0177' is wrong).
   *
   * @param charNum the character to use as a delimiter
   * @return a string of the form "\ooo" where ooo is an octal number
   * in [000, 177].
   * @throws IllegalArgumentException if charNum &gt; 0177.
   */
  public static String getHiveOctalCharCode(int charNum) {
    if (charNum > 0177) {
      throw new IllegalArgumentException(
          "Character " + charNum + " is an out-of-range delimiter");
    }

    return String.format("\\%03o", charNum);
  }

  /**
   * The JDBC connection owned by the ConnManager has been most probably opened when the import was started
   * so it might have timed out by the time TableDefWriter methods are invoked which happens at the end of import.
   * The task of this method is to discard the current connection held by ConnManager to make sure
   * that TableDefWriter will have a working one.
   */
  private void resetConnManager() {
    this.connManager.discardConnection(true);
  }

  SqoopOptions getOptions() {
    return options;
  }

  ConnManager getConnManager() {
    return connManager;
  }

  Configuration getConfiguration() {
    return configuration;
  }

  String getInputTableName() {
    return inputTableName;
  }

  String getOutputTableName() {
    return outputTableName;
  }

  boolean isCommentsEnabled() {
    return commentsEnabled;
  }

  Schema getAvroSchema() {
    if (avroSchema == null) {
      String schemaString = options.getConf().get(SQOOP_PARQUET_AVRO_SCHEMA_KEY);
      avroSchema = AvroUtil.parseAvroSchema(schemaString);
    }

    return avroSchema;
  }
}

