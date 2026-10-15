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

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;

/**
 * Virtual files obtained with {@code AbstractVFS.toVirtualFile(URL/URI)} are never garbage collected: the adaptor
 * registry is a {@code WeakHashMap} whose values strongly reference their keys, so every entry stays forever. The
 * framework calls {@code toVirtualFile(URL)} for every URL that {@code Bundle.findEntries} returns, so each bundle
 * installed and searched leaks one registry entry per found entry, also after it is uninstalled and refreshed.
 *
 * <h3>Severity</h3>
 * minor (unbounded memory leak, small per entry). Per leaked entry the JVM keeps a {@code VirtualFileAdaptor30}, a
 * {@code WeakHashMap} entry and the jboss-vfs {@code VirtualFile} with its parent chain and name strings. Measured with
 * jbosgi-framework on JDK 25 (exploratory test, 30 install/start/findEntries("/", "*", true)/uninstall/refresh cycles
 * of small bundles): +7 entries per cycle for a 7-entry bundle, +8 with a nested Bundle-ClassPath jar, +10/+12 for
 * multi-release variants; without findEntries +0. Bundle-scanning extenders (Declarative Services, Blueprint, the WAB
 * and JPA extenders) call findEntries for every bundle, so every redeploy in a long-running server adds to it.
 *
 * <h3>Component</h3>
 * jbosgi-vfs, vfs30, {@code org.jboss.osgi.vfs30.VFSAdaptor30} (the static {@code registry} used by
 * {@code toVirtualFile(URI/URL)} and {@code adapt(Object)}). Caller in the framework:
 * jbosgi-framework core {@code RevisionContent.getBundleURLs} (findEntries).
 *
 * <h3>Found by</h3>
 * subrepos-a workstream: code review of the VFS adaptor, then an exploratory framework test that counted the registry
 * size across install/uninstall cycles of plain, nested-jar and multi-release bundles (fds and temp files stayed flat,
 * the registry grew only with findEntries).
 *
 * <h3>Spec reference</h3>
 * {@code java.util.WeakHashMap} (Java SE 25 API), "Implementation note": "The value objects in a WeakHashMap are held
 * by ordinary strong references. Thus care should be taken to ensure that value objects do not strongly refer to their
 * own keys, either directly or indirectly, since that will prevent the keys from being discarded."
 * https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/WeakHashMap.html . The registry was made a
 * WeakHashMap in jbosgi-vfs commit 3dd264a (2010) with the message "Use WeakHashMap to allow garbage collection of the
 * VirtualFiles", which is exactly what does not happen.
 *
 * <h3>Code location</h3>
 * {@code vfs30/src/main/java/org/jboss/osgi/vfs30/VFSAdaptor30.java:68}
 * {@code registry = new WeakHashMap<org.jboss.vfs.VirtualFile, VirtualFile>()} and {@code :104-113} (adapt: the value is
 * {@code new VirtualFileAdaptor30(vfsFile)}, whose final field {@code vfsFile} is the key);
 * entries are only removed by {@code VirtualFileAdaptor30.close()} ({@code VirtualFileAdaptor30.java:286}).
 * Framework caller: jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/RevisionContent.java:212}
 * ({@code AbstractVFS.toVirtualFile(urls.nextElement())}, never closed). Pre-existing (3dd264a, 2010), present on WF41.
 *
 * <h3>Root cause</h3>
 * A WeakHashMap entry is only expunged when its key becomes weakly reachable; the value (the adaptor) holds the key
 * strongly and the map holds the value strongly, so the key never becomes weakly reachable. Only an explicit
 * {@code close()} removes the entry, and nothing closes the virtual files that are created for findEntries URLs (or by
 * other callers that just want a URL's content, such as {@code BundleInfo.createBundleInfo(URL)}).
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-vfs
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl vfs30 test -Dtest=VirtualFileRegistryLeakIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * testFindEntriesVirtualFilesCollectable: Virtual files obtained for findEntries URLs must become garbage collectable once
 *   the caller drops them and the root is closed, but 3 of 3 are still reachable after 20 GC rounds (VFSAdaptor30.registry
 *   keeps them) expected:&lt;0&gt; but was:&lt;3&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * An adaptor that nobody references any more (and whose root was closed) can be collected; the registry does not
 * grow with every distinct path ever looked up.
 *
 * <h3>Reference behaviour</h3>
 * n/a (jboss-osgi specific API).
 *
 * <h3>Suggested fix</h3>
 * Do not let the value reach the key: store {@code new WeakReference<VirtualFile>(adaptor)} as the value (and re-create
 * the adaptor when the reference was cleared), or drop the registry entirely for adaptors without a mount (only mounted
 * roots need a shared owner). Independently, RevisionContent.getBundleURLs need not create registered adaptors: it can
 * resolve the entry relative to its own root ({@code virtualFile.getChild(path)}).
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 */
public class VirtualFileRegistryLeakIssueTestCase {

    @Test
    public void testFindEntriesVirtualFilesCollectable() throws Exception {
        List<WeakReference<VirtualFile>> refs = createAndDropFindEntriesFiles();

        int alive = refs.size();
        for (int i = 0; i < 20 && alive > 0; i++) {
            System.gc();
            byte[][] pressure = new byte[64][];
            for (int j = 0; j < pressure.length; j++)
                pressure[j] = new byte[64 * 1024];
            Thread.sleep(50);
            alive = 0;
            for (WeakReference<VirtualFile> ref : refs) {
                if (ref.get() != null)
                    alive++;
            }
        }
        assertEquals("Virtual files obtained for findEntries URLs must become garbage collectable once the caller drops them and the root is closed, but "
                + alive + " of " + refs.size() + " are still reachable after 20 GC rounds (VFSAdaptor30.registry keeps them)", 0, alive);
    }

    // Same calls as jbosgi-framework RevisionContent.findEntries/getBundleURLs; nothing but weak references survive this method
    private static List<WeakReference<VirtualFile>> createAndDropFindEntriesFiles() throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "registry-leak.jar");
        archive.addAsResource(new StringAsset("a"), "dir/a.txt");
        archive.addAsResource(new StringAsset("b"), "dir/b.txt");
        List<WeakReference<VirtualFile>> refs = new ArrayList<WeakReference<VirtualFile>>();
        InputStream input = archive.as(ZipExporter.class).exportAsInputStream();
        VirtualFile root = AbstractVFS.toVirtualFile("registry-leak-" + System.nanoTime(), input);
        try {
            Enumeration<URL> urls = root.findEntries("/", "*", true);
            while (urls.hasMoreElements()) {
                VirtualFile child = AbstractVFS.toVirtualFile(urls.nextElement());
                child.getPathName();
                refs.add(new WeakReference<VirtualFile>(child));
            }
        } finally {
            root.close();
        }
        if (refs.size() < 3)
            throw new IllegalStateException("Precondition: findEntries returns at least dir/, dir/a.txt and dir/b.txt, but returned " + refs.size() + " entries");
        return refs;
    }
}
