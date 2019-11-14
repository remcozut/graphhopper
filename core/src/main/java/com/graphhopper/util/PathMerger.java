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
package com.graphhopper.util;

import com.graphhopper.PathWrapper;
import com.graphhopper.routing.InstructionsFromEdges;
import com.graphhopper.routing.InstructionsHelper;
import com.graphhopper.routing.Path;
import com.graphhopper.routing.profiles.BooleanEncodedValue;
import com.graphhopper.routing.profiles.Roundabout;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.details.PathDetailsBuilderFactory;
import com.graphhopper.util.details.PathDetailsFromEdges;
import com.graphhopper.util.exceptions.ConnectionNotFoundException;
import org.locationtech.jts.io.WKTWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.graphhopper.util.Helper.MIN_INSTRUCTION_DISTANCE_THRESHOLD;

/**
 * This class merges multiple {@link Path} objects into one continues object that
 * can be used in the {@link PathWrapper}. There will be a Path between every waypoint.
 * So for two waypoints there will be only one Path object. For three waypoints there will be
 * two Path objects.
 * <p>
 * The instructions are generated per Path object and are merged into one continues InstructionList.
 * The PointList per Path object are merged and optionally simplified.
 *
 * @author Peter Karich
 * @author ratrun
 * @author Robin Boldt
 */
public class PathMerger {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Graph graph;
    private final Weighting weighting;

    private static final DouglasPeucker DP = new DouglasPeucker();
    private boolean enableInstructions = true;
    private int versionCode = 0;
    private boolean simplifyResponse = true;
    private boolean enableFiltering = true;
    private DouglasPeucker douglasPeucker = DP;
    private boolean calcPoints = true;
    private PathDetailsBuilderFactory pathBuilderFactory;
    private List<String> requestedPathDetails = Collections.EMPTY_LIST;
    private double favoredHeading = Double.NaN;

    public PathMerger(Graph graph, Weighting weighting) {
        this.graph = graph;
        this.weighting = weighting;
    }

    public PathMerger setCalcPoints(boolean calcPoints) {
        this.calcPoints = calcPoints;
        return this;
    }

    public PathMerger setDouglasPeucker(DouglasPeucker douglasPeucker) {
        this.douglasPeucker = douglasPeucker;
        return this;
    }

    public PathMerger setPathDetailsBuilders(PathDetailsBuilderFactory pathBuilderFactory, List<String> requestedPathDetails) {
        this.pathBuilderFactory = pathBuilderFactory;
        this.requestedPathDetails = requestedPathDetails;
        return this;
    }

    public PathMerger setSimplifyResponse(boolean simplifyRes) {
        this.simplifyResponse = simplifyRes;
        return this;
    }

    public PathMerger setVersionCode(int versionCode) {
        this.versionCode = versionCode;
        return this;
    }

    public PathMerger setEnableInstructions(boolean enableInstructions) {
        this.enableInstructions = enableInstructions;
        return this;
    }

    public PathMerger setEnableFiltering(boolean enableFiltering) {
        this.enableFiltering = enableFiltering;
        return this;
    }

