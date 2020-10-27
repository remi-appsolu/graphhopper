package com.graphhopper.matrix;

import com.graphhopper.GHResponse;
import com.graphhopper.api.GHMRequest;
import com.graphhopper.api.MatrixResponse;
import com.graphhopper.config.Profile;
import com.graphhopper.matrix.algorithm.OneToOneLoopMatrixAlgorithm;
import com.graphhopper.routing.*;
//import com.graphhopper.routing.QueryGraph;
import com.graphhopper.routing.ch.CHRoutingAlgorithmFactory;
import com.graphhopper.routing.lm.LMRoutingAlgorithmFactory;
import com.graphhopper.routing.lm.LandmarkStorage;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.*;
import com.graphhopper.routing.weighting.BlockAreaWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.*;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.PMap;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.StopWatch;
import com.graphhopper.util.TranslationMap;
import com.graphhopper.util.details.PathDetailsBuilderFactory;
import com.graphhopper.util.shapes.GHPoint;

import java.util.*;
import java.util.stream.Collectors;

import static com.graphhopper.routing.weighting.Weighting.INFINITE_U_TURN_COSTS;
import static com.graphhopper.util.Parameters.Algorithms.*;
import static com.graphhopper.util.Parameters.Routing.*;

/**
 * Provides a Distance Matrix API
 *
 * @author Pascal Büttiker
 */
public class DistanceMatrixService {

    /*private final GraphHopper hopper; // TODO after GraphHopper refactoring, this dependency should go away
    private final MatrixAlgorithmFactory matrixAlgorithmFactory;
*/

    /*public DistanceMatrixService(GraphHopper hopper){
        this(hopper, new SimpleMatrixAlgorithmFactory());
    }

    public DistanceMatrixService(GraphHopper hopper, MatrixAlgorithmFactory matrixAlgorithmFactory){
        this.hopper = hopper;
        this.matrixAlgorithmFactory = matrixAlgorithmFactory;
    }*/

    private final GraphHopperStorage ghStorage;
    private final EncodingManager encodingManager;
    private final LocationIndex locationIndex;
    private final Map<String, Profile> profilesByName;
    private final PathDetailsBuilderFactory pathDetailsBuilderFactory;
    private final TranslationMap translationMap;
    private final RouterConfig routerConfig;
    private final WeightingFactory weightingFactory;
    // todo: these should not be necessary anymore as soon as GraphHopperStorage (or something that replaces) it acts
    // like a 'graph database'
    private final Map<String, RoutingCHGraph> chGraphs;
    private final Map<String, LandmarkStorage> landmarks;
    private final boolean chEnabled;
    private final boolean lmEnabled;

    public DistanceMatrixService(GraphHopperStorage ghStorage, LocationIndex locationIndex,
                                 Map<String, Profile> profilesByName, PathDetailsBuilderFactory pathDetailsBuilderFactory,
                                 TranslationMap translationMap, RouterConfig routerConfig, WeightingFactory weightingFactory,
                                 Map<String, CHGraph> chGraphs, Map<String, LandmarkStorage> landmarks){
        this.ghStorage = ghStorage;
        this.encodingManager = ghStorage.getEncodingManager();
        this.locationIndex = locationIndex;
        this.profilesByName = profilesByName;
        this.pathDetailsBuilderFactory = pathDetailsBuilderFactory;
        this.translationMap = translationMap;
        this.routerConfig = routerConfig;
        this.weightingFactory = weightingFactory;
        this.chGraphs = new LinkedHashMap<>(chGraphs.size());
        for (Map.Entry<String, CHGraph> e : chGraphs.entrySet()) {
            this.chGraphs.put(e.getKey(), new RoutingCHGraphImpl(e.getValue()));
        }
        this.landmarks = landmarks;
        // note that his is not the same as !ghStorage.getCHConfigs().isEmpty(), because the GHStorage might have some
        // CHGraphs that were not built yet (and possibly no CH profiles were configured).
        this.chEnabled = !chGraphs.isEmpty();
        this.lmEnabled = !landmarks.isEmpty();
    }

