package com.graphhopper.test;

import java.io.*;
import java.net.*;

public class Html {

    public static String getHTML(String urlToRead) throws Exception {
        StringBuilder result = new StringBuilder();
        URL url = new URL(urlToRead);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String line;
        while ((line = rd.readLine()) != null) {
            result.append(line);
        }
        rd.close();
        return result.toString();
    }

    public static void main(String[] args) throws Exception
    {
        String url = "https://secure.locatienet.com/location/xml/xmldistance.asp?username=leidenuniv&password=99SNVJQZ&Postcode_Start=6841HN&Housenr_Start=25&Country_Start=NL&Postcode_Stop=6129EL&Housenr_Stop=49&Country_Stop=NL&DynamicInfo=true&StartTime=2019-07-16T07:45:00Z&IsDestTime=true";
        System.out.println(getHTML(url));
    }
}