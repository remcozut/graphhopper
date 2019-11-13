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
package com.graphhopper.storage;

import com.graphhopper.util.PointAccess;

/**
 * This interface specifies how to access properties of the nodes in the graph. Similar to
 * EdgeExplorer as it needs multiple instances for different threads or loops but without the need
 * for an additional iterator.
 * <p>
 *
 * @author Peter Karich
 */
public interface NodeAccess extends PointAccess {
    /**
     * @return the additional value at the specified node index
     * @throws AssertionError if, and only if, the extendedStorage does not require an additional
     *                        node field
     */
    int getAdditionalNodeField(int nodeId);

    /**
     * Sets the additional value at the specified node index
     * <p>
     *
     * @throws AssertionError if, and only if, the extendedStorage does not require an additional
     *                        node field
     */
    void setAdditionalNodeField(int nodeId, int additionalValue);


    String getName(int nodeId);

    void setName(int nodeId, String name);


}
