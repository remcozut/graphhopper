/*
 *  Licensed to GraphHopper and Peter Karich under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper licenses this file to you under the Apache License,
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

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.reader.DataReader;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.osm.OSMReader;
import com.graphhopper.routing.Path;
import com.graphhopper.storage.DataAccess;
import com.graphhopper.storage.Directory;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint3D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 *
 * @author Peter Karich
 */
public class MyGraphHopper extends GraphHopper {

    // mapping of internal edge ID to OSM way ID
    private DataAccess nodeMapping;
    private BitUtil bitUtil;

    private MyOsmNodeReader nodeReader;

    private Logger logger = LoggerFactory.getLogger(MyGraphHopper.class);

    public MyGraphHopper() {

    }


    @Override
    public boolean load(String graphHopperFolder) {



        boolean loaded = super.load(graphHopperFolder);

        nodeReader = new MyOsmNodeReader();
        nodeReader.setFile(new File(getDataReaderFile()));
        try {
            nodeReader.readGraph();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        Directory dir = getGraphHopperStorage().getDirectory();
        bitUtil = BitUtil.get(dir.getByteOrder());
        nodeMapping = dir.find("node_mapping");

        if (loaded) {
            nodeMapping.loadExisting();
        }

        return loaded;
    }

    @Override
    protected DataReader createReader(GraphHopperStorage ghStorage) {
        OSMReader reader = new OSMReader(ghStorage) {

            {
                nodeMapping.create(1000);
            }

            // this method is only in >0.6 protected, before it was private
            @Override
            protected void storeOsmWayID(int edgeId, long osmWayId) {
                super.storeOsmWayID(edgeId, osmWayId);
            }

            @Override
            protected void storeOsmNodeID(int nodeId, long osmNodeId) {
                if (nodeId >= 0) {
                    super.storeOsmNodeID(nodeId, osmNodeId);

                    long pointer = 8L * nodeId;
                    nodeMapping.ensureCapacity(pointer + 8L);

                    nodeMapping.setInt(pointer, bitUtil.getIntLow(osmNodeId));
                    nodeMapping.setInt(pointer + 4, bitUtil.getIntHigh(osmNodeId));
                }
            }

            @Override
            protected void finishedReading() {
                super.finishedReading();

                nodeMapping.flush();
            }
        };

        return initDataReader(reader);
    }

    public long getOSMNode(int internalNodeId) {
        long pointer = 8L * internalNodeId;
        return bitUtil.combineIntsToLong(nodeMapping.getInt(pointer), nodeMapping.getInt(pointer + 4L));
    }

    @Override
    public List<Path> calcPaths(GHRequest request, GHResponse rsp) {
        List<Path> paths = super.calcPaths(request, rsp);


  /*      if ("ncnbike".equalsIgnoreCase(request.getVehicle())) {
            InstructionList instructions = rsp.getBest().getInstructions();
            Map<Integer, Instruction> extraInstructions = new TreeMap<>(); // use treemap eq. sorted map
            for (int i = 0; i < instructions.size(); i++) {
                Instruction instruction = instructions.get(i);
                int nodeIdx = 0;
                for (Integer nodeId : instruction.getNodes().keySet()) {
                    String cyclingNetworkTag = nodeReader.getCyclingNetworkNode(getOSMNode(nodeId));
                    if (cyclingNetworkTag != null) {

                        InstructionAnnotation ea = InstructionAnnotation.EMPTY;
                        Instruction newInstruction = new Instruction(Instruction.JUNCTION, String.valueOf(cyclingNetworkTag), ea, PointList.EMPTY);
                        newInstruction.setName(cyclingNetworkTag);
                        if (nodeIdx == 0 || nodeIdx == instruction.getNodes().size()-1) {
                            newInstruction.setExtraInfo("node", "tower");
                            extraInstructions.put(i, newInstruction);
                        } else {
                            newInstruction.setExtraInfo("node", "pillar");
                            extraInstructions.put(i+1, newInstruction);
                        }
                        newInstruction.setExtraInfo("nodeIdx", nodeIdx++);
                        StringBuilder sb = new StringBuilder("LINESTRING (");
                        for (Map.Entry<Integer, double[]> integerEntry : instruction.getNodes().entrySet()) {
                            sb.append(integerEntry.getValue()[1]).append(" ").append(integerEntry.getValue()[0]).append(", ");
                        }
                        sb.delete(sb.length()-2, sb.length()-1);
                        sb.append(")");
                        newInstruction.setExtraInfo("nodes", sb.toString());
                        newInstruction.setExtraInfo("points", instruction.getPoints().toLineString(false).toText());
                        newInstruction.setExtraInfo("osmId", getOSMNode(nodeId));
                    }
                }
            }

            List<Integer> keys = new ArrayList<Integer>(extraInstructions.keySet());
            for (int i = keys.size() - 1; i >= 0; i--) {
                instructions.add(keys.get(i), extraInstructions.get(keys.get(i)));
            }

            for (Instruction instruction : instructions) {
                logger.info(instruction.toString());
            }

        }
*/



        return paths;
    }
}
