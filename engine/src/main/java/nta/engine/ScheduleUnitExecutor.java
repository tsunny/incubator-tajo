package nta.engine;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.protobuf.Message;
import nta.catalog.TCatUtil;
import nta.catalog.TableMeta;
import nta.catalog.proto.CatalogProtos.*;
import nta.catalog.statistics.ColumnStat;
import nta.catalog.statistics.StatisticsUtil;
import nta.catalog.statistics.TableStat;
import nta.common.Sleeper;
import nta.engine.MasterInterfaceProtos.*;
import nta.engine.cluster.*;
import nta.engine.exception.EmptyClusterException;
import nta.engine.exception.UnknownWorkerException;
import nta.engine.ipc.protocolrecords.Fragment;
import nta.engine.ipc.protocolrecords.QueryUnitRequest;
import nta.engine.json.GsonCreator;
import nta.engine.planner.PlannerUtil;
import nta.engine.planner.global.MasterPlan;
import nta.engine.planner.global.QueryUnit;
import nta.engine.planner.global.QueryUnitAttempt;
import nta.engine.planner.global.ScheduleUnit;
import nta.engine.planner.global.ScheduleUnit.PARTITION_TYPE;
import nta.engine.planner.logical.ExprType;
import nta.engine.planner.logical.GroupbyNode;
import nta.engine.planner.logical.IndexWriteNode;
import nta.engine.planner.logical.ScanNode;
import nta.engine.query.GlobalPlanner;
import nta.engine.query.Priority;
import nta.engine.query.QueryUnitRequestImpl;
import nta.storage.StorageManager;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import tajo.index.IndexUtil;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author jihoon
 */
public class ScheduleUnitExecutor extends Thread {
  private enum Status {
    INPROGRESS, FINISHED, ABORTED,
  }

  private final static Log LOG = LogFactory.getLog(ScheduleUnitExecutor.class);
  private final static int WAIT_PERIOD = 3000;

  private BlockingQueue<ScheduleUnit> inprogressQueue;
  private BlockingQueue<QueryUnit> pendingQueue;

  private Status status;

  private final Configuration conf;
  private final ClusterManager cm;
  private final WorkerCommunicator wc;
  private final StorageManager sm;
  private final GlobalPlanner planner;
  private final QueryManager qm;
  private final SubQueryId id;
  private MasterPlan plan;

  private QueryScheduler scheduler;
  private QueryUnitSubmitter submitter;

  private Sleeper sleeper;

  public ScheduleUnitExecutor(Configuration conf, WorkerCommunicator wc,
      GlobalPlanner planner, ClusterManager cm, QueryManager qm,
      StorageManager sm, MasterPlan masterPlan) {
    this.conf = conf;
    this.wc = wc;
    this.planner = planner;
    this.plan = masterPlan;
    this.cm = cm;
    this.qm = qm;
    this.sm = sm;
    this.inprogressQueue = new LinkedBlockingQueue<ScheduleUnit>();
    this.pendingQueue = new LinkedBlockingQueue<QueryUnit>();
    this.scheduler = new QueryScheduler();
    this.submitter = new QueryUnitSubmitter();
    this.id = masterPlan.getRoot().getId().getSubQueryId();
    this.sleeper = new Sleeper();
  }

  @Override
  public void run() {
    this.status = Status.INPROGRESS;
    this.scheduler.start();
    this.submitter.start();
    while (this.status == Status.INPROGRESS) {
      try {
        this.sleeper.sleep(WAIT_PERIOD);

        if (scheduler.getStatus() == Status.FINISHED
            && submitter.getStatus() == Status.FINISHED) {
          shutdown(id + " is finished!");
        } else if (scheduler.getStatus() == Status.ABORTED
            || submitter.getStatus() == Status.ABORTED) {
          scheduler.abort();
          submitter.abort();
          abort(id + " is aborted!");
        }
      } catch (InterruptedException e) {
        abort(ExceptionUtils.getStackTrace(e));
      }
    }

    try {
      scheduler.join();
      submitter.join();
    } catch (InterruptedException e) {
      LOG.error(ExceptionUtils.getStackTrace(e));
    }
  }

  public Status getStatus() {
    return this.status;
  }

