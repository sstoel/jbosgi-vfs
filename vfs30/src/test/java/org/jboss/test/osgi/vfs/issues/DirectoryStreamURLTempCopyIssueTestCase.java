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

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

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
 * {@code VirtualFile.getStreamURL()} of a directory writes a temporary jar copy of the directory, which is only
 * deleted by {@code close()} of the virtual file or by the finalizer of jboss-vfs' TempDir. The framework opens the
 * stream URL for every connection of a bundle entry URL that names a directory, with a virtual file that nobody closes,
 * so the copies stay until a GC finalizes them (or until JVM exit with --finalization=disabled).
 *
 * The vfs30 part of the framework issue subrepos/14 (DirectoryEntryUrlTempCopyIssueTestCase in the framework itest).
 *
 * Code location: {@code vfs30/src/main/java/org/jboss/osgi/vfs30/VirtualFileAdaptor30.java} (getStreamURL created
 * a urlstream-* temp dir with the copy).
 */
public class DirectoryStreamURLTempCopyIssueTestCase {

    private VirtualFile root;

    @Before
    public void setUp() throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "dir-stream.jar");
        archive.addAsManifestResource(new StringAsset("Manifest-Version: 1.0\nBundle-SymbolicName: dir-stream\n\n"), "MANIFEST.MF");
        archive.addAsResource(new StringAsset("a"), "dir/a.txt");
        archive.addAsResource(new StringAsset("b"), "dir/sub/b.txt");
        InputStream input = archive.as(ZipExporter.class).exportAsInputStream();
        root = AbstractVFS.toVirtualFile("dir-stream-" + System.nanoTime(), input);
    }

    @After
    public void tearDown() {
        VFSUtils.safeClose(root);
    }

    @Test
    public void testDirectoryStreamWithoutTempCopy() throws Exception {
        int before = countStreamDirs(new File(System.getProperty("java.io.tmpdir"), "vfs"));
        for (int i = 0; i < 5; i++) {
            // Like the bundle URL handler of the framework: a new virtual file for every connection, never closed
            VirtualFile dir = root.getChild("dir");
            URL streamURL = dir.getStreamURL();
            try (JarInputStream jarIn = new JarInputStream(streamURL.openConnection().getInputStream())) {
                assertEquals("The jar stream of the directory", "{a.txt=a, sub/=, sub/b.txt=b}", readEntries(jarIn).toString());
            }
        }
        int after = countStreamDirs(new File(System.getProperty("java.io.tmpdir"), "vfs"));
        assertEquals("Opening the stream URL of a directory must not leave a temporary copy", before, after);
    }

    @Test
    public void testMountedRootStream() throws Exception {
        try (JarInputStream jarIn = new JarInputStream(root.openStream())) {
            Manifest manifest = jarIn.getManifest();
            assertEquals("The manifest of the root stream", "dir-stream", manifest.getMainAttributes().getValue("Bundle-SymbolicName"));
            assertEquals("The jar stream of the root", "{META-INF/=, dir/=, dir/a.txt=a, dir/sub/=, dir/sub/b.txt=b}", readEntries(jarIn).toString());
        }
        // The stream URL can be opened more than once
        try (JarInputStream jarIn = new JarInputStream(root.getStreamURL().openStream())) {
            assertEquals("The jar stream of the root", "{META-INF/=, dir/=, dir/a.txt=a, dir/sub/=, dir/sub/b.txt=b}", readEntries(jarIn).toString());
        }
    }

    @Test
    public void testFileStreamURL() throws Exception {
        VirtualFile file = root.getChild("dir/a.txt");
        try (InputStream in = file.getStreamURL().openStream()) {
            assertEquals("The stream of a file is its content", "a", new String(in.readAllBytes(), "UTF-8"));
        }
    }

    private static Map<String, String> readEntries(JarInputStream jarIn) throws Exception {
        Map<String, String> entries = new TreeMap<String, String>();
        JarEntry entry = jarIn.getNextJarEntry();
        while (entry != null) {
            entries.put(entry.getName(), new String(jarIn.readAllBytes(), "UTF-8"));
            entry = jarIn.getNextJarEntry();
        }
        return entries;
    }

    private static int countStreamDirs(File dir) {
        File[] files = dir.listFiles();
        if (files == null)
            return 0;
        int count = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                if (file.getName().startsWith("urlstream"))
                    count++;
                count += countStreamDirs(file);
            }
        }
        return count;
    }
}
