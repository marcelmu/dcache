package org.dcache.chimera.namespace;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import diskCacheV111.util.CacheException;
import diskCacheV111.util.PnfsId;
import diskCacheV111.vehicles.PnfsDeleteEntryNotificationMessage;
import diskCacheV111.vehicles.PoolManagerPoolUpMessage;
import diskCacheV111.vehicles.PoolRemoveFilesMessage;

import dmg.cells.nucleus.CellPath;
import dmg.cells.nucleus.NoRouteToCellException;

import org.dcache.cells.AbstractCell;
import org.dcache.cells.CellStub;
import org.dcache.cells.Option;
import org.dcache.db.AlarmEnabledDataSource;
import org.dcache.services.hsmcleaner.PoolInformationBase;
import org.dcache.services.hsmcleaner.RequestTracker;
import org.dcache.util.Args;
import org.dcache.util.BroadcastRegistrationTask;
import org.dcache.util.CacheExceptionFactory;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Irina Kozlova
 * @version 22 Oct 2007
 *
 * ChimeraCleaner: takes file names from the table public.t_locationinfo_trash,
 * removes them from the corresponding pools and then from the table as well.
 * @since 1.8
 */

public class ChimeraCleaner extends AbstractCell implements Runnable
{
    private static final Class<?> POOLUP_MESSAGE =
        PoolManagerPoolUpMessage.class;

    private static final Logger _log =
        LoggerFactory.getLogger(ChimeraCleaner.class);

    private static final long BROADCAST_REGISTRATION_PERIOD =
            TimeUnit.MINUTES.toMillis(5);
    private static final long BROADCAST_REGISTRATION_EXPIRATION =
            TimeUnit.MINUTES.toMillis(6);

    @Option(
        name="refresh",
        description="Refresh interval",
        required=true
    )
    protected long _refreshInterval;

    @Option(
            name="refreshUnit",
            description="Refresh interval unit",
            required=true
    )
    protected TimeUnit _refreshIntervalUnit;

    @Option(
        name="recover",
        description="",
        required=true
    )
    protected long _recoverTimer;

    @Option(
        name="recoverUnit",
        description="",
        required=true
    )
    protected TimeUnit _recoverTimerUnit;

    @Option(
        name="poolTimeout",
        description="",
        required=true
    )
    protected long _replyTimeout;

    @Option(
        name="poolTimeoutUnit",
        description="",
        required=true
    )
    protected TimeUnit _replyTimeoutUnit;

    @Option(
        name="processFilesPerRun",
        description="The number of files to process at once",
        required=true,
        unit="files"
    )
    protected int _processAtOnce;

    @Option(
        name="reportRemove",
        description="The cells to report removes to",
        required=true
    )
    protected String _reportTo;

    @Option(
        name="hsmCleaner",
        description="Whether to enable the HSM cleaner",
        required=true
    )
    protected boolean _hsmCleanerEnabled;

    @Option(
        name="hsmCleanerRequest",
        description="Maximum number of files to include in a single request",
        required=true,
        unit="files"
    )
    protected int _hsmCleanerRequest;

    @Option(
        name="hsmCleanerTimeout",
        description="Timeout in milliseconds for delete requests send to HSM-pools",
        required=true
    )
    protected long _hsmTimeout;

    @Option(
        name="hsmCleanerTimeoutUnit",
        description="Timeout in milliseconds for delete requests send to HSM-pools",
        required=true
    )
    protected TimeUnit _hsmTimeoutUnit;

    @Option(
        name="threads",
        description="Size of thread pool",
        required=true
    )
    protected int _threadPoolSize;

    @Option(
            name="broadcast",
            description="Cell address of broadcast service",
            required=true
    )
    protected String _broadcastAddress;

    private CellPath[] _deleteNotificationTargets;

    private final ConcurrentHashMap<String, Long> _poolsBlackList =
        new ConcurrentHashMap<>();

