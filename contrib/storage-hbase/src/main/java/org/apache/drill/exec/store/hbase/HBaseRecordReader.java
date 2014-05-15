/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.hbase;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.drill.common.exceptions.DrillRuntimeException;
import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.expression.PathSegment;
import org.apache.drill.common.expression.PathSegment.NameSegment;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.types.TypeProtos;
import org.apache.drill.common.types.Types;
import org.apache.drill.exec.exception.SchemaChangeException;
import org.apache.drill.exec.memory.OutOfMemoryException;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.drill.exec.physical.impl.OutputMutator;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.store.RecordReader;
import org.apache.drill.exec.vector.NullableVarBinaryVector;
import org.apache.drill.exec.vector.ValueVector;
import org.apache.drill.exec.vector.VarBinaryVector;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;

public class HBaseRecordReader implements RecordReader, DrillHBaseConstants {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HBaseRecordReader.class);

  private static final int TARGET_RECORD_COUNT = 4000;

  private List<SchemaPath> columns;
  private OutputMutator outputMutator;

  private Map<FamilyQualifierWrapper, NullableVarBinaryVector> vvMap;
  private VarBinaryVector rowKeyVector;
  private SchemaPath rowKeySchemaPath;

  private HTable hTable;
  private ResultScanner resultScanner;

  private String hbaseTable;
  private Scan hbaseScan;
  private Configuration hbaseConf;
  private Result leftOver;

  public HBaseRecordReader(Configuration conf, HBaseSubScan.HBaseSubScanSpec subScanSpec,
      List<SchemaPath> projectedColumns, FragmentContext context) throws OutOfMemoryException {
    hbaseConf = conf;
    hbaseTable = subScanSpec.getTableName();
    hbaseScan = new Scan(subScanSpec.getStartRow(), subScanSpec.getStopRow());
    boolean rowKeyOnly = true;
    if (projectedColumns != null && projectedColumns.size() != 0) {
      /*
       * This will change once the non-scaler value vectors are available.
       * Then, each column family will have a single top level value vector
       * and each column will be an item vector in its corresponding TLV.
       */
      this.columns = Lists.newArrayList(projectedColumns);
      Iterator<SchemaPath> columnIterator = columns.iterator();
      while(columnIterator.hasNext()) {
        SchemaPath column = columnIterator.next();
        if (column.getRootSegment().getPath().toString().equalsIgnoreCase(ROW_KEY)) {
          rowKeySchemaPath = ROW_KEY_PATH;
          continue;
        }
        rowKeyOnly = false;
        NameSegment root = column.getRootSegment();
        byte[] family = root.getPath().toString().getBytes();
        PathSegment child = root.getChild();
        if (child != null && child.isNamed()) {
          byte[] qualifier = child.getNameSegment().getPath().toString().getBytes();
          hbaseScan.addColumn(family, qualifier);
        } else {
          columnIterator.remove();
          hbaseScan.addFamily(family);
        }
      }
    } else {
      this.columns = Lists.newArrayList();
      rowKeyOnly = false;
      rowKeySchemaPath = ROW_KEY_PATH;
      this.columns.add(rowKeySchemaPath);
    }

    hbaseScan.setFilter(subScanSpec.getScanFilter());
    if (rowKeyOnly) {
      /* if only the row key was requested, add a FirstKeyOnlyFilter to the scan
       * to fetch only one KV from each row. If a filter is already part of this
       * scan, add the FirstKeyOnlyFilter as the LAST filter of a MUST_PASS_ALL
       * FilterList.
       */
      hbaseScan.setFilter(
          HBaseUtils.andFilterAtIndex(hbaseScan.getFilter(), HBaseUtils.LAST_FILTER, new FirstKeyOnlyFilter())
          );
    }
    hbaseScan.setCaching(TARGET_RECORD_COUNT);
  }

  @Override
  public void setup(OutputMutator output) throws ExecutionSetupException {
    this.outputMutator = output;
    output.removeAllFields();
    vvMap = new HashMap<FamilyQualifierWrapper, NullableVarBinaryVector>();

    try {
      // Add Vectors to output in the order specified when creating reader
      for (SchemaPath column : columns) {
        if (column.equals(rowKeySchemaPath)) {
          MaterializedField field = MaterializedField.create(column, Types.required(TypeProtos.MinorType.VARBINARY));
          rowKeyVector = output.addField(field, VarBinaryVector.class);
        } else if (column.getRootSegment().getChild() != null) {
          getOrCreateColumnVector(new FamilyQualifierWrapper(column), false);
        }
      }
      output.setNewSchema();

      logger.debug("Opening scanner for HBase table '{}', Zookeeper quorum '{}', port '{}', znode '{}'.",
          hbaseTable, hbaseConf.get(HConstants.ZOOKEEPER_QUORUM),
          hbaseConf.get(HBASE_ZOOKEEPER_PORT), hbaseConf.get(HConstants.ZOOKEEPER_ZNODE_PARENT));
      hTable = new HTable(hbaseConf, hbaseTable);
      resultScanner = hTable.getScanner(hbaseScan);
    } catch (SchemaChangeException | IOException e) {
      throw new ExecutionSetupException(e);
    }
  }

  @Override
  public int next() {
    Stopwatch watch = new Stopwatch();
    watch.start();
    if (rowKeyVector != null) {
      rowKeyVector.clear();
      rowKeyVector.allocateNew();
    }
    for (ValueVector v : vvMap.values()) {
      v.clear();
      v.allocateNew();
    }

    for (int count = 0; count < TARGET_RECORD_COUNT; count++) {
      Result result = null;
      try {
        if (leftOver != null) {
          result = leftOver;
          leftOver = null;
        } else {
          result = resultScanner.next();
        }
      } catch (IOException e) {
        throw new DrillRuntimeException(e);
      }
      if (result == null) {
        setOutputValueCount(count);
        logger.debug("Took {} ms to get {} records", watch.elapsed(TimeUnit.MILLISECONDS), count);
        return count;
      }

      // parse the result and populate the value vectors
      KeyValue[] kvs = result.raw();
      byte[] bytes = result.getBytes().get();
      if (rowKeyVector != null) {
        if (!rowKeyVector.getMutator().setSafe(count, bytes, kvs[0].getRowOffset(), kvs[0].getRowLength())) {
          setOutputValueCount(count);
          leftOver = result;
          logger.debug("Took {} ms to get {} records", watch.elapsed(TimeUnit.MILLISECONDS), count);
          return count;
        }
      }
      for (KeyValue kv : kvs) {
        int familyOffset = kv.getFamilyOffset();
        int familyLength = kv.getFamilyLength();
        int qualifierOffset = kv.getQualifierOffset();
        int qualifierLength = kv.getQualifierLength();
        int valueOffset = kv.getValueOffset();
        int valueLength = kv.getValueLength();
        NullableVarBinaryVector v = getOrCreateColumnVector(
            new FamilyQualifierWrapper(bytes, familyOffset, familyLength, qualifierOffset, qualifierLength), true);
        if (!v.getMutator().setSafe(count, bytes, valueOffset, valueLength)) {
          setOutputValueCount(count);
          leftOver = result;
          logger.debug("Took {} ms to get {} records", watch.elapsed(TimeUnit.MILLISECONDS), count);
          return count;
        }
      }
    }
    setOutputValueCount(TARGET_RECORD_COUNT);
    logger.debug("Took {} ms to get {} records", watch.elapsed(TimeUnit.MILLISECONDS), TARGET_RECORD_COUNT);
    return TARGET_RECORD_COUNT;
  }

  private NullableVarBinaryVector getOrCreateColumnVector(FamilyQualifierWrapper column, boolean allocateOnCreate) {
    try {
      NullableVarBinaryVector v = vvMap.get(column);
      if(v == null) {
        MaterializedField field = MaterializedField.create(column.asSchemaPath(), Types.optional(TypeProtos.MinorType.VARBINARY));
        v = outputMutator.addField(field, NullableVarBinaryVector.class);
        if (allocateOnCreate) {
          v.allocateNew();
        }
        vvMap.put(column, v);
        outputMutator.setNewSchema();
      }
      return v;
    } catch (SchemaChangeException e) {
      throw new DrillRuntimeException(e);
    }
  }

  @Override
  public void cleanup() {
    try {
      if (resultScanner != null) {
        resultScanner.close();
      }
      if (hTable != null) {
        hTable.close();
      }
    } catch (IOException e) {
      logger.warn("Failure while closing HBase table: " + hbaseTable, e);
    }
  }

  private void setOutputValueCount(int count) {
    for (ValueVector vv : vvMap.values()) {
      vv.getMutator().setValueCount(count);
    }
    if (rowKeyVector != null) {
      rowKeyVector.getMutator().setValueCount(count);
    }
  }

  private static class FamilyQualifierWrapper implements Comparable<FamilyQualifierWrapper> {
    int hashCode;
    protected String stringVal;
    protected String family;
    protected String qualifier;

    public FamilyQualifierWrapper(SchemaPath column) {
      this(column.getRootSegment().getPath(), column.getRootSegment().getChild().getNameSegment().getPath());
    }

    public FamilyQualifierWrapper(byte[] bytes, int familyOffset, int familyLength, int qualifierOffset, int qualifierLength) {
      this(new String(bytes, familyOffset, familyLength), new String(bytes, qualifierOffset, qualifierLength));
    }

    public FamilyQualifierWrapper(String family, String qualifier) {
      this.family = family;
      this.qualifier = qualifier;
      hashCode = 31*family.hashCode() + qualifier.hashCode();
    }

    @Override
    public int hashCode() {
      return this.hashCode;
    }

    @Override
    public boolean equals(Object anObject) {
      if (this == anObject) {
        return true;
      }
      if (anObject instanceof FamilyQualifierWrapper) {
        FamilyQualifierWrapper that = (FamilyQualifierWrapper) anObject;
        // we compare qualifier first since many columns will have same family
        if (!qualifier.equals(that.qualifier)) {
          return false;
        }
        return family.equals(that.family);
      }
      return false;
    }

    @Override
    public String toString() {
      if (stringVal == null) {
        stringVal = new StringBuilder().append(new String(family)).append(".").append(new String(qualifier)).toString();
      }
      return stringVal;
    }

    public SchemaPath asSchemaPath() {
      return SchemaPath.getCompoundPath(family, qualifier);
    }

    @Override
    public int compareTo(FamilyQualifierWrapper o) {
      int val = family.compareTo(o.family);
      if (val != 0) {
        return val;
      }
      return qualifier.compareTo(o.qualifier);
    }

  }

}