    /**
     * Calculates a distance matrix based on the given request.
     */
    public MatrixResponse calculateMatrix(GHMRequest request){
        MatrixResponse response = new MatrixResponse();
        final boolean disableCH = getDisableCH(request.getHints());
        final boolean disableLM = getDisableLM(request.getHints());
        Profile profile = profilesByName.get(request.getProfile());
        if (profile == null)
            throw new IllegalArgumentException("The requested profile '" + request.getProfile() + "' does not exist.\nAvailable profiles: " + profilesByName.keySet());
        /*if (!profile.isTurnCosts() && !request.getCurbsides().isEmpty())
            throw new IllegalArgumentException("To make use of the " + CURBSIDE + " parameter you need to use a profile that supports turn costs" +
                    "\nThe following profiles do support turn costs: " + getTurnCostProfiles());*/
        // todo later: should we be able to control this using the edge_based parameter?
        TraversalMode traversalMode = profile.isTurnCosts() ? TraversalMode.EDGE_BASED : TraversalMode.NODE_BASED;
        //traversalMode = TraversalMode.EDGE_BASED;
        final int uTurnCostsInt = request.getHints().getInt(Parameters.Routing.U_TURN_COSTS, INFINITE_U_TURN_COSTS);
        if (uTurnCostsInt != INFINITE_U_TURN_COSTS && !traversalMode.isEdgeBased()) {
            throw new IllegalArgumentException("Finite u-turn costs can only be used for edge-based routing, you need to use a profile that" +
                    " supports turn costs. Currently the following profiles that support turn costs are available: " + getTurnCostProfiles());
        }
        final boolean passThrough = getPassThrough(request.getHints());
        final boolean forceCurbsides = request.getHints().getBool(FORCE_CURBSIDE, true);
        int maxVisitedNodesForRequest = request.getHints().getInt(Parameters.Routing.MAX_VISITED_NODES, routerConfig.getMaxVisitedNodes());
        if (maxVisitedNodesForRequest > routerConfig.getMaxVisitedNodes())
            throw new IllegalArgumentException("The max_visited_nodes parameter has to be below or equal to:" + routerConfig.getMaxVisitedNodes());

        // determine weighting
        final boolean useCH = chEnabled && !disableCH;
        Weighting weighting = createWeighting(profile, request.getHints(), request.getPoints(), useCH);

        AlgorithmOptions algoOpts = AlgorithmOptions.start().
                //algorithm(ASTAR_BI/*request.getAlgorithm()*/).
                algorithm(DIJKSTRA_BI).
                traversalMode(traversalMode).
                weighting(weighting).
                maxVisitedNodes(maxVisitedNodesForRequest).
                hints(request.getHints()).
                build();

        algoOpts.getHints().putObject("calc_paths", false);
        algoOpts.getHints().putObject(INSTRUCTIONS, false);
        algoOpts.getHints().putObject(CALC_POINTS, false);
        // A verifier si plus rapide
        //algoOpts.getHints().putObject("elevation", false);

        response = routeVia2(request, algoOpts, profile, weighting, traversalMode, useCH);
/*
        for (int i = 0; i< request.getFromPoints().size(); i++) {
            int[] distances = new int[request.getToPoints().size()];
            long[] times = new long[request.getToPoints().size()];
            for(int j =0; j < request.getToPoints().size(); j++) {
                //resp = routeVia(Arrays.asList(request.getFromPoints().get(i), request.getToPoints().get(j)), algoOpts, weighting, profile, passThrough, forceCurbsides, disableCH, disableLM);
                distances[j] = (int) resp.getBest().getDistance();
                times[j] = resp.getBest().getTime();
            }
            response.setDistanceRow(i, distances);
            response.setTimeRow(i, times);
        }*/


        /*

*/
        return response;
    }

