package org.opentripplanner.ext.fares.model;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.routing.core.Money;
import org.opentripplanner.transit.model.framework.FeedScopedId;

public record FareProduct(
  FeedScopedId id,
  String name,
  Money amount,
  Duration duration,
  RiderCategory category,
  FareContainer container
) {
  public boolean coversItinerary(Itinerary i, List<FareTransferRule> transferRules) {
    var transitLegs = i.getScheduledTransitLegs();
    var allLegsInProductFeed = transitLegs
      .stream()
      .allMatch(leg -> leg.getAgency().getId().getFeedId().equals(id.getFeedId()));

    return (
      allLegsInProductFeed &&
      (
        transitLegs.size() == 1 ||
        coversDuration(i.getTransitDuration()) ||
        coversItineraryWithFreeTransfers(i, transferRules)
      )
    );
  }

  private boolean coversItineraryWithFreeTransfers(
    Itinerary i,
    List<FareTransferRule> transferRules
  ) {
    var feedIdsInItinerary = i
      .getScheduledTransitLegs()
      .stream()
      .map(l -> l.getAgency().getId().getFeedId())
      .collect(Collectors.toSet());

    return (
      feedIdsInItinerary.size() == 1 &&
      transferRules.stream().anyMatch(r -> r.fareProduct().amount.cents() == 0)
    );
  }

  public boolean coversDuration(Duration journeyDuration) {
    return Objects.nonNull(duration) && duration.toSeconds() > journeyDuration.toSeconds();
  }
}
