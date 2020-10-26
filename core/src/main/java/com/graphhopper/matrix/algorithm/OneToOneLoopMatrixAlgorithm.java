package com.graphhopper.matrix.algorithm;

import com.graphhopper.api.MatrixResponse;
import com.graphhopper.routing.*;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.TraversalMode;
//import com.graphhopper.routing.util.Weighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;

import java.util.List;

/**
 * This implementation will just run a plain route algorithm for
 * each origin-destination combination. The used route algorithm can be
 * specified using the routingAlgoOptions.
 *
 * Data structures between the route algorithms are not reused, resulting
 * in O(R^2) performance hit, where R is the complexity of the underlying route algorithm,
 * assuming a quadratic matrix (origins == destinations)
 *
 * For Dijkstra this is O( (|E|+|V|log(|V|))^2 )
 *
 * @author Pascal Büttiker
 */
public class OneToOneLoopMatrixAlgorithm extends AbstractMatrixAlgorithm {

    private final RoutingAlgorithmFactory routingFactory;
    private final AlgorithmOptions routingAlgoOptions;
    private final PathCalculator pathCalculator;

    public OneToOneLoopMatrixAlgorithm(Graph graph, FlagEncoder encoder, Weighting weighting, TraversalMode traversalMode, AlgorithmOptions routingAlgoOptions) {
        super(graph, encoder, weighting, traversalMode);
        this.routingAlgoOptions = routingAlgoOptions;
        this.routingFactory = new RoutingAlgorithmFactorySimple();
        this.pathCalculator = null;
    }


    /**
     * @param graph                 specifies the graph where this algorithm will run on
     * @param encoder               sets the used vehicle (bike, car, foot)
     * @param weighting             set the used weight calculation (e.g. fastest, shortest).
     * @param traversalMode         how the graph is traversed e.g. if via nodes or edges.
     * @param routingAlgoOptions    the route algorithm options used for each route request
     */
    public OneToOneLoopMatrixAlgorithm(Graph graph, FlagEncoder encoder, Weighting weighting, TraversalMode traversalMode, AlgorithmOptions routingAlgoOptions, PathCalculator pathCalculator) {
        super(graph, encoder, weighting, traversalMode);
        this.routingAlgoOptions = routingAlgoOptions;
        this.routingFactory = null;
        this.pathCalculator = pathCalculator;
    }

    @Override
    public MatrixResponse calcMatrix(int[] origins, int[] destinations) {
        boolean withWeights = false;

        MatrixResponse matrix = new MatrixResponse(origins.length, destinations.length, true, true, withWeights); /* new DistanceMatrix(
                origins.length, destinations.length,
                true,  // Include distances
                true,  // Include times
                true); // Include weights*/

        int[] distances;
        long[] times;
        double[] weights = null;
        EdgeRestrictions edgeRestrictions = new EdgeRestrictions();
        for (int i=0;i<origins.length;i++) {
            distances = new int[destinations.length];
            times = new long[destinations.length];
            //if (withWeights) weights = new double[destinations.length];

            int origin = origins[i];
            for (int j=0;j<destinations.length;j++) {
                Path path = null;
                if (routingFactory != null) {
                    RoutingAlgorithm algorithm = routingFactory.createAlgo(graph, routingAlgoOptions);
                    path = algorithm.calcPath(origin, destinations[j]);
                }
                else {
                    List<Path> paths = pathCalculator.calcPaths(origin, destinations[j], edgeRestrictions);
                    if (paths != null) {
                        path = paths.get(0);
                    }
                }

                if (path != null){
                    distances[j] = (int) path.getDistance();
                    times[j] = path.getTime();
                    //if (withWeights) weights[j] = path.getWeight();
                }

                //matrix.setCell(i, j, path.getDistance(), path.getTime(), path.getWeight());
            }
            matrix.setDistanceRow(i, distances);
            matrix.setTimeRow(i, times);
            //if (withWeights) matrix.setWeightRow(i, weights);
        }


        return matrix;
    }
}