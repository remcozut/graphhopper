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
package com.graphhopper.routing.util;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.util.PMap;

import java.util.TreeMap;

import static com.graphhopper.routing.util.PriorityCode.*;

/**
 * Specifies the settings for race biking
 * <p>
 *
 * @author ratrun
 * @author Peter Karich
 */
public class RacingBikeFlagEncoder extends BikeCommonFlagEncoder {
    public RacingBikeFlagEncoder() {
        this(4, 2, 0);
    }

    public RacingBikeFlagEncoder(PMap properties) {
        this(
                (int) properties.getLong("speed_bits", 4),
                properties.getDouble("speed_factor", 2),
                properties.getBool("turn_costs", false) ? 1 : 0
        );
        this.properties = properties;
        this.setBlockFords(properties.getBool("block_fords", true));
    }

    public RacingBikeFlagEncoder(String propertiesStr) {
        this(new PMap(propertiesStr));
    }

    public RacingBikeFlagEncoder(int speedBits, double speedFactor, int maxTurnCosts) {
        super(speedBits, speedFactor, maxTurnCosts);
        preferHighwayTags.add("road");
        preferHighwayTags.add("secondary");
        preferHighwayTags.add("secondary_link");
        preferHighwayTags.add("tertiary");
        preferHighwayTags.add("tertiary_link");
        preferHighwayTags.add("residential");

        final int CYCLEWAY_SPEED = 24;  // Make sure cycleway and path use same speed value, see #634


        setTrackTypeSpeed("grade1", CYCLEWAY_SPEED); // paved
        setTrackTypeSpeed("grade2", CYCLEWAY_SPEED / 2); // now unpaved ...
        setTrackTypeSpeed("grade3", PUSHING_SECTION_SPEED);
        setTrackTypeSpeed("grade4", PUSHING_SECTION_SPEED);
        setTrackTypeSpeed("grade5", PUSHING_SECTION_SPEED);

        setSurfaceSpeed("paved", CYCLEWAY_SPEED );
        setSurfaceSpeed("asphalt", CYCLEWAY_SPEED );
        setSurfaceSpeed("cobblestone", 20);
        setSurfaceSpeed("cobblestone:flattened", 20);
        setSurfaceSpeed("sett", 20);
        setSurfaceSpeed("concrete", CYCLEWAY_SPEED );
        setSurfaceSpeed("concrete:lanes", CYCLEWAY_SPEED );
        setSurfaceSpeed("concrete:plates", CYCLEWAY_SPEED );
        setSurfaceSpeed("paving_stones", CYCLEWAY_SPEED );
        setSurfaceSpeed("paving_stones:30", CYCLEWAY_SPEED );
        setSurfaceSpeed("unpaved", PUSHING_SECTION_SPEED);
        setSurfaceSpeed("compacted", CYCLEWAY_SPEED  / 2);
        setSurfaceSpeed("dirt", PUSHING_SECTION_SPEED );
        setSurfaceSpeed("earth", PUSHING_SECTION_SPEED);
        setSurfaceSpeed("fine_gravel", PUSHING_SECTION_SPEED);
        setSurfaceSpeed("grass", PUSHING_SECTION_SPEED );
        setSurfaceSpeed("grass_paver", PUSHING_SECTION_SPEED);
        setSurfaceSpeed("gravel", PUSHING_SECTION_SPEED );
        setSurfaceSpeed("ground", PUSHING_SECTION_SPEED );
        setSurfaceSpeed("ice", PUSHING_SECTION_SPEED / 2);
        setSurfaceSpeed("metal", PUSHING_SECTION_SPEED / 2);
        setSurfaceSpeed("mud", PUSHING_SECTION_SPEED / 2);
        setSurfaceSpeed("pebblestone", PUSHING_SECTION_SPEED);
        setSurfaceSpeed("salt", PUSHING_SECTION_SPEED / 2);
        setSurfaceSpeed("sand", PUSHING_SECTION_SPEED / 2);
        setSurfaceSpeed("wood", PUSHING_SECTION_SPEED / 2);

        setHighwaySpeed("cycleway", CYCLEWAY_SPEED);
        setHighwaySpeed("path", PUSHING_SECTION_SPEED );
        setHighwaySpeed("footway", PUSHING_SECTION_SPEED );
        setHighwaySpeed("pedestrian", PUSHING_SECTION_SPEED );
        setHighwaySpeed("road", CYCLEWAY_SPEED);
        setHighwaySpeed("track", PUSHING_SECTION_SPEED ); // assume unpaved
        setHighwaySpeed("service", 16);
        setHighwaySpeed("unclassified", 18);
        setHighwaySpeed("residential", 18);

        setHighwaySpeed("trunk", CYCLEWAY_SPEED);
        setHighwaySpeed("trunk_link", CYCLEWAY_SPEED);
        setHighwaySpeed("primary", CYCLEWAY_SPEED);
        setHighwaySpeed("primary_link", CYCLEWAY_SPEED);
        setHighwaySpeed("secondary", CYCLEWAY_SPEED);
        setHighwaySpeed("secondary_link", CYCLEWAY_SPEED);
        setHighwaySpeed("tertiary", CYCLEWAY_SPEED);
        setHighwaySpeed("tertiary_link", CYCLEWAY_SPEED);

        addPushingSection("path");
        addPushingSection("footway");
        addPushingSection("pedestrian");
        addPushingSection("steps");

        setCyclingNetworkPreference("icn", PriorityCode.VERY_NICE.getValue());
        setCyclingNetworkPreference("ncn", PriorityCode.VERY_NICE.getValue());
        setCyclingNetworkPreference("rcn", PriorityCode.VERY_NICE.getValue());
        setCyclingNetworkPreference("lcn", PriorityCode.UNCHANGED.getValue());
        setCyclingNetworkPreference("mtb", PriorityCode.UNCHANGED.getValue());

        absoluteBarriers.add("kissing_gate");

        setAvoidSpeedLimit(81);
        setSpecificClassBicycle("roadcycling");

        init();
    }

    @Override
    public int getVersion() {
        return 2;
    }

    @Override
    public boolean hasNodeNameReferences() {
        return false;
    }

    @Override
    void collect(ReaderWay way, double wayTypeSpeed, TreeMap<Double, Integer> weightToPrioMap) {
        super.collect(way, wayTypeSpeed, weightToPrioMap);

        String highway = way.getTag("highway");
        if ("service".equals(highway)) {
            weightToPrioMap.put(40d, UNCHANGED.getValue());
        } else if ("track".equals(highway)) {
            String trackType = way.getTag("tracktype");
            if ("grade1".equals(trackType))
                weightToPrioMap.put(110d, PREFER.getValue());
            else if (trackType == null || trackType.startsWith("grade"))
                weightToPrioMap.put(110d, AVOID_AT_ALL_COSTS.getValue());
        }
    }

    @Override
    boolean isPushingSection(ReaderWay way) {
        String highway = way.getTag("highway");
        String trackType = way.getTag("tracktype");
        return way.hasTag("highway", pushingSectionsHighways)
                || way.hasTag("railway", "platform")
                || way.hasTag("bicycle", "dismount")
                || "track".equals(highway) && trackType != null && !"grade1".equals(trackType);
    }

    @Override
    boolean isSacScaleAllowed(String sacScale) {
        // for racing bike it is only allowed if empty
        return false;
    }

    @Override
    public String toString() {
        return "racingbike";
    }
}
