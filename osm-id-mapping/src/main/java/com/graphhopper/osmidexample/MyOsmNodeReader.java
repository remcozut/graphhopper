package com.graphhopper.osmidexample;

import com.graphhopper.reader.DataReader;
import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.dem.ElevationProvider;
import com.graphhopper.reader.osm.OSMFileHeader;
import com.graphhopper.reader.osm.OSMInput;
import com.graphhopper.reader.osm.OSMInputFile;
import com.graphhopper.storage.DataAccess;
import com.graphhopper.util.Helper;

import javax.xml.stream.XMLStreamException;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.TreeMap;
import java.util.TreeSet;

public class MyOsmNodeReader implements DataReader {
    private int workerThreads = 2;

    private File osmFile;
    private Date osmDataDate;

    public MyOsmNodeReader() {

    }

    TreeMap<Long, ReaderNode> nodes = new TreeMap<Long, ReaderNode>();


    protected OSMInput openOsmInputFile(File osmFile) throws XMLStreamException, IOException {
        return new OSMInputFile(osmFile).setWorkerThreads(workerThreads).open();
    }


    @Override
    public DataReader setFile(File osmFile) {
        this.osmFile = osmFile;
        return this;
    }

    @Override
    public DataReader setElevationProvider(ElevationProvider ep) {
        return null;
    }

    @Override
    public MyOsmNodeReader setWorkerThreads(int numOfWorkers) {
        this.workerThreads = numOfWorkers;
        return this;
    }

    @Override
    public DataReader setWayPointMaxDistance(double wayPointMaxDistance) {
        return null;
    }

    @Override
    public DataReader setSmoothElevation(boolean smoothElevation) {
        return null;
    }

    @Override
    public void readGraph() throws IOException {
        try (OSMInput in = openOsmInputFile(osmFile)) {
            long tmpWayCounter = 1;
            long tmpRelationCounter = 1;
            ReaderElement item;
            while ((item = in.getNext()) != null) {
                if (item.isType(ReaderElement.NODE)) {
                    ReaderNode node = (ReaderNode)item;
                    if (item.hasTag("rcn_ref") || item.hasTag("icn_ref") || item.hasTag("lcn_ref") || item.hasTag("ncn_ref")) {
                        nodes.put(item.getId(), node);
                    }
                } else if (item.isType(ReaderElement.FILEHEADER)) {
                    final OSMFileHeader fileHeader = (OSMFileHeader) item;
                    osmDataDate = Helper.createFormatter().parse(fileHeader.getTag("timestamp"));
                }

            }

        } catch (Exception ex) {
            throw new RuntimeException("Problem while parsing file", ex);
        }

    }

    public ReaderNode getOsmNode(long osmId) {
        return nodes.get(osmId);
    }



    public String getCyclingNetworkNode(long osmId) {

        ReaderNode node = getOsmNode(osmId);



        if (node != null) {
            if (node.hasTag("lcn_ref")) {
                return node.getTag("lcn_ref");
            } else if (node.hasTag("icn_ref")) {
                return node.getTag("icn_ref");
            } else if (node.hasTag("ncn_ref")) {
                return node.getTag("ncn_ref");
            } else if (node.hasTag("rcn_ref")) {
                return node.getTag("rcn_ref");

            }
        }
        return null;
    }

    @Override
    public Date getDataDate() {
        return osmDataDate;
    }
}
