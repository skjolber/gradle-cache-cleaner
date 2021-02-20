package com.github.skjolber.gradle;

import static org.gradle.internal.serialize.BaseSerializerFactory.*;

import org.gradle.cache.FileLock;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.internal.DefaultFileLockManager;
import org.gradle.cache.internal.ProcessMetaDataProvider;
import org.gradle.cache.internal.btree.BTreePersistentIndexedCache;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;

import org.gradle.cache.internal.filelock.LockOptionsBuilder;
import org.gradle.cache.internal.locklistener.DefaultFileLockContentionHandler;
import org.gradle.internal.concurrent.DefaultExecutorFactory;
import org.gradle.internal.file.impl.DefaultDeleter;
import org.gradle.internal.remote.internal.inet.InetAddressFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class Runner {

    private static final int CACHE_KEY_LENGTH = 32;

    private static FilenameFilter jarFilter = (d, name) -> name.endsWith(".jar");

    public static final void main(String[] args) {

        if(args.length < 1 || args.length > 2) {
            System.out.println("Usage: options path");
            System.out.println("");
            System.out.println("Options:");
            System.out.println(" --quiet (default : true)");
            System.exit(0);
        }

        boolean quiet = args[0].equals("--quiet");
        
        long deadline = Long.parseLong(args[args.length - 1]);
        File cacheFile = new File(System.getProperty("user.home") + "/.gradle/caches/journal-1/file-access.bin");

        DefaultFileLockContentionHandler defaultFileLockContentionHandler = new DefaultFileLockContentionHandler(new DefaultExecutorFactory(), new InetAddressFactory());

        ProcessMetaDataProvider d = new ProcessMetaDataProvider() {
            @Override
            public String getProcessIdentifier() {
                return "gradle";
            }

            @Override
            public String getProcessDisplayName() {
                return "gradle";
            }
        };

        DefaultFileLockManager defaultFileLockManager = new DefaultFileLockManager(d, defaultFileLockContentionHandler);
        try {

            File cachesDirectory = new File(System.getProperty("user.home") + "/.gradle/caches");
            FileFilter jarCacheFilter = (f) -> f.isDirectory() && (f.getName().startsWith("jars-") || f.getName().startsWith("modules-"));

            DefaultDeleter deleter = new DefaultDeleter(() -> 0L, (f) -> Files.isSymbolicLink(f.toPath()), false);

            File[] caches = cachesDirectory.listFiles(jarCacheFilter);
            for (File cache : caches) {
                System.out.println("Found cache " + cache);
            }

            long time = System.currentTimeMillis();

            AtomicInteger deleted = new AtomicInteger();

            for (File cache : caches) {
                BTreePersistentIndexedCache<File, Long> journal = new BTreePersistentIndexedCache<File, Long>(cacheFile, FILE_SERIALIZER, LONG_SERIALIZER);

                try {
                    ConcurrentLinkedQueue<File> queue = new ConcurrentLinkedQueue<>();

                    FileLock readerLock = defaultFileLockManager.lock(cache, LockOptionsBuilder.mode(FileLockManager.LockMode.Exclusive), "cleaner", "prepare", (t) -> {
                        System.out.println("Contended cache");
                    });
                    try {
                        readerLock.writeFile(() -> {

                            Consumer<File> nuker = (file) -> {
                                queue.add(file);
                            };

                            Predicate<File> nukePredicate = (f) -> {
                                Long journalTimestamp;
                                synchronized (journal) {
                                    journalTimestamp = journal.get(f);
                                }
                                if (journalTimestamp != null) {
                                    if(journalTimestamp >= deadline) {
                                        return false;
                                    }
                                    return true;
                                }

                                return false;
                            };

                            try {
                                ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
                                //ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
                                try {
                                    if (cache.getName().startsWith("jars-")) {
                                        System.out.println("Delete from " + cache);
                                        deleteFromJars(cache, nukePredicate, nuker, executor, deleted);
                                    } else if (cache.getName().startsWith("modules-")) {
                                        System.out.println("Delete from " + cache);
                                        deleteFromModules(cache, nukePredicate, nuker, executor, deleted);
                                    } else {
                                        System.out.println("Ignore " + cache);
                                    }
                                } finally {
                                    executor.shutdown();
                                    executor.awaitTermination(60, TimeUnit.SECONDS);
                                }
                            } catch (Exception e) {
                                // ignore
                                System.out.println("Problem deleting from " + cache);
                                e.printStackTrace();
                            }
                        });
                    } finally {
                        readerLock.close();
                    }

                    FileLock writeLock = defaultFileLockManager.lock(cache, LockOptionsBuilder.mode(FileLockManager.LockMode.Exclusive), "cleaner", "perform", (t) -> {
                        System.out.println("Contended cache");
                    });
                    try {
                        writeLock.writeFile(() -> {
                            for (File file : queue) {
                                System.out.println("Remove " + file);
                                journal.remove(file);
                                try {
                                    deleter.deleteRecursively(file);
                                } catch (IOException e) {
                                    System.out.println("Problem deleting " + file);
                                    e.printStackTrace();
                                }
                            }
                        });
                    } finally {
                        writeLock.close();
                    }
                } finally {
                    journal.close();
                }
            }
            System.out.println("Deleted " + deleted + " in " + (System.currentTimeMillis() - time) + "ms");
        } catch(Exception e) {
            e.printStackTrace();
        } finally {
            System.out.println("Finished");

            defaultFileLockContentionHandler.stop();
        }
    }

    public static void deleteFromJars(File parentDirectory, Predicate<File> predicate, Consumer<File> deleter, ThreadPoolExecutor executor, AtomicInteger deleteCounter) throws IOException {
        FileFilter dirFilter = (f) -> f.isDirectory() && f.getName().length() == CACHE_KEY_LENGTH;
        List<File> files = Arrays.asList(parentDirectory.listFiles(dirFilter));
        System.out.println("Check " + files.size() + " files in " + parentDirectory);
        AtomicInteger counter = new AtomicInteger(0);
        int maximumPoolSize = executor.getMaximumPoolSize();
        for(int i = 0; i < maximumPoolSize; i++) {
            executor.submit(new JarsWorkerRunnable(counter, files, predicate, deleter, deleteCounter));
        }
    }

    public static void deleteFromModules(File parentDirectory, Predicate<File> predicate, Consumer<File> deleter, ThreadPoolExecutor executor, AtomicInteger deleteCounter) throws IOException {
        FileFilter dirFilter = (f) -> f.isDirectory();

        List<File> files = new ArrayList<>();

        List<File> metadataDirectories = new ArrayList<>();
        File[] groupFiles = parentDirectory.listFiles(dirFilter);
        for(File f : groupFiles) {
            if(f.getName().startsWith("files-")) {
                File[] parts = f.listFiles(dirFilter);
                System.out.println("Check " + parts.length + " files in " + f);
                Collections.addAll(files, parts);
            } else {
    	        File descriptors = new File(f, "descriptors");
    	        if(descriptors.exists()) {
    	        	metadataDirectories.add(descriptors);
    	        }
            }
        }

        Consumer<File> fileAndMetadataDeleter = (f) -> {
        	
        	File artifactId = f.getParentFile();
        	File groupId = artifactId.getParentFile();
        	
        	for(File metadataDirectory: metadataDirectories) {
        		File child1 = new File(metadataDirectory, groupId.getName());
        		File child2 = new File(child1, artifactId.getName());
        		File child3 = new File(child2, f.getName());
        		if(child3.exists()) {
        			deleter.accept(child3);
        		}
        	}
			deleter.accept(f);
        	
        };
        

        AtomicInteger counter = new AtomicInteger(0);
        int maximumPoolSize = executor.getMaximumPoolSize();
        for(int i = 0; i < maximumPoolSize; i++) {
            executor.submit(new ModulesWorkerRunnable(counter, files, predicate, fileAndMetadataDeleter, deleteCounter));
        }
    }

    private static class JarsWorkerRunnable implements Runnable {

        private volatile boolean closed = false;
        private final AtomicInteger index;
        private final List<File> files;
        private final Predicate<File> predicate;
        private final Consumer<File> deleter;
        private final AtomicInteger deleteCounter;

        private JarsWorkerRunnable(AtomicInteger index, List<File> files, Predicate<File> predicate, Consumer<File> deleter, AtomicInteger deleteCounter) {
            this.index = index;
            this.files = files;
            this.predicate = predicate;
            this.deleter = deleter;
            this.deleteCounter = deleteCounter;
        }

        @Override
        public void run() {
            int count = 0;
            while(!closed) {
                int i = index.getAndIncrement();
                if(i >= files.size()) {
                    break;
                }

                File file = files.get(i);
                if(predicate.test(file)) {
                    count++;
                    deleter.accept(file);
                }
            }
            deleteCounter.addAndGet(count);
        }

        public void close() {
            closed = true;
        }
    }

    private static class ModulesWorkerRunnable implements Runnable {

        private final static FileFilter dirFilter = (f) -> f.isDirectory();

        private volatile boolean closed = false;
        private final AtomicInteger index;
        private final List<File> files;
        private final Predicate<File> predicate;
        private final Consumer<File> deleter;
        private final AtomicInteger deleteCounter;

        private ModulesWorkerRunnable(AtomicInteger index, List<File> files, Predicate<File> predicate, Consumer<File> deleter, AtomicInteger deleteCounter) {
            this.index = index;
            this.files = files;
            this.predicate = predicate;
            this.deleter = deleter;
            this.deleteCounter = deleteCounter;
        }

        @Override
        public void run() {
            int count = 0;
            while(!closed) {
                int i = index.getAndIncrement();
                if(i >= files.size()) {
                    break;
                }

                File groupId = files.get(i);
                File[] artifactIds = groupId.listFiles(dirFilter);
                
                for(File artifactId : artifactIds) {
                    File[] versions = artifactId.listFiles(dirFilter);

                    versions:
                    for(File version : versions) {
                        File[] hashes = version.listFiles(dirFilter);
                    	// keep all or delete all
                        for(File hash : hashes) {
                            if(!predicate.test(hash)) {
                            	continue versions;
                            }
                        }
                        deleter.accept(version);

                        count++;

                        if(versions.length == 1) {
                            artifactId.delete();
                            if(artifactIds.length == 1) {
                                groupId.delete();
                            }
                        }
                    }
                }
            }
            deleteCounter.addAndGet(count);
        }

        public void close() {
            closed = true;
        }
    }

}