    public void doWork(PathWrapper altRsp, List<Path> paths, EncodingManager encodingManager, Translation tr) {
        int origPoints = 0;
        long fullTimeInMillis = 0;
        double fullWeight = 0;
        double fullDistance = 0;
        boolean allFound = true;

        InstructionList fullInstructions = new InstructionList(tr);
        PointList fullPoints = PointList.EMPTY;
        List<String> description = new ArrayList<>();
        BooleanEncodedValue roundaboutEnc = encodingManager.getBooleanEncodedValue(Roundabout.KEY);
        for (int pathIndex = 0; pathIndex < paths.size(); pathIndex++) {
            Path path = paths.get(pathIndex);
            if (!path.isFound()) {
                allFound = false;
                continue;
            }
            description.addAll(path.getDescription());
            fullTimeInMillis += path.getTime();
            fullDistance += path.getDistance();
            fullWeight += path.getWeight();
            if (enableInstructions) {
                InstructionList il = InstructionsFromEdges.calcInstructions(path, graph, weighting, roundaboutEnc, tr, enableFiltering, versionCode);
                if (!il.isEmpty()) {
                    fullInstructions.addAll(il);

                    // for all paths except the last replace the FinishInstruction with a ViaInstructionn
                    if (pathIndex + 1 < paths.size()) {
                        ViaInstruction newInstr = new ViaInstruction(fullInstructions.get(fullInstructions.size() - 1));
                        newInstr.setViaCount(pathIndex + 1);
                        fullInstructions.set(fullInstructions.size() - 1, newInstr);

                    }
                }

            }
            if (calcPoints || enableInstructions) {
                PointList tmpPoints = path.calcPoints();
                if (fullPoints.isEmpty())
                    fullPoints = new PointList(tmpPoints.size(), tmpPoints.is3D());

                // Remove duplicated points, see #1138
                if (pathIndex + 1 < paths.size()) {
                    tmpPoints.removeLastPoint();
                }

                fullPoints.add(tmpPoints);
                altRsp.addPathDetails(PathDetailsFromEdges.calcDetails(path, weighting, requestedPathDetails, pathBuilderFactory, origPoints));
                origPoints = fullPoints.size();
            }

            allFound = allFound && path.isFound();
        }

        if (!fullPoints.isEmpty()) {
            String debug = altRsp.getDebugInfo() + ", simplify (" + origPoints + "->" + fullPoints.getSize() + ")";
            altRsp.addDebugInfo(debug);
            if (fullPoints.is3D)
                calcAscendDescend(altRsp, fullPoints);
        }

        if (enableInstructions) {
            fullInstructions = updateInstructionsWithContext(fullInstructions);
                fullInstructions = postProcessInstructions(fullInstructions, tr);

            altRsp.setInstructions(fullInstructions);
        }

        if (!allFound) {
            altRsp.addError(new ConnectionNotFoundException("Connection between locations not found", Collections.<String, Object>emptyMap()));
        }

        altRsp.setDescription(description).
                setPoints(fullPoints).
                setRouteWeight(fullWeight).
                setDistance(fullDistance).
                setTime(fullTimeInMillis);

        if (allFound && simplifyResponse && (calcPoints || enableInstructions)) {
            PathSimplification ps = new PathSimplification(altRsp, douglasPeucker, enableInstructions);
            ps.simplify();
        }
    }


    /*
     *  RZU
     *  check instructionlist on ambigouus instructions
     * */
    public InstructionList postProcessInstructions(final InstructionList instructions, final Translation tr) {
        List<InstructionList> groupedInstructions = new ArrayList<>();

/*
        StringBuilder sb = new StringBuilder("\nOriginal instructionlist:");
        for ( int i = 0; i < instructions.size(); i++) {
            sb.append(String.format("\n%s", instructions.get(i).toString()));
        }
        sb.append("\nOriginal Polyline:");
        for ( int i = 0; i < instructions.size(); i++) {
            sb.append(String.format("\n%s,", instructions.get(i).toWkt()));
        }
        logger.info(sb.toString());

*/

        InstructionList cachedInstructions = new InstructionList(tr);
        Instruction instruction, prevInstruction = new Instruction(0);

        int groupIdx = 1;


        double straightDist = 0;
        for (int i = 0; i < instructions.size(); i++) {
            instruction = instructions.get(i);

            if (i == 0) {
                instruction.setExtraInfo("groupIdx", groupIdx++);
                cachedInstructions.add(instruction);
                groupedInstructions.add(cachedInstructions);
                cachedInstructions = new InstructionList(tr);
                prevInstruction = instruction;
            }
            else if (i == instructions.size() - 1) {
                groupedInstructions.add(cachedInstructions);
                cachedInstructions = new InstructionList(tr);
                instruction.setExtraInfo("groupIdx", ++groupIdx);
                cachedInstructions.add(instruction);
                groupedInstructions.add(cachedInstructions);
                prevInstruction = instruction;

            } else if (instruction.getSign() == Instruction.STRAIGHT
                    && instruction.getTurnType() != TurnType.ARRIVE
                    && instruction.getTurnType() != TurnType.DEPART
                    && cachedInstructions.size() > 0) {

                instruction.setExtraInfo("groupIdx", groupIdx);
/*                if (instruction.getDistance() < Helper.MAX_INSTRUCTION_DISTANCE_THRESHOLD /2) {
                    instruction.setExtraInfo("crossing", "true");
                    instruction.setCrossing(true);
                }*/

                /*
                *  Merge instructions when going Straight but not further than MAX_MERGE_STRAIGHT_DISTANCE
                * */
                if (enableFiltering && !instruction.isForceKeep() && prevInstruction.getDistance() < Helper.MAX_MERGE_STRAIGHT_DISTANCE) {
                    prevInstruction = cachedInstructions.mergeLast(instruction);

                } else {
                    straightDist += instruction.getDistance();
                    cachedInstructions.add(instruction);
                    prevInstruction = instruction;
                }

             } else {

                if (cachedInstructions.size() > 0 && prevInstruction.getDistance() + straightDist > Helper.MAX_INSTRUCTION_DISTANCE_THRESHOLD) {
                    groupIdx++;
                    straightDist = 0;
                    groupedInstructions.add(cachedInstructions);
                    cachedInstructions = new InstructionList(tr);
                }

                instruction.setExtraInfo("groupIdx", groupIdx);
                cachedInstructions.add(instruction);
                prevInstruction = instruction;
            }


        }
        if (versionCode > 1) {

            for (int i = 0; i < groupedInstructions.size(); i++) {
                mergeInstuctions(i, groupedInstructions);

            }
        }

        InstructionList retVal = new InstructionList(tr);
        for (InstructionList groupedInstruction : groupedInstructions) {
            retVal.addAll(groupedInstruction);
        }


/*
        sb = new StringBuilder("\nMerged instructionlist:");
        for ( int i = 0; i < retVal.size(); i++) {
            sb.append(String.format("\n%s", retVal.get(i).toString()));
        }
        sb.append("\nMerged Polyline:");
        for ( int i = 0; i < retVal.size(); i++) {
            sb.append(String.format("\n%s,", retVal.get(i).toWkt()));
        }
        logger.info(sb.toString());

*/

        return retVal;
    }

