package org.opentripplanner.transit.raptor.moduletests;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.opentripplanner.transit.raptor._data.api.PathUtils.pathsToString;
import static org.opentripplanner.transit.raptor._data.transit.TestRoute.route;
import static org.opentripplanner.transit.raptor._data.transit.TestTransfer.flex;
import static org.opentripplanner.transit.raptor._data.transit.TestTransfer.flexAndWalk;
import static org.opentripplanner.transit.raptor._data.transit.TestTransfer.walk;
import static org.opentripplanner.transit.raptor._data.transit.TestTripSchedule.schedule;
import static org.opentripplanner.transit.raptor.api.transit.RaptorSlackProvider.defaultSlackProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentripplanner.transit.raptor.RaptorService;
import org.opentripplanner.transit.raptor._data.RaptorTestConstants;
import org.opentripplanner.transit.raptor._data.transit.TestTransitData;
import org.opentripplanner.transit.raptor._data.transit.TestTripSchedule;
import org.opentripplanner.transit.raptor.api.request.RaptorProfile;
import org.opentripplanner.transit.raptor.api.request.RaptorRequestBuilder;
import org.opentripplanner.transit.raptor.api.request.SearchDirection;
import org.opentripplanner.transit.raptor.rangeraptor.configure.RaptorConfig;

/**
 * FEATURE UNDER TEST
 * <p>
 * With FLEX access Raptor must support access paths with more then one leg.
 * These access paths have more transfers that regular paths, hence should not dominate
 * access walking, but only get accepted when they are better on time and/or cost.
 */
public class B10_FlexAccess implements RaptorTestConstants {
  private static final int FLEX_COST_FACTOR = 400;
  private final TestTransitData data = new TestTransitData();
  private final RaptorRequestBuilder<TestTripSchedule> requestBuilder = new RaptorRequestBuilder<>();
  private final RaptorService<TestTripSchedule> raptorService = new RaptorService<>(
      RaptorConfig.defaultConfigForTest()
  );

  @BeforeEach
  public void setup() {
    data.withRoute(
        route("R1", STOP_B, STOP_C, STOP_D, STOP_E, STOP_F)
            .withTimetable(
                schedule("0:10, 0:12, 0:14, 0:16, 0:18")
            )
    );
    requestBuilder.searchParams()
        // All access paths are all pareto-optimal (McRaptor).
        .addAccessPaths(
            walk(STOP_B, D10m),           // lowest num-of-transfers (0)
            flexAndWalk(STOP_C, D2m, 2, D2m*400),  // lowest cost
            flex(STOP_D, D3m, 2, D3m*400),         // latest departure time
            flexAndWalk(STOP_E, D7m)      // best on combination of transfers and time
        )
        .addEgressPaths(walk(STOP_F, D1m));

    requestBuilder.searchParams()
        .earliestDepartureTime(T00_00)
        .latestArrivalTime(T00_30);

    // We will test board- and alight-slack in a separate test
    requestBuilder.slackProvider(
        defaultSlackProvider(60, 0, 0)
    );

    ModuleTestDebugLogging.setupDebugLogging(data, requestBuilder);
  }

  @Test
  public void standard() {
    requestBuilder.profile(RaptorProfile.STANDARD);

    var response = raptorService.route(requestBuilder.build(), data);

    assertEquals(
        "Flex 3m 2x ~ 4 ~ BUS R1 0:14 0:18 ~ 6 ~ Walk 1m [0:10 0:19 9m]",
        pathsToString(response)
    );
  }

  @Test
  public void standardReverse() {
    requestBuilder
        .profile(RaptorProfile.STANDARD)
        .searchDirection(SearchDirection.REVERSE);

    var response = raptorService.route(requestBuilder.build(), data);

    assertEquals(
        "Flex 3m 2x ~ 4 ~ BUS R1 0:14 0:18 ~ 6 ~ Walk 1m [0:10 0:19 9m]",
        pathsToString(response)
    );
  }

  @Test
  public void multiCriteria() {
    requestBuilder.profile(RaptorProfile.MULTI_CRITERIA);

    var response = raptorService.route(requestBuilder.build(), data);

    assertEquals(""
            + "Flex 3m 2x ~ 4 ~ BUS R1 0:14 0:18 ~ 6 ~ Walk 1m [0:10 0:19 9m $1860]\n"
            + "Flex 2m 2x ~ 3 ~ BUS R1 0:12 0:18 ~ 6 ~ Walk 1m [0:09 0:19 10m $1740]\n"
            + "Flex 7m 1x ~ 5 ~ BUS R1 0:16 0:18 ~ 6 ~ Walk 1m [0:08 0:19 11m $2700]\n"
            + "Walk 10m ~ 2 ~ BUS R1 0:10 0:18 ~ 6 ~ Walk 1m [0:00 0:19 19m $3720]",
        pathsToString(response)
    );
  }

  private static int flexCost(int durationInSeconds) {
    return FLEX_COST_FACTOR * durationInSeconds;
  }
}
