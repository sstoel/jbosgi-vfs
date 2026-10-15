/*
 * #%L
 * JBossOSGi VFS
 * %%
 * Copyright (C) 2026 JBoss by Red Hat
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */
package org.jboss.test.osgi.vfs.issues;

import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;

/**
 * {@code AbstractVFS.toVirtualFile(InputStream)} names the mount point of a stream after the current time in
 * milliseconds, so two streams that are mounted within the same millisecond collide: the second mount fails with
 * "Filesystem already mounted at mount point" (and the framework reports "Cannot obtain virtual file from input
 * stream").
 *
 * <h3>Severity</h3>
 * major. Every {@code BundleContext.installBundle(location, InputStream)} and {@code Bundle.update(InputStream)} of
 * jbosgi-framework goes through this method; installs/updates that run concurrently (several management threads,
 * provisioning) or quickly one after the other fail at random with
 * {@code BundleException: JBOSGI011229: Cannot obtain virtual file from input stream}.
 *
 * <h3>Component</h3>
 * jbosgi-vfs, vfs30, {@code org.jboss.osgi.vfs30.VFSAdaptor30.toVirtualFile(InputStream)} and
 * {@code VirtualFileAdaptor30(String, InputStream)}.
 *
 * <h3>Found by</h3>
 * connect workstream (concurrent install/update), framework-level stress test of concurrent stream installs
 * ({@code installBundle(location, stream)} from 8 threads failed with JBOSGI011229 in every round, on WF41 as on
 * WF41-osgi-r8), then reduced to this VFS-level test.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, BundleContext.installBundle(String, InputStream) javadoc (osgi.core 8.0.0): "The specified
 * InputStream object must be used to read the contents of the bundle"; 4.4.3 Installing Bundles: installation must
 * succeed for valid content regardless of other installs (the framework is @ThreadSafe per the BundleContext
 * javadoc). The VFS API itself documents no single-use restriction for {@code toVirtualFile(InputStream)}.
 *
 * <h3>Code location</h3>
 * {@code vfs30/src/main/java/org/jboss/osgi/vfs30/VFSAdaptor30.java:91-94}:
 * {@code String name = "stream" + System.currentTimeMillis(); return new VirtualFileAdaptor30(name, inputStream);}
 * and {@code vfs30/src/main/java/org/jboss/osgi/vfs30/VirtualFileAdaptor30.java:119-124}, which mounts the zip at
 * {@code VFS.getChild(name)}. Unchanged on WF41-osgi-r8 (pre-existing).
 *
 * <h3>Root cause</h3>
 * The mount point name is not unique: the millisecond clock is used as the only discriminator.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-vfs
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full -pl vfs30 -am install -Dtest=StreamVirtualFileNameCollisionIssueTestCase -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; without it the missing VFSLogger implementation adds unrelated ExceptionInInitializerErrors.)
 * <h3>Observed</h3>
 * <pre>
 * testStreamsMountedConcurrently: Every stream must be mounted as its own virtual file, but in round 0 of 8
 *   concurrent mounts: [stream 7: java.io.IOException: VFS000017: Filesystem already mounted at mount point
 *   ""/stream1791915504080"", stream 5: java.io.IOException: VFS000017: Filesystem already mounted at mount point
 *   ""/stream1791915504081"", ...]
 * </pre>
 *
 * <h3>Expected</h3>
 * Every call returns a virtual file with the content of its own stream.
 *
 * <h3>Reference behaviour</h3>
 * n/a (jboss-osgi specific API).
 *
 * <h3>Suggested fix</h3>
 * Use a unique name, e.g. {@code "stream" + UUID.randomUUID()} or a static {@code AtomicLong} counter combined with
 * the time.
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 */
public class StreamVirtualFileNameCollisionIssueTestCase {

    private static final int THREADS = 8;
    private static final int ROUNDS = 5;

    @Test
    public void testStreamsMountedConcurrently() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            final CyclicBarrier barrier = new CyclicBarrier(THREADS);
            final List<String> failures = new CopyOnWriteArrayList<String>();
            final List<VirtualFile> files = new CopyOnWriteArrayList<VirtualFile>();
            List<Thread> threads = new ArrayList<Thread>();
            for (int i = 0; i < THREADS; i++) {
                final int index = round * THREADS + i;
                Thread thread = new Thread() {
                    @Override
                    public void run() {
                        try {
                            InputStream input = createArchive(index);
                            barrier.await();
                            VirtualFile file = AbstractVFS.toVirtualFile(input);
                            files.add(file);
                            if (file.getChild("entry-" + index + ".txt") == null)
                                failures.add("stream " + index + " is mounted with the content of another stream");
                        } catch (Throwable th) {
                            failures.add("stream " + index + ": " + th);
                        }
                    }
                };
                thread.start();
                threads.add(thread);
            }
            for (Thread thread : threads)
                thread.join(30000);
            for (VirtualFile file : files)
                VFSUtils.safeClose(file);
            assertTrue("Every stream must be mounted as its own virtual file, but in round " + round + " of " + THREADS + " concurrent mounts: " + failures,
                    failures.isEmpty());
        }
    }

    private static InputStream createArchive(int index) {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "stream-" + index + ".jar");
        archive.addAsResource(new StringAsset("content " + index), "entry-" + index + ".txt");
        return archive.as(ZipExporter.class).exportAsInputStream();
    }
}