    private PointList calcPolyline(final int index, final List<InstructionList> groupedInstructions) {
        PointList polyline = new PointList(true);

        InstructionList instructionList = groupedInstructions.get(index);
        double distance = 0;


        Instruction instr = instructionList.get(0).getPrevInstruction();
        if (instr != null) {
            double endLat, endLon;
            double startLat = instructionList.get(0).points.getLat(0);
            double startLon = instructionList.get(0).points.getLon(0);


            int idx = instr.points.getSize() - 1;
            while (distance < Helper.MAX_INSTRUCTION_DISTANCE_THRESHOLD / 2f) {
                if (idx >= 0) {
                    endLat = instr.points.getLat(idx);
                    endLon = instr.points.getLon(idx);
                    distance += Helper.DIST_EARTH.calcDist(startLat, startLon, endLat, endLon);
                    startLat = endLat;
                    startLon = endLon;
                    idx--;
/*        if (size>0 && lat == latitudes[size-1] && lon == longitudes[size-1]) {
            return;
        }*/
                    polyline.add(startLat, startLon);
                } else {
                    instr = instr.getPrevInstruction();
                    if (instr != null) {
                        idx = instr.points.getSize() - 1;
                    } else {
                        break;
                    }
                }
            }
        }
        polyline.reverse();

        for (int i = 0; i < instructionList.size() - 1; i++) {
            Instruction instruction = instructionList.get(i);

            int size = instruction.points.getSize();

            for (int p = 0; p < size; p++) {
                polyline.add(
                        instruction.points.getLat(p),
                        instruction.points.getLon(p)
                );

            }
        }

        instr = instructionList.get(instructionList.size() - 1);
        double endLat, endLon;
        double startLat = instr.points.getLat(0);
        double startLon = instr.points.getLon(0);

        polyline.add(startLat, startLon);

        int idx = 1;
        int instrIdx = 0;
        instructionList = groupedInstructions.get(index+1);
        distance = 0;
        while (distance < Helper.MAX_INSTRUCTION_DISTANCE_THRESHOLD) {
             if (idx < instr.points.size) {
                    endLat = instr.points.getLat(idx);
                    endLon = instr.points.getLon(idx);
                    distance += Helper.DIST_EARTH.calcDist(startLat, startLon, endLat, endLon);
                    startLat = endLat;
                    startLon = endLon;
                     polyline.add(endLat, endLon);

                 idx++;

             } else {
                 if (instrIdx < instructionList.size()) {
                     instr = instructionList.get(instrIdx++);
                     idx = 0;
                 } else {
                     break;
                 }

             }

        }




//        logger.info(String.format("\nGroup polyline:\n%s", (new WKTWriter()).write(polyline.toLineString(false))));

        return polyline;
    }

