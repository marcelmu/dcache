package org.dcache.services.pinmanager1;

import diskCacheV111.util.PnfsId;
import diskCacheV111.vehicles.PnfsFlagMessage;
import diskCacheV111.vehicles.PnfsGetCacheLocationsMessage;
import diskCacheV111.vehicles.PoolSetStickyMessage;
import org.dcache.vehicles.PnfsGetFileAttributes;
import org.dcache.namespace.FileAttribute;
import org.dcache.cells.CellStub;

import dmg.cells.nucleus.CellPath;
import dmg.cells.nucleus.NoRouteToCellException;

import java.util.List;
import java.util.ArrayList;
import java.util.EnumSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background task used to perform the actual unpinning operation.
 *
 * It is spawned by a Pin instance, and will do a callback to that pin
 * when done.
 */
class Unpinner extends SMCTask
{
    private static final Logger _logger =
        LoggerFactory.getLogger(Unpinner.class);

    protected final PinManager _manager;
    protected final PinManagerJob _job;
    protected final Pin _pin;
    protected final UnpinnerContext _fsm;
    protected final CellStub _pnfsManager;
    protected final CellStub _pool;
    protected final List<String> locations = new ArrayList<String>();
    protected final boolean isOldStylePin;
    protected final boolean _retry;

    public Unpinner(PinManager manager,
                    PinManagerJob job,
                    Pin pin,
                    boolean retry,
                    CellStub pnfsManager,
                    CellStub pool)
    {
        super(manager.getCellEndpoint());
        _manager = manager;
        _retry = retry;
        _job = job;
        _pin = pin;
        _pnfsManager = pnfsManager;
        _pool = pool;
        String poolName = _pin.getPool();
        isOldStylePin = (poolName == null || poolName.equals("unknown"));
        if(!isOldStylePin) {
            locations.add(poolName);
        }

        _fsm = new UnpinnerContext(this);
        setContext(_fsm);
        synchronized (this) {
            _fsm.go();
        }
        job.setSMCTask(this);
        _logger.info("Unpinner constructor done, isOldStylePin="+isOldStylePin);
    }

    boolean isOldStylePin() {
        return isOldStylePin;
    }

   boolean isRetry() {
        return _retry;
    }

    public String toString()
    {
        return _fsm.getState().toString();
    }

    void fail(Object reason)
    {
        _logger.error(" failed: "+reason);
        //_pin.unpinFailed(reason);
        try {
            _manager.unpinFailed(_pin);
        } catch (PinException pe) {
            _logger.error(pe.toString());
        }
    }

    void succeed()
    {
        _logger.info("succeeded");
        try {
            _manager.unpinSucceeded(_pin);
        } catch (PinException pe) {
            _logger.error(pe.toString());
        }

        //_pin.unpinSucceeded();
    }

    void fileRemoved()
    {
        _logger.info("fileRemoved, make unpin succeed");
        try {
            _manager.unpinSucceeded(_pin);
        } catch (PinException pe) {
            _logger.error(pe.toString());
        }

        //_pin.unpinSucceeded();
    }

    void deletePnfsFlags()
    {
        _logger.info("deletePnfsFlags");
        PnfsFlagMessage pfm = new PnfsFlagMessage(
                _job.getPnfsId(), "s", PnfsFlagMessage.FlagOperation.REMOVE);
        pfm.setValue("*");
        send(_pnfsManager, pfm);
    }

    void checkThatFileExists()
    {
        _logger.info("checkThatFileExists");
        PnfsGetFileAttributes message =
            new PnfsGetFileAttributes(_job.getPnfsId(),
                                      EnumSet.noneOf(FileAttribute.class));
        send(_pnfsManager, message);
    }

    void findCacheLocations()
    {
        _logger.info("findCacheLocations");
        PnfsGetCacheLocationsMessage request =
            new PnfsGetCacheLocationsMessage(_job.getPnfsId());
        send(_pnfsManager, request);
    }

    void setLocations(List<String> locations) {
        if(locations != null) {
            _logger.info("setLocations");
            this.locations.addAll(locations);
        } else {
            _logger.info("setLocations - no locations found");
        }
    }

    void unsetStickyFlags()
    {
        for (String poolName: locations) {
            String stickyBitName = getCellName()+
                    Long.toString(_pin.getId());
            String oldStickyBitName = getCellName();
            _logger.info("unsetStickyFlags in "+poolName+" for "+
                _job.getPnfsId()+" stickyBitNameName:"+stickyBitName);

            PoolSetStickyMessage setStickyRequest =
                new PoolSetStickyMessage(poolName,
                _job.getPnfsId(), false,stickyBitName,-1);
            if(!isOldStylePin) {
                send(_pool, setStickyRequest, poolName);
            } else {
                try {
                    // unpin using new format
                    _pool.send(new CellPath(poolName), setStickyRequest);
                    setStickyRequest =
                        new PoolSetStickyMessage(poolName,
                            _job.getPnfsId(), false, oldStickyBitName, -1);
                    // unpin using old format
                    _pool.send(new CellPath(poolName), setStickyRequest);
                } catch (NoRouteToCellException e) {
                    _logger.error("PoolSetStickyMessage (false) failed : " + e.getMessage());
                }
            }
        }
        if(isOldStylePin) {
            _fsm.unsetStickyFlagMessagesSent();
        }
    }
}
