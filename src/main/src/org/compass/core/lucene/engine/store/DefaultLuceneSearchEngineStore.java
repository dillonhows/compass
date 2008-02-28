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

package org.compass.core.lucene.engine.store;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LuceneUtils;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.DirectoryWrapper;
import org.apache.lucene.store.LockFactory;
import org.apache.lucene.store.NativeFSLockFactory;
import org.apache.lucene.store.NoLockFactory;
import org.apache.lucene.store.SimpleFSLockFactory;
import org.apache.lucene.store.SingleInstanceLockFactory;
import org.compass.core.config.CompassConfigurable;
import org.compass.core.config.CompassEnvironment;
import org.compass.core.config.CompassSettings;
import org.compass.core.config.ConfigurationException;
import org.compass.core.engine.SearchEngine;
import org.compass.core.engine.SearchEngineException;
import org.compass.core.engine.event.SearchEngineEventManager;
import org.compass.core.lucene.LuceneEnvironment;
import org.compass.core.lucene.engine.LuceneSearchEngineFactory;
import org.compass.core.lucene.engine.store.localcache.LocalDirectoryCacheManager;
import org.compass.core.lucene.engine.store.wrapper.DirectoryWrapperProvider;
import org.compass.core.mapping.CompassMapping;
import org.compass.core.mapping.ResourceMapping;
import org.compass.core.util.ClassUtils;
import org.compass.core.util.StringUtils;

/**
 * @author kimchy
 */
public class DefaultLuceneSearchEngineStore implements LuceneSearchEngineStore {

    private static Log log = LogFactory.getLog(DefaultLuceneSearchEngineStore.class);

    private CompassSettings settings;

    private DirectoryStore directoryStore;

    private Map<String, List<String>> aliasesBySubIndex = new HashMap<String, List<String>>();

    private Map<String, List<String>> subIndexesByAlias = new HashMap<String, List<String>>();

    private String defaultSubContext;

    private String[] subIndexes;

    private String connectionString;

    private DirectoryWrapperProvider[] directoryWrapperProviders;

    private LocalDirectoryCacheManager localDirectoryCacheManager;

    private Map<String, Map<String, Directory>> dirs;