  public Status getSchedulerStatus() {
    return scheduler.getStatus();
  }

  public boolean isSchedulerFinished() {
    return scheduler.isFinished();
  }

  public Status getSubmitterStatus() {
    return submitter.getStatus();
  }

  public QueryUnitSubmitter getSubmitter() {
    return this.submitter;
  }

  public void shutdown(final String msg) {
    status = Status.FINISHED;
    LOG.info("Shutdown: " + msg);
    synchronized (this) {
      notifyAll();
    }
  }

  public void abort(final String msg) {
    status = Status.ABORTED;
    LOG.error("Abort: " + msg);
    synchronized (this) {
      notifyAll();
    }
  }

  private void writeStat(ScheduleUnit scheduleUnit, TableStat stat)
      throws IOException {

    if (scheduleUnit.getLogicalPlan().getType() == ExprType.CREATE_INDEX) {
      IndexWriteNode index = (IndexWriteNode) scheduleUnit.getLogicalPlan();
      Path indexPath = new Path(sm.getTablePath(index.getTableName()), "index");
      TableMeta meta;
      if (sm.getFileSystem().exists(new Path(indexPath, ".meta"))) {
        meta = sm.getTableMeta(indexPath);
      } else {
        meta = TCatUtil.newTableMeta(scheduleUnit.getOutputSchema(), StoreType.CSV);
      }
      String indexName = IndexUtil.getIndexName(index.getTableName(),
          index.getSortSpecs());
      String json = GsonCreator.getInstance().toJson(index.getSortSpecs());
      meta.putOption(indexName, json);

      sm.writeTableMeta(indexPath, meta);

    } else {
      TableMeta meta = TCatUtil.newTableMeta(scheduleUnit.getOutputSchema(),
          StoreType.CSV);
      meta.setStat(stat);
      sm.writeTableMeta(sm.getTablePath(scheduleUnit.getOutputName()), meta);
    }
  }

  private boolean requestToWC(String host, Message proto) throws Exception {
    boolean result = true;
    try {
      if (proto instanceof QueryUnitRequestProto) {
        wc.requestQueryUnit(host, (QueryUnitRequestProto) proto);
      } else if (proto instanceof CommandRequestProto) {
        wc.requestCommand(host, (CommandRequestProto) proto);
      }
    } catch (UnknownWorkerException e) {
      handleUnknownWorkerException(e);
      result = false;
    }
    return result;
  }

  private void handleUnknownWorkerException(UnknownWorkerException e) {
    LOG.warn(e);
    cm.addFailedWorker(e.getUnknownName());
    LOG.info(e.getUnknownName() + " is excluded from the query planning.");
  }

  private void sendCommand(QueryUnitAttempt unit, CommandType type)
      throws Exception {
    Command.Builder cmd = Command.newBuilder();
    cmd.setId(unit.getId().getProto()).setType(type);
    requestToWC(unit.getHost(),
        CommandRequestProto.newBuilder().addCommand(cmd.build()).build());
  }

  class QueryScheduler extends Thread {
    private Status status;
    private PriorityQueue<ScheduleUnit> scheduleQueue;
    private Sleeper sleeper;

    public class QueryUnitCluster implements Comparable<QueryUnitCluster> {
      private String host;
      private Set<QueryUnit> queryUnits;
      private Iterator<QueryUnit> it;

      public QueryUnitCluster(String host) {
        this.host = host;
        queryUnits = Sets.newHashSet();
      }

      public String getHost() {
        return this.host;
      }

      public boolean isEmpty() {
        return queryUnits.isEmpty();
      }

      public void addQueryUnit(QueryUnit unit) {
        queryUnits.add(unit);
      }

      public void removeQueryUnit(QueryUnit unit) {
        queryUnits.remove(unit);
      }

      public void initIteration() {
        it = queryUnits.iterator();
      }

      public boolean hasNext() {
        return it.hasNext();
      }

      public QueryUnit next() {
        return it.next();
      }

      @Override
      public int compareTo(QueryUnitCluster o) {
        return this.queryUnits.size() - o.queryUnits.size();
      }
    }

    public QueryScheduler() {
      this.scheduleQueue = new PriorityQueue<ScheduleUnit>(1,
          new PriorityComparator());
      this.sleeper = new Sleeper();
    }

