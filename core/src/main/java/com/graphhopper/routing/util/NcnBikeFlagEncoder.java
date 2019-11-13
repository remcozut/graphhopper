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

import static com.graphhopper.routing.util.PriorityCode.*;

/**
 * Specifies the settings for cycletouring/trekking
 *
 * @author ratrun
 * @author Peter Karich
 */
public class NcnBikeFlagEncoder extends BikeCommonFlagEncoder {


    public NcnBikeFlagEncoder() {
        this(4, 2, 0);
    }

    public NcnBikeFlagEncoder(String propertiesString) {
        this(new PMap(propertiesString));
    }

    public NcnBikeFlagEncoder(PMap properties) {
        this((int) properties.getLong("speed_bits", 4),
                properties.getLong("speed_factor", 2),
                properties.getBool("turn_costs", false) ? 1 : 0);
        this.properties = properties;
        this.setBlockFords(properties.getBool("block_fords", true));
    }

    public NcnBikeFlagEncoder(int speedBits, double speedFactor, int maxTurnCosts) {
        super(speedBits, speedFactor, maxTurnCosts);
//        addPushingSection("path");
//        addPushingSection("footway");
//        addPushingSection("pedestrian");
//        addPushingSection("steps");

/*        avoidHighwayTags.add("trunk");
        avoidHighwayTags.add("trunk_link");
        avoidHighwayTags.add("primary");
        avoidHighwayTags.add("primary_link");
        avoidHighwayTags.add("secondary");
        avoidHighwayTags.add("secondary_link");*/

//        surfaceSpeeds.clear();

        preferHighwayTags.add("road");
        preferHighwayTags.add("track");
        preferHighwayTags.add("path");
        preferHighwayTags.add("service");
        preferHighwayTags.add("tertiary");
        preferHighwayTags.add("tertiary_link");
        preferHighwayTags.add("residential");
        preferHighwayTags.add("unclassified");


        nodeNameReferences.add("ncn_ref");
        nodeNameReferences.add("icn_ref");
        nodeNameReferences.add("rcn_ref");
//        nodeNameReferences.add("highway=traffic_signals");
//        nodeNameReferences.add("lcn_ref");

//        absoluteBarriers.add("kissing_gate");
//        absoluteBarriers.add("gate");
//        absoluteBarriers.add("lift_gate");

        setSpecificClassBicycle("touring");

        setCyclingNetworkPreference("icn", VERY_NICE.getValue());
        setCyclingNetworkPreference("ncn", BEST.getValue());
        setCyclingNetworkPreference("rcn", BEST.getValue());
        setCyclingNetworkPreference("lcn", UNCHANGED.getValue());
        setCyclingNetworkPreference("mtb", UNCHANGED.getValue());




        init();
    }



        @Override
    public int getVersion() {
        return 2;
    }

    @Override
    public boolean hasNodeNameReferences() {
        return ! nodeNameReferences.isEmpty();
    }

    @Override
    boolean isPushingSection(ReaderWay way) {
        String highway = way.getTag("highway");
        String trackType = way.getTag("tracktype");
        return super.isPushingSection(way) || "track".equals(highway) && trackType != null && !"grade1".equals(trackType);
    }

    @Override
    public String toString() {
        return "ncnbike";
    }
}
