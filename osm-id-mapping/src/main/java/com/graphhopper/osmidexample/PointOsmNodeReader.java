package com.graphhopper.osmidexample;

import com.graphhopper.reader.DataReader;
import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.dem.ElevationProvider;
import com.graphhopper.reader.osm.OSMFileHeader;
import com.graphhopper.reader.osm.OSMInput;
import com.graphhopper.reader.osm.OSMInputFile;
import com.graphhopper.util.Helper;
import com.graphhopper.util.shapes.GHPoint;
import com.graphhopper.util.shapes.GHPoint3D;

import javax.xml.stream.XMLStreamException;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Locale;
import java.util.TreeMap;

public class PointOsmNodeReader implements DataReader {
    private int workerThreads = 2;

    private File osmFile;
    private Date osmDataDate;

    public PointOsmNodeReader() {

    }

    TreeMap<String, ReaderNode> nodes = new TreeMap<>();


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
    public PointOsmNodeReader setWorkerThreads(int numOfWorkers) {
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
            ReaderElement item;
            while ((item = in.getNext()) != null) {
                if (item.isType(ReaderElement.NODE)) {
                    ReaderNode node = (ReaderNode)item;

                        String id = String.format(Locale.ROOT, "%.4f,%.4f", node.getLat(), node.getLon());

                        if (node.hasTag("lcn_ref")) {
                            nodes.put(id, node);
                        } else if (node.hasTag("icn_ref")) {
                            nodes.put(id, node);
                        } else if (node.hasTag("ncn_ref")) {
                            nodes.put(id, node);
                        } else if (node.hasTag("rcn_ref")) {
                            nodes.put(id, node);

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

    public ReaderNode getNode(GHPoint point) {
        String id = String.format(Locale.ROOT, "%.4f,%.4f", point.getLat(), point.getLon());
        return nodes.get(id);
    }





    @Override
    public Date getDataDate() {
        return osmDataDate;
    }
}