    public void init() {
      this.status = Status.INPROGRESS;
      // insert schedule units to the schedule queue
      initScheduleQueue(plan.getRoot());
    }

    @Override
    public void run() {
      LOG.info("Query scheduler is started!");
      init();

      while (status == Status.INPROGRESS) {
        try {
          this.sleeper.sleep(WAIT_PERIOD);
          if (this.isFinished()) {
            this.shutdown();
          }

          ScheduleUnit scheduleUnit;
          while ((scheduleUnit = takeScheduleUnit()) != null) {
            LOG.info("Schedule unit plan: \n" + scheduleUnit.getLogicalPlan());
            if (scheduleUnit.hasUnionPlan()) {
              finishUnionUnit(scheduleUnit);
            } else {
              qm.addScheduleUnit(scheduleUnit);

              initOutputDir(scheduleUnit.getOutputName(),
                  scheduleUnit.getOutputType());
              int numTasks = getTaskNum(scheduleUnit);
              QueryUnit[] units = planner.localize(scheduleUnit, numTasks);
              inprogressQueue.put(scheduleUnit);
              scheduleUnit.setStatus(QueryStatus.QUERY_INPROGRESS);

              if (units.length == 0) {
                finishScheduleUnitForEmptyInput(scheduleUnit);
              } else {
                // insert query units to the pending queue
                scheduleQueryUnits(units, scheduleUnit.hasChildQuery());
              }
            }
          }
          LOG.info("*** Scheduled / in-progress queries: ("
              + scheduleQueue.size() + " / " + inprogressQueue.size() + ")");
        } catch (InterruptedException e) {
          LOG.error(ExceptionUtils.getStackTrace(e));
          abort();
        } catch (IOException e) {
          LOG.error(ExceptionUtils.getStackTrace(e));
          abort();
        } catch (URISyntaxException e) {
          LOG.error(ExceptionUtils.getStackTrace(e));
          abort();
        } catch (Exception e) {
          LOG.error(ExceptionUtils.getStackTrace(e));
          abort();
        }
      }
    }

    public boolean isFinished() {
      return scheduleQueue.isEmpty();
    }

    public void shutdown() {
      status = Status.FINISHED;
    }

    public void abort() {
      status = Status.ABORTED;
      for (ScheduleUnit unit : scheduleQueue) {
        unit.setStatus(QueryStatus.QUERY_ABORTED);
      }
      scheduleQueue.clear();

      for (ScheduleUnit unit : inprogressQueue) {
        unit.setStatus(QueryStatus.QUERY_ABORTED);
      }
      inprogressQueue.clear();
    }

    public Status getStatus() {
      return this.status;
    }

    private TableStat generateUnionStat(ScheduleUnit unit) {
      TableStat stat = new TableStat();
      TableStat childStat;
      long avgRows = 0, numBytes = 0, numRows = 0;
      int numBlocks = 0, numPartitions = 0;
      List<ColumnStat> columnStats = Lists.newArrayList();

      for (ScheduleUnit child : unit.getChildQueries()) {
        childStat = child.getStats();
        avgRows += childStat.getAvgRows();
        columnStats.addAll(childStat.getColumnStats());
        numBlocks += childStat.getNumBlocks();
        numBytes += childStat.getNumBytes();
        numPartitions += childStat.getNumPartitions();
        numRows += childStat.getNumRows();
      }
      stat.setColumnStats(columnStats);
      stat.setNumBlocks(numBlocks);
      stat.setNumBytes(numBytes);
      stat.setNumPartitions(numPartitions);
      stat.setNumRows(numRows);
      stat.setAvgRows(avgRows);
      return stat;
    }

    private void initScheduleQueue(ScheduleUnit scheduleUnit) {
      int priority;
      if (scheduleUnit.hasChildQuery()) {
        int maxPriority = 0;
        Iterator<ScheduleUnit> it = scheduleUnit.getChildIterator();
        while (it.hasNext()) {
          ScheduleUnit su = it.next();
          initScheduleQueue(su);
          if (su.getPriority().get() > maxPriority) {
            maxPriority = su.getPriority().get();
          }
        }
        priority = maxPriority + 1;
      } else {
        priority = 0;
      }
      scheduleUnit.setPriority(priority);
      scheduleQueue.add(scheduleUnit);
      scheduleUnit.setStatus(QueryStatus.QUERY_PENDING);
    }