    public enum ManoevreType {
        STRAIGHT, // rechtdoor
        SINGLE,   // 1 bocht
        MULTI     // meerdere bochten
    }

    private void mergeInstuctions(final int index, List<InstructionList> groupedInstructions) {

        final InstructionList instrGroup = groupedInstructions.get(index);

        if (! enableFiltering) {
            return;
        }

        long manoevreCount = instrGroup.stream().filter(instruction -> instruction.getSign() != 0).count();
        if (manoevreCount < 2) {
            return;
        }


        PointList polyline = calcPolyline(index, groupedInstructions);
        PointList simplified = polyline.clone(false);

        // bepaal de vereenvoudigde lijn om alle hoeken te kunnen detecteren
        // conditie is dat de oorspronkelijke lijn wel meer dan 1 hoek hebben.
        if (polyline.getSize() > 3) {
            DouglasPeucker dp = new DouglasPeucker().setMaxDistance(MIN_INSTRUCTION_DISTANCE_THRESHOLD);
            dp.simplify(simplified);
        }

        double orientationIn = Helper.ANGLE_CALC.calcOrientation(polyline.getLat(0), polyline.getLon(0), polyline.getLat(1), polyline.getLon(1));
        double delta = InstructionsHelper.calculateOrientationDelta(simplified.getLat(0), simplified.getLon(0), simplified.getLat(1), simplified.getLon(1), orientationIn);

        boolean complexManoevre = instrGroup.stream().filter(instruction -> instruction.getSign() != 0).count() > 2; // als er meer dan 2 instructie nodig zijn om de beweging te beschrijven dan is ie complex, rechtdoor niet meegerekend;


        // vergelijk hoek ingaande/uitgaande vertex op originele polyline
        int sign = InstructionsHelper.calculateSign(polyline.getLat(polyline.getSize() - 2), polyline.getLon(polyline.getSize() - 2), polyline.getLat(polyline.getSize() - 1), polyline.getLon(polyline.getSize() - 1), orientationIn);

        ManoevreType manoevreType = ManoevreType.STRAIGHT;
        if (simplified.getSize() == 3) {
            manoevreType = ManoevreType.SINGLE;
        } else if (simplified.getSize() > 3) {
            manoevreType = ManoevreType.MULTI;
        }
                // ga na of we instructie gaan samenvoegen
        boolean merge = false;
        if (manoevreType == ManoevreType.STRAIGHT) { // als we vermoedelijk rechtdoor gaan

            // DP zegt rechte lijn .. wel even controleren of de hoek tussen DP en eerste vertex van polyline ongeveer dezelfde kant uit gaan
            // alleen dan kunnen we de instructie groep gaan vereenvoudigen
            if (Math.abs(delta) < .8) {
                merge = true;
            }
            complexManoevre = manoevreCount > 2; // als er meer dan 2 instructie nodig zijn om de beweging te beschrijven dan is ie complex, rechtdoor niet meegerekend

        } else if (manoevreType == ManoevreType.SINGLE) { // als we vermoedelijk alleen maar de bocht om gaan
            //
            complexManoevre = manoevreCount > 2; // als er meer dan 2 instructie nodig zijn om de beweging te beschrijven dan is ie complex, rechtdoor niet meegerekend
            merge = true;
        } else if (manoevreType == ManoevreType.MULTI) { // als we vermodelijke meerdere bochten doen
            complexManoevre = manoevreCount > 3; // als er meer dan 2 instructie nodig zijn om de beweging te beschrijven dan is ie complex, rechtdoor niet meegerekend
            merge = true;
        }

//        merge = false;

/*
        StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < instrGroup.size(); i++) {
            sb.append(String.format("\n%s", instrGroup.get(i).toString()));
        }

        sb.append(String.format("\n%s," +
                        "\n%s" +
                        "\nsign: %d" +
                        "\ndelta: %f"+
                "\ninstructions: %d" +
                "\ncomplex manoevre: %b" +
                "\nmanoevre type: %s" +
                "\nmerge: %b"
                ,
                    (new WKTWriter()).write(polyline.toLineString(false)),
                    (new WKTWriter()).write(simplified.toLineString(false)),
                    sign,
                    delta,
                    instrGroup.size(),
                    complexManoevre,
                    manoevreType.name(),
                    merge
                )
        );
        logger.info(sb.toString());

*/

        // we gaan de instructie vergelijken met de vereenvoudigde lijn met kenmerkende hoeken
        if (merge) {




            switch (manoevreType) {
                case STRAIGHT: {
                                            /*
                        eenvoudige beweging zoals:

                             ^
                             |
                             |
                             |
                        +----+
                        |
                        |
                        |
                        +
                        */

                    int idx = 0;
                    Instruction instruction = instrGroup.get(idx++);

                    double dist = instruction.getDistance(); // afstand van vorige instructie


                        if ((dist < MIN_INSTRUCTION_DISTANCE_THRESHOLD && !complexManoevre) || (instruction.getTurnType() != TurnType.FORK && instruction.getTurnType() != TurnType.END_OF_ROAD && instruction.getTurnType() != TurnType.OFF_RAMP)) {
                            instruction.setSign(sign); // sign == STRAIGHT
                        }


                        while(idx < instrGroup.size()) {
                            dist = instruction.getDistance(); // afstand van vorige instructie
                            instruction = instrGroup.get(idx);

                            if (instruction.isForceKeep()) {
                                idx++;
                                continue;
                            }

                            if (Math.abs(instruction.getSign()) > 1
                                    && dist > MIN_INSTRUCTION_DISTANCE_THRESHOLD
                                    && !complexManoevre
                                    && instruction.getTurnType() != TurnType.CONTINUE
                            ) {
                                //wel een belangrijke instructie
                            } else {
                                Instruction newInstr = instrGroup.merge(idx--);
                                if (complexManoevre && idx < instrGroup.size() - 1 && instruction.getDistance() < MIN_INSTRUCTION_DISTANCE_THRESHOLD) {
                                    newInstr.setTurnType(instruction.getTurnType());
                                    newInstr.setSign(instruction.getSign());
                                }
                            }
                            idx++;
                        }


                        if (instrGroup.size() == 1) {
                            instrGroup.get(0).setSign(sign);
                        }




                    break;
                }
                case SINGLE:
                case MULTI: {

                    double prevDist = 0;
                    int simplifiedIdx = 1;
                    int matchedCorners = 0;

                    Instruction instruction = instrGroup.get(0);

//                    TODO: Detect roundabout and give simple instruction on direction


                    int idx = 0;
                    while (idx < instrGroup.size()) {
                        instruction = instrGroup.get(idx);

                        if (instruction.isForceKeep()) {
                            idx++;
                            continue;
                        }


                        double cornerLat = 0, cornerLon = 0;
                        boolean cornerMatch = false;

                        for (int s = simplifiedIdx; s < simplified.size - 1; s++) {

                            cornerLat = simplified.getLat(s);
                            cornerLon = simplified.getLon(s);

                            for (int j = 0; j < instruction.getPoints().size; j++) {
                                if (cornerLat == instruction.getPoints().getLat(j) && cornerLon == instruction.getPoints().getLon(j)) {
                                    cornerMatch = true;
                                    simplifiedIdx = s;
                                    break;
                                }
                            }
                            if (cornerMatch) {
                                break;
                            }
                        }


                       // komt de positie van de instructie overeen met een hoekpunt in de DP lijn
                        // dan een echte instructie
                        if (cornerMatch) {

/*
                            if (simplifiedIdx > 0 && simplifiedIdx < simplified.size - 1) {
                                orientationIn = Helper.ANGLE_CALC.calcOrientation(simplified.getLat(simplifiedIdx-1), simplified.getLon(simplifiedIdx-1), simplified.getLat(simplifiedIdx), simplified.getLon(simplifiedIdx));
                                sign = InstructionsHelper.calculateSign(simplified.getLat(simplifiedIdx), simplified.getLon(simplifiedIdx), simplified.getLat(simplifiedIdx+1), simplified.getLon(simplifiedIdx+1), orientationIn);
                                instruction.setSign(sign);
                            }
*/

                            if (idx > 0
                                    && instruction.getDistance() <= MIN_INSTRUCTION_DISTANCE_THRESHOLD
                                    && (instruction.getTurnType() != TurnType.FORK && instruction.getTurnType() != TurnType.END_OF_ROAD && instruction.getTurnType() != TurnType.OFF_RAMP)
                                    && !instruction.isCrossing()) {
                                    Instruction newInstr = instrGroup.merge(idx--);
/*                                newInstr.setSign(instruction.getSign());
                                newInstr.setTurnType(instruction.getTurnType());*/

                            }
                            matchedCorners++;

                        } else {

                            if (idx > 0
                                    && prevDist <= MIN_INSTRUCTION_DISTANCE_THRESHOLD
                                    && ! instruction.isCrossing()
                                    && instruction.getTurnType() == TurnType.CONTINUE) {
                                Instruction newInstr = instrGroup.merge(idx--);
                                newInstr.setSign(instruction.getSign());
                                newInstr.setTurnType(instruction.getTurnType());
                            } else {
                                if (
                                       instruction.getSign() != 0
                                        && (instruction.isCrossing()
                                            || instruction.getTurnType() == TurnType.FORK
                                            || instruction.getTurnType() == TurnType.END_OF_ROAD
                                            || instruction.getTurnType() == TurnType.OFF_RAMP)
                                ) {
                                    // belangrijke instructie behouden
                                } else if (idx > 0) {
                                        Instruction newInst = instrGroup.merge(idx--);
                                } else {
                                       groupedInstructions.get(index - 1).mergeLast(instruction);
                                        instrGroup.remove(0);
                                    idx--;
                                }
                            }


                        }
                        prevDist = instruction.getDistance();
                        idx++;


                    }


/*                    if (instrGroup.size() == 1) {
                        instrGroup.get(0).setSign(sign);
                    }*/




                    break;
                }
            }
        }




    }


