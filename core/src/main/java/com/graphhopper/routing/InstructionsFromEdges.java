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
package com.graphhopper.routing;

import com.graphhopper.routing.profiles.BooleanEncodedValue;
import com.graphhopper.routing.profiles.IntEncodedValue;
import com.graphhopper.routing.util.*;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.*;
import com.graphhopper.util.TurnType;
import com.graphhopper.util.shapes.GHPoint;

import java.util.logging.Logger;

/**
 * This class calculates instructions from the edges in a Path.
 *
 * @author Peter Karich
 * @author Robin Boldt
 * @author jan soe
 */
public class InstructionsFromEdges implements Path.EdgeVisitor {
    private static Logger logger = Logger.getLogger(InstructionsFromEdges.class.getName());
    private final Weighting weighting;
    private final FlagEncoder encoder;
    private final NodeAccess nodeAccess;

    private final Translation tr;
    private final InstructionList ways;
    private final EdgeExplorer outEdgeExplorer;
    private final EdgeExplorer crossingExplorer;
    private final EdgeExplorer allEdgeExplorer;
    private final BooleanEncodedValue roundaboutEnc;
    private final BooleanEncodedValue accessEnc;

    /*
     * We need three points to make directions
     *
     *        (1)----(2)
     *        /
     *       /
     *    (0)
     *
     * 0 is the node visited at t-2, 1 is the node visited
     * at t-1 and 2 is the node being visited at instant t.
     * heading is the angle of the vector(1->2) expressed
     * as atan2, while previousOrientation is the angle of the
     * vector(0->1)
     * Intuitively, if heading is smaller than
     * previousOrientation, then we have to turn right, while
     * if it is greater we have to turn left. To make this
     * algorithm work, we need to make the comparison by
     * considering heading belonging to the interval
     * [ - pi + previousOrientation , + pi + previousOrientation ]
     */
    private EdgeIteratorState prevEdge;
    private double prevLat;
    private double prevLon;
    private double doublePrevLat, doublePrevLon; // Lat and Lon of node t-2
    private int prevNode;
    private double prevOrientation;
    private double prevInstructionPrevOrientation = Double.NaN;
    private Instruction prevInstruction;
    private boolean prevInRoundabout;
    private int fietsknooppunt;
    private String prevName;
    private String prevInstructionName;
    private InstructionAnnotation prevAnnotation;
    private int versionCode;
    private boolean forceKeep;

    private TurnType turnType;

    private boolean filtering = true;
    private final int MAX_U_TURN_DISTANCE = 10;

    public InstructionsFromEdges(Graph graph, Weighting weighting,
                                 BooleanEncodedValue roundaboutEnc,
                                 Translation tr, boolean filtering, int versionCode, InstructionList ways) {
        this.weighting = weighting;
        this.encoder = weighting.getFlagEncoder();
        this.accessEnc = encoder.getAccessEnc();
        this.roundaboutEnc = roundaboutEnc;
        this.nodeAccess = graph.getNodeAccess();
        this.filtering = filtering;
        this.versionCode = versionCode;
        this.tr = tr;
        this.ways = ways;
//        prevLat = this.nodeAccess.getLatitude(tmpNode);
//        prevLon = this.nodeAccess.getLongitude(tmpNode);
        prevNode = -1;
        prevInRoundabout = false;
        prevName = null;
        outEdgeExplorer = graph.createEdgeExplorer(DefaultEdgeFilter.outEdges(encoder));
        crossingExplorer = graph.createEdgeExplorer(DefaultEdgeFilter.allEdges(encoder));
        allEdgeExplorer = graph.createEdgeExplorer(EdgeFilter.ALL_EDGES);
    }