    private ScheduleUnit takeScheduleUnit() {
      ScheduleUnit unit = removeFromScheduleQueue();
      if (unit == null) {
        return null;
      }
      List<ScheduleUnit> pended = Lists.newArrayList();
      Priority priority = unit.getPriority();
      do {
        if (isReady(unit)) {
          break;
        } else {
          pended.add(unit);
        }
        unit = removeFromScheduleQueue();
        if (unit == null) {
          scheduleQueue.addAll(pended);
          return null;
        }
      } while (priority.equals(unit.getPriority()));
      if (!priority.equals(unit.getPriority())) {
        pended.add(unit);
        unit = null;
      }
      scheduleQueue.addAll(pended);
      return unit;
    }

    private boolean isReady(ScheduleUnit scheduleUnit) {
      if (scheduleUnit.hasChildQuery()) {
        for (ScheduleUnit child : scheduleUnit.getChildQueries()) {
          if (child.getStatus() !=
              QueryStatus.QUERY_FINISHED) {
            return false;
          }
        }
        return true;
      } else {
        return true;
      }
    }

    private ScheduleUnit removeFromScheduleQueue() {
      if (scheduleQueue.isEmpty()) {
        return null;
      } else {
        return scheduleQueue.remove();
      }
    }

    private void initOutputDir(String outputName, PARTITION_TYPE type)
        throws IOException {
      switch (type) {
      case HASH:
        Path tablePath = sm.getTablePath(outputName);
        sm.getFileSystem().mkdirs(tablePath);
        LOG.info("Table path " + sm.getTablePath(outputName).toString()
            + " is initialized for " + outputName);
        break;
      case RANGE: // TODO - to be improved

      default:
        if (!sm.getFileSystem().exists(sm.getTablePath(outputName))) {
          sm.initTableBase(null, outputName);
          LOG.info("Table path " + sm.getTablePath(outputName).toString()
              + " is initialized for " + outputName);
        }
      }
    }

    private int getTaskNum(ScheduleUnit scheduleUnit) {
      int numTasks;
      GroupbyNode grpNode = (GroupbyNode) PlannerUtil.findTopNode(
          scheduleUnit.getLogicalPlan(), ExprType.GROUP_BY);
      if (scheduleUnit.getParentQuery() == null && grpNode != null
          && grpNode.getGroupingColumns().length == 0) {
        numTasks = 1;
      } else {
        numTasks = cm.getOnlineWorkers().size() * 2;
      }
      return numTasks;
    }

    private void finishScheduleUnitForEmptyInput(ScheduleUnit scheduleUnit)
        throws IOException {
      TableStat stat = new TableStat();
      for (int i = 0; i < scheduleUnit.getOutputSchema().getColumnNum(); i++) {
        stat.addColumnStat(new ColumnStat(scheduleUnit.getOutputSchema()
            .getColumn(i)));
      }
      scheduleUnit.setStats(stat);
      writeStat(scheduleUnit, stat);
      scheduleUnit.setStatus(QueryStatus.QUERY_FINISHED);
    }

    private void finishUnionUnit(ScheduleUnit unit) throws IOException {
      // write meta and continue
      TableStat stat = generateUnionStat(unit);
      unit.setStats(stat);
      writeStat(unit, stat);
      unit.setStatus(QueryStatus.QUERY_FINISHED);
    }

    /**
     * Insert query units to the pending queue
     */
    private void scheduleQueryUnits(QueryUnit[] units, boolean hasChild)
        throws Exception {
      if (hasChild) {
        scheduleQueryUnitByFIFO(units);
      } else {
        List<QueryUnitCluster> clusters = clusterQueryUnits(units);

        clusters = sortQueryUnitClusterBySize(clusters);

        scheduleQueryUnitClusterByRR(clusters);
      }
    }

