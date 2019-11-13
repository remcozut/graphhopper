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

import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;

import java.util.logging.Logger;

/**
 * Simple helper class used during the instruction generation
 */
public class InstructionsHelper {
    private static Logger log = Logger.getLogger(InstructionsFromEdges.class.getName());




    public static double calculateOrientationDelta(double prevLatitude, double prevLongitude, double latitude, double longitude, double prevOrientation) {
        double orientation = Helper.ANGLE_CALC.calcOrientation(prevLatitude, prevLongitude, latitude, longitude, false);
        orientation = Helper.ANGLE_CALC.alignOrientation(prevOrientation, orientation);
        return orientation - prevOrientation;
    }

    // De delta's kunnen iets losser.
    static int _calculateSign(double prevLatitude, double prevLongitude, double latitude, double longitude, double prevOrientation) {
        double delta = calculateOrientationDelta(prevLatitude, prevLongitude, latitude, longitude, prevOrientation);
        double absDelta = Math.abs(delta);

        if (absDelta < 0.2) {
            // 0.2 ~= 1 1°
            return Instruction.STRAIGHT;

        } else if (absDelta < 0.8) {
            // 0.8 ~= 40°
            if (delta > 0)
                return Instruction.SLIGHT_LEFT;
            else
                return Instruction.SLIGHT_RIGHT;

        } else if (absDelta < 2.46) {
            // 1.8 ~= 103°
            if (delta > 0)
                return Instruction.LEFT;
            else
                return Instruction.RIGHT;

        } else {
            if (delta > 0)
                return Instruction.SHARP_LEFT;
            else
                return Instruction.SHARP_RIGHT;

        }
    }


    public static int calculateSign(double prevLatitude, double prevLongitude, double latitude, double longitude, double prevOrientation) {
        double delta = calculateOrientationDelta(prevLatitude, prevLongitude, latitude, longitude, prevOrientation);
        double absDelta = Math.abs(delta);

        if (absDelta < 0.6) {
            // 0.2 ~= 1 1°
            return Instruction.STRAIGHT;

        } else if (absDelta < 1.0) {
            // 0.8 ~= 40°
            if (delta > 0)
                return Instruction.SLIGHT_LEFT;
            else
                return Instruction.SLIGHT_RIGHT;

        } else if (absDelta < 2.46) {
            // 1.8 ~= 103°
            if (delta > 0)
                return Instruction.LEFT;
            else
                return Instruction.RIGHT;

        } else {
            if (delta > 0)
                return Instruction.SHARP_LEFT;
            else
                return Instruction.SHARP_RIGHT;

        }
    }


    /*
     * if distance between baseNode of instruction and adjNode of outgoing edge is much more then distance off instruction
     * then probably on a roundabout
     */
    static boolean isOnRoundabout(Instruction instruction, EdgeIteratorState outgoingEdge, int sign) {

        if (instruction.getPrevInstruction() == null)  {
            return false;
        }

        if (instruction.getSign() == sign) {
            double delteAzi = calcDeltaAzumith(instruction, outgoingEdge);
            return !(delteAzi > 150); // not opposite orientation

        }
        return false;
    }



    static boolean isContinueStraight(Instruction instruction, EdgeIteratorState outgoingEdge, int sign2) {

        if (instruction.getPrevInstruction() == null) {
            return false;
        }



        if (isOppositeTurn(instruction.getSign(), sign2)) {

            if (calcDistanceToBeelineFactor(instruction, outgoingEdge) > 0.8){
                return true;
            }

            double deltaAzi = calcDeltaAzumith(instruction, outgoingEdge);
            return (deltaAzi < 20); // same orientation

        }

        return false;

    }


    static boolean isOppositeTurn(int sign1, int sign2) {
        if (sign1 == Instruction.IGNORE || sign2 == Instruction.IGNORE) {
            return false;
        }
        if (Math.abs(sign1) < Instruction.USE_ROUNDABOUT && Math.abs(sign2) < Instruction.USE_ROUNDABOUT) {
            if (sign1 < 0 && sign2 > 0) {
                return true;
            } else if (sign1 > 0 && sign2 < 0) {
                return true;
            }
        }

        return false;
    }