    protected MatrixResponse routeVia2(GHMRequest request, AlgorithmOptions algoOpts, Profile profile, Weighting weighting, TraversalMode traversalMode, boolean useCH){
        //AlgorithmOptions.Builder algoOptsBuilder = buildOptions(request, hopper);

        //AlgorithmOptions tmpOptions = algoOptsBuilder.build();
        //FlagEncoder encoder = encodingManager.getEncoder(profile.getVehicle());

        Graph routingGraph = ghStorage; //hopper.getGraphHopperStorage();
        //if(hopper.isCHEnabled()) {
        //    routingGraph = hopper.getGraphHopperStorage().getGraph(CHGraph.class, tmpOptions.getWeighting());
        //}

        /*Weighting weighting = hopper.createTurnWeighting(
                queryGraph,
                tmpOptions.getFlagEncoder(),
                tmpOptions.getWeighting(),
                tmpOptions.getTraversalMode());

        AlgorithmOptions algoOpts = algoOptsBuilder.weighting(weighting).build();*/

        List<Snap> originSnaps = lookupNodes(request.getFromPoints(), algoOpts.getWeighting().getFlagEncoder());
        List<Snap> destinationSnaps = lookupNodes(request.getToPoints(), algoOpts.getWeighting().getFlagEncoder());

        List<Snap> merged = new ArrayList<>(originSnaps.stream().filter(snap -> snap.getClosestEdge() != null).collect(Collectors.toList()));
        merged.addAll(destinationSnaps.stream().filter(snap -> snap.getClosestEdge() != null).collect(Collectors.toList()));

        QueryGraph queryGraph = QueryGraph.create(routingGraph, merged);
//        queryGraph.lookup(merged);

        int[] originNodes =  mapToNodes(originSnaps);
        int[] destinationNodes =  mapToNodes(destinationSnaps);

        algoOpts.getHints().putObject(INSTRUCTIONS, false);
        algoOpts.getHints().putObject(CALC_POINTS, false);


        OneToOneLoopMatrixAlgorithm algo;
        if (useCH) {
            algo = new OneToOneLoopMatrixAlgorithm(queryGraph, weighting.getFlagEncoder(), weighting, traversalMode, algoOpts, createCHPathCalculator(queryGraph, profile, algoOpts.getHints()));
        }
        else {
            algo = new OneToOneLoopMatrixAlgorithm(queryGraph, weighting.getFlagEncoder(), weighting, traversalMode, algoOpts);
        }
        MatrixResponse response = algo.calcMatrix(originNodes, destinationNodes);
        return response;

        /*MatrixAlgorithm algorithm = matrixAlgorithmFactory.createAlgo(queryGraph, algoOpts);
        DistanceMatrix matrix = algorithm.calcMatrix(originNodes, destinationNodes);
        GHMatrixResponse response = toResponse(matrix, request);
         */
    }

    protected GHResponse routeVia(List<GHPoint> points, AlgorithmOptions algoOpts, Weighting weighting, Profile profile, boolean passThrough, boolean forceCurbsides, boolean disableCH, boolean disableLM) {
        GHResponse ghRsp = new GHResponse();
        StopWatch sw = new StopWatch().start();
        List<Snap> qResults = ViaRouting.lookup(encodingManager, points, weighting, locationIndex, new ArrayList<String>(), new ArrayList<String>());// request.getSnapPreventions(), request.getPointHints());
        ghRsp.addDebugInfo("idLookup:" + sw.stop().getSeconds() + "s");
        // (base) query graph used to resolve headings, curbsides etc. this is not necessarily the same thing as
        // the (possibly implementation specific) query graph used by PathCalculator
        QueryGraph queryGraph = QueryGraph.create(ghStorage, qResults);
        PathCalculator pathCalculator = createPathCalculator(queryGraph, profile, algoOpts, disableCH, disableLM);
        ViaRouting.Result result = ViaRouting.calcPaths(points, queryGraph, qResults, weighting.getFlagEncoder().getAccessEnc(), pathCalculator, new ArrayList<>()/*request.getCurbsides()*/, forceCurbsides, new ArrayList<>()/*request.getHeadings()*/, passThrough);

        if (points.size() != result.paths.size() + 1)
            throw new RuntimeException("There should be exactly one more point than paths. points:" + points.size() + ", paths:" + result.paths.size());

        /*// here each path represents one leg of the via-route and we merge them all together into one response path
        ResponsePath responsePath = concatenatePaths(request, weighting, queryGraph, result.paths, getWaypoints(qResults));
        responsePath.addDebugInfo(result.debug);
        ghRsp.add(responsePath);
        ghRsp.getHints().putObject("visited_nodes.sum", result.visitedNodes);
        ghRsp.getHints().putObject("visited_nodes.average", (float) result.visitedNodes / (qResults.size() - 1));*/
        return ghRsp;
    }