    /**
     * Cluster query units by hosts serving their fragments
     */
    public List<QueryUnitCluster> clusterQueryUnits(QueryUnit[] queryUnits) {
      // TODO: consider the map-side join
      Map<String, QueryUnitCluster> clusterMap = Maps.newHashMap();
      for (QueryUnit unit : queryUnits) {
        String selectedScanTable = null;
        for (ScanNode scanNode : unit.getScanNodes()) {
          if (scanNode.isBroadcast()) {
            selectedScanTable = scanNode.getTableId();
            break;
          }
        }

        if (selectedScanTable == null) {
          selectedScanTable = unit.getScanNodes()[0].getTableId();
        }

        FragmentServingInfo info = cm.getServingInfo(
            unit.getFragment(selectedScanTable));
        QueryUnitCluster cluster;
        if (clusterMap.containsKey(info.getPrimaryHost())) {
          cluster = clusterMap.get(info.getPrimaryHost());
        } else {
          cluster = new QueryUnitCluster(info.getPrimaryHost());
        }
        cluster.addQueryUnit(unit);
        clusterMap.put(cluster.getHost(), cluster);
      }

      List<QueryUnitCluster> clusters = Lists.newArrayList(clusterMap.values());
      return clusters;
    }

    /**
     * Sort query unit clusters by their size
     */
    public List<QueryUnitCluster> sortQueryUnitClusterBySize(
        List<QueryUnitCluster> clusters) {
      // TODO
      QueryUnitCluster[] arr = new QueryUnitCluster[clusters.size()];
      arr = clusters.toArray(arr);
      Arrays.sort(arr);
      clusters.clear();
      for (QueryUnitCluster c : arr) {
        clusters.add(c);
      }
      return clusters;
    }

    private void scheduleQueryUnitByFIFO(QueryUnit[] units)
        throws InterruptedException {
      for (QueryUnit unit : units) {
        pendingQueue.put(unit);
        unit.setStatus(QueryStatus.QUERY_PENDING);
      }
    }

    /**
     * Insert query units to the pending queue by the RR algorithm
     */
    private void scheduleQueryUnitClusterByRR(List<QueryUnitCluster> clusters)
        throws InterruptedException {
      List<QueryUnitCluster> toBeRemoved = Lists.newArrayList();
      while (!clusters.isEmpty()) {
        for (QueryUnitCluster cluster : clusters) {
          cluster.initIteration();
          if (cluster.hasNext()) {
            QueryUnit unit = cluster.next();
            cluster.removeQueryUnit(unit);
            pendingQueue.put(unit);
            unit.setStatus(QueryStatus.QUERY_PENDING);
          } else {
            toBeRemoved.add(cluster);
          }
        }
        clusters.removeAll(toBeRemoved);
      }
    }
  }

  class QueryUnitSubmitter extends Thread {
    public final static int RETRY_LIMIT = 3;
    private List<String> onlineWorkers;
    private Set<QueryUnitAttempt> submittedQueryUnits;
    private Status status;
    private Sleeper sleeper;

    public QueryUnitSubmitter() {
      onlineWorkers = Lists.newArrayList();
      submittedQueryUnits = Sets.newHashSet();
      this.sleeper = new Sleeper();
    }

    @Override
    public void run() {
      LOG.info("Query submitter is started!");
      status = Status.INPROGRESS;
      try {
        updateWorkers();
        cm.resetResourceInfo();

        while (status == Status.INPROGRESS) {
          this.sleeper.sleep(WAIT_PERIOD);
          if (this.isFinished()) {
            this.shutdown();
          }

          // update query unit statuses
          updateSubmittedQueryUnitStatus();
          updateInprogressQueue();

          // execute query units from the pending queue
          requestPendingQueryUnits();
          LOG.info("=== Pending queries / submitted queries: ("
              + pendingQueue.size() + " / " + submittedQueryUnits.size() + ")");
        }
      } catch (InterruptedException e) {
        LOG.error(ExceptionUtils.getStackTrace(e));
        abort();
      } catch (EmptyClusterException e) {
        LOG.error(ExceptionUtils.getStackTrace(e));
        abort();
      } catch (Exception e) {
        LOG.error(ExceptionUtils.getStackTrace(e));
        abort();
      }
    }

    public boolean isFinished() {
      if (plan.getRoot().getStatus() == QueryStatus.QUERY_FINISHED) {
        return true;
      } else {
        return false;
      }
    }

    public void shutdown() {
      status = Status.FINISHED;
    }

