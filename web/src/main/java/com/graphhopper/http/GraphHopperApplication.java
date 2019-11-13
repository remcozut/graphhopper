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
package com.graphhopper.http;

import com.graphhopper.http.cli.ImportCommand;
import com.graphhopper.http.resources.RootResource;
import io.dropwizard.Application;
import io.dropwizard.bundles.assets.ConfiguredAssetsBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

import javax.servlet.DispatcherType;
import java.util.EnumSet;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public final class GraphHopperApplication extends Application<GraphHopperServerConfiguration> {


    private static Environment environment = null;

    public static void main(String[] args) throws Exception {

//        LogManager.getLogManager().getLogger(Logger.GLOBAL_LOGGER_NAME).setLevel(Level.FINE);


        if (args.length > 0) {
            for (String arg : args) {

                switch (arg) {
                    case "stop": {
                        stop();
                        return;
                    }
                }
            }

        }
        new GraphHopperApplication().run(args);

    }

    public static void stop() throws Exception {
        if (environment != null) {
            environment.getApplicationContext().getServer().stop();
        }
    }

    @Override
    public void initialize(Bootstrap<GraphHopperServerConfiguration> bootstrap) {
        bootstrap.addBundle(new GraphHopperBundle());
        bootstrap.addBundle(new ConfiguredAssetsBundle("/assets/", "/maps/", "index.html"));
        bootstrap.addCommand(new ImportCommand(bootstrap.getObjectMapper()));
    }

    @Override
    public void run(GraphHopperServerConfiguration configuration, Environment environment) {

        this.environment = environment;

        environment.jersey().register(new RootResource());
        environment.servlets().addFilter("cors", CORSFilter.class).addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "*");
        environment.servlets().addFilter("ipfilter", new IPFilter(configuration.getGraphHopperConfiguration().get("jetty.whiteips", ""), configuration.getGraphHopperConfiguration().get("jetty.blackips", ""))).addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "*");



    }
}