    /**
     * This method iterates over all instructions and uses the available context to improve the instructions.
     * If the requests contains a heading, this method can transform the first continue to a u-turn if the heading
     * points into the opposite direction of the route.
     * At a waypoint it can transform the continue to a u-turn if the route involves turning.
     */
    private InstructionList updateInstructionsWithContext(InstructionList instructions) {
        Instruction instruction;
        Instruction nextInstruction = null;

        for (int i = 0; i < instructions.size() - 1; i++) {
            instruction = instructions.get(i);
            nextInstruction = instructions.get(i + 1);

//            instruction.setHeading(instruction.calcAzimuth(nextInstruction));

            if (i == 0 && !Double.isNaN(favoredHeading) && instruction.extraInfo.containsKey("heading")) {
                double heading = (double) instruction.extraInfo.get("heading");
//                instruction.setHeading(heading);
                double diff = Math.abs(heading - favoredHeading) % 360;
                if (diff > 170 && diff < 190) {
                    // The requested heading points into the opposite direction of the calculated heading
                    // therefore we change the continue instruction to a u-turn
                    instruction.setSign(Instruction.U_TURN_LEFT);
                }
            }

            if (instruction.getSign() == Instruction.REACHED_VIA) {
                if (nextInstruction.getSign() != Instruction.STRAIGHT
                        || !instruction.extraInfo.containsKey("last_heading")
                        || !nextInstruction.extraInfo.containsKey("heading")) {
                    // TODO throw exception?
                    continue;
                }
                double lastHeading = (double) instruction.extraInfo.get("last_heading");
                double heading = (double) nextInstruction.extraInfo.get("heading");

                // Since it's supposed to go back the same edge, we can be very strict with the diff
                double diff = Math.abs(lastHeading - heading) % 360;
                if (diff > 179 && diff < 181) {
                    nextInstruction.setSign(Instruction.U_TURN_LEFT);
                }
            }


        }

/*
        if (nextInstruction != null && nextInstruction.extraInfo.containsKey("last_heading")) {
            nextInstruction.setHeading((double) nextInstruction.extraInfo.get("last_heading"));
        }
*/


        return instructions;
    }

    private void calcAscendDescend(final PathWrapper rsp, final PointList pointList) {
        double ascendMeters = 0;
        double descendMeters = 0;
        double lastEle = pointList.getElevation(0);
        for (int i = 1; i < pointList.size(); ++i) {
            double ele = pointList.getElevation(i);
            double diff = Math.abs(ele - lastEle);

            if (ele > lastEle)
                ascendMeters += diff;
            else
                descendMeters += diff;

            lastEle = ele;

        }
        rsp.setAscend(ascendMeters);
        rsp.setDescend(descendMeters);
    }

    public void setFavoredHeading(double favoredHeading) {
        this.favoredHeading = favoredHeading;
    }
}