    public void abort() {
      status = Status.ABORTED;
      for (QueryUnit unit : pendingQueue) {
        unit.setStatus(QueryStatus.QUERY_ABORTED);
      }

      // TODO: send stop commands
      for (QueryUnitAttempt attempt : submittedQueryUnits) {
        try {
          sendCommand(attempt, CommandType.STOP);
        } catch (Exception e) {
          LOG.error(ExceptionUtils.getFullStackTrace(e));
        }
        attempt.setStatus(QueryStatus.QUERY_ABORTED);
      }
    }

    public Status getStatus() {
      return status;
    }

    @VisibleForTesting
    public void submitQueryUnit(QueryUnitAttempt attempt) {
      this.submittedQueryUnits.add(attempt);
    }

    @VisibleForTesting
    public int getSubmittedNum() {
      return this.submittedQueryUnits.size();
    }

    private void updateWorkers() throws EmptyClusterException,
        UnknownWorkerException, ExecutionException, InterruptedException {
      cm.updateOnlineWorker();
      onlineWorkers.clear();
      for (List<String> workers : cm.getOnlineWorkers().values()) {
        onlineWorkers.addAll(workers);
      }
      onlineWorkers.removeAll(cm.getFailedWorkers());
      if (onlineWorkers.size() == 0) {
        throw new EmptyClusterException();
      }
    }

    private void updateInprogressQueue() throws Exception {
      List<ScheduleUnit> finished = Lists.newArrayList();
      for (ScheduleUnit scheduleUnit : inprogressQueue) {
        int inited = 0;
        int pending = 0;
        int inprogress = 0;
        int aborted = 0;
        int killed = 0;
        int finish = 0;
        int submitted = 0;
        for (QueryUnit queryUnit : scheduleUnit.getQueryUnits()) {
          QueryStatus status = queryUnit.getStatus();

          switch (status) {
            case QUERY_SUBMITED: submitted++; break;
            case QUERY_INITED: inited++; break;
            case QUERY_PENDING: pending++; break;
            case QUERY_INPROGRESS: inprogress++; break;
            case QUERY_FINISHED: finish++; break;
            case QUERY_KILLED: killed++; break;
            case QUERY_ABORTED:
              aborted++;
              if (queryUnit.getRetryCount() <
                  QueryUnitSubmitter.RETRY_LIMIT) {
                // wait
              } else {
                LOG.info("The query " + scheduleUnit.getId() +
                    " will be aborted, because the query unit " +
                    queryUnit.getId() + " is stopped with " + status);
                this.abort();
              }
              break;
            default:
              break;
          }
        }
        LOG.info("\n--- Status of " + scheduleUnit.getId() + " ---\n" + ""
            + " In Progress (Submitted: " + submitted
            + ", Finished: " + finish + ", Inited: " + inited + ", Pending: "
            + pending + ", Running: " + inprogress + ", Aborted: " + aborted
            + ", Killed: " + killed);

        if (finish == scheduleUnit.getQueryUnits().length) {
          TableStat stat = generateStat(scheduleUnit);
          writeStat(scheduleUnit, stat);
          scheduleUnit.setStats(stat);
          scheduleUnit.setStatus(QueryStatus.QUERY_FINISHED);
          if (scheduleUnit.hasChildQuery()) {
            finalizePrevScheduleUnit(scheduleUnit);
          }
          if (scheduleUnit.equals(plan.getRoot())) {
            for (QueryUnit unit : scheduleUnit.getQueryUnits()) {
              sendCommand(unit.getLastAttempt(), CommandType.FINALIZE);
            }
          }
          finished.add(scheduleUnit);
        }
      }
      inprogressQueue.removeAll(finished);
    }

    private TableStat generateStat(ScheduleUnit scheduleUnit) {
      List<TableStat> stats = Lists.newArrayList();
      for (QueryUnit unit : scheduleUnit.getQueryUnits()) {
        stats.add(unit.getStats());
      }
      TableStat tableStat = StatisticsUtil.aggregate(stats);
      return tableStat;
    }

