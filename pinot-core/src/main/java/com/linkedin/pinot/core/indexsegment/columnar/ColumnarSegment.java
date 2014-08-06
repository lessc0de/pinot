package com.linkedin.pinot.core.indexsegment.columnar;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.log4j.Logger;

import com.linkedin.pinot.common.request.BrokerRequest;
import com.linkedin.pinot.common.segment.SegmentMetadata;
import com.linkedin.pinot.core.block.intarray.CompressedIntArrayDataSource;
import com.linkedin.pinot.core.common.Block;
import com.linkedin.pinot.core.common.BlockDocIdIterator;
import com.linkedin.pinot.core.common.Constants;
import com.linkedin.pinot.core.common.Operator;
import com.linkedin.pinot.core.common.Predicate;
import com.linkedin.pinot.core.indexsegment.ColumnarReader;
import com.linkedin.pinot.core.indexsegment.IndexSegment;
import com.linkedin.pinot.core.indexsegment.IndexType;
import com.linkedin.pinot.core.indexsegment.columnar.SegmentLoader.IO_MODE;
import com.linkedin.pinot.core.indexsegment.columnar.creator.V1Constants;
import com.linkedin.pinot.core.indexsegment.dictionary.Dictionary;
import com.linkedin.pinot.core.indexsegment.utils.Helpers;
import com.linkedin.pinot.core.indexsegment.utils.IntArray;
import com.linkedin.pinot.core.operator.DataSource;
import com.linkedin.pinot.core.plan.FilterPlanNode;


/**
 * Jul 15, 2014
 * 
 * @author Dhaval Patel <dpatel@linkedin.com>
 * 
 */
public class ColumnarSegment implements IndexSegment {
  public static final Logger logger = Logger.getLogger(ColumnarSegment.class);

  private String segmentName;
  private File segmentDir;
  private ColumnarSegmentMetadata segmentMetadata;
  private Map<String, ColumnMetadata> columnMetadata;
  private Map<String, IntArray> intArrayMap;
  private Map<String, Dictionary<?>> dictionaryMap;
  private IO_MODE mode;

  public ColumnarSegment(File indexDir, IO_MODE mode) throws ConfigurationException, IOException {
    segmentDir = indexDir;
    segmentName = indexDir.getName();
    segmentMetadata = new ColumnarSegmentMetadata(new File(indexDir, V1Constants.MetadataKeys.METADATA_FILE_NAME));
    columnMetadata = new HashMap<String, ColumnMetadata>();
    intArrayMap = new HashMap<String, IntArray>();
    dictionaryMap = new HashMap<String, Dictionary<?>>();
    this.mode = mode;

    logger.info("loaded segment metadata");

    for (String column : segmentMetadata.getAllColumnNames()) {
      logger.info("starting to load column : " + column);
      long start = System.currentTimeMillis();

      columnMetadata.put(column, new ColumnMetadata(new File(indexDir, V1Constants.MetadataKeys.METADATA_FILE_NAME),
          column, segmentMetadata.getFieldTypeFor(column)));
      logger.info("loaded metadata for column : " + column);
      dictionaryMap.put(
          column,
          DictionaryLoader.load(this.mode,
              new File(indexDir, Helpers.STRING.concat(column, V1Constants.Dict.FILE_EXTENTION)),
              columnMetadata.get(column)));
      logger.info("loaded dictionary for column : " + column + " of type : " + columnMetadata.get(column).getDataType()
          + " in : " + mode);
      if (columnMetadata.get(column).isSorted()) {
        intArrayMap.put(column, IntArrayLoader.load(this.mode,
            new File(indexDir, Helpers.STRING.concat(column, V1Constants.Indexes.SORTED_FWD_IDX_FILE_EXTENTION)),
            columnMetadata.get(column)));
      } else if (columnMetadata.get(column).isSingleValued()) {
        intArrayMap.put(column, IntArrayLoader.load(this.mode,
            new File(indexDir, Helpers.STRING.concat(column, V1Constants.Indexes.UN_SORTED_FWD_IDX_FILE_EXTENTION)),
            columnMetadata.get(column)));
      }

      logger.info("loaded fwd idx array for column : " + column + " in mode : " + mode);

      logger.info("total processing time for column : " + column + " was : " + (System.currentTimeMillis() - start));
    }
  }

  public Map<String, ColumnMetadata> getColumnMetadataMap() {
    return columnMetadata;
  }

  public ColumnMetadata getColumnMetadataFor(String column) {
    return columnMetadata.get(column);
  }

  public Map<String, IntArray> getIntArraysMap() {
    return intArrayMap;
  }

  public IntArray getIntArrayFor(String column) {
    return intArrayMap.get(column);
  }

  public Map<String, Dictionary<?>> getDictionaryMap() {
    return dictionaryMap;
  }

  public Dictionary<?> getDictionaryFor(String column) {
    return dictionaryMap.get(column);
  }

  @Override
  public IndexType getIndexType() {
    return IndexType.columnar;
  }

  @Override
  public String getSegmentName() {
    return segmentName;
  }

  @Override
  public String getAssociatedDirectory() {
    return segmentDir.getAbsolutePath();
  }

  @Override
  public DataSource getDataSource(String columnName) {
    // TODO Auto-generated method stub
    return new CompressedIntArrayDataSource(intArrayMap.get(columnName), dictionaryMap.get(columnName),
        columnMetadata.get(columnName));
  }

  @Override
  public DataSource getDataSource(String columnName, Predicate p) {
    CompressedIntArrayDataSource ds =
        new CompressedIntArrayDataSource(intArrayMap.get(columnName), dictionaryMap.get(columnName),
            columnMetadata.get(columnName));
    ds.setPredicate(p);
    return ds;
  }

  @Override
  public SegmentMetadata getSegmentMetadata() {
    return segmentMetadata;
  }

  @Override
  public Iterator<Integer> getDocIdIterator(BrokerRequest brokerRequest) {
    FilterPlanNode filterPlanNode = new FilterPlanNode(this, brokerRequest);
    final Operator operator = filterPlanNode.run();
    Iterator<Integer> iterator = new Iterator<Integer>() {

      private int next;
      private BlockDocIdIterator currentBlockDocIdIterator;

      @Override
      public boolean hasNext() {
        while (currentBlockDocIdIterator == null || (next = currentBlockDocIdIterator.next()) == Constants.EOF) {
          Block nextBlock = operator.nextBlock();
          if (nextBlock == null) {
            return false;
          }
          currentBlockDocIdIterator = nextBlock.getBlockDocIdSet().iterator();
        }
        // if it comes here, then next is set
        return true;
      }

      @Override
      public Integer next() {
        return next;
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException();
      }
    };
    return iterator;

  }

  @Override
  public ColumnarReader getColumnarReader(final String column) {
    return new ColumnarReader() {
      Dictionary<?> dict = dictionaryMap.get(column);
      IntArray arr = intArrayMap.get(column);

      @Override
      public String getStringValue(int docId) {
        return dict.getString(arr.getInt(docId));
      }

      @Override
      public long getLongValue(int docId) {
        return (Long) dict.getRaw(arr.getInt(docId));
      }

      @Override
      public int getIntegerValue(int docId) {
        return (Integer) dict.getRaw(arr.getInt(docId));
      }

      @Override
      public float getFloatValue(int docId) {
        return (Float) dict.getRaw(arr.getInt(docId));
      }

      @Override
      public double getDoubleValue(int docId) {
        return (Double) dict.getRaw(arr.getInt(docId));
      }

      @Override
      public Object getRawValue(int docId) {
        return dict.getRaw(arr.getInt(docId));
      }
    };
  }

}