    private PathCalculator createPathCalculator(QueryGraph queryGraph, Profile profile, AlgorithmOptions algoOpts, boolean disableCH, boolean disableLM) {
        if (chEnabled && !disableCH) {
            PMap opts = new PMap(algoOpts.getHints());
            opts.putObject(ALGORITHM, algoOpts.getAlgorithm());
            opts.putObject(MAX_VISITED_NODES, algoOpts.getMaxVisitedNodes());
            return createCHPathCalculator(queryGraph, profile, opts);
        } else {
            return createFlexiblePathCalculator(queryGraph, profile, algoOpts, disableLM);
        }
    }

    private PathCalculator createCHPathCalculator(QueryGraph queryGraph, Profile profile, PMap opts) {
        RoutingCHGraph chGraph = chGraphs.get(profile.getName());
        if (chGraph == null)
            throw new IllegalArgumentException("Cannot find CH preparation for the requested profile: '" + profile.getName() + "'" +
                    "\nYou can try disabling CH using " + Parameters.CH.DISABLE + "=true" +
                    "\navailable CH profiles: " + chGraphs.keySet());
        return new CHPathCalculator(new CHRoutingAlgorithmFactory(chGraph, queryGraph), opts);
    }

    private FlexiblePathCalculator createFlexiblePathCalculator(QueryGraph queryGraph, Profile profile, AlgorithmOptions algoOpts, boolean disableLM) {
        RoutingAlgorithmFactory algorithmFactory;
        // for now do not allow mixing CH&LM #1082,#1889
        if (lmEnabled && !disableLM) {
            LandmarkStorage landmarkStorage = landmarks.get(profile.getName());
            if (landmarkStorage == null)
                throw new IllegalArgumentException("Cannot find LM preparation for the requested profile: '" + profile.getName() + "'" +
                        "\nYou can try disabling LM using " + Parameters.Landmark.DISABLE + "=true" +
                        "\navailable LM profiles: " + landmarks.keySet());
            algorithmFactory = new LMRoutingAlgorithmFactory(landmarkStorage).setDefaultActiveLandmarks(routerConfig.getActiveLandmarkCount());
        } else {
            algorithmFactory = new RoutingAlgorithmFactorySimple();
        }
        return new FlexiblePathCalculator(queryGraph, algorithmFactory, algoOpts);
    }

    private Weighting createWeighting(Profile profile, PMap requestHints, List<GHPoint> points, boolean forCH) {
        if (forCH) {
            // todo: do not allow things like short_fastest.distance_factor or u_turn_costs unless CH is disabled
            // and only under certain conditions for LM

            // the request hints are ignored for CH as we cannot change the profile after the preparation like this.
            // the weighting here has to be created the same way as we did when we created the weighting for the preparation
            return weightingFactory.createWeighting(profile, new PMap(), false);
        } else {
            Weighting weighting = weightingFactory.createWeighting(profile, requestHints, false);
            if (requestHints.has(Parameters.Routing.BLOCK_AREA)) {
                FlagEncoder encoder = encodingManager.getEncoder(profile.getVehicle());

                GraphEdgeIdFinder.BlockArea blockArea = GraphEdgeIdFinder.createBlockArea(ghStorage, locationIndex, points, requestHints, DefaultEdgeFilter.allEdges(encoder));
                weighting = new BlockAreaWeighting(weighting, blockArea);
            }
            return weighting;
        }
    }


    private static boolean getDisableLM(PMap hints) {
        return hints.getBool(Parameters.Landmark.DISABLE, false);
    }