    private void finalizePrevScheduleUnit(ScheduleUnit scheduleUnit)
        throws Exception {
      ScheduleUnit prevScheduleUnit;
      for (ScanNode scan : scheduleUnit.getScanNodes()) {
        prevScheduleUnit = scheduleUnit.getChildQuery(scan);
        if (prevScheduleUnit.getStoreTableNode().getSubNode().getType() != ExprType.UNION) {
          for (QueryUnit unit : prevScheduleUnit.getQueryUnits()) {
            sendCommand(unit.getLastAttempt(), CommandType.FINALIZE);
          }
        }
      }
    }

    @VisibleForTesting
    public int updateSubmittedQueryUnitStatus() throws Exception {
      // TODO
      ClusterManager.WorkerResource wr;
      boolean retryRequired;
      boolean wait;
      QueryStatus status;
      int submitted = 0;
      int inited = 0;
      int pending = 0;
      int inprogress = 0;
      int success = 0;
      int aborted = 0;
      int killed = 0;
      List<QueryUnitAttempt> toBeRemoved = Lists.newArrayList();
      for (QueryUnitAttempt attempt : submittedQueryUnits) {
        retryRequired = false;
        wait = false;
        status = attempt.getStatus();

        switch (status) {
          case QUERY_SUBMITED:
            wait = true;
            submitted++;
            break;
          case QUERY_INITED:
            inited++;
            wait = true;
            break;
          case QUERY_PENDING:
            pending++;
            wait = true;
            break;
          case QUERY_INPROGRESS:
            inprogress++;
            wait = true;
            break;
          case QUERY_FINISHED:
            toBeRemoved.add(attempt);
            attempt.getQueryUnit().setStatus(QueryStatus.QUERY_FINISHED);
            success++;
            wr = cm.getResource(attempt.getHost());
            wr.returnResource();
            cm.updateResourcePool(wr);
            break;
          case QUERY_ABORTED:
            toBeRemoved.add(attempt);
            retryRequired = true;
            aborted++;
            break;
          case QUERY_KILLED:
            toBeRemoved.add(attempt);
            sendCommand(attempt, CommandType.STOP);
            killed++;
            wr = cm.getResource(attempt.getHost());
            wr.returnResource();
            cm.updateResourcePool(wr);
            break;
          default:
            break;
        }

        if (wait) {
          attempt.updateExpireTime(WAIT_PERIOD);
          if (attempt.getLeftTime() <= 0) {
            LOG.info("QueryUnit " + attempt.getId() + " is expired!!");
            attempt.setStatus(QueryStatus.QUERY_ABORTED);
            toBeRemoved.add(attempt);
            retryRequired = true;
          }
        }

        if (retryRequired) {
          if (!retryQueryUnit(attempt)) {
            LOG.error("failed query unit: " + attempt.getQueryUnit());
            LOG.error("The query " + attempt.getId() + " is aborted with "
                + status + " after " + RETRY_LIMIT + " retries.");
            abort();
            break;
          }
        }
      }

      LOG.info("\n--- Status of all submitted query units ---\n" + ""
          + " In Progress (Total: " + submittedQueryUnits.size()
          + ", Finished: " + success + ", Submitted: " + submitted + ", Inited: "
          + inited + ", Pending: " + pending + ", Running: " + inprogress
          + ", Aborted: " + aborted + ", Killed: " + killed);
      submittedQueryUnits.removeAll(toBeRemoved);
      return success;
    }

    private void requestPendingQueryUnits() throws Exception {
      while (cm.remainFreeResource() && !pendingQueue.isEmpty()) {
        QueryUnit q = pendingQueue.take();
        q.setStatus(QueryStatus.QUERY_INPROGRESS);

        List<Fragment> fragList = new ArrayList<Fragment>();
        for (ScanNode scan : q.getScanNodes()) {
          fragList.add(q.getFragment(scan.getTableId()));
        }
        QueryUnitAttempt attempt = q.newAttempt();
        attempt.setHost(selectWorker(q, fragList));
        qm.updateQueryAssignInfo(attempt.getHost(), q);
        QueryUnitRequest request = createQueryUnitRequest(attempt, fragList);

        printQueryUnitRequestInfo(attempt, request);
        if (!requestToWC(attempt.getHost(), request.getProto())) {
          if (!retryQueryUnit(attempt)) {
            LOG.info("The query " + attempt.getId() + " is aborted with + "
                + status + " after " + RETRY_LIMIT + " retries.");
            abort();
          }
        }
        submittedQueryUnits.add(attempt);
        attempt.setStatus(QueryStatus.QUERY_SUBMITED);
      }
    }