    public static InstructionList calcInstructions(Path path, Graph graph, Weighting weighting, BooleanEncodedValue roundaboutEnc, final Translation tr) {
        return calcInstructions(path, graph, weighting, roundaboutEnc, tr, true, 2);
    }
    /**
     * @return the list of instructions for this path.
     */
    public static InstructionList calcInstructions(Path path, Graph graph, Weighting weighting, BooleanEncodedValue roundaboutEnc, final Translation tr, boolean filtering, int versionCode) {
        final InstructionList ways = new InstructionList(tr);
        if (path.isFound()) {
            if (path.getSize() == 0) {
                ways.add(new FinishInstruction(graph.getNodeAccess(), path.getEndNode()));
            } else {
                path.forEveryEdge(new InstructionsFromEdges(graph, weighting, roundaboutEnc, tr, filtering, versionCode, ways));
            }
        }
        return ways;
    }


    @Override
    public void next(EdgeIteratorState edge, int index, int prevEdgeId) {

        if (prevNode == -1) {
            prevLat = this.nodeAccess.getLatitude(edge.getBaseNode());
            prevLon = this.nodeAccess.getLongitude(edge.getBaseNode());
        }

        // baseNode is the current node and adjNode is the next
        int adjNode = edge.getAdjNode();
        int baseNode = edge.getBaseNode();
        IntsRef flags = edge.getFlags();
        double adjLat = nodeAccess.getLatitude(adjNode);
        double adjLon = nodeAccess.getLongitude(adjNode);
        double latitude, longitude;







        PointList wayGeo = edge.fetchWayGeometry(3);
        boolean isRoundabout = versionCode < 2 ? roundaboutEnc.getBool(false, flags) : false;

        forceKeep = false;

        if (wayGeo.getSize() <= 2) {
            latitude = adjLat;
            longitude = adjLon;
        } else {
            latitude = wayGeo.getLatitude(1);
            longitude = wayGeo.getLongitude(1);
            assert Double.compare(prevLat, nodeAccess.getLatitude(baseNode)) == 0;
            assert Double.compare(prevLon, nodeAccess.getLongitude(baseNode)) == 0;
        }

        InstructionAnnotation annotation = encoder.getAnnotation(flags, tr);

        if (prevName != null) {
//        TODO ... make it work for all encoders ... but the nodeNameEncoder adds a int (32bits) flags which causes max allowed bits error
            // cycling network junctions and other poi references
            // keep track of previous state of finding an reference
            // importance = 1 (warning)

            // when in this instruction passes or ends in node with name then replace last saved


            // add instruction on node with name
            if (encoder.hasNodeNameReferences()) {
                if (encoder.getNodeNameEnc().getInt(false, flags) == baseNode) {
                    fietsknooppunt = baseNode;
                }
                if (fietsknooppunt > 0) {
                    annotation = new InstructionAnnotation(1, tr.tr("at") + " " + tr.tr("junction") + " " + nodeAccess.getName(fietsknooppunt));
                    if (prevInstruction != null && !prevInstruction.getNodes().containsKey(fietsknooppunt)) {
                        forceKeep = true;
                        fietsknooppunt = 0;
                    }
                }
            }

        }



        String name = edge.getName();
        int wayType = encoder.getWayType(edge.getFlags());

        if ((prevName == null) && (!isRoundabout)) // very first instruction (if not in Roundabout)
        {
            int sign = Instruction.STRAIGHT;
            prevInstruction = new Instruction(sign, TurnType.DEPART, name, annotation, new PointList(10, nodeAccess.is3D()));
            prevInstruction.setForceKeep(true);
            prevInstruction.addNode(baseNode, new double[] { nodeAccess.getLatitude(baseNode), nodeAccess.getLongitude(baseNode)});
            if (isCrossing(baseNode, adjNode)) {
                prevInstruction.setCrossing(true);
                prevInstruction.setExtraInfo("crossing", true);
                forceKeep = true;
            }
            double startLat = nodeAccess.getLat(baseNode);
            double startLon = nodeAccess.getLon(baseNode);
            double heading = Helper.ANGLE_CALC.calcAzimuth(startLat, startLon, latitude, longitude);
            prevInstruction.setExtraInfo("heading", Helper.round(heading, 2));
            addInstruction(prevInstruction, baseNode);
            prevName = name;
            prevAnnotation = annotation;

        } else if (isRoundabout) {
            // remark: names and annotations within roundabout are ignored
            if (!prevInRoundabout) //just entered roundabout
            {
                int sign = Instruction.USE_ROUNDABOUT;
                RoundaboutInstruction roundaboutInstruction = new RoundaboutInstruction(sign, name,
                        annotation, new PointList(10, nodeAccess.is3D()));
                roundaboutInstruction.addNode(baseNode, new double[] { nodeAccess.getLatitude(baseNode), nodeAccess.getLongitude(baseNode)});

                prevInstructionPrevOrientation = prevOrientation;
                if (prevName != null) {
                    // check if there is an exit at the same node the roundabout was entered
                    EdgeIterator edgeIter = outEdgeExplorer.setBaseNode(baseNode);
                    while (edgeIter.next()) {
                        if ((edgeIter.getAdjNode() != prevNode)
                                && !roundaboutEnc.getBool(false, edgeIter.getFlags())) {
                            if (versionCode > 1 && wayType != encoder.getWayType(edgeIter.getFlags())) {
                                break;
                            }
                            roundaboutInstruction.increaseExitNumber();
                            break;
                        }
                    }

                    // previous heading is last heading before entering roundabout
                    prevOrientation = Helper.ANGLE_CALC.calcOrientation(doublePrevLat, doublePrevLon, prevLat, prevLon);

                    // calculate direction of entrance turn to determine direction of rotation
                    // right turn == counterclockwise and vice versa
                    double orientation = Helper.ANGLE_CALC.calcOrientation(prevLat, prevLon, latitude, longitude);
                    orientation = Helper.ANGLE_CALC.alignOrientation(prevOrientation, orientation);
                    double delta = (orientation - prevOrientation);
                    roundaboutInstruction.setDirOfRotation(delta);

                } else // first instructions is roundabout instruction
                {
                    prevOrientation = Helper.ANGLE_CALC.calcOrientation(prevLat, prevLon, latitude, longitude);
                    prevName = name;
                    prevAnnotation = annotation;
                }
                prevInstruction = roundaboutInstruction;
                addInstruction(prevInstruction, baseNode);
            }

            // Add passed exits to instruction. A node is counted if there is at least one outgoing edge
            // out of the roundabout
            EdgeIterator edgeIter = outEdgeExplorer.setBaseNode(edge.getAdjNode());
            while (edgeIter.next()) {
                if (!roundaboutEnc.getBool(false, edgeIter.getFlags())) {
                    if (versionCode > 1 && wayType != encoder.getWayType(edgeIter.getFlags())) {
                        break;
                    }
                    ((RoundaboutInstruction) prevInstruction).increaseExitNumber();
                    break;
                }
            }

        } else if (prevInRoundabout) //previously in roundabout but not anymore
        {

            prevInstruction.setName(name);

            // calc angle between roundabout entrance and exit
            double orientation = Helper.ANGLE_CALC.calcOrientation(prevLat, prevLon, latitude, longitude);
            orientation = Helper.ANGLE_CALC.alignOrientation(prevOrientation, orientation);
            double deltaInOut = (orientation - prevOrientation);

            // calculate direction of exit turn to determine direction of rotation
            // right turn == counterclockwise and vice versa
            double recentOrientation = Helper.ANGLE_CALC.calcOrientation(doublePrevLat, doublePrevLon, prevLat, prevLon);
            orientation = Helper.ANGLE_CALC.alignOrientation(recentOrientation, orientation);
            double deltaOut = (orientation - recentOrientation);

            prevInstruction = ((RoundaboutInstruction) prevInstruction)
                    .setRadian(deltaInOut)
                    .setDirOfRotation(deltaOut)
                    .setExited();


            prevInstructionName = prevName;
            prevName = name;
            prevAnnotation = annotation;

        } else {


            int sign = getTurn(edge, baseNode, prevNode, adjNode, annotation, name);

            // RZU: Prevent instructions in sequence follow up quickly.
            if (filtering && versionCode == 1) {
                if (prevInstruction != null) {
                    if (prevInstruction.getDistance() < 50) {
                         if (prevInstruction.getDistance() < 30) {
                             double dist = InstructionsHelper.calcOppositeDistance(prevInstruction, edge);
                             if (dist <= 20) {
                                 if (InstructionsHelper.isContinueStraight(prevInstruction, edge, sign)) {
//                    prevInstruction.setSign(getTurn(edge, baseNode, prevNode, adjNode, annotation, name));

                                     prevInstruction.setSign(0);
                                     prevInstruction.setTurnType(TurnType.OFF_RAMP);
                                     prevInstruction.setName(name);
                                     prevInstruction.setAnnotation(annotation);
                                     sign = Instruction.IGNORE;
                                 }
                                 if (sign == prevInstruction.getSign()) {
                                     switch (prevInstruction.getSign()) {
                                         case Instruction.SLIGHT_LEFT:
                                         case Instruction.SLIGHT_RIGHT:
                                             sign = Instruction.IGNORE;
                                             prevInstruction.setSign(getTurn(edge, baseNode, prevNode, adjNode, annotation, name));
                                             prevInstruction.setName(name);
                                             prevInstruction.setAnnotation(annotation);
                                             break;

                                     }
                                 }
                             }
                         } else if (InstructionsHelper.isOnRoundabout(prevInstruction, edge, sign)) {
                            prevInstruction.setSign(0);
                            prevInstruction.setTurnType(TurnType.ROUNDABOUT);
                            prevInstruction.setName(name);
                            prevInstruction.setAnnotation(annotation);
                            sign = Instruction.IGNORE;
                        }
                    }
                }


            }


            if (sign != Instruction.IGNORE) {
                /*
                    Check if the next instruction is likely to only be a short connector to execute a u-turn
                    --A->--
                           |    <-- This is the short connector
                    --B-<--
                    Road A and Road B have to have the same name and roughly the same, but opposite heading, otherwise we are assuming this is no u-turn.

                    Note: This approach only works if there a turn instruction fro A->Connector and Connector->B.
                    Currently we don't create a turn instruction if there is no other possible turn
                    We only create a u-turn if edge B is a one-way, see #1073 for more details.
                  */

                boolean isUTurn = false;
                int uTurnType = Instruction.U_TURN_LEFT;
                if (!Double.isNaN(prevInstructionPrevOrientation)
                        && prevInstruction.getDistance() < MAX_U_TURN_DISTANCE
                        && (sign < 0) == (prevInstruction.getSign() < 0)
                        && (Math.abs(sign) == Instruction.SLIGHT_RIGHT || Math.abs(sign) == Instruction.RIGHT || Math.abs(sign) == Instruction.SHARP_RIGHT)
                        && (Math.abs(prevInstruction.getSign()) == Instruction.SLIGHT_RIGHT || Math.abs(prevInstruction.getSign()) == Instruction.RIGHT || Math.abs(prevInstruction.getSign()) == Instruction.SHARP_RIGHT)
                        && edge.get(accessEnc) != edge.getReverse(accessEnc)

//                        && ! edge.get(nodeReferenceEnc) // heeft geen ref naar poi node


                        && InstructionsHelper.isNameSimilar(prevInstructionName, name)) {
                    // Chances are good that this is a u-turn, we only need to check if the heading matches
                    GHPoint point = InstructionsHelper.getPointForOrientationCalculation(edge, nodeAccess);
                    double lat = point.getLat();
                    double lon = point.getLon();
                    double currentOrientation = Helper.ANGLE_CALC.calcOrientation(prevLat, prevLon, lat, lon, false);

                    double diff = Math.abs(prevInstructionPrevOrientation - currentOrientation);
                    if (diff > (Math.PI * .9) && diff < (Math.PI * 1.1)) {
                        isUTurn = true;
                        if (sign < 0) {
                            uTurnType = Instruction.U_TURN_LEFT;
                        } else {
                            uTurnType = Instruction.U_TURN_RIGHT;
                        }
                    }

                }

                if (isUTurn) {
                    prevInstruction.setSign(uTurnType);
                    prevInstruction.setName(name);
                } else {

                    prevInstruction = new Instruction(sign, turnType, name, annotation, new PointList(10, nodeAccess.is3D()));
                    prevInstruction.addNode(baseNode, new double[] { nodeAccess.getLatitude(baseNode), nodeAccess.getLongitude(baseNode)});
                    prevInstruction.setWayType(WayType.values()[wayType]);
                    prevInstruction.setForceKeep(forceKeep);
                    if (isCrossing(baseNode, adjNode)) {
                        prevInstruction.setCrossing(true);
                        prevInstruction.setExtraInfo("crossing", true);
                    }
                    prevInstruction.addNode(baseNode, new double[] { nodeAccess.getLatitude(baseNode), nodeAccess.getLongitude(baseNode)});

                    // Remember the Orientation and name of the road, before doing this maneuver
                    prevInstructionPrevOrientation = prevOrientation;
                    prevInstructionName = prevName;


                    addInstruction(prevInstruction, baseNode);
                    prevAnnotation = annotation;
                }
            } else {
                // sign == IGNORE
                prevInstruction.addNode(baseNode, new double[] { nodeAccess.getLatitude(baseNode), nodeAccess.getLongitude(baseNode)});


            }
            // Updated the prevName, since we don't always create an instruction on name changes the previous
            // name can be an old name. This leads to incorrect turn instructions due to name changes
            prevName = name;
        }

        updatePointsAndInstruction(edge, wayGeo);
        if (wayGeo.getSize() <= 2) {
            // RZU
            // Check wayGeo has same first and last location are not the same --> which can cause a weird instruction
            // http://localhost:8989/maps/?point=52.398714%2C4.921811&point=52.399218%2C4.921371&locale=nl&vehicle=mtb&weighting=fastest&elevation=false&use_miles=false&layer=TF%20Cycle
            if (Helper.DIST_PLANE.calcDist(prevLat, prevLon, adjLat, adjLon) > 2.0f) {
                doublePrevLat = prevLat;
                doublePrevLon = prevLon;
            }
        } else {
            int beforeLast = wayGeo.getSize() - 2;
            doublePrevLat = wayGeo.getLatitude(beforeLast);
            doublePrevLon = wayGeo.getLongitude(beforeLast);
        }


        prevInRoundabout = isRoundabout;
        prevNode = baseNode;
        prevLat = adjLat;
        prevLon = adjLon;
        prevEdge = edge;
    }