    static double calcOppositeDistance(Instruction instruction, EdgeIteratorState outgoingEdge) {
        if (instruction.getPrevInstruction() == null) {
            return Integer.MAX_VALUE;
        }


        PointList instrGeom = instruction.getPoints();
        PointList inGeom = instruction.getPrevInstruction().getPoints();
        PointList outGeom = outgoingEdge.fetchWayGeometry(3);
        double fromLat = inGeom.getLat(0);
        double fromLon = inGeom.getLon(0);
        double toLat = (instrGeom.size() > 1) ? instrGeom.getLat(instrGeom.size() - 1) : outGeom.getLat(0);
        double toLon = (instrGeom.size() > 1) ? instrGeom.getLon(instrGeom.size() - 1) : outGeom.getLon(0);

        double azu = Helper.ANGLE_CALC.calcAzimuth(fromLat, fromLon, toLat, toLon);

        return instruction.getDistance() * Math.sin(azu);
    }


    private static double calcDistanceToBeelineFactor(Instruction instruction, EdgeIteratorState outgoingEdge) {
        double distance = instruction.getPrevInstruction().getDistance() + instruction.getDistance();


        PointList instrGeom = instruction.getPoints();
        PointList inGeom = instruction.getPrevInstruction().getPoints();
        PointList outGeom = outgoingEdge.fetchWayGeometry(3);

        double fromLat = (instrGeom.size() > 1) ? instrGeom.getLat(instrGeom.size() - 1) : outGeom.getLat(0);
        double fromLon = (instrGeom.size() > 1) ? instrGeom.getLon(instrGeom.size() - 1) : outGeom.getLon(0);
        double toLat = outGeom.getLat(outGeom.size() - 1);
        double toLon = outGeom.getLon(outGeom.size() - 1);

        distance += Helper.DIST_EARTH.calcDist(fromLat, fromLon, toLat, toLon);

        fromLat = inGeom.getLat(0);
        fromLon = inGeom.getLon(0);


        double beelineDistance = Helper.DIST_EARTH.calcDist(fromLat, fromLon, toLat, toLon);

        return beelineDistance / distance;

    }

    private static double calcDeltaAzumith(Instruction instruction, EdgeIteratorState outgoingEdge) {

        PointList instrGeom = instruction.getPoints();
        PointList inGeom = instruction.getPrevInstruction().getPoints();
        PointList outGeom = outgoingEdge.fetchWayGeometry(3);
        double lat1 = inGeom.getLat(0);
        double lon1 = inGeom.getLon(0);
        double lat2 = instrGeom.getLat(0);
        double lon2 = instrGeom.getLon(0);

        double azi1 = Helper.ANGLE_CALC.calcAzimuth(lat1, lon1, lat2, lon2);

        double toLat1 = (instrGeom.size() > 1) ? instrGeom.getLat(instrGeom.size() - 1) : outGeom.getLat(0);
        double toLon1 = (instrGeom.size() > 1) ? instrGeom.getLon(instrGeom.size() - 1) : outGeom.getLon(0);
        double toLat2 = outGeom.getLat(outGeom.size() - 1);
        double toLon2 = outGeom.getLon(outGeom.size() - 1);

        double azi2 = Helper.ANGLE_CALC.calcAzimuth(toLat1, toLon1, toLat2, toLon2);

        return Math.abs(azi1 - azi2);
    }


    static boolean isNameSimilar(String name1, String name2) {
        // We don't want two empty names to be similar
        // The idea is, if there are only a random tracks, they usually don't have names
        if (name1.isEmpty() || name2.isEmpty()) {
            return true;
        }
        if (name1.equals(name2)) {
            return true;
        }
        return false;
    }

    static GHPoint getPointForOrientationCalculation(EdgeIteratorState edgeIteratorState, NodeAccess nodeAccess) {
        double tmpLat;
        double tmpLon;
        PointList tmpWayGeo = edgeIteratorState.fetchWayGeometry(3);
        if (tmpWayGeo.getSize() <= 2) {
            tmpLat = nodeAccess.getLatitude(edgeIteratorState.getAdjNode());
            tmpLon = nodeAccess.getLongitude(edgeIteratorState.getAdjNode());
        } else {
            tmpLat = tmpWayGeo.getLatitude(1);
            tmpLon = tmpWayGeo.getLongitude(1);
        }
        return new GHPoint(tmpLat, tmpLon);
    }
}