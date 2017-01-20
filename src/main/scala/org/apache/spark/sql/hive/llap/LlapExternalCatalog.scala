/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive.llap

import java.util.concurrent.CancellationException

import scala.util.control.NonFatal

import com.hortonworks.spark.sql.hive.llap.DefaultJDBCWrapper
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.hive.ql.metadata.HiveException
import org.apache.thrift.TException

import org.apache.spark.internal.Logging
import org.apache.spark.SparkConf
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.{NoSuchTableException, TableAlreadyExistsException}
import org.apache.spark.sql.catalyst.catalog.{CatalogStorageFormat, CatalogTable,
  CatalogTableType}
import org.apache.spark.sql.hive.HiveExternalCatalog
import org.apache.spark.sql.hive.client.HiveClient
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.{StringType, StructType}


/**
 * A persistent implementation of the system catalog using Hive.
 * All public methods must be synchronized for thread-safety.
 */
private[spark] class LlapExternalCatalog(
    conf: SparkConf,
    hadoopConf: Configuration)
  extends HiveExternalCatalog(conf, hadoopConf) with Logging {

  // Exceptions thrown by the hive client that we would like to wrap
  private val clientExceptions = Set(
    "org.apache.hadoop.hive.ql.metadata.HiveException",
    "org.apache.thrift.TException")

  /**
   * Whether this is an exception thrown by the hive client that should be wrapped.
   *
   * Due to classloader isolation issues, pattern matching won't work here so we need
   * to compare the canonical names of the exceptions, which we assume to be stable.
   */
  private def isClientException(e: Throwable): Boolean = {
    var temp: Class[_] = e.getClass
    var found = false
    while (temp != null && !found) {
      found = clientExceptions.contains(temp.getCanonicalName)
      temp = temp.getSuperclass
    }
    found
  }

  /**
   * Run some code involving `client` in a [[synchronized]] block and wrap certain
   * exceptions thrown in the process in [[AnalysisException]].
   */
  private def withClient[T](body: => T): T = synchronized {
    try {
      body
    } catch {
      case NonFatal(e) if isClientException(e) =>
        throw new AnalysisException(
          e.getClass.getCanonicalName + ": " + e.getMessage, cause = Some(e))
    }
  }

  private def requireDbMatches(db: String, table: CatalogTable): Unit = {
    if (table.identifier.database != Some(db)) {
      throw new AnalysisException(
        s"Provided database '$db' does not match the one specified in the " +
        s"table definition (${table.identifier.database.getOrElse("n/a")})")
    }
  }

  override def createTable(
      tableDefinition: CatalogTable,
      ignoreIfExists: Boolean): Unit = {
    logInfo(tableDefinition.toString)

    assert(tableDefinition.identifier.database.isDefined)
    val db = tableDefinition.identifier.database.get
    requireDbExists(db)

    if (tableExists(db, tableDefinition.identifier.table)) {
      if (ignoreIfExists) {
        // No-op
      } else {
        throw new TableAlreadyExistsException(db = db, table = tableDefinition.identifier.table)
      }
    } else {
      val sessionState =
        SparkSession.getActiveSession.get.sessionState.asInstanceOf[LlapSessionState]
      val stmt = sessionState.connection.createStatement()
      // Check the privilege by creating a dummy table with the given name.
      stmt.executeUpdate(s"CREATE TABLE ${tableDefinition.identifier.quotedString} (dummy INT)")
      super.dropTable(db, tableDefinition.identifier.table, true, true)
      super.createTable(tableDefinition, ignoreIfExists)
    }
  }

  override def dropTable(
      db: String,
      table: String,
      ignoreIfNotExists: Boolean,
      purge: Boolean): Unit = withClient {
    requireDbExists(db)
    val sessionState = SparkSession.getActiveSession.get.sessionState.asInstanceOf[LlapSessionState]
    val stmt = sessionState.connection.createStatement()
    val ifExistsString = if (ignoreIfNotExists) "IF EXISTS" else ""
    val purgeString = if (purge) "PURGE" else ""
    stmt.executeUpdate(s"DROP TABLE $ifExistsString $db.$table $purgeString")
  }

  override def getTable(db: String, table: String): CatalogTable = withClient {
    val sessionState = SparkSession.getActiveSession.get.sessionState.asInstanceOf[LlapSessionState]
    val dmd = sessionState.connection.getMetaData()
    val rs = dmd.getColumns(null, db, table, null)
    try {
      val schema = new StructType()
      while (rs.next()) {
        val columnName = rs.getString(4)
        val dataType = rs.getInt(5)
        val fieldSize = rs.getInt(7)
        val fieldScale = rs.getInt(9)
        val nullable = true // Hive cols nullable
        val isSigned = true
        val columnType =
          DefaultJDBCWrapper.getCatalystType(dataType, fieldSize, fieldScale, isSigned)
        val columnTypeString = DefaultJDBCWrapper.columnString(columnType, Some(fieldSize))
        schema.add(columnName, columnTypeString, nullable)
      }

      CatalogTable(
        identifier = TableIdentifier(table, Option(db)),
        tableType = CatalogTableType.EXTERNAL,
        schema = schema,
        storage = CatalogStorageFormat(
          locationUri = None,
          inputFormat = None,
          outputFormat = None,
          serde = None,
          compressed = false,
          properties = Map.empty))
    } finally {
      rs.close()
    }
  }

  override def tableExists(db: String, table: String): Boolean = withClient {
    val sessionState = SparkSession.getActiveSession.get.sessionState.asInstanceOf[LlapSessionState]
    val dmd = sessionState.connection.getMetaData()
    val rs = dmd.getTables(null, db, table, null)
    val result = rs.next()
    rs.close()
    result
  }

  override def listTables(db: String): Seq[String] = listTables(db, "*")

  override def listTables(db: String, pattern: String): Seq[String] = withClient {
    val sessionState = SparkSession.getActiveSession.get.sessionState.asInstanceOf[LlapSessionState]
    val dmd = sessionState.connection.getMetaData()
    val rs = dmd.getTables(null, db, pattern, null)
    var tableList: List[String] = Nil
    while (rs.next()) {
      tableList = rs.getString(3) :: tableList
    }
    rs.close()
    tableList.reverse
  }
}