    private boolean isCrossing(int baseNode, int adjNode) {

        EdgeIterator crossEdges = allEdgeExplorer.setBaseNode(baseNode);


        double[] baseLatLon = prevInstruction.getNodes().get(baseNode);

        int sign = 0;

            while(crossEdges.next()) {

                int w = encoder.getWayType(crossEdges.getFlags());
                WayType tmp = WayType.values()[w];
                if (tmp == WayType.NOT_ALLOWED ) {
                    int toNode = crossEdges.getAdjNode();
                    if (toNode == adjNode) {
                        continue;
                    }
                    double adjLat = nodeAccess.getLatitude(toNode);
                    double adjLon = nodeAccess.getLongitude(toNode);

                    int otherSign = InstructionsHelper.calculateSign(baseLatLon[0], baseLatLon[1], adjLat, adjLon, prevOrientation);


/*
                    logger.info("\n latlon:" + String.valueOf(adjLat) + "," + String.valueOf(adjLon) +

                            "\n sign: " + sign +
                            "\n othersign: " + otherSign

                    );
*/

                    if ((sign > 0 && otherSign < 0) || (sign < 0 && otherSign > 0)) {
                        return true;
                    } else if (sign == 0 && otherSign != 0) {
                        sign = otherSign;
                    }


                }


            }

        return false;
    }


