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

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopperAPI;
import com.graphhopper.routing.util.HintsMap;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.graphhopper.util.Parameters.Routing.*;

/**
 * Provides an endpoint that is consumable with the Mapbox Navigation SDK. The Mapbox Navigation SDK consumes json
 * responses that follow the specification of the Mapbox API v5.
 *
 * See: https://www.mapbox.com/api-documentation/#directions
 *
 * The baseurl of this endpoint is: [YOUR-IP/HOST]/navigate
 * The version of this endpoint is: v5
 * The user of this endpoint is: gh
 *
 * @author Robin Boldt
 */
@Path("service/navigate/directions/v5/mapbox")
public class NavigateResource {

    private static final Logger logger = LoggerFactory.getLogger(NavigateResource.class);

    private final GraphHopperAPI graphHopper;
    private final TranslationMap translationMap;
    private static final TranslationMap navigateResponseConverterTranslationMap = new NavigateResponseConverterTranslationMap().doImport();

    @Inject
    public NavigateResource(GraphHopperAPI graphHopper, TranslationMap translationMap) {
        this.graphHopper = graphHopper;
        this.translationMap = translationMap;
    }

    @GET
    @Path("/{profile}/{coordinatesArray : .+}")
    @Produces({MediaType.APPLICATION_JSON})
    public Response doGet(
            @Context HttpServletRequest httpReq,
            @Context UriInfo uriInfo,
            @Context ContainerRequestContext rc,
            @QueryParam("steps") @DefaultValue("true") boolean enableInstructions,
            @QueryParam("voice_instructions") @DefaultValue("true") boolean voiceInstructions,
            @QueryParam("banner_instructions") @DefaultValue("true") boolean bannerInstructions,
            @QueryParam("roundabout_exits") @DefaultValue("true") boolean roundaboutExits,
//            @QueryParam("voice_units") @DefaultValue("metric") String voiceUnits,
            @QueryParam("overview") @DefaultValue("simplified") String overview,
            @QueryParam("geometries") @DefaultValue("polyline6") String geometries,
            @QueryParam("bearings") @DefaultValue("") String bearings,
            @QueryParam("language") @DefaultValue("nl") String localeStr,
            @QueryParam("algorithm") @DefaultValue("") String algoStr,
            @QueryParam(VERSIONCODE) @DefaultValue("-1") int versionCode,
            @PathParam("profile") String profile) {

        /*
            Mapbox always uses fastest or priority weighting, except for walking then it's shortest
            https://www.mapbox.com/api-documentation/#directions
         */
        final String weighting = "fastest";

        /*
            Currently, the NavigateResponseConverter is pretty limited.
            Therefore, we enforce these values to make sure the client does not receive an unexpected answer.
         */
        if (!geometries.equals("polyline6"))
            throw new IllegalArgumentException("Currently, we only support polyline6");
        if (!enableInstructions)
            throw new IllegalArgumentException("Currently, you need to enable steps");
        if (!roundaboutExits)
            throw new IllegalArgumentException("Roundabout exits have to be enabled right now");
        if (!voiceInstructions)
            throw new IllegalArgumentException("You need to enable voice instructions right now");
        if (!bannerInstructions)
            throw new IllegalArgumentException("You need to enable banner instructions right now");

        double minPathPrecision = 1;
        if (overview.equals("full"))
            minPathPrecision = 0;

        if (versionCode == -1) {
            versionCode = Constants.VERSIONCODE;
        }

        String vehicleStr = convertProfileToGraphHopperVehicleString(profile);
        List<GHPoint> requestPoints = getPointsFromRequest(httpReq, profile);

        List<Double> favoredHeadings = getBearing(bearings);
        if (favoredHeadings.size() > 0 && favoredHeadings.size() != requestPoints.size()) {
            throw new IllegalArgumentException("Number of bearings and waypoints did not match");
        }

        StopWatch sw = new StopWatch().start();

        GHResponse ghResponse = calcRoute(uriInfo, favoredHeadings, requestPoints, vehicleStr, weighting, localeStr, algoStr, enableInstructions, minPathPrecision, versionCode);

        // Only do this, when there are more than 2 points, otherwise we use alternative routes
        if (!ghResponse.hasErrors() && favoredHeadings.size() > 0) {
            GHResponse noHeadingResponse = calcRoute(uriInfo, Collections.EMPTY_LIST, requestPoints, vehicleStr, weighting, localeStr, algoStr, enableInstructions, minPathPrecision, versionCode);
            if (ghResponse.getBest().getDistance() != noHeadingResponse.getBest().getDistance()) {
                ghResponse.getAll().add(noHeadingResponse.getBest());
            }
        }

        float took = sw.stop().getSeconds();
        String infoStr = httpReq.getRemoteAddr() + " " + httpReq.getLocale() + " " + httpReq.getHeader("User-Agent");
        String logStr = httpReq.getQueryString() + " " + infoStr + " " + requestPoints + ", took:"
                + took + ", " + weighting + ", " + vehicleStr;

        if (ghResponse.hasErrors()) {
            logger.error(logStr + ", errors:" + ghResponse.getErrors());
            // Mapbox specifies 422 return type for input errors
            return Response.status(422).entity(NavigateResponseConverter.convertFromGHResponseError(ghResponse)).
                    header("X-GH-Took", "" + Math.round(took * 1000)).
                    build();
        } else {
            return Response.ok(NavigateResponseConverter.convertFromGHResponse(ghResponse, translationMap, navigateResponseConverterTranslationMap, Helper.getLocale(localeStr))).
                    header("X-GH-Took", "" + Math.round(took * 1000)).
                    build();
        }
    }