    private String selectWorker(QueryUnit q, List<Fragment> fragList) {
      if (cm.remainFreeResource()) {
        FragmentServingInfo info;
        ClusterManager.WorkerResource wr;
        if (fragList.size() == 1) {
          info = cm.getServingInfo(fragList.get(0));
        } else {
          // TODO: to be improved
          info = cm.getServingInfo(fragList.get(0));
        }
        if (info == null) {
          String host = cm.getNextFreeHost();
          wr = cm.getResource(host);
          wr.getResource();
          cm.updateResourcePool(wr);
          return host;
        }
        if (cm.getOnlineWorkers().containsKey(info.getPrimaryHost())) {
          List<String> workers = cm.getOnlineWorkers().get(info.getPrimaryHost());
          for (String worker : workers) {
            wr = cm.getResource(worker);
            if (wr.hasFreeResource()) {
              wr.getResource();
              cm.updateResourcePool(wr);
              return worker;
            }
          }
        }
        String backup;
        while ((backup=info.getNextBackupHost()) != null) {
          if (cm.getOnlineWorkers().containsKey(backup)) {
            List<String>workers = cm.getOnlineWorkers().get(backup);
            for (String worker : workers) {
              wr = cm.getResource(worker);
              if (wr.hasFreeResource()) {
                wr.getResource();
                cm.updateResourcePool(wr);
                return worker;
              }
            }
          }
        }
        backup = cm.getNextFreeHost();
        wr = cm.getResource(backup);
        wr.getResource();
        cm.updateResourcePool(wr);
        return backup;
      } else {
        return null;
      }
    }

    private QueryUnitRequest createQueryUnitRequest(QueryUnitAttempt q,
                                                    List<Fragment> fragList) {
      QueryUnitRequest request = new QueryUnitRequestImpl(q.getId(), fragList,
          q.getQueryUnit().getOutputName(), false,
          q.getQueryUnit().getLogicalPlan().toJSON());

      if (q.getQueryUnit().getStoreTableNode().isLocal()) {
        request.setInterQuery();
      }

      for (ScanNode scan : q.getQueryUnit().getScanNodes()) {
        Collection<URI> fetches = q.getQueryUnit().getFetch(scan);
        if (fetches != null) {
          for (URI fetch : fetches) {
            request.addFetch(scan.getTableId(), fetch);
          }
        }
      }
      return request;
    }

    private void printQueryUnitRequestInfo(QueryUnitAttempt q,
                                           QueryUnitRequest request) {
      LOG.info("QueryUnitRequest " + request.getId() + " is sent to "
          + (q.getHost()));
    }

    private boolean retryQueryUnit(QueryUnitAttempt attempt) throws Exception {
      QueryUnit unit = attempt.getQueryUnit();
      int retryCnt = unit.getRetryCount();

      if (retryCnt < RETRY_LIMIT) {
        attempt.setStatus(QueryStatus.QUERY_ABORTED);
        commitBackupTask(attempt);
        return true;
      } else {
        // Cancel the executed query
        return false;
      }
    }

    private void commitBackupTask(QueryUnitAttempt unit) throws Exception {
      LOG.info("Commit backup task: " + unit.getQueryUnit().getId());
      sendCommand(unit, CommandType.STOP);
      cm.addFailedWorker(unit.getHost());
      requestBackupTask(unit.getQueryUnit());
      unit.resetExpireTime();
    }

    private void requestBackupTask(QueryUnit q) throws Exception {
      FileSystem fs = sm.getFileSystem();
      Path path = new Path(sm.getTablePath(q.getOutputName()), q.getId()
          .toString());
      fs.delete(path, true);
      updateWorkers();
      pendingQueue.add(q);
    }
  }

  class PriorityComparator implements Comparator<ScheduleUnit> {
    public PriorityComparator() {

    }

    @Override
    public int compare(ScheduleUnit s1, ScheduleUnit s2) {
      return s1.getPriority().get() - s2.getPriority().get();
    }
  }
}