    private void addInstruction(Instruction instruction, int node) {
        if (ways.size() > 0) {
            Instruction lastInstruction = ways.get(ways.size() - 1);
            if (instruction.getAnnotation().getImportance() == 1) {
                instruction.setExtraInfo("annotate", "true");
                instruction.setExtraInfo("nodeName", nodeAccess.getName(node));
                InstructionAnnotation newAnnotation = new InstructionAnnotation(0, tr.tr("towards") + " " + tr.tr("junction") + " " + nodeAccess.getName(node));
                lastInstruction.setAnnotation(newAnnotation);
                ways.replaceLast(lastInstruction);
            }
            instruction.setPrevInstruction(lastInstruction);
        }


        ways.add(instruction);

    }

    @Override
    public void finish() {
        if (prevInRoundabout) {
            // calc angle between roundabout entrance and finish
            double orientation = Helper.ANGLE_CALC.calcOrientation(doublePrevLat, doublePrevLon, prevLat, prevLon);
            orientation = Helper.ANGLE_CALC.alignOrientation(prevOrientation, orientation);
            double delta = (orientation - prevOrientation);
            ((RoundaboutInstruction) prevInstruction).setRadian(delta);

        }

        Instruction finishInstruction = new FinishInstruction(nodeAccess, prevEdge.getAdjNode());
        // This is the heading how the edge ended
        finishInstruction.setExtraInfo("last_heading", Helper.ANGLE_CALC.calcAzimuth(doublePrevLat, doublePrevLon, prevLat, prevLon));
        ways.add(finishInstruction);
    }