    private static boolean getDisableCH(PMap hints) {
        return hints.getBool(Parameters.CH.DISABLE, false);
    }

    private static boolean getPassThrough(PMap hints) {
        return hints.getBool(PASS_THROUGH, false);
    }

    /**
     * Creates a GHMatrixResponse from the given matrix
     */
    /*private GHMatrixResponse toResponse(DistanceMatrix matrix, GHMatrixRequest request){
        MatrixResponse response = new MatrixResponse(matrix);
        return response;
    }*/



    /**
     * Builds the AlgorithmOptions
     * // TODO Refactor: The following is almost copy & paste from GraphHopper.calcPoints()
     *
     * //@param request The matrix request
     * //@param hopper Instance of hopper
     * @return
     */
   /* private AlgorithmOptions.Builder buildOptions(GHMRequest request, GraphHopper hopper){
        String algoStr = request.getAlgorithm().isEmpty() ? MATRIX_ONE_TO_ONE : request.getAlgorithm();


        TraversalMode tMode;
        String tModeStr = request.getHints().getString("traversal_mode", TraversalMode.NODE_BASED.toString());
        try
        {
            tMode = TraversalMode.fromString(tModeStr);
        } catch (Exception ex)
        {
            throw new IllegalStateException("Invalid TraversalMode");
        }

        FlagEncoder encoder = hopper.getEncodingManager().getEncoder(request.getVehicle());

        RoutingAlgorithmFactory tmpAlgoFactory = hopper.getAlgorithmFactory(request.getHints());
        Weighting weighting;

        if (hopper.getCHPreparationHandler().isEnabled()) //.getCHFactoryDecorator()
        {
            if (tmpAlgoFactory instanceof PrepareContractionHierarchies){
                weighting = ((PrepareContractionHierarchies) tmpAlgoFactory).getWeighting();

            }else {
                throw new IllegalStateException("Although CH was enabled a non-CH algorithm factory was returned " + tmpAlgoFactory);
            }
        } else
            weighting = hopper.createWeighting(request.getHints(), encoder);

        int maxVisitedNodes = Integer.MAX_VALUE;
        int maxVisistedNodesForRequest = request.getHints().getInt("routing.maxVisitedNodes", maxVisitedNodes);
        if (maxVisistedNodesForRequest > maxVisitedNodes)
        {
            throw new IllegalStateException("The routing.maxVisitedNodes parameter has to be below or equal to:" + maxVisitedNodes);
        }

        return AlgorithmOptions.start().
                algorithm(algoStr)
                .traversalMode(tMode)
                .flagEncoder(encoder)
                .weighting(weighting)
                .maxVisitedNodes(maxVisistedNodesForRequest)
                .hints(request.getHints());
    }*/


    private List<Snap> lookupNodes(List<GHPoint> points, FlagEncoder encoder)
    {
        if(points == null) throw new IllegalArgumentException("points must not be Null");
        if(encoder == null) throw new IllegalArgumentException("encoder must not be Null");

        List<Snap> nodes = new ArrayList<>();

        //LocationIndex locationIndex = hopper.getLocationIndex();
        EdgeFilter edgeFilter = DefaultEdgeFilter.allEdges(encoder);

        for (GHPoint point : points ) {
            nodes.add(locationIndex.findClosest(point.lat, point.lon, edgeFilter));
        }

        if(nodes.size() != points.size()){
            throw new IllegalStateException("Could not find nodes for all points!");
        }

        return nodes;
    }

    private int[] mapToNodes(List<Snap> nodeQueryResults){
        int[] nodes = new int[nodeQueryResults.size()];
        for (int i=0;i<nodes.length;i++) {
            nodes[i] = nodeQueryResults.get(i).getClosestNode();
        }
        return nodes;
    }



    private List<String> getTurnCostProfiles() {
        List<String> turnCostProfiles = new ArrayList<>();
        for (Profile p : profilesByName.values()) {
            if (p.isTurnCosts()) {
                turnCostProfiles.add(p.getName());
            }
        }
        return turnCostProfiles;
    }
}

