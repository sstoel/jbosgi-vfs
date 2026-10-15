/*
 * #%L
 * JBossOSGi VFS VFS30
 * %%
 * Copyright (C) 2010 - 2012 JBoss by Red Hat
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
/*
 * JBoss, Home of Professional Open Source
 * Copyright 2006, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.osgi.vfs30;

import static org.jboss.osgi.vfs.VFSLogger.LOGGER;
import static org.jboss.osgi.vfs.VFSMessages.MESSAGES;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.AccessController;
import java.security.CodeSigner;
import java.security.PrivilegedAction;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.vfs.TempDir;
import org.jboss.vfs.TempFileProvider;
import org.jboss.vfs.VFS;
import org.jboss.vfs.VFSUtils;

/**
 * An adaptor to the jboss-vfs-3.0.x VirtualFile.
 *
 * @author thomas.diesler@jboss.com
 * @since 02-Mar-2010
 */
class VirtualFileAdaptor30 implements VirtualFile {

    private final org.jboss.vfs.VirtualFile vfsFile;
    private IOException leakDebuggingStack;
    private Closeable mount;
    private boolean closed;

    private static boolean LEAK_DEBUGGING;
    static {
        if (System.getSecurityManager() != null) {
            LEAK_DEBUGGING = AccessController.doPrivileged(new PrivilegedAction<Boolean>() {
                public Boolean run() {
                    return "true".equals(System.getProperty(PROPERTY_VFS_LEAK_DEBUGGING));
                }
            });
        } else {
            LEAK_DEBUGGING = "true".equals(System.getProperty(PROPERTY_VFS_LEAK_DEBUGGING));
        }
    }

    private static final TempFileProvider tmpProvider;
    static {
        try {
            tmpProvider = TempFileProvider.create("osgitmp-", null);
        } catch (IOException ex) {
            throw MESSAGES.illegalStateCannotCreateTempFileProvider(ex);
        }

        Thread shutdownThread = new Thread("vfs-shutdown") {
            public void run() {
                try {
                    tmpProvider.close();
                } catch (IOException ex) {
                    throw MESSAGES.illegalStateCannotCloseTempFileProvider(ex);
                }
            }
        };
        Runtime.getRuntime().addShutdownHook(shutdownThread);
    }

    VirtualFileAdaptor30(String name, InputStream input) throws IOException {
        this(VFS.getChild(name));
        if (input == null)
            throw MESSAGES.illegalArgumentNull("input");
        mount = mountZip(input, vfsFile.getName(), vfsFile);
    }

    /**
     * Mount the archive with the {@link LenientZipFileSystem}: unlike the zip file system of jboss-vfs it keeps the
     * entries below a path that is also a file entry, as java.util.zip does
     */
    private static Closeable mountZip(InputStream zipData, String zipName, org.jboss.vfs.VirtualFile mountPoint) throws IOException {
        boolean ok = false;
        try {
            final TempDir tempDir = tmpProvider.createTempDir(zipName);
            try {
                final LenientZipFileSystem fileSystem = new LenientZipFileSystem(zipName, zipData, tempDir);
                try {
                    final Closeable mountHandle = VFS.mount(mountPoint, fileSystem);
                    ok = true;
                    return new Closeable() {
                        @Override
                        public void close() throws IOException {
                            VFSUtils.safeClose(mountHandle);
                            VFSUtils.safeClose(fileSystem);
                        }
                    };
                } finally {
                    if (!ok)
                        VFSUtils.safeClose(fileSystem);
                }
            } finally {
                if (!ok)
                    VFSUtils.safeClose(tempDir);
            }
        } finally {
            VFSUtils.safeClose(zipData);
        }
    }

    VirtualFileAdaptor30(org.jboss.vfs.VirtualFile vfsFile) {
        if (vfsFile == null)
            throw MESSAGES.illegalArgumentNull("file");
        this.vfsFile = vfsFile;
        if (LEAK_DEBUGGING == true)
            leakDebuggingStack = new IOException("VirtualFile created in this stack frame not closed: " + vfsFile);
    }

    @Override
    protected void finalize() throws Throwable {
        if (mount != null && leakDebuggingStack != null) {
            leakDebuggingStack.printStackTrace(System.err);
        }
    }

    public org.jboss.vfs.VirtualFile getVirtualFile() {
        return vfsFile;
    }

    public String getName() {
        return vfsFile.getName();
    }

    public String getPathName() {
        return vfsFile.getPathName();
    }

    public boolean isFile() throws IOException {
        return vfsFile.isFile();
    }

    public boolean isDirectory() throws IOException {
        return vfsFile.isDirectory();
    }

    @Override
    public URL toURL() throws IOException {
        URL url = vfsFile.toURL();
        return url;
    }

