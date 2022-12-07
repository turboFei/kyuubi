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

package org.apache.kyuubi.operation

import java.nio.ByteBuffer
import java.util.{ArrayList => JArrayList, Collections}

import com.google.common.primitives.Ints
import org.apache.hive.service.rpc.thrift.{TColumn, TColumnDesc, TI32Column, TPrimitiveTypeEntry, TRow, TRowSet, TTableSchema, TTypeDesc, TTypeEntry, TTypeId}

import org.apache.kyuubi.operation.FetchOrientation.FetchOrientation
import org.apache.kyuubi.session.Session

class Select1Operation(session: Session) extends InterceptedOperation(session) {
  private var alreadyFetched: Boolean = false

  override def getNextRowSet(order: FetchOrientation, rowSetSize: Int): TRowSet = {
    val tRow = new TRowSet(0, new JArrayList[TRow](1))
    val tColumn = new TColumn()
    if (!alreadyFetched) {
      tColumn.setI32Val(new TI32Column(Ints.asList(1), ByteBuffer.wrap(Array[Byte](0))))
      alreadyFetched = true
    } else {
      tColumn.setI32Val(new TI32Column(
        Collections.emptyList[Integer](),
        ByteBuffer.wrap(Array[Byte](0))));
    }
    tRow.addToColumns(tColumn)
    tRow
  }

  override def getResultSetSchema: TTableSchema = {
    val schema = new TTableSchema()
    val tColumnDesc = new TColumnDesc()
    tColumnDesc.setColumnName("1")
    tColumnDesc.setComment("intercepted by gateway")
    val tTypeDesc = new TTypeDesc()
    tTypeDesc.addToTypes(TTypeEntry.primitiveEntry(new TPrimitiveTypeEntry(TTypeId.INT_TYPE)))
    tColumnDesc.setTypeDesc(tTypeDesc)
    tColumnDesc.setPosition(1)
    schema.addToColumns(tColumnDesc)
    schema
  }
}