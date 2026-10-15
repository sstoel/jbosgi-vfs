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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.TreeSet;

import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;

/**
 * {@code VirtualFile.getEntryPaths} and {@code findEntries} resolve a path with ".." segments to a file above the
 * root and then fail with StringIndexOutOfBoundsException when they cut off the root path (or, for an archive on disk,
 * enumerate the directory that contains it) instead of returning null.
 *
 * The vfs30 part of the framework issue fuzz/24 (EntryPathAboveRootIssueTestCase in the framework itest).
 * Bundle.getEntryPaths (OSGi Core R8, 10.1.5.18) and Bundle.findEntries (10.1.5.13) return null when there is no
 * such entry and declare only IllegalStateException.
 *
 * Code location: {@code vfs30/src/main/java/org/jboss/osgi/vfs30/VFSEntryPathsEnumeration.java} (fixPath) and
 * {@code VirtualFileAdaptor30} (findEntries, getEntryPaths), which did not check that the resolved file is below the
 * root.
 */
public class EntryPathAboveRootIssueTestCase {

    @Test
    public void testGetEntryPathsAboveRoot() throws Exception {
        VirtualFile root = createStreamRoot();
        try {
            assertNull("getEntryPaths(\"a/../..\") must return null", root.getEntryPaths("a/../.."));
            assertNull("getEntryPaths(\"..\") must return null", root.getEntryPaths(".."));
        } catch (RuntimeException ex) {
            fail("getEntryPaths with a path above the root must return null, but threw " + ex);
        } finally {
            VFSUtils.safeClose(root);
        }
    }

    @Test
    public void testFindEntriesAboveRoot() throws Exception {
        VirtualFile root = createStreamRoot();
        try {
            assertNull("findEntries(\"..\", \"*\", false) must return null", root.findEntries("..", "*", false));
            assertNull("findEntries(\"a/../..\", \"*\", true) must return null", root.findEntries("a/../..", "*", true));
        } catch (RuntimeException ex) {
            fail("findEntries with a path above the root must return null, but threw " + ex);
        } finally {
            VFSUtils.safeClose(root);
        }
    }

    @Test
    public void testArchiveOnDiskDoesNotExposeItsDirectory() throws Exception {
        File file = new File("target/entry-above-root.jar");
        try (InputStream in = createArchive().as(ZipExporter.class).exportAsInputStream(); FileOutputStream out = new FileOutputStream(file)) {
            in.transferTo(out);
        }
        VirtualFile root = AbstractVFS.toVirtualFile(file.toURI());
        try {
            assertNull("findEntries(\"..\", \"*\", false) must not enumerate the directory of the archive", root.findEntries("..", "*", false));
            assertNull("getEntryPaths(\"..\") must not enumerate the directory of the archive", root.getEntryPaths(".."));
        } finally {
            VFSUtils.safeClose(root);
        }
    }

    @Test
    public void testPathBackToRoot() throws Exception {
        VirtualFile root = createStreamRoot();
        try {
            assertEquals("getEntryPaths(\"a/..\") is the root", "[a/]", getEntryPaths(root, "a/.."));
            assertEquals("getEntryPaths(\"a/../a\")", "[a/b.txt]", getEntryPaths(root, "a/../a"));
            Enumeration<URL> entries = root.findEntries("a/..", "b.txt", true);
            assertEquals("findEntries(\"a/..\", \"b.txt\", true) finds a/b.txt", "b.txt", AbstractVFS.toVirtualFile(entries.nextElement()).getName());
        } finally {
            VFSUtils.safeClose(root);
        }
    }

    private static String getEntryPaths(VirtualFile root, String path) throws Exception {
        TreeSet<String> result = new TreeSet<String>();
        Enumeration<String> paths = root.getEntryPaths(path);
        while (paths != null && paths.hasMoreElements())
            result.add(paths.nextElement());
        return result.toString();
    }

    private static VirtualFile createStreamRoot() throws Exception {
        InputStream input = createArchive().as(ZipExporter.class).exportAsInputStream();
        return AbstractVFS.toVirtualFile(input);
    }

    private static JavaArchive createArchive() {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "entry-above-root.jar");
        archive.addAsResource(new StringAsset("b"), "a/b.txt");
        return archive;
    }
}
