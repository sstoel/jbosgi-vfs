/*
 * #%L
 * JBossOSGi VFS VFS30
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
package org.jboss.osgi.vfs30;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Objects;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.jboss.vfs.VirtualFile;
import org.jboss.vfs.VirtualJarInputStream;

/**
 * A URL stream handler that streams the content of a directory as a jar.
 *
 * The jar is produced while it is read, so that no temporary copy of the directory is needed (that nobody would delete).
 *
 * @since 15-Oct-2026
 */
final class VirtualJarStreamHandler extends URLStreamHandler {

    static final String PROTOCOL = "vfsjar";
    static final String CONTENT_TYPE = "application/java-archive";

    private final VirtualFile directory;

    private VirtualJarStreamHandler(VirtualFile directory) {
        this.directory = directory;
    }

    /**
     * The URL of the jar stream of the given directory
     */
    @SuppressWarnings("deprecation")
    static URL toStreamURL(VirtualFile directory) throws IOException {
        return new URL(PROTOCOL, null, -1, directory.getPathName(), new VirtualJarStreamHandler(directory));
    }

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        return new URLConnection(url) {

            @Override
            public void connect() throws IOException {
                connected = true;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                connect();
                if (directory.isDirectory() == false)
                    return directory.openStream();
                return new JarInputStreamCopy(new VirtualJarInputStream(directory));
            }

            @Override
            public String getContentType() {
                return CONTENT_TYPE;
            }

            @Override
            public long getLastModified() {
                return directory.getLastModified();
            }
        };
    }

    /**
     * The jar of the entries of a {@link VirtualJarInputStream}, written in parts whenever the reader has consumed the
     * previous part.
     */
    static final class JarInputStreamCopy extends InputStream {

        private final VirtualJarInputStream jarIn;
        private final Buffer buffer = new Buffer();
        private final JarOutputStream jarOut;
        private final byte[] chunk = new byte[8192];
        private int position;
        private boolean entryOpen;
        private boolean finished;

        JarInputStreamCopy(VirtualJarInputStream jarIn) throws IOException {
            this.jarIn = jarIn;
            this.jarOut = new JarOutputStream(buffer);
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : single[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0)
                return 0;

            while (position == buffer.size()) {
                if (finished)
                    return -1;
                buffer.reset();
                position = 0;
                writeNextPart();
            }

            int count = Math.min(length, buffer.size() - position);
            System.arraycopy(buffer.bytes(), position, bytes, offset, count);
            position += count;
            return count;
        }

        @Override
        public int available() {
            return buffer.size() - position;
        }

        @Override
        public void close() throws IOException {
            jarIn.closeEntry();
            jarIn.close();
        }

        // Write the next entry header, the next chunk of the entry content or the end of the jar to the buffer
        private void writeNextPart() throws IOException {
            if (entryOpen) {
                int count = jarIn.read(chunk, 0, chunk.length);
                if (count < 0) {
                    jarOut.closeEntry();
                    entryOpen = false;
                } else {
                    jarOut.write(chunk, 0, count);
                }
            } else {
                ZipEntry nextEntry = jarIn.getNextEntry();
                if (nextEntry == null) {
                    jarOut.close();
                    finished = true;
                } else {
                    jarOut.putNextEntry(nextEntry);
                    entryOpen = true;
                }
            }
        }
    }

    private static final class Buffer extends ByteArrayOutputStream {
        byte[] bytes() {
            return buf;
        }
    }
}
