package com.linkedin.pinot.core.query.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.pinot.common.data.DataManager;
import com.linkedin.pinot.common.query.QueryExecutor;
import com.linkedin.pinot.common.request.BrokerRequest;
import com.linkedin.pinot.common.request.InstanceRequest;
import com.linkedin.pinot.common.response.InstanceResponse;
import com.linkedin.pinot.common.response.ProcessingException;
import com.linkedin.pinot.common.utils.NamedThreadFactory;
import com.linkedin.pinot.core.data.manager.InstanceDataManager;
import com.linkedin.pinot.core.data.manager.PartitionDataManager;
import com.linkedin.pinot.core.data.manager.ResourceDataManager;
import com.linkedin.pinot.core.data.manager.SegmentDataManager;
import com.linkedin.pinot.core.indexsegment.IndexSegment;
import com.linkedin.pinot.core.query.config.QueryExecutorConfig;
import com.linkedin.pinot.core.query.planner.ParallelQueryPlannerImpl;
import com.linkedin.pinot.core.query.planner.QueryPlan;
import com.linkedin.pinot.core.query.planner.QueryPlanner;
import com.linkedin.pinot.core.query.pruner.SegmentPrunerService;
import com.linkedin.pinot.core.query.pruner.SegmentPrunerServiceImpl;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.Timer;


public class ServerQueryExecutor implements QueryExecutor {

  private static Logger LOGGER = LoggerFactory.getLogger(ServerQueryExecutor.class);

  private static final String Domain = "com.linkedin.pinot";
  private QueryExecutorConfig _queryExecutorConfig = null;
  private InstanceDataManager _instanceDataManager = null;
  private SegmentPrunerService _segmentPrunerService = null;
  private QueryPlanner _queryPlanner = null;
  private PlanExecutor _planExecutor = null;
  private Timer _queryExecutorTimer = null;
  private boolean _isStarted = false;

  public ServerQueryExecutor() {
  }

  @Override
  public void init(Configuration queryExecutorConfig, DataManager dataManager) throws ConfigurationException {
    _queryExecutorConfig = new QueryExecutorConfig(queryExecutorConfig);
    _instanceDataManager = (InstanceDataManager) dataManager;
    LOGGER.info("Trying to build SegmentPrunerService");
    if (_segmentPrunerService == null) {
      _segmentPrunerService = new SegmentPrunerServiceImpl(_queryExecutorConfig.getPrunerConfig());
    }
    LOGGER.info("Trying to build QueryPlanner");
    if (_queryPlanner == null) {
      _queryPlanner = new ParallelQueryPlannerImpl();
    }
    LOGGER.info("Trying to build PlanExecutor");
    if (_planExecutor == null) {
      _planExecutor =
          new DefaultPlanExecutor(Executors.newCachedThreadPool(new NamedThreadFactory("plan-executor-global")));
    }
    LOGGER.info("Trying to build QueryExecutorTimer");
    if (_queryExecutorTimer == null) {
      _queryExecutorTimer =
          Metrics.newTimer(new MetricName(Domain, "timer", "query-executor-time-"), TimeUnit.MILLISECONDS,
              TimeUnit.SECONDS);
    }
  }

  public InstanceResponse processQuery(InstanceRequest instanceRequest) {
    long start = System.currentTimeMillis();
    final BrokerRequest brokerRequest = instanceRequest.getQuery();

    LOGGER.info("Incoming query is :" + brokerRequest);
    List<IndexSegment> queryableSegmentDataManagerList = getPrunedQueryableSegments(instanceRequest);

    final QueryPlan queryPlan = _queryPlanner.computeQueryPlan(brokerRequest, queryableSegmentDataManagerList);

    InstanceResponse result = null;
    try {
      result = _queryExecutorTimer.time(new Callable<InstanceResponse>() {
        @Override
        public InstanceResponse call() throws Exception {
          return _planExecutor.ProcessQueryBasedOnPlan(brokerRequest, queryPlan);
        }
      });
    } catch (Exception e) {
      LOGGER.error("Got error while processing the query", e);
      result = new InstanceResponse();
      List<ProcessingException> exceptions = new ArrayList<ProcessingException>();
      exceptions.add(new ProcessingException(250));
      result.setExceptions(exceptions);
    }
    long end = System.currentTimeMillis();
    result.setTimeUsedMs(end - start);
    return result;
  }

  private List<IndexSegment> getPrunedQueryableSegments(InstanceRequest instanceRequest) {
    String resourceName = instanceRequest.getQuery().getQuerySource().getResourceName();
    ResourceDataManager resourceDataManager = _instanceDataManager.getResourceDataManager(resourceName);
    if (resourceDataManager == null) {
      return null;
    }
    List<IndexSegment> queryableSegmentDataManagerList = new ArrayList<IndexSegment>();
    for (PartitionDataManager partitionDataManager : resourceDataManager.getPartitionDataManagerList()) {
      if ((partitionDataManager == null) || (partitionDataManager.getAllSegments() == null)) {
        continue;
      }
      for (SegmentDataManager segmentDataManager : partitionDataManager.getAllSegments()) {
        IndexSegment indexSegment = segmentDataManager.getSegment();
        if (instanceRequest.getSearchSegments() == null
            || instanceRequest.getSearchSegments().contains(indexSegment.getSegmentName())) {
          if (!_segmentPrunerService.prune(indexSegment, instanceRequest.getQuery())) {
            queryableSegmentDataManagerList.add(indexSegment);
          }
        }
      }
    }
    return queryableSegmentDataManagerList;
  }

  @Override
  public synchronized void shutDown() {
    if (isStarted()) {
      _isStarted = false;
      _planExecutor.shutDown();
      LOGGER.info("QueryExecutor is shutDown!");
    } else {
      LOGGER.warn("QueryExecutor is already shutDown, won't do anything!");
    }
  }

  public boolean isStarted() {
    return _isStarted;
  }

  @Override
  public synchronized void start() {
    _isStarted = true;
    LOGGER.info("QueryExecutor is started!");
  }
}