    public void configure(LuceneSearchEngineFactory searchEngineFactory, CompassSettings settings, CompassMapping mapping) {
        this.settings = settings;
        this.connectionString = settings.getSetting(CompassEnvironment.CONNECTION);
        this.dirs = new ConcurrentHashMap<String, Map<String, Directory>>();

        this.defaultSubContext = settings.getSetting(CompassEnvironment.CONNECTION_SUB_CONTEXT, "index");

        // setup the directory store
        String connection = settings.getSetting(CompassEnvironment.CONNECTION);
        if (connection.startsWith(RAMDirectoryStore.PROTOCOL)) {
            directoryStore = new RAMDirectoryStore();
        } else if (connection.startsWith(FSDirectoryStore.PROTOCOL)) {
            directoryStore = new FSDirectoryStore();
        } else if (connection.startsWith(MMapDirectoryStore.PROTOCOL)) {
            directoryStore = new MMapDirectoryStore();
        } else if (connection.startsWith(JdbcDirectoryStore.PROTOCOL)) {
            directoryStore = new JdbcDirectoryStore();
        } else if (connection.indexOf("://") > -1) {
            String pluggableStore = connection.substring(0, connection.indexOf("://"));
            InputStream is = LuceneSearchEngineStore.class.getResourceAsStream("/META-INF/compass/store-" + pluggableStore + ".properties");
            Properties props;
            try {
                props = new Properties();
                props.load(is);
            } catch (Exception e) {
                try {
                    is.close();
                } catch (Exception e1) {
                    // ignore
                }
                throw new SearchEngineException("Failed to create store [" + connection + "]", e);
            }
            String className = props.getProperty("type");
            try {
                directoryStore = (DirectoryStore) ClassUtils.forName(className, settings.getClassLoader()).newInstance();
            } catch (Exception e) {
                throw new SearchEngineException("Failed to create connection [" + connection + "]", e);
            }
        } else {
            directoryStore = new FSDirectoryStore();
        }
        if (directoryStore instanceof CompassConfigurable) {
            ((CompassConfigurable) directoryStore).configure(settings);
        }

        // setup sub indexes and aliases
        HashSet<String> subIndexesSet = new HashSet<String>();
        for (ResourceMapping resourceMapping : mapping.getRootMappings()) {
            String alias = resourceMapping.getAlias();
            String[] tempSubIndexes = resourceMapping.getSubIndexHash().getSubIndexes();
            for (String subIndex : tempSubIndexes) {
                subIndexesSet.add(subIndex.intern());

                List<String> list = subIndexesByAlias.get(alias);
                if (list == null) {
                    list = new ArrayList<String>();
                    subIndexesByAlias.put(alias, list);
                }
                list.add(subIndex);

                list = aliasesBySubIndex.get(subIndex);
                if (aliasesBySubIndex.get(subIndex) == null) {
                    list = new ArrayList<String>();
                    aliasesBySubIndex.put(subIndex, list);
                }
                list.add(alias);
            }
        }
        subIndexes = subIndexesSet.toArray(new String[subIndexesSet.size()]);

        // set up directory wrapper providers
        Map<String, CompassSettings> dwSettingGroups = settings.getSettingGroups(LuceneEnvironment.DirectoryWrapper.PREFIX);
        if (dwSettingGroups.size() > 0) {
            ArrayList<DirectoryWrapperProvider> dws = new ArrayList<DirectoryWrapperProvider>();
            for (Map.Entry<String, CompassSettings> entry : dwSettingGroups.entrySet()) {
                String dwName = entry.getKey();
                if (log.isInfoEnabled()) {
                    log.info("Building directory wrapper [" + dwName + "]");
                }
                CompassSettings dwSettings = entry.getValue();
                String dwType = dwSettings.getSetting(LuceneEnvironment.DirectoryWrapper.TYPE);
                if (dwType == null) {
                    throw new ConfigurationException("Directory wrapper [" + dwName + "] has no type associated with it");
                }
                DirectoryWrapperProvider dw;
                try {
                    dw = (DirectoryWrapperProvider) ClassUtils.forName(dwType, settings.getClassLoader()).newInstance();
                } catch (Exception e) {
                    throw new ConfigurationException("Failed to create directory wrapper [" + dwName + "]", e);
                }
                if (dw instanceof CompassConfigurable) {
                    ((CompassConfigurable) dw).configure(dwSettings);
                }
                dws.add(dw);
            }
            directoryWrapperProviders = dws.toArray(new DirectoryWrapperProvider[dws.size()]);
        }

        this.localDirectoryCacheManager = new LocalDirectoryCacheManager(searchEngineFactory);
        localDirectoryCacheManager.configure(settings);
    }

    public void close() {
        localDirectoryCacheManager.close();
        closeDirectories();
    }

    private void closeDirectories() {
        for (Map<String, Directory> subIndexsDirs : dirs.values()) {
            synchronized (subIndexsDirs) {
                for (Directory dir : subIndexsDirs.values()) {
                    try {
                        dir.close();
                    } catch (IOException e) {
                        log.debug("Failed to close directory while shutting down, ignoring", e);
                    }
                }
            }
        }
        dirs.clear();
    }

    public void performScheduledTasks() {
        for (Map.Entry<String, Map<String, Directory>> entry : dirs.entrySet()) {
            String subContext = entry.getKey();
            synchronized (entry.getValue()) {
                for (Map.Entry<String, Directory> entry2 : entry.getValue().entrySet()) {
                    String subIndex = entry2.getKey();
                    Directory dir = entry2.getValue();
                    directoryStore.performScheduledTasks(unwrapDir(dir), subContext, subIndex);
                }
            }
        }
    }

    public int getNumberOfAliasesBySubIndex(String subIndex) {
        return (aliasesBySubIndex.get(subIndex)).size();
    }

    public String[] getSubIndexes() {
        return subIndexes;
    }

