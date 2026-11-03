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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;

import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.vfs.VFS;
import org.junit.Test;

/**
 * An adaptor of an archive that another adaptor mounted reads through that mount, but does not take part in it: when
 * the first adaptor is closed, the archive is unmounted under the second, whose vfs: URLs fail until it mounts the
 * archive again on its next access. Two adaptors that mount the archive again at the same time collide on the mount.
 *
 * <h2>Severity</h2>
 * minor. Seen as a failed resource URL of a bundle whose archive another revision or caller closed.
 *
 * <h2>Component</h2>
 * jbosgi-vfs, vfs30: {@code VFSAdaptor30.adapt}, {@code VirtualFileAdaptor30.ensureMounted}.
 *
 * <h2>Found by</h2>
 * R8 release check, sup-check F16.
 *
 * <h2>Spec reference</h2>
 * none; the close contract of {@code org.jboss.osgi.vfs.VirtualFile}: the close of one adaptor does not close another
 * (subrepos/03).
 *
 * <h2>Code location</h2>
 * vfs30/src/main/java/org/jboss/osgi/vfs30/VFSAdaptor30.java:118 (6d8006c): an archive that is mounted is a directory, so
 * a second adaptor of it is a new, unregistered adaptor without a share in the mount.
 *
 * <h2>Root cause</h2>
 * The mount belongs to the adaptor that made it, not to the adaptors that use it.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-vfs
 * mvn -pl vfs30 test -Dtest=SharedArchiveMountIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The archive stays mounted until the last adaptor that uses the mount is closed, then it is unmounted.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class SharedArchiveMountIssueTestCase {

    @Test
    public void testCloseOfMountingAdaptor() throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "shared-mount-issue.jar");
        archive.addAsResource(new StringAsset("content"), "a/b.txt");
        File file = new File("target/shared-mount-issue.jar").getCanonicalFile();
        try (InputStream in = archive.as(ZipExporter.class).exportAsInputStream(); FileOutputStream out = new FileOutputStream(file)) {
            in.transferTo(out);
        }

        VirtualFile first = AbstractVFS.toVirtualFile(file.toURI());
        VirtualFile second;
        URL url;
        try {
            assertNotNull("Precondition: the first adaptor mounts the archive", first.getChild("a/b.txt"));
            second = AbstractVFS.toVirtualFile(file.toURI());
            VirtualFile child = second.getChild("a/b.txt");
            assertNotNull("The second adaptor reads the mounted archive", child);
            url = child.toURL();
        } finally {
            first.close();
        }
        try {
            assertEquals("The URL of the second adaptor reads the content after the first adaptor is closed", "content", read(url));
            assertEquals("The second adaptor reads the content after the first adaptor is closed", "content", read(second.getChild("a/b.txt").toURL()));
        } finally {
            second.close();
        }
        assertFalse("The archive is unmounted when the last adaptor is closed", VFS.getChild(file.toURI()).isDirectory());
    }

    private static String read(URL url) throws Exception {
        try (InputStream in = url.openStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString("UTF-8");
        }
    }
}