    private RequestTracker _requests;
    private ScheduledExecutorService _executor;
    private ScheduledFuture<?> _cleanerTask;
    private PoolInformationBase _pools = new PoolInformationBase();
    private AlarmEnabledDataSource _dataSource;
    private JdbcTemplate _db;
    private BroadcastRegistrationTask _broadcastRegistration;
    private CellStub _broadcasterStub;
    private CellStub _notificationStub;
    private CellStub _poolStub;

    public ChimeraCleaner(String cellName, String args)
        throws InterruptedException, ExecutionException
    {
        super(cellName, args);
        doInit();
    }

    @Override
    protected void init()
        throws Exception
    {
        useInterpreter(true);

        _notificationStub = new CellStub(this);
        _deleteNotificationTargets =
                Stream.of(_reportTo.split(",")).map(CellPath::new).toArray(CellPath[]::new);

        _executor = Executors.newScheduledThreadPool(_threadPoolSize);

        dbInit(getArgs().getOpt("chimera.db.url"),
                getArgs().getOpt("chimera.db.user"), getArgs().getOpt("chimera.db.password"));

        if (_hsmCleanerEnabled) {
            _requests = new RequestTracker();
            _requests.setMaxFilesPerRequest(_hsmCleanerRequest);
            _requests.setTimeout(_hsmTimeoutUnit.toMillis(_hsmTimeout));
            _requests.setPoolStub(new CellStub(this));
                _requests.setPoolInformationBase(_pools);
            _requests.setSuccessSink(uri -> _executor.execute(() -> onSuccess(uri)));
            _requests.setFailureSink(uri -> _executor.execute(() -> onFailure(uri)));
            addMessageListener(_requests);
            addCommandListener(_requests);
        }

        addMessageListener(_pools);
        addCommandListener(_pools);

        _poolStub = new CellStub(this, null, _replyTimeout, _replyTimeoutUnit);

        _broadcasterStub = new CellStub(this, new CellPath(_broadcastAddress));

        _broadcastRegistration = new BroadcastRegistrationTask();
        _broadcastRegistration.setTarget(new CellPath(getCellName(), getCellDomainName()));
        _broadcastRegistration.setBroadcastStub(_broadcasterStub);
        _broadcastRegistration.setEventClass(POOLUP_MESSAGE);
        _broadcastRegistration.setExpires(BROADCAST_REGISTRATION_EXPIRATION);
        _executor.scheduleAtFixedRate(
                _broadcastRegistration, 0, BROADCAST_REGISTRATION_PERIOD, TimeUnit.MILLISECONDS);

        _cleanerTask =
            _executor.scheduleWithFixedDelay(this,
                                             _refreshInterval,
                                             _refreshInterval,
                                             _refreshIntervalUnit);
    }

