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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.Callable;

import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;

/**
 * A closed VirtualFile still gives access to its content: a closed directory, and isFile, openStream and the other
 * accessors of a closed archive, do not throw the IllegalStateException that the VirtualFile API documents.
 *
 * <h2>Severity</h2>
 * minor. Code that uses a file after it closed it is not told so; for an archive it reads whatever the file system
 * still has at the path.
 *
 * <h2>Component</h2>
 * jbosgi-vfs, vfs30: {@code VirtualFileAdaptor30}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of subrepos/03 (support-06).
 *
 * <h2>Spec reference</h2>
 * {@code org.jboss.osgi.vfs.VirtualFile}: "@throws IllegalStateException if the file is closed".
 *
 * <h2>Code location</h2>
 * vfs30/src/main/java/org/jboss/osgi/vfs30/VirtualFileAdaptor30.java:330-333 (1dd912f): the check guarded only the
 * mount of an archive.
 *
 * <h2>Root cause</h2>
 * The accessors did not look at the closed state.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-vfs
 * mvn -pl vfs30 test -Dtest=ClosedVirtualFileAccessIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The content accessors of a closed archive or directory throw IllegalStateException; its name stays available. The
 * close of one adaptor of a directory does not close another adaptor of it.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class ClosedVirtualFileAccessIssueTestCase {

    @Test
    public void testClosedArchive() throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "closed-access-issue.jar");
        archive.addAsResource(new StringAsset("content"), "a/b.txt");
        File file = new File("target/closed-access-issue.jar");
        try (InputStream in = archive.as(ZipExporter.class).exportAsInputStream(); FileOutputStream out = new FileOutputStream(file)) {
            in.transferTo(out);
        }
        assertClosedAccess(AbstractVFS.toVirtualFile(file.toURI()), "closed-access-issue.jar");
    }

    @Test
    public void testClosedDirectory() throws Exception {
        File dir = new File("target/closed-access-issue-dir");
        new File(dir, "a").mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "a/b.txt"))) {
            out.write('x');
        }
        assertClosedAccess(AbstractVFS.toVirtualFile(dir.toURI()), "closed-access-issue-dir");
    }

    // Every user of a directory, e.g. a revision of a deployment of the server whose root another revision of the
    // same name used before, has an adaptor of its own: the close of one does not close the other
    @Test
    public void testCloseOfOneAdaptorOfDirectory() throws Exception {
        File dir = new File("target/closed-access-issue-shared");
        new File(dir, "a").mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "a/b.txt"))) {
            out.write('x');
        }
        VirtualFile first = AbstractVFS.toVirtualFile(dir.toURI());
        VirtualFile second = AbstractVFS.toVirtualFile(dir.toURI());
        first.close();
        try {
            assertEquals("The other adaptor of the directory stays open", "b.txt", second.getChild("a/b.txt").getName());
        } finally {
            second.close();
        }
    }

    private void assertClosedAccess(final VirtualFile root, String name) throws Exception {
        try {
            if (root.getChild("a/b.txt") == null)
                throw new IllegalStateException("Precondition: a/b.txt is found while the file is open");
        } finally {
            root.close();
        }
        assertEquals("The name of a closed file is available", name, root.getName());
        assertIllegalState("getChild", new Callable<Object>() {
            public Object call() throws Exception {
                return root.getChild("a/b.txt");
            }
        });
        assertIllegalState("getChildren", new Callable<Object>() {
            public Object call() throws Exception {
                return root.getChildren();
            }
        });
        assertIllegalState("isDirectory", new Callable<Object>() {
            public Object call() throws Exception {
                return root.isDirectory();
            }
        });
        assertIllegalState("isFile", new Callable<Object>() {
            public Object call() throws Exception {
                return root.isFile();
            }
        });
        assertIllegalState("getEntryPaths", new Callable<Object>() {
            public Object call() throws Exception {
                return root.getEntryPaths("");
            }
        });
        assertIllegalState("openStream", new Callable<Object>() {
            public Object call() throws Exception {
                InputStream input = root.openStream();
                VFSUtils.safeClose(input);
                return input;
            }
        });
    }

    private static void assertIllegalState(String method, Callable<Object> call) throws Exception {
        Object result;
        try {
            result = call.call();
        } catch (IllegalStateException expected) {
            return;
        }
        fail(method + " of a closed file must throw IllegalStateException, but it returned " + result);
    }
}
