/*  This file is part of Openrouteservice.
 *
 *  Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1
 *  of the License, or (at your option) any later version.

 *  This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.

 *  You should have received a copy of the GNU Lesser General Public License along with this library;
 *  if not, see <https://www.gnu.org/licenses/>.
 */

package org.heigit.ors.routing.graphhopper.extensions.storages.builders;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.graphhopper.GraphHopper;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.VirtualEdgeIteratorState;
import com.graphhopper.storage.GraphExtension;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.EdgeIteratorState;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKTReader;
import me.tongfei.progressbar.ProgressBar;
import org.apache.log4j.Logger;
import org.geotools.data.DataUtilities;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.SchemaException;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.geojson.feature.FeatureJSON;
import org.geotools.geojson.geom.GeometryJSON;
import org.heigit.ors.mapmatching.RouteSegmentInfo;
import org.heigit.ors.routing.graphhopper.extensions.ORSGraphHopper;
import org.heigit.ors.routing.graphhopper.extensions.TrafficRelevantWayType;
import org.heigit.ors.routing.graphhopper.extensions.reader.traffic.HereTrafficReader;
import org.heigit.ors.routing.graphhopper.extensions.reader.traffic.TrafficEnums;
import org.heigit.ors.routing.graphhopper.extensions.reader.traffic.TrafficPattern;
import org.heigit.ors.routing.graphhopper.extensions.storages.TrafficGraphStorage;
import org.heigit.ors.routing.graphhopper.extensions.reader.traffic.TrafficLink;
import org.heigit.ors.util.ErrorLoggingUtility;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.MissingResourceException;

public class HereTrafficGraphStorageBuilder extends AbstractGraphStorageBuilder {
    static final Logger LOGGER = Logger.getLogger(HereTrafficGraphStorageBuilder.class.getName());
    private int trafficWayType = TrafficRelevantWayType.UNWANTED;

    private static final String PARAM_KEY_OUTPUT_LOG = "output_log";
    private static boolean outputLog = false;

    public static final String BUILDER_NAME = "HereTraffic";

    private static final Date date = Calendar.getInstance().getTime();
    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_hh:mm");

    private static final String PARAM_KEY_STREETS = "streets";
    private static final String PARAM_KEY_PATTERNS_15MINUTES = "pattern_15min";
    private static final String PARAM_KEY_REFERENCE_PATTERN = "ref_pattern";
    private static final String MATCHING_RADIUS = "radius";
    private static int matchingRadius = 200;

    DistanceCalcEarth distCalc;
    ORSGraphHopper orsGraphHopper;

    private TrafficGraphStorage storage;
    private HereTrafficReader htReader;

    private LinkedList<String> allOSMEdgeGeometries = new LinkedList<>();
    private HashMap<Integer, String> matchedHereLinks = new HashMap<>();
    private LinkedList<String> matchedOSMLinks = new LinkedList<>();

    public HereTrafficGraphStorageBuilder() throws SchemaException {
    }