    private GHResponse calcRoute(UriInfo uriInfo, List<Double> favoredHeadings, List<GHPoint> requestPoints, String vehicleStr, String weighting, String localeStr, String algoStr, boolean enableInstructions, double minPathPrecision, int versionCode) {
        GHRequest request;
        if (favoredHeadings.size() > 0) {
            request = new GHRequest(requestPoints, favoredHeadings);
        } else {
            request = new GHRequest(requestPoints);
        }

        initHints(request.getHints(), uriInfo.getQueryParameters());


        request.setVehicle(vehicleStr).
                setWeighting(weighting).
                setLocale(localeStr).
                setAlgorithm(algoStr).
                setVersionCode(versionCode).
                getHints().
                put(CALC_POINTS, true).
                put(INSTRUCTIONS, enableInstructions).
                put(WAY_POINT_MAX_DISTANCE, minPathPrecision).
//                put(Parameters.CH.DISABLE, true).
                put(Parameters.Routing.PASS_THROUGH, false);

        return graphHopper.route(request);
    }

    /**
     * This method is parsing the request URL String. Unfortunately it seems that there is no better options right now.
     * See: https://stackoverflow.com/q/51420380/1548788
     *
     * The url looks like: ".../{profile}/1.522438,42.504606;1.527209,42.504776;1.526113,42.505144;1.527218,42.50529?.."
     */
    private List<GHPoint> getPointsFromRequest(HttpServletRequest httpServletRequest, String profile) {

        String url = httpServletRequest.getRequestURI();
        url = url.replaceFirst("/service/navigate/directions/v5/mapbox/" + profile + "/", "");
        url = url.replace(".json", "");
        url = url.replaceAll("\\?[*]]", "");

        String[] pointStrings = url.split(";");

        List<GHPoint> points = new ArrayList<>(pointStrings.length);
        for (int i = 0; i < pointStrings.length; i++) {
            points.add(GHPoint.fromStringLonLat(pointStrings[i]));
        }

        return points;
    }

    private String convertProfileToGraphHopperVehicleString(String profile) {
        switch (profile) {
            case "driving":
                // driving-traffic is mapped to regular car as well
            case "driving-traffic":
                return "car";
/*            case "walking":
                return "foot";*/
            case "cycling":
                return "bike";
            case "cycling-recreational": {
                return "ncnbike";
            }
            case "cycling-mountain":
                return "mtb";
            case "cycling-race":
                return "racingbike";
            default:
                throw new IllegalArgumentException("Not supported profile: " + profile);
        }
    }

    static List<Double> getBearing(String bearingString) {
        if (bearingString == null || bearingString.isEmpty())
            return Collections.EMPTY_LIST;

        String[] bearingArray = bearingString.split(";", -1);
        List<Double> bearings = new ArrayList<>(bearingArray.length);

        for (int i = 0; i < bearingArray.length; i++) {
            String singleBearing = bearingArray[i];
            if (singleBearing.isEmpty()) {
                bearings.add(Double.NaN);
            } else {
                if (!singleBearing.contains(",")) {
                    throw new IllegalArgumentException("You passed an invalid bearings parameter " + bearingString);
                }
                String[] singleBearingArray = singleBearing.split(",");
                try {
                    bearings.add(Double.parseDouble(singleBearingArray[0]));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("You passed an invalid bearings parameter " + bearingString);
                }
            }
        }
        return bearings;
    }

    static void initHints(HintsMap m, MultivaluedMap<String, String> parameterMap) {
        for (Map.Entry<String, List<String>> e : parameterMap.entrySet()) {
            if (e.getValue().size() == 1) {
                m.put(e.getKey(), e.getValue().get(0));
            } else {
                // Do nothing.
                // TODO: this is dangerous: I can only silently swallow
                // the forbidden multiparameter. If I comment-in the line below,
                // I get an exception, because "point" regularly occurs
                // multiple times.
                // I think either unknown parameters (hints) should be allowed
                // to be multiparameters, too, or we shouldn't use them for
                // known parameters either, _or_ known parameters
                // must be filtered before they come to this code point,
                // _or_ we stop passing unknown parameters alltogether..
                //
                // throw new WebApplicationException(String.format("This query parameter (hint) is not allowed to occur multiple times: %s", e.getKey()));
            }
        }
    }

}
