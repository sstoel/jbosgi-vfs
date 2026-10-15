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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A closed {@code VirtualFile} of the jboss-vfs 3 adaptor does not throw {@code IllegalStateException} as its API
 * documents: the next {@code getChild}/{@code getChildren}/{@code findEntries}/{@code getEntryPaths} silently mounts the
 * archive again (opens the jar and registers a new jboss-vfs mount) and that mount is never closed by the owner, who
 * already closed the file.
 *
 * <h3>Severity</h3>
 * minor. API contract violation with a resource consequence: code that keeps using a closed root (for example a
 * {@code Deployment}/{@code BundleInfo} root after the bundle content was closed) does not fail fast, it re-opens the
 * archive behind the owner's back; the new mount (an open {@code JarFile} and an entry in the static jboss-vfs mount
 * table) stays until somebody calls {@code close()} a second time. For a root mounted from a stream
 * ({@code toVirtualFile(InputStream)}, every {@code installBundle(location, stream)}) the re-mount fails instead and the
 * caller gets a {@code FileNotFoundException} for "/streamNNN" rather than the documented IllegalStateException.
 * The framework does use closed roots: with a trial fix that throws IllegalStateException after close, the framework
 * itest {@code FrameworkExtensionTestCase.testRefreshStopsTheFramework} fails at {@code framework.loadClass} with
 * "IllegalStateException: closed: /stream..." (stack: VirtualFileResourceLoader.findChild/getClassSpec), i.e. the class
 * loader of an uninstalled but still attached framework extension keeps probing its closed content; today that
 * IOException is swallowed by VirtualFileResourceLoader and the class comes from the extension's temporary copy.
 * (Closing in-use content at uninstall is the framework issue wildfly/28; this report is about the VFS contract.)
 *
 * <h3>Component</h3>
 * jbosgi-vfs, vfs30, {@code org.jboss.osgi.vfs30.VirtualFileAdaptor30} ({@code close()}, {@code ensureMounted()}),
 * contract in jbosgi-vfs api {@code org.jboss.osgi.vfs.VirtualFile}.
 *
 * <h3>Found by</h3>
 * subrepos-a workstream, javadoc-vs-behaviour review of the VFS API, exploratory test (getChild after close returns the
 * child and the archive is mounted again).
 *
 * <h3>Spec reference</h3>
 * jbosgi-vfs api {@code org.jboss.osgi.vfs.VirtualFile} (the public contract of this type), e.g. {@code getChild}:
 * "@throws IllegalStateException if the file is closed or it is a leaf node"; the same "@throws IllegalStateException
 * if the file is closed" is declared for getName, getPathName, isFile, isDirectory, getParent,
 * getChildrenRecursively, getChildren and openStream ({@code api/src/main/java/org/jboss/osgi/vfs/VirtualFile.java:74-187}).
 * {@code java.io.Closeable#close()}: "Closes this stream and releases any system resources associated with it."
 *
 * <h3>Code location</h3>
 * {@code vfs30/src/main/java/org/jboss/osgi/vfs30/VirtualFileAdaptor30.java:280-294} ({@code close()} only sets
 * {@code mount = null}, there is no closed state) and {@code :317-323} ({@code ensureMounted()} mounts again whenever
 * {@code mount == null}). Pre-existing, present on WF41.
 *
 * <h3>Root cause</h3>
 * {@code close()} does not record that the file is closed; lazy mounting cannot distinguish "not mounted yet" from
 * "closed", so every later access re-mounts.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-vfs
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl vfs30 test -Dtest=ClosedVirtualFileRemountIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * testGetChildAfterClose: getChild on a closed VirtualFile must throw IllegalStateException (VirtualFile javadoc), but it
 *   returned ".../target/closed-remount-issue.jar/a/b.txt"
 * testNoMountAfterClose: A closed VirtualFile must not mount its archive again, but after close() and getChildren() the
 *   archive .../target/closed-remount-issue.jar is mounted (a directory in jboss-vfs) again
 * </pre>
 *
 * <h3>Expected</h3>
 * After {@code close()} the methods documented with "@throws IllegalStateException if the file is closed" throw it, and
 * no new mount is created.
 *
 * <h3>Reference behaviour</h3>
 * n/a (jboss-osgi specific API).
 *
 * <h3>Suggested fix</h3>
 * Add a {@code closed} flag set in {@code close()}; {@code ensureMounted()} (and the other documented methods) throw
 * {@code IllegalStateException} when it is set. If re-opening after close is intended instead, the javadoc must say so
 * and the adaptor must track the new mount for a later close. Verified with a trial patch (closed flag + ISE in
 * ensureMounted): this test passes, SimpleVFS30TestCase passes, and the full jbosgi-framework build (core 74, itest 436)
 * passes except FrameworkExtensionTestCase.testRefreshStopsTheFramework, see Severity: VirtualFileResourceLoader must
 * then treat a closed root like a missing entry (or the framework must stop using closed content first).
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 */
public class ClosedVirtualFileRemountIssueTestCase {

    private File file;
    private VirtualFile root;

    @Before
    public void setUp() throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "closed-remount-issue.jar");
        archive.addAsResource(new StringAsset("content"), "a/b.txt");
        file = new File("target/closed-remount-issue.jar");
        try (InputStream in = archive.as(ZipExporter.class).exportAsInputStream(); FileOutputStream out = new FileOutputStream(file)) {
            in.transferTo(out);
        }
        root = AbstractVFS.toVirtualFile(file.toURI());
        if (root.getChild("a/b.txt") == null)
            throw new IllegalStateException("Precondition: a/b.txt is found while the file is open");
    }

    @After
    public void tearDown() {
        VFSUtils.safeClose(root);
    }

    @Test
    public void testGetChildAfterClose() throws Exception {
        root.close();
        VirtualFile child;
        try {
            child = root.getChild("a/b.txt");
        } catch (IllegalStateException expected) {
            return;
        }
        fail("getChild on a closed VirtualFile must throw IllegalStateException (VirtualFile javadoc), but it returned " + child);
    }

    @Test
    public void testNoMountAfterClose() throws Exception {
        org.jboss.vfs.VirtualFile jbossFile = (org.jboss.vfs.VirtualFile) AbstractVFS.adapt(root);
        root.close();
        if (jbossFile.isDirectory())
            throw new IllegalStateException("Precondition: close() unmounts the archive");
        try {
            root.getChildren();
        } catch (IllegalStateException expected) {
            // the documented behaviour
        }
        assertFalse("A closed VirtualFile must not mount its archive again, but after close() and getChildren() the archive " + file.getAbsolutePath()
                + " is mounted (a directory in jboss-vfs) again", jbossFile.isDirectory());
    }
}