    @Override
    public URL getStreamURL() throws IOException {
        if (vfsFile.isFile() == true)
            return vfsFile.toURL();

        // A directory (or a mounted archive) is streamed as a jar
        return VirtualJarStreamHandler.toStreamURL(vfsFile);
    }

    @Override
    public VirtualFile getParent() {
        org.jboss.vfs.VirtualFile parent = vfsFile.getParent();
        return parent != null ? new VirtualFileAdaptor30(parent) : null;
    }

    @Override
    public VirtualFile getChild(String path) throws IOException {
        org.jboss.vfs.VirtualFile child = getMountedChild(path);
        boolean exists = child != null && child.exists();
        LOGGER.tracef("getChild: %s => %s (%s)", path, child, exists);
        return exists ? new VirtualFileAdaptor30(child) : null;
    }

    @Override
    public List<VirtualFile> getChildrenRecursively() throws IOException {
        List<VirtualFile> files = new ArrayList<VirtualFile>();
        for (org.jboss.vfs.VirtualFile child : getMountedChildrenRecursively())
            files.add(new VirtualFileAdaptor30(child));
        return Collections.unmodifiableList(files);
    }

    @Override
    public List<VirtualFile> getChildren() throws IOException {
        List<VirtualFile> files = new ArrayList<VirtualFile>();
        for (org.jboss.vfs.VirtualFile child : getMountedChildren())
            files.add(new VirtualFileAdaptor30(child));
        return Collections.unmodifiableList(files);
    }

    @Override
    public Enumeration<URL> findEntries(String path, String pattern, boolean recurse) throws IOException {
        if (path == null)
            throw MESSAGES.illegalArgumentNull("path");

        if (pattern == null)
            pattern = "*";

        if (path.startsWith("/"))
            path = path.substring(1);

        org.jboss.vfs.VirtualFile child = getMountedChild(path);
        if (isSelfOrDescendant(child) == false || child.exists() == false)
            return null;

        return new VFSFindEntriesEnumeration(vfsFile, child, pattern, recurse);
    }

    @Override
    public Enumeration<String> getEntryPaths(String path) throws IOException {
        if (path == null)
            throw MESSAGES.illegalArgumentNull("path");

        if (path.startsWith("/"))
            path = path.substring(1);

        org.jboss.vfs.VirtualFile child;
        if (path.length() > 0) {
            child = getMountedChild(path);
        } else {
            ensureMounted();
            child = vfsFile;
        }

        if (isSelfOrDescendant(child) == false || child.exists() == false)
            return null;

        return new VFSEntryPathsEnumeration(vfsFile, child);
    }

    @Override
    public InputStream openStream() throws IOException {
        if (mount != null)
            return getStreamURL().openStream();

        return vfsFile.openStream();
    }

    @Override
    public Certificate[] getCertificates() {
        return vfsFile.getCertificates();
    }

    @Override
    public CodeSigner[] getCodeSigners() {
        return vfsFile.getCodeSigners();
    }

    @Override
    public void close() {
        synchronized (this) {
            VFSUtils.safeClose(mount);
            leakDebuggingStack = null;
            mount = null;
            closed = true;
            VFSAdaptor30.unregister(this);
        }
    }

    // A path with ".." segments can lead to a file above this one
    private boolean isSelfOrDescendant(org.jboss.vfs.VirtualFile file) {
        return file.getParentFileList().contains(vfsFile);
    }

    private boolean acceptForMount() {
        boolean accept = !vfsFile.isDirectory();
        LOGGER.tracef("acceptForMount: %s => %s", vfsFile, accept);
        return accept;
    }

    private org.jboss.vfs.VirtualFile getMountedChild(String path) throws IOException {
        ensureMounted();
        return vfsFile.getChild(path);
    }

    private List<org.jboss.vfs.VirtualFile> getMountedChildren() throws IOException {
        ensureMounted();
        return vfsFile.getChildren();
    }

    private List<org.jboss.vfs.VirtualFile> getMountedChildrenRecursively() throws IOException {
        ensureMounted();
        return vfsFile.getChildrenRecursively();
    }

    private void ensureMounted() throws IOException {
        synchronized (this) {
            if (mount == null && acceptForMount()) {
                // A closed file must not mount its archive again, nobody would close that mount
                if (closed)
                    throw MESSAGES.illegalStateVirtualFileClosed(vfsFile);
                mount = mountZip(vfsFile.openStream(), vfsFile.getName(), vfsFile);
                VFSAdaptor30.mounted(this);
            }
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;
        if (!(obj instanceof VirtualFileAdaptor30))
            return false;
        VirtualFileAdaptor30 other = (VirtualFileAdaptor30) obj;
        return vfsFile.equals(other.vfsFile);
    }

    @Override
    public int hashCode() {
        return vfsFile.hashCode();
    }

    @Override
    public String toString() {
        return vfsFile.toString();
    }
}