    /**
     * Initialize the Here Traffic graph extension <br/><br/>
     * Files required for the process are obtained from the app.config and passed to a CountryBordersReader object
     * which stores information required for the process (i.e. country geometries and border types)
     *
     * @param graphhopper Provide a graphhopper object.
     * @throws Exception Throws an exception if the storag is already initialized.
     */
    @Override
    public GraphExtension init(GraphHopper graphhopper) throws Exception {
        if (storage != null)
            throw new Exception("GraphStorageBuilder has been already initialized.");

        if (this.htReader == null) {
            // Read the border shapes from the file
            // First check if parameters are present
            String streetsFile;
            String patterns15MinutesFile;
            String refPatternIdsFile;
            String oldMatchedEdgeToTrafficFile;
            if (parameters.containsKey(PARAM_KEY_STREETS))
                streetsFile = parameters.get(PARAM_KEY_STREETS);
            else {
                ErrorLoggingUtility.logMissingConfigParameter(HereTrafficGraphStorageBuilder.class, PARAM_KEY_STREETS);
                // We cannot continue without the information
                throw new MissingResourceException("The Here traffic shp file is needed to use the traffic extended storage!", HereTrafficGraphStorageBuilder.class.getName(), PARAM_KEY_STREETS);
            }
            if (parameters.containsKey(PARAM_KEY_PATTERNS_15MINUTES))
                patterns15MinutesFile = parameters.get(PARAM_KEY_PATTERNS_15MINUTES);
            else {
                ErrorLoggingUtility.logMissingConfigParameter(HereTrafficGraphStorageBuilder.class, PARAM_KEY_PATTERNS_15MINUTES);
                // We cannot continue without the information
                throw new MissingResourceException("The Here 15 minutes traffic patterns file is needed to use the traffic extended storage!", HereTrafficGraphStorageBuilder.class.getName(), PARAM_KEY_PATTERNS_15MINUTES);
            }
            if (parameters.containsKey(PARAM_KEY_REFERENCE_PATTERN))
                refPatternIdsFile = parameters.get(PARAM_KEY_REFERENCE_PATTERN);
            else {
                ErrorLoggingUtility.logMissingConfigParameter(HereTrafficGraphStorageBuilder.class, PARAM_KEY_REFERENCE_PATTERN);
                // We cannot continue without the information
                throw new MissingResourceException("The Here traffic pattern reference file is needed to use the traffic extended storage!", HereTrafficGraphStorageBuilder.class.getName(), PARAM_KEY_REFERENCE_PATTERN);
            }
            if (parameters.containsKey(PARAM_KEY_OUTPUT_LOG))
                outputLog = Boolean.parseBoolean(parameters.get(PARAM_KEY_OUTPUT_LOG));
            else {
                ErrorLoggingUtility.logMissingConfigParameter(HereTrafficGraphStorageBuilder.class, PARAM_KEY_OUTPUT_LOG);
                // We cannot continue without the information
                throw new MissingResourceException("The Here similarity factor for the geometry matching algorithm is not set!", HereTrafficGraphStorageBuilder.class.getName(), PARAM_KEY_OUTPUT_LOG);
            }

            if (parameters.containsKey(MATCHING_RADIUS))
                matchingRadius = Integer.parseInt(parameters.get(MATCHING_RADIUS));
            else {
                ErrorLoggingUtility.logMissingConfigParameter(HereTrafficGraphStorageBuilder.class, MATCHING_RADIUS);
                // We cannot continue without the information
                LOGGER.info("The Here matching radius is not set. The default is applied!");
            }

            // Read the file containing all of the country border polygons
            this.htReader = new HereTrafficReader(streetsFile, patterns15MinutesFile, refPatternIdsFile);
        }

        storage = new TrafficGraphStorage();
        distCalc = new DistanceCalcEarth();

        return storage;
    }

    @Override
    public void processWay(ReaderWay way) {

        // Reset the trafficWayType
        trafficWayType = TrafficGraphStorage.IGNORE;

        boolean hasHighway = way.hasTag("highway");
        Iterator<Map.Entry<String, Object>> it = way.getProperties();
        while (it.hasNext()) {
            Map.Entry<String, Object> pairs = it.next();
            String key = pairs.getKey();
            String value = pairs.getValue().toString();
            if (hasHighway && key.equals("highway")) {
                trafficWayType = TrafficGraphStorage.getWayTypeFromString(value);
            }
        }
    }

    @Override
    public void processEdge(ReaderWay way, EdgeIteratorState edge) {
        // processEdge(ReaderWay way, EdgeIteratorState edge, com.vividsolutions.jts.geom.Coordinate[] coords) overwrites this function.
        // If the coords are directly delivered it becomes much faster than querying it from the edge
    }

    @Override
    public void processEdge(ReaderWay way, EdgeIteratorState edge, com.vividsolutions.jts.geom.Coordinate[] coords) {
        if (outputLog) {
            String lineString = edge.fetchWayGeometry(3).toLineString(false).toString();
            allOSMEdgeGeometries.push(lineString);
        }
        short converted = TrafficRelevantWayType.getHereTrafficClassFromOSMRoadType((short) trafficWayType);
        storage.setOrsRoadProperties(edge.getEdge(), TrafficGraphStorage.Property.ROAD_TYPE, converted);
    }

