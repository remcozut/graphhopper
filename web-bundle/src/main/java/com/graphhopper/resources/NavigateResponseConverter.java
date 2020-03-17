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
package com.graphhopper.resources;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphhopper.GHResponse;
import com.graphhopper.PathWrapper;
import com.graphhopper.http.WebHelper;
import com.graphhopper.util.*;
import org.locationtech.jts.io.WKTWriter;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class NavigateResponseConverter {

    private static final int VOICE_INSTRUCTION_MERGE_TRESHHOLD = 100;

    /**
     * Converts a GHResponse into a json that follows the Mapbox API specification
     */
    public static ObjectNode convertFromGHResponse(GHResponse ghResponse, TranslationMap translationMap, TranslationMap navigateResponseConverterTranslationMap, Locale locale) {
        ObjectNode json = JsonNodeFactory.instance.objectNode();

        if (ghResponse.hasErrors())
            throw new IllegalStateException("If the response has errors, you should use the method NavigateResponseConverter#convertFromGHResponseError");



        PointList waypoints = ghResponse.getBest().getWaypoints();

        final ArrayNode routesJson = json.putArray("routes");

        List<PathWrapper> paths = ghResponse.getAll();

        for (int i = 0; i < paths.size(); i++) {
            PathWrapper path = paths.get(i);
            ObjectNode pathJson = routesJson.addObject();

            putRouteInformation(pathJson, path, i, translationMap, navigateResponseConverterTranslationMap, locale);
        }

        final ArrayNode waypointsJson = json.putArray("waypoints");
        for (int i = 0; i < waypoints.size(); i++) {
            ObjectNode waypointJson = waypointsJson.addObject();
            // TODO get names
            waypointJson.put("name", "");
            putLocation(waypoints.getLat(i), waypoints.getLon(i), waypointJson);
        }

        json.put("code", "Ok");
        // TODO: Maybe we need a different format... uuid: "cji4ja4f8004o6xrsta8w4p4h"
        json.put("uuid", UUID.randomUUID().toString().replaceAll("-", ""));

        return json;
    }

    private static void putRouteInformation(ObjectNode pathJson, PathWrapper path, int routeNr, TranslationMap translationMap, TranslationMap navigateResponseConverterTranslationMap, Locale locale) {
        InstructionList instructions = path.getInstructions();

        pathJson.put("geometry", WebHelper.encodePolyline(path.getPoints(), false, 1e6));
        ArrayNode legsJson = pathJson.putArray("legs");

        ObjectNode legJson = legsJson.addObject();
        ArrayNode steps = legJson.putArray("steps");

        double weight = 0;
        long time = 0;
        double distance = 0;
        boolean isFirstInstructionOfLeg = true;

        for (int i = 0; i < instructions.size(); i++) {
            Instruction instruction = instructions.get(i);
            ObjectNode instructionJson = steps.addObject();
            putInstruction(instructions, i, locale, translationMap, navigateResponseConverterTranslationMap, instructionJson, isFirstInstructionOfLeg);
            weight += instruction.getWeight();
            time += instruction.getTime();
            distance += instruction.getDistance();
            isFirstInstructionOfLeg = false;
            if (instruction.getSign() == Instruction.REACHED_VIA || instruction.getSign() == Instruction.FINISH) {
                putLegInformation(legJson, path, routeNr, weight, time, distance);
                isFirstInstructionOfLeg = true;
                time = 0;
                distance = 0;
                weight = 0;

                if (instruction.getSign() == Instruction.REACHED_VIA ) {
                    // Create new leg and steps after a via points
                    legJson = legsJson.addObject();
                    steps = legJson.putArray("steps");
                }
            }
        }


//        pathJson.put("mean_speed", Helper.round(3.6 * path.getDistance() / convertToSeconds(path.getTime()), 1));
        pathJson.put("weight_name", "routability");
        pathJson.put("weight", Helper.round(path.getRouteWeight(), 1));
        pathJson.put("duration", convertToSeconds(path.getTime()));
        pathJson.put("distance", Helper.round(path.getDistance(), 1));
        pathJson.put("voiceLocale", locale.toLanguageTag());
    }

    private static void putLegInformation(ObjectNode legJson, PathWrapper path, int i, double weight, long time, double distance) {
        // TODO: Improve path descriptions, so that every path has a description, not just alternative routes
        String summary;
        if (!path.getDescription().isEmpty())
            summary = String.join(",", path.getDescription());
        else
            summary = "Velomatic Route " + i;
        legJson.put("summary", summary);
//        legJson.put("mean_speed", Helper.round(3.6 * distance / convertToSeconds(time), 1));
        legJson.put("weight", Helper.round(weight, 1));
        legJson.put("duration", convertToSeconds(time));
        legJson.put("distance", Helper.round(distance, 1));
    }

    private static ObjectNode putInstruction(InstructionList instructions, int index, Locale locale, TranslationMap translationMap, TranslationMap navigateResponseConverterTranslationMap, ObjectNode instructionJson, boolean isFirstInstructionOfLeg) {
        Instruction instruction = instructions.get(index);

        ArrayNode intersections = instructionJson.putArray("intersections");
        ObjectNode intersection = intersections.addObject();
        intersection.putArray("entry");
        intersection.putArray("bearings");
        //Make pointList mutable

        PointList pointList = instruction.getPoints().clone(false);

        if (index + 2 < instructions.size()) {
            // Add the first point of the next instruction
            PointList nextPoints = instructions.get(index + 1).getPoints();
            if (nextPoints.getSize() > 0) {
                pointList.add(nextPoints.getLat(0), nextPoints.getLon(0), nextPoints.getEle(0));
            } else {
                nextPoints = instructions.get(index + 2).getPoints();
                pointList.add(nextPoints.getLat(0), nextPoints.getLon(0), nextPoints.getEle(0));

            }
        } else if (pointList.size() == 1) {
            // Duplicate the last point in the arrive instruction, if the size is 1
            pointList.add(pointList.getLat(0), pointList.getLon(0), pointList.getEle(0));
        }

        putLocation(pointList.getLat(0), pointList.getLon(0), intersection);

        instructionJson.put("driving_side", "right");

        // Does not include elevation
        instructionJson.put("geometry", WebHelper.encodePolyline(pointList, false, 1e6));

        instructionJson.put("wkt", (new WKTWriter()).write(pointList.toLineString(false)));
//        instructionJson.put("geometry", WebHelper.convertPointListToString(pointList));
        // TODO: how about other modes?

        if (instruction.getWayType() == WayType.FERRY) {
            instructionJson.put("mode", "ferry");
        } else if (instruction.getWayType() == WayType.CYCLEWAY) {
            instructionJson.put("mode", "cycling");
        } else if (instruction.getWayType() == WayType.PUSHING_SECTION) {
            instructionJson.put("mode", "walking");
        } else {
            instructionJson.put("mode", "driving");
        }

        putManeuver(instruction, instructionJson, locale, translationMap, isFirstInstructionOfLeg);

        // TODO distance = weight, is weight even important?
        double distance = Helper.round(instruction.getDistance(), 1);
        instructionJson.put("weight", distance);
        instructionJson.put("duration", convertToSeconds(instruction.getTime()));
        instructionJson.put("name", instruction.getName());
        instructionJson.put("distance", distance);

        ArrayNode voiceInstructions = instructionJson.putArray("voiceInstructions");
        ArrayNode bannerInstructions = instructionJson.putArray("bannerInstructions");

        // Voice and banner instructions are empty for the last element
        if (index + 1 < instructions.size()) {
            putVoiceInstructions(instructions, distance, index, locale, translationMap, navigateResponseConverterTranslationMap, voiceInstructions);
            putBannerInstructions(instructions, distance, index, locale, translationMap, bannerInstructions);
        }

        return instructionJson;
    }

    private static void putVoiceInstructions(InstructionList instructions, double distance, int index, Locale locale, TranslationMap translationMap, TranslationMap navigateResponseConverterTranslationMap, ArrayNode voiceInstructions) {
        /*
            A VoiceInstruction Object looks like this
            {
                distanceAlongGeometry: 40.9,
                announcement: "Exit the traffic circle",
                ssmlAnnouncement: "<speak><amazon:effect name="drc"><prosody rate="1.08">Exit the traffic circle</prosody></amazon:effect></speak>",
            }
        */
        Instruction nextInstruction = instructions.get(index + 1);
        String turnDescription = nextInstruction.getTurnDescription(translationMap.getWithFallBack(locale));

        double distanceForInitialStayInstruction = 4250;
        if (distance > distanceForInitialStayInstruction) {
            // The instruction should not be spoken straight away, but wait until the user merged on the new road and can listen to instructions again
            double tmpDistance = distance - 250;
            int spokenDistance = (int) (tmpDistance / 1000);
            String continueDescription = translationMap.getWithFallBack(locale).tr("continue") + " " + navigateResponseConverterTranslationMap.getWithFallBack(locale).tr("for_km", spokenDistance);
            // TODO In the worst case scenario it might be over 1km after merging onto the road until this instruction is spoken (e.g. (5249-250/1000)*1000=4000 - because java is rounding down)
            // TODO this might be annoying for unnecessary keeps on the motorway, especially if they happen more often then every 10km
            putSingleVoiceInstruction(spokenDistance * 1000, continueDescription, voiceInstructions);
        }

        double far = 2000;
        double mid = 1000;
        double close = 400;
        double veryClose = 200;

        String thenVoiceInstruction = getThenVoiceInstructionpart(instructions, index, locale, translationMap, navigateResponseConverterTranslationMap);

        if (distance > far) {
            putSingleVoiceInstruction(far, navigateResponseConverterTranslationMap.getWithFallBack(locale).tr("in_km", 2) + " " + turnDescription, voiceInstructions);
        }
        if (distance > mid) {
            putSingleVoiceInstruction(mid, navigateResponseConverterTranslationMap.getWithFallBack(locale).tr("in_km_singular") + " " + turnDescription, voiceInstructions);
        }
        if (distance > close) {
            putSingleVoiceInstruction(close, navigateResponseConverterTranslationMap.getWithFallBack(locale).tr("in_m", 400) + " " + turnDescription + thenVoiceInstruction, voiceInstructions);
        } else if (distance > veryClose) {
            // This is an edge case when turning on narrow roads in cities, too close for the close turn, but too far for the direct turn
            putSingleVoiceInstruction(veryClose, navigateResponseConverterTranslationMap.getWithFallBack(locale).tr("in_m", 200) + " " + turnDescription + thenVoiceInstruction, voiceInstructions)
            ;
        }

        // Speak 80m instructions 80 before the turn
        // Note: distanceAlongGeometry: "how far from the upcoming maneuver the voice instruction should begin"
        double distanceAlongGeometry = Helper.round(Math.min(distance, 80), 1);

        // Special case for the arrive instruction
        if (index + 2 == instructions.size())
            distanceAlongGeometry = Helper.round(Math.min(distance, 25), 1);

        putSingleVoiceInstruction(distanceAlongGeometry, turnDescription + thenVoiceInstruction, voiceInstructions);
    }

    private static void putSingleVoiceInstruction(double distanceAlongGeometry, String turnDescription, ArrayNode voiceInstructions) {
        ObjectNode voiceInstruction = voiceInstructions.addObject();
        voiceInstruction.put("distanceAlongGeometry", distanceAlongGeometry);
        //TODO: ideally, we would even generate instructions including the instructions after the next like turn left **then** turn right
        voiceInstruction.put("announcement", turnDescription);
        voiceInstruction.put("ssmlAnnouncement", "<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">" + turnDescription + "</prosody></amazon:effect></speak>");
    }

    /**
     * For close turns, it is important to announce the next turn in the earlier instruction.
     * e.g.: instruction i+1= turn right, instruction i+2=turn left, with instruction i+1 distance < VOICE_INSTRUCTION_MERGE_TRESHHOLD
     * The voice instruction should be like "turn right, then turn left"
     *
     * For instruction i+1 distance > VOICE_INSTRUCTION_MERGE_TRESHHOLD an empty String will be returned
     */
    private static String getThenVoiceInstructionpart(InstructionList instructions, int index, Locale locale, TranslationMap translationMap, TranslationMap navigateResponseConverterTranslationMap) {
        if (instructions.size() > index + 2) {
            Instruction firstInstruction = instructions.get(index + 1);
            if (firstInstruction.getDistance() < VOICE_INSTRUCTION_MERGE_TRESHHOLD) {
                Instruction secondInstruction = instructions.get(index + 2);
                if (secondInstruction.getSign() != Instruction.REACHED_VIA)
                    return ", " + navigateResponseConverterTranslationMap.getWithFallBack(locale).tr("then") + " " + secondInstruction.getTurnDescription(translationMap.getWithFallBack(locale));
            }
        }

        return "";
    }

    /**
     * Banner instructions are the turn instructions that are shown to the user in the top bar.
     *
     * Between two instructions we can show multiple banner instructions, you can control when they pop up using distanceAlongGeometry.
     */
    private static void putBannerInstructions(InstructionList instructions, double distance, int index, Locale locale, TranslationMap translationMap, ArrayNode bannerInstructions) {
        /*
        A BannerInstruction looks like this
        distanceAlongGeometry: 107,
        primary: {
            text: "Lichtensteinstraße",
            components: [
            {
                text: "Lichtensteinstraße",
                type: "text",
            }
            ],
            type: "turn",
            modifier: "right",
        },
        secondary: null,
         */

        ObjectNode bannerInstruction = bannerInstructions.addObject();

        //Show from the beginning
        bannerInstruction.put("distanceAlongGeometry", distance);

        ObjectNode primary = bannerInstruction.putObject("primary");
        putSingleBannerInstruction(instructions.get(index + 1), locale, translationMap, primary);

        putBannerNodeNameInstruction(instructions, index, bannerInstruction);

        if (instructions.size() > index + 2 && (instructions.get(index + 2).getSign() != Instruction.REACHED_VIA)) {
            // Sub shows the instruction after the current one
            ObjectNode sub = bannerInstruction.putObject("sub");
            putSingleBannerInstruction(instructions.get(index + 2), locale, translationMap, sub);
        }
    }


    private static void putSingleBannerInstruction(Instruction instruction, Locale locale, TranslationMap translationMap, ObjectNode singleBannerInstruction) {
        String bannerInstructionName = instruction.getName();


        if (instruction.getAnnotation().getImportance() > 0) {
            bannerInstructionName = instruction.getAnnotation().getMessage();
        }

        if (bannerInstructionName == null || bannerInstructionName.isEmpty()) {
            // Fix for final instruction and for instructions without name
            bannerInstructionName = instruction.getTurnDescription(translationMap.getWithFallBack(locale));

            // Uppercase first letter
            // TODO: should we do this for all cases? Then we might change the spelling of street names though
            bannerInstructionName = Helper.firstBig(bannerInstructionName);
        }


        singleBannerInstruction.put("text", bannerInstructionName);
        ArrayNode components = singleBannerInstruction.putArray("components");
        ObjectNode component = components.addObject();
        component.put("text", bannerInstructionName);
        component.put("type", "text");

        singleBannerInstruction.put("type", getTurnType(instruction, false));
        String modifier = getModifier(instruction);

        if (instruction.getSign() == Instruction.USE_ROUNDABOUT) {
            if (instruction instanceof RoundaboutInstruction) {
                double turnAngle = ((RoundaboutInstruction) instruction).getTurnAngle();
                if (Double.isNaN(turnAngle)) {
                    singleBannerInstruction.putNull("degrees");
                } else {
                    double degree = (Math.abs(turnAngle) * 180) / Math.PI;
                    singleBannerInstruction.put("degrees", degree);

                    // RZU: de richting moet bijgewerkt op basis van de hoek van uitrijden.
                    modifier = getRoundAboutModifier(degree);



                }
            }
        }

        if (modifier != null)
            singleBannerInstruction.put("modifier", modifier);

    }

    /**
     * Banner instructions are the turn instructions that are shown to the user in the top bar.
     *
     * Between two instructions we can show multiple banner instructions, you can control when they pop up using distanceAlongGeometry.
     */
    private static void putBannerNodeNameInstruction(InstructionList instructions, int index, ObjectNode bannerInstruction) {


        //Show from the beginning
//        bannerInstruction.put("distanceAlongGeometry", distance);

        int next = 1;
        if (index+2 < instructions.size()) {
            if (instructions.get(index + 1).getExtraInfoJSON().containsKey("nodeName")) {
                next += 2;
            }
        }
        double junctionDistance = 0;
        String nodeName = "";
        ObjectNode secondary = bannerInstruction.putObject("secondary");
        for(int i = index + next; i < instructions.size(); i++) {
            junctionDistance +=instructions.get(i).getDistance();

            if (instructions.get(i).getExtraInfoJSON().containsKey("nodeName")) {
                nodeName = String.valueOf(instructions.get(i).getExtraInfoJSON().get("nodeName"));
                break;
            }
        }

        secondary.put("text", nodeName);



        ArrayNode components = secondary.putArray("components");
        ObjectNode component = components.addObject();
        component.put("text", nodeName);
        component.put("type", "text");

        secondary.put("type", "notification");
        secondary.put("modifier", "straight");


        if (nodeName.isEmpty()) {
            junctionDistance = 0;
        }

//        secondary.put("degrees", junctionDistance);


    }
    private static String getRoundAboutModifier(double degree) {

        if (degree <= 67.5) {
            return getModifier(new Instruction(Instruction.SHARP_RIGHT));
        } else if (degree <= 112.5) {
            return getModifier(new Instruction(Instruction.RIGHT));
        } else if (degree <= 157.5) {
            return getModifier(new Instruction(Instruction.SLIGHT_RIGHT));
        } else if (degree <= 202.5) {
            return getModifier(new Instruction(Instruction.STRAIGHT));
        } else if (degree <= 247.5) {
            return getModifier(new Instruction(Instruction.SLIGHT_LEFT));
        } else if (degree <= 292.5) {
            return getModifier(new Instruction(Instruction.LEFT));
        } else if (degree <= 337.5) {
            return getModifier(new Instruction(Instruction.SHARP_LEFT));
        }
        return getModifier(new Instruction(Instruction.RIGHT));


    }

    private static void putManeuver(Instruction instruction, ObjectNode instructionJson, Locale locale, TranslationMap translationMap, boolean isFirstInstructionOfLeg) {
        ObjectNode maneuver = instructionJson.putObject("maneuver");
        maneuver.put("bearing_after", 0);
        maneuver.put("bearing_before", 0);

        PointList points = instruction.getPoints();
        putLocation(points.getLat(0), points.getLon(0), maneuver);

        String modifier = getModifier(instruction);
        if (modifier != null)
            maneuver.put("modifier", modifier);

        maneuver.put("type", getTurnType(instruction, isFirstInstructionOfLeg));
        // exit number
        if (instruction instanceof RoundaboutInstruction)
            maneuver.put("exit", ((RoundaboutInstruction) instruction).getExitNumber());

        maneuver.put("instruction", instruction.getTurnDescription(translationMap.getWithFallBack(locale)));

    }

    /**
     * Relevant maneuver types are:
     * depart (firs instruction)
     * turn (regular turns)
     * roundabout (enter roundabout, maneuver contains also the exit number)
     * arrive (last instruction and waypoints)
     *
     * You can find all maneuver types at: https://www.mapbox.com/api-documentation/#maneuver-types
     */
    private static String getTurnType(Instruction instruction, boolean isFirstInstructionOfLeg) {
        if (isFirstInstructionOfLeg) {
            return "depart";
        } else {
            return instruction.getTurnType().getValue();
        }
    }

    /**
     * No modifier values for arrive and depart
     *
     * Find modifier values here: https://www.mapbox.com/api-documentation/#stepmaneuver-object
     */
    private static String getModifier(Instruction instruction) {
        switch (instruction.getSign()) {
            case Instruction.STRAIGHT:
                return "straight";
            case Instruction.U_TURN_LEFT:
                return "uturn left";
            case Instruction.U_TURN_RIGHT:
                return "uturn right";
            case Instruction.SLIGHT_LEFT:
                return "slight left";
            case Instruction.LEFT:
                return "left";
            case Instruction.SHARP_LEFT:
                return "sharp left";
            case Instruction.SLIGHT_RIGHT:
                return "slight right";
            case Instruction.RIGHT:
                return "right";
            case Instruction.SHARP_RIGHT:
                return "sharp right";
            case Instruction.USE_ROUNDABOUT:
                // TODO: This might be an issue in left-handed traffic, because there it schould be left
                return "right";
            default:
                return "straight";
        }
    }

    /**
     * Puts a location array in GeoJson format into the node
     */
    private static ObjectNode putLocation(double lat, double lon, ObjectNode node) {
        ArrayNode location = node.putArray("location");
        // GeoJson lon,lat
        location.add(Helper.round6(lon));
        location.add(Helper.round6(lat));
        return node;
    }

    /**
     * Mapbox uses seconds instead of milliSeconds
     */
    private static double convertToSeconds(double milliSeconds) {
        return Helper.round(milliSeconds / 1000, 1);
    }

    public static ObjectNode convertFromGHResponseError(GHResponse ghResponse) {
        ObjectNode json = JsonNodeFactory.instance.objectNode();
        // TODO we could make this more fine grained
        json.put("code", "InvalidInput");
        json.put("message", ghResponse.getErrors().get(0).getMessage());
        return json;
    }
}
