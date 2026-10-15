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
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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
 * {@code VirtualFile.equals} of the jboss-vfs 3 adaptor is not reflexive: a virtual file is not equal to itself, and two
 * virtual files for the same entry are not equal although they have the same hash code.
 *
 * <h3>Severity</h3>
 * minor. {@code org.jboss.osgi.vfs.VirtualFile} is the public file abstraction of the OSGi layer (bundle roots,
 * {@code Deployment.getRoot()}, {@code BundleInfo.getRoot()}, {@code InvocationContext.getRoot()}). Every
 * {@code equals}-based collection operation on it is broken: {@code List.contains/indexOf/remove} do not find the very
 * object that was added, a {@code HashSet} keeps duplicates for the same entry, {@code Objects.equals(a, a)} is
 * {@code false}. The jbosgi framework itself currently only keeps such files in lists it does not search, so no
 * framework behaviour is known to depend on it; code in bundles or integration layers that uses the public type does.
 *
 * <h3>Component</h3>
 * jbosgi-vfs, vfs30, {@code org.jboss.osgi.vfs30.VirtualFileAdaptor30#equals(Object)}.
 *
 * <h3>Found by</h3>
 * subrepos-a workstream, code review of the VFS adaptor (API contract check against {@code java.lang.Object}), confirmed
 * with an exploratory test (a {@code List<VirtualFile>} does not contain the element it was given).
 *
 * <h3>Spec reference</h3>
 * {@code java.lang.Object#equals(Object)} (Java SE 25 API): "The equals method implements an equivalence relation on
 * non-null object references: It is reflexive: for any non-null reference value x, x.equals(x) should return true. It
 * is symmetric: for any non-null reference values x and y, x.equals(y) should return true if and only if y.equals(x)
 * returns true." and {@code Object#hashCode()}: equal objects must have equal hash codes (the adaptor already returns
 * the hash code of the underlying jboss-vfs file, so two adaptors of the same entry are meant to be equal).
 * https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Object.html#equals(java.lang.Object)
 *
 * <h3>Code location</h3>
 * {@code vfs30/src/main/java/org/jboss/osgi/vfs30/VirtualFileAdaptor30.java:325-333} on WF41-osgi-r8:
 * <pre>
 * public boolean equals(Object obj) {
 *     return vfsFile.equals(obj);
 * }
 * </pre>
 * Unchanged since the adaptor was written (2010, before 2e1dfa4); pre-existing, present on WF41.
 *
 * <h3>Root cause</h3>
 * The adaptor compares its wrapped {@code org.jboss.vfs.VirtualFile} with the other <em>adaptor</em>.
 * {@code org.jboss.vfs.VirtualFile.equals(Object)} returns {@code o instanceof org.jboss.vfs.VirtualFile && ...}, so the
 * result is always {@code false} for another {@code VirtualFileAdaptor30} (including {@code this}), and {@code true} for
 * a raw jboss-vfs file of the same path, which in turn is never equal to the adaptor (asymmetric).
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-vfs
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl vfs30 test -Dtest=VirtualFileEqualsIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * testReflexive: A virtual file must be equal to itself, but root.equals(root) is false
 * testSameEntryEqual: Two virtual files for the same entry a/b.txt (equal hash codes) must be equal, but they are not
 * testListContainsAddedFile: A List that the virtual file was added to must contain it, but list.contains(file) is false
 * testSymmetric: equals must be symmetric, but adaptor.equals(jbossVfsFile) is true while jbossVfsFile.equals(adaptor) is false
 * </pre>
 *
 * <h3>Expected</h3>
 * Two adaptors are equal if and only if their wrapped jboss-vfs files are equal; an adaptor is never equal to an object
 * that is not an {@code org.jboss.osgi.vfs.VirtualFile}.
 *
 * <h3>Reference behaviour</h3>
 * n/a (jboss-osgi specific API); {@code org.jboss.vfs.VirtualFile} itself implements the equivalence correctly.
 *
 * <h3>Suggested fix</h3>
 * <pre>
 * public boolean equals(Object obj) {
 *     if (obj == this) return true;
 *     if (!(obj instanceof VirtualFileAdaptor30)) return false;
 *     return vfsFile.equals(((VirtualFileAdaptor30) obj).vfsFile);
 * }
 * </pre>
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 */
public class VirtualFileEqualsIssueTestCase {

    private File file;
    private VirtualFile root;

    @Before
    public void setUp() throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "equals-issue.jar");
        archive.addAsResource(new StringAsset("content"), "a/b.txt");
        file = new File("target/equals-issue.jar");
        try (InputStream in = archive.as(ZipExporter.class).exportAsInputStream(); FileOutputStream out = new FileOutputStream(file)) {
            in.transferTo(out);
        }
        root = AbstractVFS.toVirtualFile(file.toURI());
    }

    @After
    public void tearDown() {
        VFSUtils.safeClose(root);
    }

    @Test
    public void testReflexive() throws Exception {
        assertTrue("A virtual file must be equal to itself, but root.equals(root) is false", root.equals(root));
    }

    @Test
    public void testSameEntryEqual() throws Exception {
        VirtualFile first = root.getChild("a/b.txt");
        VirtualFile second = root.getChild("a/b.txt");
        assertTrue("Precondition: both lookups return the same entry with the same hash code", first.getPathName().equals(second.getPathName()) && first.hashCode() == second.hashCode());
        assertTrue("Two virtual files for the same entry a/b.txt (equal hash codes) must be equal, but they are not", first.equals(second));
    }

    @Test
    public void testListContainsAddedFile() throws Exception {
        VirtualFile child = root.getChild("a/b.txt");
        List<VirtualFile> list = new ArrayList<VirtualFile>();
        list.add(child);
        assertTrue("A List that the virtual file was added to must contain it, but list.contains(file) is false", list.contains(child));
    }

    @Test
    public void testSymmetric() throws Exception {
        Object jbossVfsFile = AbstractVFS.adapt(root);
        boolean forward = root.equals(jbossVfsFile);
        boolean backward = jbossVfsFile.equals(root);
        assertFalse("equals must be symmetric, but adaptor.equals(jbossVfsFile) is " + forward + " while jbossVfsFile.equals(adaptor) is " + backward,
                forward != backward);
    }
}