    public String[] calcSubIndexes(String[] subIndexes, String[] aliases) {
        if (aliases == null) {
            if (subIndexes == null) {
                return getSubIndexes();
            }
            return subIndexes;
        }
        HashSet<String> ret = new HashSet<String>();
        for (String aliase : aliases) {
            List<String> subIndexesList = subIndexesByAlias.get(aliase);
            if (subIndexesList == null) {
                throw new IllegalArgumentException("No sub-index is mapped to alias [" + aliase + "]");
            }
            for (String subIndex : subIndexesList) {
                ret.add(subIndex);
            }
        }
        if (subIndexes != null) {
            ret.addAll(Arrays.asList(subIndexes));
        }
        return ret.toArray(new String[ret.size()]);
    }

    public Directory openDirectory(String subIndex) throws SearchEngineException {
        return openDirectory(defaultSubContext, subIndex);
    }

    public Directory openDirectory(String subContext, String subIndex) throws SearchEngineException {
        Map<String, Directory> subContextDirs = dirs.get(subContext);
        if (subContextDirs == null) {
            subContextDirs = new ConcurrentHashMap<String, Directory>();
            dirs.put(subContext, subContextDirs);
        }
        Directory dir = subContextDirs.get(subIndex);
        if (dir != null) {
            return dir;
        }
        synchronized (subContextDirs) {
            dir = subContextDirs.get(subIndex);
            if (dir != null) {
                return dir;
            }
            dir = directoryStore.open(subContext, subIndex);
            String lockFactoryType = settings.getSetting(LuceneEnvironment.LockFactory.TYPE);
            if (lockFactoryType != null) {
                String path = settings.getSetting(LuceneEnvironment.LockFactory.PATH);
                if (path != null) {
                    path = StringUtils.replace(path, "#subindex#", subIndex);
                    path = StringUtils.replace(path, "#subContext#", subContext);
                }
                LockFactory lockFactory;
                if (LuceneEnvironment.LockFactory.Type.NATIVE_FS.equalsIgnoreCase(lockFactoryType)) {
                    String lockDir = path;
                    if (lockDir == null) {
                        lockDir = connectionString + "/" + subContext + "/" + subIndex;
                        if (lockDir.startsWith(FSDirectoryStore.PROTOCOL)) {
                            lockDir = lockDir.substring(FSDirectoryStore.PROTOCOL.length());
                        }
                    }
                    try {
                        lockFactory = new NativeFSLockFactory(lockDir);
                    } catch (IOException e) {
                        throw new SearchEngineException("Failed to create native fs lock factory with lock dir [" + lockDir + "]", e);
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("Using native fs lock for sub index [" + subIndex + "] and lock directory [" + lockDir + "]");
                    }
                } else if (LuceneEnvironment.LockFactory.Type.SIMPLE_FS.equalsIgnoreCase(lockFactoryType)) {
                    String lockDir = path;
                    if (lockDir == null) {
                        lockDir = connectionString + "/" + subContext + "/" + subIndex;
                        if (lockDir.startsWith(FSDirectoryStore.PROTOCOL)) {
                            lockDir = lockDir.substring(FSDirectoryStore.PROTOCOL.length());
                        }
                    }
                    try {
                        lockFactory = new SimpleFSLockFactory(lockDir);
                    } catch (IOException e) {
                        throw new SearchEngineException("Failed to create simple fs lock factory with lock dir [" + lockDir + "]", e);
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("Using simple fs lock for sub index [" + subIndex + "] and lock directory [" + lockDir + "]");
                    }

                } else if (LuceneEnvironment.LockFactory.Type.SINGLE_INSTANCE.equalsIgnoreCase(lockFactoryType)) {
                    lockFactory = new SingleInstanceLockFactory();
                } else if (LuceneEnvironment.LockFactory.Type.NO_LOCKING.equalsIgnoreCase(lockFactoryType)) {
                    lockFactory = new NoLockFactory();
                } else {
                    Object temp;
                    try {
                        temp = ClassUtils.forName(lockFactoryType, settings.getClassLoader()).newInstance();
                    } catch (Exception e) {
                        throw new SearchEngineException("Failed to create lock type [" + lockFactoryType + "]", e);
                    }
                    if (temp instanceof LockFactory) {
                        lockFactory = (LockFactory) temp;
                    } else if (temp instanceof LockFactoryProvider) {
                        lockFactory = ((LockFactoryProvider) temp).createLockFactory(path, subIndex, settings);
                    } else {
                        throw new SearchEngineException("No specific type of lock factory");
                    }

                    if (lockFactory instanceof CompassConfigurable) {
                        ((CompassConfigurable) lockFactory).configure(settings);
                    }
                }
                dir.setLockFactory(lockFactory);
            }
            if (directoryWrapperProviders != null) {
                for (DirectoryWrapperProvider directoryWrapperProvider : directoryWrapperProviders) {
                    dir = directoryWrapperProvider.wrap(subIndex, dir);
                }
            }
            dir = localDirectoryCacheManager.createLocalCache(subContext, subIndex, dir);
            subContextDirs.put(subIndex, dir);
        }
        return dir;
    }

