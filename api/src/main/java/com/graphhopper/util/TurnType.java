package com.graphhopper.util;

public enum TurnType {
    TURN("turn"),
    DEPART("depart"),
    ARRIVE("arrive"),
    MERGE("merge"),
    ON_RAMP("on ramp"),
    OFF_RAMP("off ramp"),
    FORK("fork"),
    END_OF_ROAD("end of road"),
    CONTINUE("continue"),
    ROUNDABOUT("roundabout"),
    ROTARY("rotary"),
    ROUNDABOUT_TURN("roundabout turn"),
    NOTIFICATION("notification"),
    EXIT_ROUNDABOUT("exit roundabout"),
    EXIT_ROTARY("exit rotary");

    TurnType(String value)  {
        this.value = value;
    }
    private String value;


    public String getValue() {
        return value;
    }
}
