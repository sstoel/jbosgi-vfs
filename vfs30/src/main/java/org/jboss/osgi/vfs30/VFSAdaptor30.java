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

import static org.jboss.osgi.vfs.VFSMessages.MESSAGES;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.osgi.vfs.VFSAdaptor;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.vfs.VFS;
import org.jboss.vfs.VFSUtils;

/**
 * An adaptor to the jboss-vfs-3.0.x VFS.
 *
 * @author thomas.diesler@jboss.com
 * @since 02-Mar-2010
 */
public final class VFSAdaptor30 implements VFSAdaptor {

    // The adaptors of the jboss-vfs files. The value must not reference its key strongly, otherwise the entry is never
    // expunged: an adaptor that nobody uses any more can be collected, unless it owns a mount that was not closed yet
    private static final Map<org.jboss.vfs.VirtualFile, WeakReference<VirtualFileAdaptor30>> registry = new WeakHashMap<org.jboss.vfs.VirtualFile, WeakReference<VirtualFileAdaptor30>>();
    private static final Set<VirtualFileAdaptor30> mountOwners = Collections.newSetFromMap(new IdentityHashMap<VirtualFileAdaptor30, Boolean>());
    // Makes the mount point of a stream unique, also for streams that are mounted within the same millisecond
    private static final AtomicLong streamCount = new AtomicLong();

    @Override
    public VirtualFile toVirtualFile(URL url) throws IOException {
        try {
            return toVirtualFile(VFSUtils.toURI(url));
        } catch (URISyntaxException ex) {
            throw new IOException(ex);
        }
    }

    @Override
    public VirtualFile toVirtualFile(URI uri) throws IOException {
        org.jboss.vfs.VirtualFile vfsFile = VFS.getChild(uri);
        return (VirtualFileAdaptor30) adapt(vfsFile);
    }

    @Override
    public VirtualFile toVirtualFile(String name, InputStream inputStream) throws IOException {
        return new VirtualFileAdaptor30(name, inputStream);
    }

    @Override
    public VirtualFile toVirtualFile(InputStream inputStream) throws IOException {
        String name = "stream" + System.currentTimeMillis() + "-" + streamCount.incrementAndGet();
        return new VirtualFileAdaptor30(name, inputStream);
    }

    @Override
    public VirtualFile adapt(Object other) {
        if (other == null)
            return null;

        if (other instanceof org.jboss.vfs.VirtualFile == false)
            throw MESSAGES.illegalArgumentNoVirtualFile(other);

        VirtualFileAdaptor30 absFile;
        synchronized (registry) {
            org.jboss.vfs.VirtualFile vfsFile = (org.jboss.vfs.VirtualFile) other;
            absFile = getRegistered(vfsFile);
            if (absFile == null) {
                // Register the VirtualFile abstraction, the entry of a collected adaptor must not keep its old key
                absFile = new VirtualFileAdaptor30(vfsFile);
                registry.remove(vfsFile);
                registry.put(vfsFile, new WeakReference<VirtualFileAdaptor30>(absFile));
            }
        }
        return absFile;
    }

    @Override
    public Object adapt(VirtualFile absFile) {
        if (absFile == null)
            return null;

        VirtualFileAdaptor30 adaptor = (VirtualFileAdaptor30) absFile;
        return adaptor.getVirtualFile();
    }

    // A registered adaptor that mounted its archive stays registered until it is closed
    static void mounted(VirtualFileAdaptor30 absFile) {
        synchronized (registry) {
            if (getRegistered(absFile.getVirtualFile()) == absFile)
                mountOwners.add(absFile);
        }
    }

    static void unregister(VirtualFileAdaptor30 absFile) {
        synchronized (registry) {
            mountOwners.remove(absFile);
            VirtualFileAdaptor30 registered = getRegistered(absFile.getVirtualFile());
            if (registered == null || registered == absFile)
                registry.remove(absFile.getVirtualFile());
        }
    }

    private static VirtualFileAdaptor30 getRegistered(org.jboss.vfs.VirtualFile vfsFile) {
        WeakReference<VirtualFileAdaptor30> ref = registry.get(vfsFile);
        return ref != null ? ref.get() : null;
    }
}