    public boolean indexExists() throws SearchEngineException {
        for (String subIndex : subIndexes) {
            if (!indexExists(subIndex)) {
                return false;
            }
        }
        return true;
    }

    public boolean indexExists(String subIndex) throws SearchEngineException {
        return indexExists(defaultSubContext, subIndex);
    }

    public boolean indexExists(String subContext, String subIndex) throws SearchEngineException {
        boolean closeDir = !directoryExists(subContext, subIndex);
        Directory dir = openDirectory(subContext, subIndex);
        Boolean retVal = directoryStore.indexExists(unwrapDir(dir));
        if (retVal != null) {
            return retVal;
        }
        try {
            retVal = IndexReader.indexExists(dir);
        } catch (IOException e) {
            return false;
        }
        if (closeDir) {
            closeDirectory(dir, subContext, subIndex);
        }
        return retVal;
    }

    public void createIndex() throws SearchEngineException {
        for (String subIndex : subIndexes) {
            createIndex(subIndex);
        }
    }

    public void createIndex(String subIndex) throws SearchEngineException {
        createIndex(defaultSubContext, subIndex);
    }

    public void createIndex(String subContext, String subIndex) throws SearchEngineException {
        Directory dir = openDirectory(subContext, subIndex);
        try {
            IndexWriter indexWriter = new IndexWriter(dir, new StandardAnalyzer(), true);
            indexWriter.close();
        } catch (IOException e) {
            throw new SearchEngineException("Failed to create index for sub index [" + subIndex + "]", e);
        }
    }

    public void deleteIndex() throws SearchEngineException {
        for (String subIndex : subIndexes) {
            deleteIndex(subIndex);
        }
    }

    public void deleteIndex(String subIndex) throws SearchEngineException {
        deleteIndex(defaultSubContext, subIndex);
    }

    public void deleteIndex(String subContext, String subIndex) throws SearchEngineException {
        Directory dir = openDirectory(subContext, subIndex);
        directoryStore.deleteIndex(unwrapDir(dir), subContext, subIndex);
        closeDirectory(dir, subContext, subIndex);
    }

    public boolean verifyIndex() throws SearchEngineException {
        boolean createdIndex = false;
        for (String subIndex : subIndexes) {
            if (verifyIndex(subIndex)) {
                createdIndex = true;
            }
        }
        return createdIndex;
    }

    public boolean verifyIndex(String subIndex) throws SearchEngineException {
        return verifyIndex(defaultSubContext, subIndex);
    }

    public boolean verifyIndex(String subContext, String subIndex) throws SearchEngineException {
        if (!indexExists(subContext, subIndex)) {
            createIndex(subContext, subIndex);
            return true;
        }
        return false;
    }

    public void cleanIndex(String subIndex) throws SearchEngineException {
        cleanIndex(defaultSubContext, subIndex);
    }

    public void cleanIndex(String subContext, String subIndex) throws SearchEngineException {
        Directory dir = directoryStore.open(subContext, subIndex);

        Directory unwrapDir = unwrapDir(dir);
        directoryStore.cleanIndex(unwrapDir, subContext, subIndex);

        closeDirectory(dir, subContext, subIndex);
        createIndex(subContext, subIndex);
    }