    public void writeLogFiles() throws IOException, SchemaException {
        if (outputLog) {
            SimpleFeatureType TYPE = DataUtilities.createType("my", "geom:MultiLineString");
            File osmFile = null;
            File osmMatchedFile = null;
            File hereMatchedFile = null;
            File hereFile = null;
            int decimals = 14;
            GeometryJSON gjson = new GeometryJSON(decimals);
            FeatureJSON featureJSON = new FeatureJSON(gjson);
            osmFile = new File(dateFormat.format(date) + "_radius_" + matchingRadius + "_OSM_edges_output.geojson");
            osmMatchedFile = new File(dateFormat.format(date) + "_radius_" + matchingRadius + "_OSM_matched_edges_output.geojson");
            hereMatchedFile = new File(dateFormat.format(date) + "_radius_" + matchingRadius + "_Here_matched_edges_output.geojson");
            hereFile = new File(dateFormat.format(date) + "_radius_" + matchingRadius + "_Here_edges_output.geojson");

            DefaultFeatureCollection allOSMCollection = new DefaultFeatureCollection();
            DefaultFeatureCollection matchedOSMCollection = new DefaultFeatureCollection();
            DefaultFeatureCollection allHereCollection = new DefaultFeatureCollection();
            DefaultFeatureCollection matchedHereCollection = new DefaultFeatureCollection();

            GeometryFactory gf = new GeometryFactory();
            WKTReader reader = new WKTReader(gf);

            for (String value : allOSMEdgeGeometries) {
                try {
                    SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(TYPE);
                    com.vividsolutions.jts.geom.Geometry linestring = reader.read(value);
                    featureBuilder.add(linestring);
                    SimpleFeature feature = featureBuilder.buildFeature(null);
                    allOSMCollection.add(feature);
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            }


            matchedOSMLinks.forEach((value) -> {
                try {
                    SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(TYPE);
                    com.vividsolutions.jts.geom.Geometry linestring = reader.read(value);
                    featureBuilder.add(linestring);
                    SimpleFeature feature = featureBuilder.buildFeature(null);
                    matchedOSMCollection.add(feature);
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            });

            matchedHereLinks.forEach((linkID, emptyString) -> {
                try {
                    String hereLinkGeometry = htReader.getHereTrafficData().getLink(linkID).getLinkGeometry().toString();
                    SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(TYPE);
                    com.vividsolutions.jts.geom.Geometry linestring = reader.read(hereLinkGeometry);
                    featureBuilder.add(linestring);
                    SimpleFeature feature = featureBuilder.buildFeature(null);
                    matchedHereCollection.add(feature);
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            });

            for (IntObjectCursor<TrafficLink> trafficLink : htReader.getHereTrafficData().getLinks()) {
                try {
                    String hereLinkGeometry = trafficLink.value.getLinkGeometry().toString();
                    SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(TYPE);
                    com.vividsolutions.jts.geom.Geometry linestring = reader.read(hereLinkGeometry);
                    featureBuilder.add(linestring);
                    SimpleFeature feature = featureBuilder.buildFeature(null);
                    allHereCollection.add(feature);
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            }
            if (allOSMCollection.size() > 0) {
                osmFile.createNewFile();
                featureJSON.writeFeatureCollection(allOSMCollection, osmFile);
            }
            if (matchedOSMCollection.size() > 0) {
                osmMatchedFile.createNewFile();
                featureJSON.writeFeatureCollection(matchedOSMCollection, osmMatchedFile);
            }
            if (allHereCollection.size() > 0) {
                hereMatchedFile.createNewFile();
                featureJSON.writeFeatureCollection(allHereCollection, hereFile);
            }
            if (matchedHereCollection.size() > 0) {
                hereFile.createNewFile();
                featureJSON.writeFeatureCollection(matchedHereCollection, hereMatchedFile);
            }
        }
    }

    /**
     * Method identifying the name of the extension which is used in various building processes
     *
     * @return The name of this extension.
     */
    @Override
    public String getName() {
        return BUILDER_NAME;
    }

    public HereTrafficReader getHtReader() {
        return htReader;
    }

    public void addHereSegmentForLogging(Integer linkID) {
        matchedHereLinks.putIfAbsent(linkID, "");
    }

    public void addOSMGeometryForLogging(String osmGeometry) {
        matchedOSMLinks.add(osmGeometry);
    }

    private RouteSegmentInfo[] matchLinkToSegments(int trafficLinkFunctionalClass, double originalTrafficLinkLength, Geometry geometry, boolean bothDirections) {
        RouteSegmentInfo[] matchedSegments = new RouteSegmentInfo[0];
        if (geometry == null) {
            LOGGER.info("Teadrop node.");
            return matchedSegments;
        }
        try {
            matchedSegments = orsGraphHopper.getMatchedSegmentsInternal(geometry, originalTrafficLinkLength, trafficLinkFunctionalClass, bothDirections, matchingRadius);
        } catch (Exception e) {
            LOGGER.info("Error while matching: " + e);
        }
        return matchedSegments;
    }

    @Override
    public void postProcess(ORSGraphHopper graphHopper) {
        if (!storage.isMatched()) {
            LOGGER.info("Starting MapMatching traffic data");
            processTrafficPatterns();
            processLinks(htReader.getHereTrafficData().getLinks(), graphHopper);
            storage.setMatched();
            storage.flush();
            LOGGER.info("Flush and lock storage.");
        } else {
            LOGGER.info("Traffic data already matched.");
        }
        // TODO RAD
//        GraphHopperStorage graphHopperStorage = graphHopper.getGraphHopperStorage();
//
//        for (GraphExtension ge : GraphStorageUtils.getGraphExtensions(graphHopperStorage)) {
//            if (ge instanceof TrafficGraphStorage) {
//                long seconds1 = Long.parseLong("632354626000");
//                int patterValue1 = ((TrafficGraphStorage) ge).getEdgeIdTrafficPatternLookup(14277, 5022, 222, TrafficEnums.WeekDay.SUNDAY);
//                int speedValue1 = ((TrafficGraphStorage) ge).getSpeedValue(14277, 5022, 222, seconds1);
//                assert patterValue1 == 1309;
//                assert speedValue1 == 31;
//
//                long seconds2 = Long.parseLong("632527426000");
//                int patterValue2 = ((TrafficGraphStorage) ge).getEdgeIdTrafficPatternLookup(14278, 222, 5180, TrafficEnums.WeekDay.TUESDAY);
//                int speedValue2 = ((TrafficGraphStorage) ge).getSpeedValue(14278, 222, 5180, seconds2);
//                assert patterValue2 == 5538;
//                assert speedValue2 == 27;
//
//                long seconds3 = Long.parseLong("632613826000");
//                int patterValue3 = ((TrafficGraphStorage) ge).getEdgeIdTrafficPatternLookup(40, 8282, 250, TrafficEnums.WeekDay.WEDNESDAY);
//                int speedValue3 = ((TrafficGraphStorage) ge).getSpeedValue(40, 8282, 250, seconds3);
//                assert patterValue3 == 29;
//                assert speedValue3 == 30;
//
//                System.out.println("");
//            }
//        }
        // TODO RAD
    }

    private void processTrafficPatterns() {
        IntObjectHashMap<TrafficPattern> patterns = htReader.getHereTrafficData().getPatterns();
        ProgressBar pb = new ProgressBar("Processing traffic patterns", patterns.values().size());
        pb.start();
        for (ObjectCursor<TrafficPattern> pattern : patterns.values()) {
            storage.setTrafficPatterns(pattern.value.getPatternId(), pattern.value.getValues());
        }
        pb.stop();
    }

    private void processLinks(IntObjectHashMap<TrafficLink> links, ORSGraphHopper graphHopper) {
        ProgressBar pb = new ProgressBar("Matching Here Links", links.size()); // name, initial max
        orsGraphHopper = graphHopper;
        pb.start();
        for (ObjectCursor<TrafficLink> trafficLink : links.values()) {
            processLink(trafficLink.value);
            if (!outputLog) {
                links.put(trafficLink.index, null);
            }
            pb.step();
        }
        pb.stop();
    }

    private void processLink(TrafficLink hereTrafficLink) {
        if (hereTrafficLink == null)
            return;
        RouteSegmentInfo[] matchedSegmentsFrom = new RouteSegmentInfo[]{};
        RouteSegmentInfo[] matchedSegmentsTo = new RouteSegmentInfo[]{};
        double trafficLinkLength = hereTrafficLink.getLength(distCalc);
        // TODO RAD START
//        else if (hereTrafficLink.getLinkId() == 53061704 || hereTrafficLink.getLinkId() == 808238429)
////         TODO RAD END
        if (hereTrafficLink.isBothDirections()) {
            // Both Directions
            // Split
            matchedSegmentsFrom = matchLinkToSegments(hereTrafficLink.getFunctionalClass(), trafficLinkLength, hereTrafficLink.getFromGeometry(), false);
            matchedSegmentsTo = matchLinkToSegments(hereTrafficLink.getFunctionalClass(), trafficLinkLength, hereTrafficLink.getToGeometry(), false);
        } else if (hereTrafficLink.isOnlyFromDirection()) {
            // One Direction
            matchedSegmentsFrom = matchLinkToSegments(hereTrafficLink.getFunctionalClass(), trafficLinkLength, hereTrafficLink.getFromGeometry(), false);
        } else {
            // One Direction
            matchedSegmentsTo = matchLinkToSegments(hereTrafficLink.getFunctionalClass(), trafficLinkLength, hereTrafficLink.getToGeometry(), false);
        }

        processSegments(hereTrafficLink.getLinkId(), hereTrafficLink.getTrafficPatternIds(TrafficEnums.TravelDirection.FROM), matchedSegmentsFrom);
        processSegments(hereTrafficLink.getLinkId(), hereTrafficLink.getTrafficPatternIds(TrafficEnums.TravelDirection.TO), matchedSegmentsTo);
    }

    private void processSegments(int linkId, Map<TrafficEnums.WeekDay, Integer> trafficPatternIds, RouteSegmentInfo[] matchedSegments) {
        if (matchedSegments == null)
            return;
        for (RouteSegmentInfo routeSegment : matchedSegments) {
            if (routeSegment == null) continue;
            processSegment(trafficPatternIds, linkId, routeSegment);
        }
    }

    private void processSegment(Map<TrafficEnums.WeekDay, Integer> trafficPatternIds, int trafficLinkId, RouteSegmentInfo routeSegment) {
        for (EdgeIteratorState edge : routeSegment.getEdges()) {
            if (edge instanceof VirtualEdgeIteratorState) {
                VirtualEdgeIteratorState virtualEdge = (VirtualEdgeIteratorState) edge;
                int originalEdgeId;
                int originalBaseNodeId;
                int originalAdjNodeId;
                if (virtualEdge.getAdjNode() < orsGraphHopper.getGraphHopperStorage().getNodes()) {
                    EdgeIteratorState originalEdgeIter = orsGraphHopper.getGraphHopperStorage().getEdgeIteratorState(virtualEdge.getOriginalEdge(), virtualEdge.getAdjNode());
                    originalEdgeId = originalEdgeIter.getEdge();
                    originalBaseNodeId = originalEdgeIter.getBaseNode();
                    originalAdjNodeId = originalEdgeIter.getAdjNode();
                } else if (virtualEdge.getBaseNode() < orsGraphHopper.getGraphHopperStorage().getNodes()) {
                    EdgeIteratorState originalEdgeIter = orsGraphHopper.getGraphHopperStorage().getEdgeIteratorState(virtualEdge.getOriginalEdge(), virtualEdge.getBaseNode());
                    originalEdgeId = originalEdgeIter.getEdge();
                    originalBaseNodeId = originalEdgeIter.getAdjNode();
                    originalAdjNodeId = originalEdgeIter.getBaseNode();
                } else {
                    continue;
                }
                final int finalOriginalEdgeId = originalEdgeId;
                final int finalOriginalBaseNodeId = originalBaseNodeId;
                final int finalOriginalAdjNodeId = originalAdjNodeId;
                trafficPatternIds.forEach((weekDay, patternId) -> storage.setEdgeIdTrafficPatternLookup(finalOriginalEdgeId, finalOriginalBaseNodeId, finalOriginalAdjNodeId, patternId, weekDay, edge.getDistance()));
            } else {
                trafficPatternIds.forEach((weekDay, patternId) -> storage.setEdgeIdTrafficPatternLookup(edge.getEdge(), edge.getBaseNode(), edge.getAdjNode(), patternId, weekDay, edge.getDistance()));
            }
            if (outputLog) {
                LineString lineString = edge.fetchWayGeometry(3).toLineString(false);
                addOSMGeometryForLogging(lineString.toString());
                addHereSegmentForLogging(trafficLinkId);
            }
        }
    }
}