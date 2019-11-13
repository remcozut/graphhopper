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
package com.graphhopper.osmidexample;

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
 * @author Remco Zut
 */
public class JunctionIndex implements Storable<JunctionIndex> {
    private static final Logger logger = LoggerFactory.getLogger(JunctionIndex.class);
    private static final long START_POINTER = 1;
    private final DataAccess junctions;
    private long bytePointer = START_POINTER;
    // minor optimization for the previous stored name
    private String lastName;
    private long lastIndex;

    public JunctionIndex(Directory dir) {
        junctions = dir.find("junctions");
    }

    @Override
    public JunctionIndex create(long initBytes) {
        junctions.create(initBytes);
        return this;
    }

    @Override
    public boolean loadExisting() {
        if (junctions.loadExisting()) {
            bytePointer = BitUtil.LITTLE.combineIntsToLong(junctions.getHeader(0), junctions.getHeader(4));
            return true;
        }

        return false;
    }

    /**
     * @return the byte pointer to the name
     */
    public long put(String name) {
        if (name == null || name.isEmpty()) {
            return 0;
        }
        if (name.equals(lastName)) {
            return lastIndex;
        }
        byte[] bytes = getBytes(name);
        long oldPointer = bytePointer;
        junctions.ensureCapacity(bytePointer + 1 + bytes.length);
        byte[] sizeBytes = new byte[]{
                (byte) bytes.length
        };
        junctions.setBytes(bytePointer, sizeBytes, sizeBytes.length);
        bytePointer++;
        junctions.setBytes(bytePointer, bytes, bytes.length);
        bytePointer += bytes.length;
        lastName = name;
        lastIndex = oldPointer;
        return oldPointer;
    }

    private byte[] getBytes(String junction) {
        byte[] bytes = null;
        for (int i = 0; i < 2; i++) {
            bytes = junction.getBytes(Helper.UTF_CS);
            // we have to store the size of the array into *one* byte
            if (bytes.length > 255) {
                String newName = junction.substring(0, 256 / 4);
                logger.info("Junction name is too long: " + junction + " truncated to " + newName);
                junction = newName;
                continue;
            }
            break;
        }
        if (bytes.length > 255) {
            // really make sure no such problem exists
            throw new IllegalStateException("Junction name is too long: " + junction);
        }
        return bytes;
    }

    public String get(long pointer) {
        if (pointer < 0)
            throw new IllegalStateException("Pointer to access JunctionIndex cannot be negative:" + pointer);

        // default
        if (pointer == 0)
            return "";

        byte[] sizeBytes = new byte[1];
        junctions.getBytes(pointer, sizeBytes, 1);
        int size = sizeBytes[0] & 0xFF;
        byte[] bytes = new byte[size];
        junctions.getBytes(pointer + sizeBytes.length, bytes, size);
        return new String(bytes, Helper.UTF_CS);
    }

    @Override
    public void flush() {
        junctions.setHeader(0, BitUtil.LITTLE.getIntLow(bytePointer));
        junctions.setHeader(4, BitUtil.LITTLE.getIntHigh(bytePointer));
        junctions.flush();
    }

    @Override
    public void close() {
        junctions.close();
    }

    @Override
    public boolean isClosed() {
        return junctions.isClosed();
    }

    public void setSegmentSize(int segments) {
        junctions.setSegmentSize(segments);
    }

    @Override
    public long getCapacity() {
        return junctions.getCapacity();
    }

    public void copyTo(JunctionIndex junctionIndex) {
        junctions.copyTo(junctionIndex.junctions);
    }
}
