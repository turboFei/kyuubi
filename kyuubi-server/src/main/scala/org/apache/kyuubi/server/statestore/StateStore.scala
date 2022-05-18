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

package org.apache.kyuubi.server.statestore

import org.apache.kyuubi.server.statestore.api._

trait StateStore {
  def createBatch(batch: Batch): Unit

  def getBatch(batchId: String): Batch

  def updateBatchAppInfo(
      batchId: String,
      appId: String,
      appName: String,
      appUrl: String,
      appState: String,
      appError: Option[String]): Unit

  def closeBatch(batchId: String, state: String, endTime: Long): Unit

  def getBatchesByType(batchType: String, from: Int, size: Int): Seq[Batch]

  def getBatchesByOwner(batchOwner: String, from: Int, size: Int): Seq[Batch]

  def getBatchesByKyuubiInstance(kyuubiInstance: String, from: Int, size: Int): Seq[Batch]

  def saveBatchRequest(batchRequest: BatchRequest): Unit

  def getBatchRequest(batchId: String): BatchRequest

  def checkAndCleanupBatches(): Unit
}