    private int getTurn(EdgeIteratorState edge, int baseNode, int prevNode, int adjNode, InstructionAnnotation annotation, String name) {
        turnType = getTurnType(edge, baseNode, prevNode, adjNode, annotation, name);
        GHPoint point = InstructionsHelper.getPointForOrientationCalculation(edge, nodeAccess);
        double lat = point.getLat();
        double lon = point.getLon();
        prevOrientation = Helper.ANGLE_CALC.calcOrientation(doublePrevLat, doublePrevLon, prevLat, prevLon);

        InstructionsOutgoingEdges outgoingEdges = new InstructionsOutgoingEdges(prevEdge, edge, encoder, crossingExplorer, nodeAccess, prevNode, baseNode, adjNode);
        int nrOfPossibleTurns = outgoingEdges.nrOfAllowedOutgoingEdges();

        int sign = InstructionsHelper.calculateSign(prevLat, prevLon, lat, lon, prevOrientation);

        double dist = Helper.DIST_PLANE.calcDist(prevLat, prevLon, lat, lon);

        boolean forceInstruction = versionCode < 2 ? false : true; //!filtering; // default false

        if (fietsknooppunt == 0 && !annotation.equals(prevAnnotation) && !annotation.isEmpty()) {
            forceInstruction = true;
        }


        // there is no other turn possible
        if (nrOfPossibleTurns <= 1 && sign != 0) {
            // RZU
            // if (Math.abs(sign) > 1 && outgoingEdges.nrOfAllIncomingEdges() > 1) {
            // http://localhost:8989/maps/?point=52.368609%2C4.938361&point=52.368511%2C4.939085&locale=nl&vehicle=ncnbike&weighting=fastest&elevation=false&use_miles=false&layer=TF%20Cycle
            if (Math.abs(sign) > 1 && dist > 1) {
                // This is an actual turn because |sign| > 1
                // There could be some confusion, if we would not create a turn instruction, even though it is the only
                // possible turn, also see #1048
                // TODO if we see issue with this approach we could consider checking if the edge is a oneway
                return sign;
            }
            return returnForcedInstructionOrIgnore(forceInstruction, sign);
        }




        // Very certain, this is a turn
        if (Math.abs(sign) > 1) {
            /*
             * Don't show an instruction if the user is following a street, even though the street is
             * bending. We should only do this, if following the street is the obvious choice.
             */
            if (versionCode < 2) {
                if (InstructionsHelper.isNameSimilar(name, prevName) && outgoingEdges.outgoingEdgesAreSlowerByFactor(2)) {
                    return returnForcedInstructionOrIgnore(forceInstruction, sign);
                }
            }

            return sign;
        }



        /*
        The current state is a bit uncertain. So we are going more or less straight sign < 2
        So it really depends on the surrounding street if we need a turn instruction or not
        In most cases this will be a simple follow the current street and we don't necessarily
        need a turn instruction
         */
        if (prevEdge == null) {
            // TODO Should we log this case?
            return sign;
        }

        IntsRef flag = edge.getFlags();
        IntsRef prevFlag = prevEdge.getFlags();

        boolean outgoingEdgesAreSlower = false;

        outgoingEdgesAreSlower = outgoingEdges.outgoingEdgesAreSlowerByFactor(1.5);

        // There is at least one other possibility to turn, and we are almost going straight
        // Check the other turns if one of them is also going almost straight
        // If not, we don't need a turn instruction
        EdgeIteratorState otherContinue = outgoingEdges.getOtherContinue(prevLat, prevLon, prevOrientation);


        // Signs provide too less detail, so we use the delta for a precise comparision
        double delta = InstructionsHelper.calculateOrientationDelta(prevLat, prevLon, lat, lon, prevOrientation);

        // This state is bad! Two streets are going more or less straight
        // Happens a lot for trunk_links
        // For _links, comparing flags works quite good, as links usually have different speeds => different flags
        if (otherContinue != null) {
            //We are at a fork
            if (!InstructionsHelper.isNameSimilar(name, prevName)
                    || InstructionsHelper.isNameSimilar(otherContinue.getName(), prevName)
                    || !prevFlag.equals(flag)
                    || prevFlag.equals(otherContinue.getFlags())
                    || !outgoingEdgesAreSlower) {
                GHPoint tmpPoint = InstructionsHelper.getPointForOrientationCalculation(otherContinue, nodeAccess);
                double otherDelta = InstructionsHelper.calculateOrientationDelta(prevLat, prevLon, tmpPoint.getLat(), tmpPoint.getLon(), prevOrientation);


                // This is required to avoid keep left/right on the motorway at off-ramps/motorway_links
                if (Math.abs(delta) < .15 && Math.abs(otherDelta) > .15 && InstructionsHelper.isNameSimilar(name, prevName)) {
//                    RZU: Not usefull to check changing streetnames when bike routing
//                    return Instruction.STRAIGHT;
                    return returnForcedInstructionOrIgnore(false, sign);
                }
                // TODO: RZU nagaan of je ook zonder deze toekenning kan.
                if (otherDelta < delta) {
                    return Instruction.SLIGHT_LEFT;
                } else {
                    return Instruction.SLIGHT_RIGHT;
                }






            }
        }

        if (filtering && versionCode >= 2 && sign == 0) {
            // is het wegtype van de outgoing groter dan de huidige dan overgang melden
            int wayType = encoder.getWayType(flag);

            boolean crossing = false;
            for (EdgeIteratorState outgoingEdge : outgoingEdges.getAllOutgoingEdges()) {
                int outgoingWayType = encoder.getWayType(outgoingEdge.getFlags());
                if (wayType < outgoingWayType) {
                    crossing = true;
                    break;
                }

            }

            return returnForcedInstructionOrIgnore(crossing, sign);
        }



        if (!outgoingEdgesAreSlower) {
//            if (Math.abs(delta) > .3) {
            if (Math.abs(delta) > .4) {
//                RZU: niet er handig
//                    || outgoingEdges.isLeavingCurrentStreet(prevName, name)) {
                // Leave the current road -> create instruction
                return sign;

            }

        }




        return returnForcedInstructionOrIgnore(forceInstruction, sign);
    }