    void dbInit(String jdbcUrl, String user, String pass )
        throws ClassNotFoundException
    {
        if (jdbcUrl == null || user == null || pass == null) {
            throw new IllegalArgumentException("Not enough arguments to Init SQL database");
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(pass);
        config.setMinimumIdle(1);
        config.setMaximumPoolSize(10);

        _dataSource = new AlarmEnabledDataSource(jdbcUrl,
                                                 ChimeraCleaner.class.getSimpleName(),
                                                 new HikariDataSource(config));
        _db = new JdbcTemplate(_dataSource);

        _log.info("Database connection with jdbcUrl={}; user={}",
                  jdbcUrl, user);
    }

    @Override
    public void cleanUp()
    {
        if (_requests != null) {
            _requests.shutdown();
        }
        if (_executor != null) {
            _executor.shutdownNow();
        }
        if (_broadcastRegistration != null) {
            _broadcastRegistration.unregister();
        }
        if (_dataSource != null) {
            try {
                _dataSource.close();
            } catch (IOException e) {
                _log.debug("Failed to shutdown database connection pool: {}", e.getMessage());
            }
        }
    }

    @Override
    public void run() {

        try {
            _log.info("*********NEW_RUN*************");

            if (_log.isDebugEnabled()){
                _log.debug("INFO: Refresh Interval : " + _refreshInterval + " " + _refreshIntervalUnit);
                _log.debug("INFO: Number of files processed at once: " + _processAtOnce);
            }

            // get list of pool names from the trash_table
            List<String> poolList = getPoolList();

            if (_log.isDebugEnabled()){
                _log.debug("List of Pools from the trash-table : "+ poolList);
            }

            // if there are some pools in the blackPoolList (i.e.,
            //pools that are down/do not exist), extract them from the
            //poolList
            if (!_poolsBlackList.isEmpty()) {
                _log.debug("htBlackPools.size()="+ _poolsBlackList.size());

                for (Map.Entry<String, Long> blackListEntry : _poolsBlackList.entrySet()) {
                    String poolName = blackListEntry.getKey();
                    long valueTime = blackListEntry.getValue();

                    //check, if it is time to remove pool from the black list
                    if ((valueTime != 0)
                        && (_recoverTimer > 0)
                        && ((System.currentTimeMillis() - valueTime) > _recoverTimerUnit.toMillis(_recoverTimer))) {
                        _poolsBlackList.remove(poolName);
                        if (_log.isDebugEnabled()) {
                            _log.debug("Remove the following pool from the Black List : "+ poolName);
                        }
                    }
                }

                poolList.removeAll(_poolsBlackList.keySet());
            }

            if (!poolList.isEmpty()) {
                _log.debug("The following pools are sent to runDelete(..): {}",
                           poolList);
                runDelete(poolList);
            }

            //HSM part
            if (_hsmCleanerEnabled){
                runDeleteHSM();
            }

            // Notify other components that we are done deleting
            runNotification();
        } catch (DataAccessException e) {
            _log.error("Database failure: " + e.getMessage());
        } catch (InterruptedException e) {
            _log.info("Cleaner was interrupted");
        } catch (RuntimeException e) {
            _log.error("Bug detected" , e);
        }
    }

    /**
     * Returns a list of dinstinctpool names from the trash-table.
     *
     * @return list of pool names
     */
    List<String> getPoolList()
    {
        return _db.query("SELECT DISTINCT ilocation FROM t_locationinfo_trash WHERE itype=1",
                         (rs, rowNum) -> rs.getString("ilocation"));
    }

    /**
     * Delete entries from the trash-table.
     * Pool name and the file names are input parameters.
     *
     * @param poolname name of the pool
     * @param filelist file list for this pool
     *
     */
    void removeFiles(final String poolname, final List<String> filelist)
    {
        _db.batchUpdate("DELETE FROM t_locationinfo_trash WHERE ilocation=? AND ipnfsid=? AND itype=1",
                        new BatchPreparedStatementSetter()
                        {
                            @Override
                            public int getBatchSize()
                            {
                                return filelist.size();
                            }

                            @Override
                            public void setValues(PreparedStatement ps, int i)
                                    throws SQLException
                            {
                                ps.setString(1, poolname);
                                ps.setString(2, filelist.get(i));
                            }
                        });
    }

    /**
     * runDelete
     * Delete files on each pool from the poolList.
     *
     * @param poolList list of pools
     * @throws InterruptedException
     */
    private void runDelete(List<String> poolList)
        throws InterruptedException
    {
        for (String pool: poolList) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Cleaner interrupted");
            }

            _log.info("runDelete(): Now processing pool {}", pool);
            if (!_poolsBlackList.containsKey(pool)) {
                cleanPoolComplete(pool);
            }
        }
    }

    /**
     * sendRemoveToPoolCleaner
     * removes set of files from the pool
     *
     * @param poolName name of the pool
     * @param removeList list of files to be removed from this pool
     * @throws InterruptedException
     */

    private void sendRemoveToPoolCleaner(String poolName, List<String> removeList)
            throws InterruptedException
    {
        _log.debug("sendRemoveToPoolCleaner: poolName={}", poolName);
        _log.debug("sendRemoveToPoolCleaner: removeList={}", removeList);

        try {
            PoolRemoveFilesMessage msg =
                    CellStub.get(_poolStub.send(new CellPath(poolName),
                                                new PoolRemoveFilesMessage(poolName, removeList)));
            if (msg.getReturnCode() == 0) {
                removeFiles(poolName, removeList);
            } else if (msg.getReturnCode() == 1 && msg.getErrorObject() instanceof String[]) {
                Set<String> notRemoved =
                        new HashSet<>(Arrays.asList((String[]) msg.getErrorObject()));
                List<String> removed = new ArrayList<>(removeList);
                removed.removeAll(notRemoved);
                removeFiles(poolName, removed);
            } else {
                throw CacheExceptionFactory.exceptionOf(msg);
            }
        } catch (CacheException e) {
            _log.warn("Failed to remove files from {}: {}", poolName, e.getMessage());
            _poolsBlackList.put(poolName, System.currentTimeMillis());
        }
    }

    public void messageArrived(NoRouteToCellException e)
    {
        _log.warn("Failed to send message: {}", e.getMessage());
    }

    public void messageArrived(PoolManagerPoolUpMessage poolUpMessage) {

        String poolName = poolUpMessage.getPoolName();

        /*
         * Keep track of pools statuses:
         *     remove pool from the black list in case of new status is enabled.
         *     put a pool into black list if new status is disabled.
         */

        if ( poolUpMessage.getPoolMode().isEnabled() ) {
            _poolsBlackList.remove(poolName);
        }

        if ( poolUpMessage.getPoolMode().isDisabled() ) {
            _poolsBlackList.putIfAbsent(poolName, System.currentTimeMillis());
        }
    }

    private void runNotification()
            throws InterruptedException
    {
        final String QUERY =
                "SELECT ipnfsid FROM t_locationinfo_trash t1 " +
                "WHERE itype=2 AND NOT EXISTS (SELECT 1 FROM t_locationinfo_trash t2 WHERE t2.ipnfsid=t1.ipnfsid AND t2.itype <> 2)";
        for (String id : _db.queryForList(QUERY, String.class)) {
            try {
                sendDeleteNotifications(id);
            } catch (CacheException e) {
                _log.warn(e.getMessage());
            }
        }
    }

    private void sendDeleteNotifications(String id) throws InterruptedException, CacheException
    {
        PnfsId pnfsId = new PnfsId(id);
        for (CellPath address : _deleteNotificationTargets) {
            try {
                _notificationStub.sendAndWait(address, new PnfsDeleteEntryNotificationMessage(pnfsId));
            } catch (CacheException e) {
                throw new CacheException("Failed to notify " + address + " about deletion of " + id + ": " + e.getMessage(), e);
            }
        }
        _db.update("DELETE FROM t_locationinfo_trash WHERE ipnfsid=? AND itype=2", id);
    }

    /**
     * cleanPoolComplete
     * delete all files from the pool 'poolName' found in the trash-table for this pool
     *
     * @param poolName name of the pool
     */
    void cleanPoolComplete(final String poolName)
    {
        _log.trace("CleanPoolComplete(): poolname={}", poolName);

        List<String> files = new ArrayList<>(_processAtOnce);
        _db.query("SELECT ipnfsid FROM t_locationinfo_trash WHERE ilocation=? AND itype=1 ORDER BY iatime",
                  rs -> {
                      try {
                          files.add(rs.getString("ipnfsid"));
                          if (files.size() >= _processAtOnce || rs.isLast()) {
                              sendRemoveToPoolCleaner(poolName, files);
                              files.clear();
                          }
                      } catch (InterruptedException e) {
                          throw new TransientDataAccessResourceException("Cleaner was interrupted", e);
                      }
                  },
                  poolName);
    }

    /**
     * Delete files stored on tape (HSM).
     */
    private void runDeleteHSM()
    {
        _db.query("SELECT ilocation FROM t_locationinfo_trash WHERE itype=0",
                  rs -> {
                      try {
                          URI uri = new URI(rs.getString("ilocation"));
                          _log.debug("Submitting a request to delete a file: {}", uri);
                          _requests.submit(uri);
                      } catch (URISyntaxException e) {
                          throw new DataIntegrityViolationException("Invalid URI in database: " + e.getMessage(), e);
                      }
                  });
    }

    ////////////////////////////////////////////////////////////////////////////
    public static final String hh_rundelete = " # run Cleaner ";
    public String ac_rundelete(Args args)
        throws InterruptedException
    {
        runDelete(getPoolList());
        return "";
    }

    public static final String hh_show_info = " # show info ";
    public String ac_show_info(Args args)
    {

        StringBuilder sb = new StringBuilder();

            sb.append("Refresh Interval: ").append(_refreshInterval).append(" ").append(_refreshIntervalUnit).append("\n");
            sb.append("Reply Timeout: ").append(_replyTimeout).append(" ").append(_replyTimeoutUnit).append("\n");
            sb.append("Recover Timer: ").append(_recoverTimer).append(" ").append(_recoverTimerUnit).append("\n");
            sb.append("Number of files processed at once: ").append(_processAtOnce);
            if ( _hsmCleanerEnabled ) {
                sb.append("\n HSM Cleaner enabled. Info : \n");
                sb.append("Timeout for cleaning requests to HSM-pools: ").append(_hsmTimeout).append(" ").append(_hsmTimeoutUnit).append("\n");
                sb.append("Maximal number of concurrent requests to a single HSM : ").append(_hsmCleanerRequest);
            } else {
               sb.append("\n HSM Cleaner disabled.");
            }
        return sb.toString();
    }


    public static final String hh_ls_blacklist = " # list pools in the Black List";
    public String ac_ls_blacklist(Args args)
    {
        StringBuilder sb = new StringBuilder();
        for (String pool : _poolsBlackList.keySet()) {
            sb.append(pool).append("\n");
        }
        return sb.toString();
    }

    public static final String hh_remove_from_blacklist = "<poolName> # remove this pool from the Black List";
    public String ac_remove_from_blacklist_$_1(Args args)
    {
        String poolName = args.argv(0);
        if (_poolsBlackList.remove(poolName) != null) {
            return "Pool " + poolName + " is removed from the Black List ";
        }
        return "Pool " + poolName + " was not found in the Black List ";
    }

    public static final String hh_clean_file =
        "<pnfsID> # clean this file (file will be deleted from DISK)";
    public String ac_clean_file_$_1(Args args)
    {
        String pnfsid = args.argv(0);
        List<String> removeFile = Collections.singletonList(pnfsid);
        _db.query("SELECT ilocation FROM t_locationinfo_trash WHERE ipnfsid=? AND itype=1 ORDER BY iatime",
                  rs -> {
                      try {
                          String pool = rs.getString("ilocation");
                          sendRemoveToPoolCleaner(pool, removeFile);
                      } catch (InterruptedException e) {
                          throw new TransientDataAccessResourceException("Cleaner was interrupted", e);
                      }
                  },
                  pnfsid);
        return "";
    }

    public static final String hh_clean_pool = "<poolName> # clean this pool ";
    public String ac_clean_pool_$_1(Args args)
    {
        String poolName = args.argv(0);
        if (_poolsBlackList.containsKey(poolName)) {
            return "This pool is not available for the moment and therefore will not be cleaned.";
        }
        cleanPoolComplete(poolName);
        return "";
    }

    public static final String hh_set_refresh = "[<refreshTimeInSeconds>]";
    public static final String fh_set_refresh =
        "Alters refresh rate and triggers a new run. Maximum rate is every 5 seconds.";
    public String ac_set_refresh_$_0_1(Args args)
    {
        if (args.argc() > 0) {
            long newRefresh = Long.parseLong(args.argv(0));
            if (newRefresh < 5) {
                throw new IllegalArgumentException("Time must be greater than 5 seconds");
            }

            _refreshInterval = newRefresh;
            _refreshIntervalUnit = SECONDS;

            if (_cleanerTask != null) {
                _cleanerTask.cancel(true);
            }
            _cleanerTask =
                _executor.scheduleWithFixedDelay(this,
                                                 0,
                                                 _refreshInterval,
                                                 _refreshIntervalUnit);
        }
        return "Refresh set to " + _refreshInterval + " " + _refreshIntervalUnit;
    }

    public static final String hh_set_processedAtOnce = "<processedAtOnce> # max number of files sent to pool for processing at once ";
    public String ac_set_processedAtOnce_$_1(Args args)
    {
        if (args.argc() > 0) {
            int processAtOnce = Integer.valueOf(args.argv(0));
            if (processAtOnce <= 0) {
                throw new IllegalArgumentException("Number of files must be greater than 0 ");
            }
            _processAtOnce = processAtOnce;
        }

        return "Number of files processed at once set to " + _processAtOnce;
    }

    /////  HSM admin commands /////

    public static final String hh_rundelete_hsm = " # run HSM Cleaner";
    public String ac_rundelete_hsm(Args args)
    {
        if (!_hsmCleanerEnabled) {
            return "HSM Cleaner is disabled.";
        }

        runDeleteHSM();
        return "";
    }

    //explicitly clean HSM-file
    public static final String hh_clean_file_hsm =
        "<pnfsID> # clean this file on HSM (file will be deleted from HSM)";
    public String ac_clean_file_hsm_$_1(Args args)
    {
        if (!_hsmCleanerEnabled) {
            return "HSM Cleaner is disabled.";
        }

        _db.query("SELECT ilocation FROM t_locationinfo_trash WHERE ipnfsid=? AND itype=0 ORDER BY iatime",
                  rs -> {
                      try {
                          _requests.submit(new URI(rs.getString("ilocation")));
                      } catch (URISyntaxException e) {
                          throw new DataIntegrityViolationException("Invalid URI in database: " + e.getMessage(), e);
                      }
                  },
                  args.argv(0));

        return "";
    }

    public static final String hh_hsm_set_MaxFilesPerRequest = "<number> # maximal number of concurrent requests to a single HSM";

    public String ac_hsm_set_MaxFilesPerRequest_$_1(Args args) throws NumberFormatException
    {
        if (!_hsmCleanerEnabled) {
            return "HSM Cleaner is disabled.";
        }
        if (args.argc() > 0) {
            int maxFilesPerRequest = Integer.valueOf(args.argv(0));
            if (maxFilesPerRequest == 0) {
                throw new
                        IllegalArgumentException("The number must be greater than 0 ");
            }
            _hsmCleanerRequest = maxFilesPerRequest;
        }

        return "Maximal number of concurrent requests to a single HSM is set to " + _hsmCleanerRequest;
    }

    public static final String hh_hsm_set_TimeOut = "<seconds> # cleaning request timeout in seconds (for HSM-pools)";

    public String ac_hsm_set_TimeOut_$_1(Args args) throws NumberFormatException
    {
        if (!_hsmCleanerEnabled) {
            return "HSM Cleaner is disabled.";
        }
        if (args.argc() > 0) {
            long timeOutHSM = Long.valueOf(args.argv(0));

            _hsmTimeout = timeOutHSM;
            _hsmTimeoutUnit = SECONDS;
        }
        return "Timeout for cleaning requests to HSM-pools is set to " + _hsmTimeout + " " + _hsmTimeoutUnit;
    }

    /**
     * Called when a file was successfully deleted from the HSM.
     */
    protected void onSuccess(URI uri)
    {
        try {
            _log.debug("HSM-ChimeraCleaner: remove entries from the trash-table. ilocation={}", uri);
            _db.update("DELETE FROM t_locationinfo_trash WHERE ilocation=? AND itype=0", uri.toString());
        } catch (DataAccessException e) {
            _log.error("Error when deleting from the trash-table: " + e.getMessage());
        }
    }

    /**
     * Called when a file could not be deleted from the HSM.
     */
    protected void onFailure(URI uri)
    {
        _log.info("Failed to delete a file {} from HSM. Will try again later.", uri);
    }
}
