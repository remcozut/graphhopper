/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.search;

import com.graphhopper.storage.DataAccess;
import com.graphhopper.storage.Directory;
import com.graphhopper.storage.Storable;
import com.graphhopper.util.BitUtil;
import com.graphhopper.util.Helper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Ottavio Campana
 * @author Peter Karich
 */
public class NodeNameIndex implements Storable<NodeNameIndex> {
    private static final Logger logger = LoggerFactory.getLogger(NodeNameIndex.class);
    private static final long START_POINTER = 1;
    private final DataAccess references;
    private long bytePointer = START_POINTER;
    // minor optimization for the previous stored name
    private String lastReference;
    private long lastIndex;

    public NodeNameIndex(Directory dir) {
        references = dir.find("node_names");
    }

    @Override
    public NodeNameIndex create(long initBytes) {
        references.create(initBytes);
        return this;
    }

    @Override
    public boolean loadExisting() {
        if (references.loadExisting()) {
            bytePointer = BitUtil.LITTLE.combineIntsToLong(references.getHeader(0), references.getHeader(4));
            return true;
        }

        return false;
    }

    /**
     * @return the byte pointer to the reference
     */
    public long put(String reference) {
        if (reference == null || reference.isEmpty()) {
            return 0;
        }
        if (reference.equals(lastReference)) {
            return lastIndex;
        }
        byte[] bytes = getBytes(reference);
        long oldPointer = bytePointer;
        references.ensureCapacity(bytePointer + 1 + bytes.length);
        byte[] sizeBytes = new byte[]{
                (byte) bytes.length
        };
        references.setBytes(bytePointer, sizeBytes, sizeBytes.length);
        bytePointer++;
        references.setBytes(bytePointer, bytes, bytes.length);
        bytePointer += bytes.length;
        lastReference = reference;
        lastIndex = oldPointer;
        return oldPointer;
    }

    private byte[] getBytes(String name) {
        byte[] bytes = null;
        for (int i = 0; i < 2; i++) {
            bytes = name.getBytes(Helper.UTF_CS);
            // we have to store the size of the array into *one* byte
            if (bytes.length > 255) {
                String newName = name.substring(0, 256 / 4);
                logger.info("Noderef name is too long: " + name + " truncated to " + newName);
                name = newName;
                continue;
            }
            break;
        }
        if (bytes.length > 255) {
            // really make sure no such problem exists
            throw new IllegalStateException("Noderef name is too long: " + name);
        }
        return bytes;
    }

    public String get(long pointer) {
        if (pointer < 0)
            throw new IllegalStateException("Pointer to access NodeNameIndex cannot be negative:" + pointer);

        // default
        if (pointer == 0)
            return "";

        byte[] sizeBytes = new byte[1];
        references.getBytes(pointer, sizeBytes, 1);
        int size = sizeBytes[0] & 0xFF;
        byte[] bytes = new byte[size];
        references.getBytes(pointer + sizeBytes.length, bytes, size);
        return new String(bytes, Helper.UTF_CS);
    }

    @Override
    public void flush() {
        references.setHeader(0, BitUtil.LITTLE.getIntLow(bytePointer));
        references.setHeader(4, BitUtil.LITTLE.getIntHigh(bytePointer));
        references.flush();
    }

    @Override
    public void close() {
        references.close();
    }

    @Override
    public boolean isClosed() {
        return references.isClosed();
    }

    public void setSegmentSize(int segments) {
        references.setSegmentSize(segments);
    }

    @Override
    public long getCapacity() {
        return references.getCapacity();
    }

    public void copyTo(NodeNameIndex nameIndex) {
        references.copyTo(nameIndex.references);
    }
}
