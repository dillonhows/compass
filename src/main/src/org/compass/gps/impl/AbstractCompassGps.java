/*
 * Copyright 2004-2006 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.compass.gps.impl;

import java.util.HashMap;
import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.compass.core.Compass;
import org.compass.core.mapping.CascadeMapping;
import org.compass.core.mapping.ResourceMapping;
import org.compass.core.spi.InternalCompass;
import org.compass.core.util.ClassUtils;
import org.compass.gps.CompassGpsDevice;
import org.compass.gps.CompassGpsException;
import org.compass.gps.spi.CompassGpsInterfaceDevice;

/**
 * A simple base class for {@link org.compass.gps.CompassGps}
 * implementations.
 * 
 * @author kimchy
 */
public abstract class AbstractCompassGps implements CompassGpsInterfaceDevice {

    protected Log log = LogFactory.getLog(getClass());

    protected HashMap devices = new HashMap();

    private boolean started = false;

    private boolean performingIndexOperation = false;

    public void addGpsDevice(CompassGpsDevice gpsDevice) {
        checkDeviceValidity(gpsDevice);
        gpsDevice.setGps(this);
        devices.put(gpsDevice.getName(), gpsDevice);
    }

    public void setGpsDevices(CompassGpsDevice[] devices) {
        this.devices.clear();
        for (int i = 0; i < devices.length; i++) {
            checkDeviceValidity(devices[i]);
            devices[i].setGps(this);
            this.devices.put(devices[i].getName(), devices[i]);
        }
    }

    private void checkDeviceValidity(CompassGpsDevice device) {
        if (device.getName() == null) {
            throw new IllegalArgumentException("Must specify a name for a gps device");
        }
        if (devices.get(device.getName()) != null) {
            throw new IllegalArgumentException("A gps device with the name [" + device.getName()
                    + "] is defined twice. It is not allowed.");
        }
    }

    protected boolean hasRootMappingForEntity(Class clazz, Compass checkedCompass) {
        return getRootMappingForEntity(clazz, checkedCompass) != null;
    }

    protected boolean hasMappingForEntity(Class clazz, Compass checkedCompass, CascadeMapping.Cascade cascade) {
        ResourceMapping resourceMapping = ((InternalCompass) checkedCompass).getMapping().getResourceMappingByClass(clazz);
        if (resourceMapping == null) {
            return false;
        }
        if (resourceMapping.isRoot()) {
            return true;
        }
        return resourceMapping.operationAllowed(cascade);
    }

    protected boolean hasMappingForEntity(String name, Compass checkedCompass, CascadeMapping.Cascade cascade) {
        ResourceMapping resourceMapping = ((InternalCompass) checkedCompass).getMapping().getResourceMappingByAlias(name);
        if (resourceMapping == null) {
            return false;
        }
        if (resourceMapping.isRoot()) {
            return true;
        }
        return resourceMapping.operationAllowed(cascade);
    }

    protected ResourceMapping getRootMappingForEntity(Class clazz, Compass checkedCompass) {
        return ((InternalCompass) checkedCompass).getMapping().getRootMappingByClass(clazz);
    }
    
    protected boolean hasRootMappingForEntity(String name, Compass checkedCompass) {
        return getRootMappingForEntity(name, checkedCompass) != null;
    }

    protected ResourceMapping getMappingForEntity(String name, Compass checkedCompass) {
        ResourceMapping resourceMapping = ((InternalCompass) checkedCompass).getMapping().getResourceMappingByAlias(name);
        if (resourceMapping != null) {
            return resourceMapping;
        }
        try {
            Class clazz = ClassUtils.forName(name);
            return ((InternalCompass) checkedCompass).getMapping().getResourceMappingByClass(clazz);
        } catch (Exception e) {
            // do nothing
        }
        return null;
    }

    protected ResourceMapping getRootMappingForEntity(String name, Compass checkedCompass) {
        ResourceMapping resourceMapping = ((InternalCompass) checkedCompass).getMapping().getRootMappingByAlias(name);
        if (resourceMapping != null) {
            return resourceMapping;
        }
        try {
            Class clazz = ClassUtils.forName(name);
            return ((InternalCompass) checkedCompass).getMapping().getRootMappingByClass(clazz);
        } catch (Exception e) {
            // do nothing
        }
        return null;
    }

    public synchronized void index() throws CompassGpsException, IllegalStateException {
        if (!isRunning()) {
            throw new IllegalStateException("CompassGps must be running in order to perform the index operation");
        }
        if (((InternalCompass) getMirrorCompass()).getTransactionFactory().getTransactionBoundSession() != null) {
            throw new CompassGpsException("index() operation is not allowed to be called within a transaction (mirror)");
        }
        if (((InternalCompass) getIndexCompass()).getTransactionFactory().getTransactionBoundSession() != null) {
            throw new CompassGpsException("index() operation is not allowed to be called within a transaction (index)");
        }
        if (isPerformingIndexOperation()) {
            throw new IllegalArgumentException("Indexing alredy in process, not allowed to call index()");
        }
        try {
            performingIndexOperation = true;
            doIndex();
        } finally {
            performingIndexOperation = false;
        }
    }

    protected abstract void doIndex() throws CompassGpsException;

    public synchronized void start() throws CompassGpsException {
        doStart();
        if (!started) {
            for (Iterator it = devices.values().iterator(); it.hasNext();) {
                CompassGpsDevice device = (CompassGpsDevice) it.next();
                device.start();
            }
            started = true;
        }
    }

    protected abstract void doStart() throws CompassGpsException;

    protected abstract void doStop() throws CompassGpsException;

    public synchronized void stop() throws CompassGpsException {
        if (started) {
            for (Iterator it = devices.values().iterator(); it.hasNext();) {
                CompassGpsDevice device = (CompassGpsDevice) it.next();
                device.stop();
            }
            started = false;
        }
        doStop();
    }

    public boolean isRunning() {
        return started;
    }

    public boolean isPerformingIndexOperation() {
        return performingIndexOperation;
    }
}
