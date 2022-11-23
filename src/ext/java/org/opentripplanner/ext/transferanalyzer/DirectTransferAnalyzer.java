package org.opentripplanner.ext.transferanalyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.opentripplanner.ext.transferanalyzer.annotations.TransferCouldNotBeRouted;
import org.opentripplanner.ext.transferanalyzer.annotations.TransferRoutingDistanceTooLong;
import org.opentripplanner.graph_builder.DataImportIssueStore;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graphfinder.DirectGraphFinder;
import org.opentripplanner.routing.graphfinder.NearbyStop;
import org.opentripplanner.routing.graphfinder.StreetGraphFinder;
import org.opentripplanner.street.model.vertex.TransitStopVertex;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.service.TransitModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Module used for analyzing the transfers between nearby stops generated by routing via OSM data.
 * It creates data import issues both for nearby stops that cannot be routed between and instances
 * where the street routing distance is unusually long compared to the euclidean distance (sorted by
 * the ratio between the two distances). These lists can typically be used to improve the quality of
 * OSM data for transfer purposes. This can take a long time if the transfer distance is long and/or
 * there are many stops to route between.
 */
public class DirectTransferAnalyzer implements GraphBuilderModule {

  private static final int RADIUS_MULTIPLIER = 5;

  private static final int MIN_RATIO_TO_LOG = 2;

  private static final int MIN_STREET_DISTANCE_TO_LOG = 100;

  private static final Logger LOG = LoggerFactory.getLogger(DirectTransferAnalyzer.class);

  private final Graph graph;
  private final TransitModel transitModel;
  private final DataImportIssueStore issueStore;
  private final double radiusMeters;

  public DirectTransferAnalyzer(
    Graph graph,
    TransitModel transitModel,
    DataImportIssueStore issueStore,
    double radiusMeters
  ) {
    this.graph = graph;
    this.transitModel = transitModel;
    this.issueStore = issueStore;
    this.radiusMeters = radiusMeters;
  }

  @Override
  public void buildGraph() {
    /* Initialize transit index which is needed by the nearby stop finder. */
    transitModel.index();

    LOG.info("Analyzing transfers (this can be time consuming)...");

    List<TransferInfo> directTransfersTooLong = new ArrayList<>();
    List<TransferInfo> directTransfersNotFound = new ArrayList<>();

    DirectGraphFinder nearbyStopFinderEuclidian = new DirectGraphFinder(
      transitModel.getStopModel()::findRegularStops
    );
    StreetGraphFinder nearbyStopFinderStreets = new StreetGraphFinder(graph);

    int stopsAnalyzed = 0;

    for (TransitStopVertex originStopVertex : graph.getVerticesOfType(TransitStopVertex.class)) {
      if (++stopsAnalyzed % 1000 == 0) {
        LOG.info("{} stops analyzed", stopsAnalyzed);
      }

      /* Find nearby stops by euclidean distance */
      Coordinate c0 = originStopVertex.getCoordinate();
      Map<RegularStop, NearbyStop> stopsEuclidean = nearbyStopFinderEuclidian
        .findClosestStops(c0, radiusMeters)
        .stream()
        .filter(t -> t.stop instanceof RegularStop)
        .collect(Collectors.toMap(t -> (RegularStop) t.stop, t -> t));

      /* Find nearby stops by street distance */
      Map<RegularStop, NearbyStop> stopsStreets = nearbyStopFinderStreets
        .findClosestStops(c0, radiusMeters * RADIUS_MULTIPLIER)
        .stream()
        .filter(t -> t.stop instanceof RegularStop)
        .collect(Collectors.toMap(t -> (RegularStop) t.stop, t -> t));

      RegularStop originStop = originStopVertex.getStop();

      /* Get stops found by both street and euclidean search */
      List<RegularStop> stopsConnected = stopsEuclidean
        .keySet()
        .stream()
        .filter(t -> stopsStreets.containsKey(t) && t != originStop)
        .toList();

      /* Get stops found by euclidean search but not street search */
      List<RegularStop> stopsUnconnected = stopsEuclidean
        .keySet()
        .stream()
        .filter(t -> !stopsStreets.containsKey(t) && t != originStop)
        .toList();

      for (RegularStop destStop : stopsConnected) {
        NearbyStop euclideanStop = stopsEuclidean.get(destStop);
        NearbyStop streetStop = stopsStreets.get(destStop);

        TransferInfo transferInfo = new TransferInfo(
          originStop,
          destStop,
          euclideanStop.distance,
          streetStop.distance
        );

        /* Log transfer where the street distance is too long compared to the euclidean distance */
        if (
          transferInfo.ratio > MIN_RATIO_TO_LOG &&
          transferInfo.streetDistance > MIN_STREET_DISTANCE_TO_LOG
        ) {
          directTransfersTooLong.add(transferInfo);
        }
      }

      for (RegularStop destStop : stopsUnconnected) {
        NearbyStop euclideanStop = stopsEuclidean.get(destStop);

        /* Log transfers that are found by euclidean search but not by street search */
        directTransfersNotFound.add(
          new TransferInfo(originStop, destStop, euclideanStop.distance, -1)
        );
      }
    }

    /* Sort by street distance to euclidean distance ratio before adding to issues */
    directTransfersTooLong.sort(Comparator.comparingDouble(t -> t.ratio));
    Collections.reverse(directTransfersTooLong);

    for (TransferInfo transferInfo : directTransfersTooLong) {
      issueStore.add(
        new TransferRoutingDistanceTooLong(
          transferInfo.origin,
          transferInfo.destination,
          transferInfo.directDistance,
          transferInfo.streetDistance,
          transferInfo.ratio
        )
      );
    }

    /* Sort by direct distance before adding to issues */
    directTransfersNotFound.sort(Comparator.comparingDouble(t -> t.directDistance));

    for (TransferInfo transferInfo : directTransfersNotFound) {
      issueStore.add(
        new TransferCouldNotBeRouted(
          transferInfo.origin,
          transferInfo.destination,
          transferInfo.directDistance
        )
      );
    }

    LOG.info(
      "Done analyzing transfers. {} transfers could not be routed and {} transfers had a too long routing" +
      " distance.",
      directTransfersNotFound.size(),
      directTransfersTooLong.size()
    );
  }

  @Override
  public void checkInputs() {
    // No inputs
  }

  private static class TransferInfo {

    final RegularStop origin;
    final RegularStop destination;
    final double directDistance;
    final double streetDistance;
    final double ratio;

    TransferInfo(
      RegularStop origin,
      RegularStop destination,
      double directDistance,
      double streetDistance
    ) {
      this.origin = origin;
      this.destination = destination;
      this.directDistance = directDistance;
      this.streetDistance = streetDistance;
      this.ratio = directDistance != 0 ? streetDistance / directDistance : 0;
    }
  }
}