    public boolean isLocked() throws SearchEngineException {
        for (String subIndex : getSubIndexes()) {
            if (isLocked(subIndex)) {
                return true;
            }
        }
        return false;
    }

    public boolean isLocked(String subIndex) throws SearchEngineException {
        return isLocked(defaultSubContext, subIndex);
    }

    public boolean isLocked(String subContext, String subIndex) throws SearchEngineException {
        try {
            return IndexReader.isLocked(openDirectory(subContext, subIndex));
        } catch (IOException e) {
            throw new SearchEngineException("Failed to check if index is locked for sub context [" + subContext + "] and sub index [" + subIndex + "]", e);
        }
    }

    public void releaseLocks() throws SearchEngineException {
        for (String subIndex : subIndexes) {
            releaseLock(subIndex);
        }
    }

    public void releaseLock(String subIndex) throws SearchEngineException {
        releaseLock(defaultSubContext, subIndex);
    }

    public void releaseLock(String subContext, String subIndex) throws SearchEngineException {
        try {
            IndexReader.unlock(openDirectory(subContext, subIndex));
        } catch (IOException e) {
            throw new SearchEngineException("Failed to unlock index for sub context [" + subContext + "] and sub index [" + subIndex + "]", e);
        }
    }

    public void copyFrom(LuceneSearchEngineStore searchEngineStore) throws SearchEngineException {
        // clear any possible wrappers
        ArrayList<Directory> subIndexDirs = new ArrayList<Directory>();
        for (String subIndex : subIndexes) {
            Directory dir = openDirectory(subIndex);
            subIndexDirs.add(unwrapDir(dir));
            if (dir instanceof DirectoryWrapper) {
                try {
                    ((DirectoryWrapper) dir).clearWrapper();
                } catch (IOException e) {
                    throw new SearchEngineException("Failed to clear wrapper for sub index [" + subIndex + "]", e);
                }
            }
        }
        CopyFromHolder holder = directoryStore.beforeCopyFrom(defaultSubContext, subIndexDirs.toArray(new Directory[subIndexDirs.size()]));
        final byte[] buffer = new byte[32768];
        try {
            for (String subIndex : subIndexes) {
                Directory dest = openDirectory(subIndex);
                Directory src = searchEngineStore.openDirectory(subIndex);
                LuceneUtils.copy(src, dest, buffer);
            }
        } catch (Exception e) {
            directoryStore.afterFailedCopyFrom(defaultSubContext, holder);
            if (e instanceof SearchEngineException) {
                throw (SearchEngineException) e;
            }
            throw new SearchEngineException("Failed to copy from " + searchEngineStore, e);
        }
        directoryStore.afterSuccessfulCopyFrom(defaultSubContext, holder);
    }

    public void registerEventListeners(SearchEngine searchEngine, SearchEngineEventManager eventManager) {
        directoryStore.registerEventListeners(searchEngine, eventManager);
    }

    public String getDefaultSubContext() {
        return this.defaultSubContext;
    }

    private boolean directoryExists(String subContext, String subIndex) throws SearchEngineException {
        Map<String, Directory> subContextDirs = dirs.get(subContext);
        return subContextDirs != null && subContextDirs.containsKey(subIndex);
    }

    private void closeDirectory(Directory dir, String subContext, String subIndex) throws SearchEngineException {
        directoryStore.closeDirectory(dir, subContext, subIndex);
        Map<String, Directory> subContextDirs = dirs.get(subContext);
        if (subContextDirs != null) {
            subContextDirs.remove(subIndex);
        }
    }

    private Directory unwrapDir(Directory dir) {
        while (dir instanceof DirectoryWrapper) {
            dir = ((DirectoryWrapper) dir).getWrappedDirectory();
        }
        return dir;
    }

    public String toString() {
        return "store [" + connectionString + "][" + defaultSubContext + "] sub-indexes [" + StringUtils.arrayToCommaDelimitedString(subIndexes) + "]";
    }
}