    private TurnType getTurnType(EdgeIteratorState edge, int baseNode, int prevNode, int adjNode, InstructionAnnotation annotation, String name) {

        prevOrientation = Helper.ANGLE_CALC.calcOrientation(doublePrevLat, doublePrevLon, prevLat, prevLon);


        InstructionsOutgoingEdges outgoingEdges = new InstructionsOutgoingEdges(prevEdge, edge, encoder, crossingExplorer, nodeAccess, prevNode, baseNode, adjNode);
        int nrOfPossibleTurns = outgoingEdges.nrOfAllowedOutgoingEdges();

        // there is no other turn possible
        if (nrOfPossibleTurns <= 1) {
            return TurnType.CONTINUE;
        }


        IntsRef flag = edge.getFlags();
        IntsRef prevFlag = prevEdge.getFlags();

        boolean outgoingEdgesAreSlower = false;

        outgoingEdgesAreSlower = outgoingEdges.outgoingEdgesAreSlowerByFactor(1.5);

        // There is at least one other possibility to turn, and we are almost going straight
        // Check the other turns if one of them is also going almost straight
        // If not, we don't need a turn instruction
        EdgeIteratorState otherContinue = outgoingEdges.getOtherContinue(prevLat, prevLon, prevOrientation);

        PointList points = edge.fetchWayGeometry(3);
        double angle = Helper.ANGLE_CALC.calcAzimuth(points.getLat(1), points.getLon(1),points.getLat(0), points.getLon(0));
        PointList prevPoints = prevEdge.fetchWayGeometry(3);
        double prevAngle = Helper.ANGLE_CALC.calcAzimuth(prevPoints.getLat(prevPoints.getSize() - 2), prevPoints.getLon(prevPoints.getSize() - 2),prevPoints.getLat(prevPoints.getSize() - 1), prevPoints.getLon(prevPoints.getSize() - 1));



        TurnType retVal = null;
        for (EdgeIteratorState outgoingedge : outgoingEdges.getAllowedOutgingEdges()) {
            GHPoint tmpPoint = InstructionsHelper.getPointForOrientationCalculation(outgoingedge, nodeAccess);
            double otherAngle = Helper.ANGLE_CALC.calcAzimuth(prevLat, prevLon, tmpPoint.getLat(), tmpPoint.getLon());
            if (outgoingEdges.nrOfAllowedOutgoingEdges() > 1 && Math.abs(prevAngle - otherAngle) < 10) {
                retVal = TurnType.OFF_RAMP;
            } else if (Math.abs(angle - otherAngle) < 10) {
                retVal = TurnType.END_OF_ROAD;
            }
        }
        if (retVal != null) {
            return retVal;
        }


        // This state is bad! Two streets are going more or less straight
        // Happens a lot for trunk_links
        // For _links, comparing flags works quite good, as links usually have different speeds => different flags
        if (otherContinue != null) {
            //We are at a fork
            if (!outgoingEdgesAreSlower
                    || InstructionsHelper.isNameSimilar(otherContinue.getName(), prevName)
                    || !prevFlag.equals(flag)
                    || prevFlag.equals(otherContinue.getFlags())
                    || !InstructionsHelper.isNameSimilar(name, prevName)
            ) {

                return TurnType.FORK;
            } else {

            }
        }

        return TurnType.TURN;
    }



    private int returnForcedInstructionOrIgnore(boolean forceInstruction, int sign) {
        if (forceInstruction || forceKeep)
            return sign;
        return Instruction.IGNORE;
    }

    private void updatePointsAndInstruction(EdgeIteratorState edge, PointList pl) {
        // skip adjNode
        int len = pl.size() - 1;
        for (int i = 0; i < len; i++) {
            prevInstruction.getPoints().add(pl, i);
        }
        double newDist = edge.getDistance();
        prevInstruction.setDistance(newDist + prevInstruction.getDistance());
        prevInstruction.setTime(weighting.calcMillis(edge, false, EdgeIterator.NO_EDGE)
                + prevInstruction.getTime());

    }

}