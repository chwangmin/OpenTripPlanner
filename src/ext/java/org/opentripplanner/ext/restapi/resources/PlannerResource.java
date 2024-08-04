package org.opentripplanner.ext.restapi.resources;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.glassfish.grizzly.http.server.Request;
import org.opentripplanner.api.common.Message;
import org.opentripplanner.api.error.PlannerError;
import org.opentripplanner.apis.support.mapping.PlannerErrorMapper;
import org.opentripplanner.apis.transmodel.ResponseTooLargeException;
import org.opentripplanner.ext.restapi.mapping.TripPlanMapper;
import org.opentripplanner.ext.restapi.mapping.TripSearchMetadataMapper;
import org.opentripplanner.ext.restapi.model.ElevationMetadata;
import org.opentripplanner.ext.restapi.model.TripPlannerResponse;
import org.opentripplanner.framework.application.OTPRequestTimeoutException;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.api.response.RoutingResponse;
import org.opentripplanner.routing.error.RoutingValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * This is the primary entry point for the trip planning web service. All parameters are passed in
 * the query string. These parameters are defined as fields in the abstract RoutingResource
 * superclass, which also has methods for building routing requests from query parameters. This
 * allows multiple web services to have the same set of query parameters. In order for inheritance
 * to work, the REST resources are request-scoped (constructed at each request) rather than
 * singleton-scoped (a single instance existing for the lifetime of the OTP server).
 */
@Path("routers/{ignoreRouterId}/plan")
public class PlannerResource extends RoutingResource {

  private static final Logger LOG = LoggerFactory.getLogger(PlannerResource.class);

  @Deprecated
  @PathParam("ignoreRouterId")
  private String ignoreRouterId;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response plan(@Context UriInfo uriInfo, @Context Request grizzlyRequest) {
    TripPlannerResponse response = new TripPlannerResponse(uriInfo);
    List<RouteRequest> requests = new ArrayList<>();
    try {
      // 여러 fromPlace와 toPlace를 처리
      List<String> fromPlaces = uriInfo.getQueryParameters().get("fromPlace");
      List<String> toPlaces = uriInfo.getQueryParameters().get("toPlace");

      if (fromPlaces == null || toPlaces == null || fromPlaces.size() != toPlaces.size()) {
        response.setError(new PlannerError(Message.SYSTEM_ERROR));
        return Response.status(Response.Status.BAD_REQUEST).entity(response).build();
      }

      for (int i = 0; i < fromPlaces.size(); i++) {
        String fromPlace = fromPlaces.get(i);
        String toPlace = toPlaces.get(i);
        RouteRequest request = super.buildRequestForPlaces(uriInfo, fromPlace, toPlace);
        requests.add(request);
      }

      // 각 요청에 대해 경로 계획 수행
      for (RouteRequest request : requests) {
        RoutingResponse res = serverContext.routingService().route(request);

        // Map to API
        TripPlanMapper tripPlanMapper = new TripPlanMapper(request.locale(), showIntermediateStops);
        response.addPlan(tripPlanMapper.mapTripPlan(res.getTripPlan()));

        if (res.getPreviousPageCursor() != null) {
          response.setPreviousPageCursor(res.getPreviousPageCursor().encode());
        }
        if (res.getNextPageCursor() != null) {
          response.setNextPageCursor(res.getNextPageCursor().encode());
        }
        response.setMetadata(TripSearchMetadataMapper.mapTripSearchMetadata(res.getMetadata()));
        if (!res.getRoutingErrors().isEmpty()) {
          response.setError(PlannerErrorMapper.mapMessage(res.getRoutingErrors().get(0)));
        }
        response.debugOutput = res.getDebugTimingAggregator().finishedRendering();
      }

      response.elevationMetadata = new ElevationMetadata();
      response.elevationMetadata.ellipsoidToGeoidDifference = serverContext.graph().ellipsoidToGeoidDifference;
      response.elevationMetadata.geoidElevation = requests.get(0).preferences().system().geoidElevation();

    } catch (RoutingValidationException e) {
      if (e.isFromToLocationNotFound()) {
        response.setError(new PlannerError(Message.GEOCODE_FROM_TO_NOT_FOUND));
      } else if (e.isFromLocationNotFound()) {
        response.setError(new PlannerError(Message.GEOCODE_FROM_NOT_FOUND));
      } else if (e.isToLocationNotFound()) {
        response.setError(new PlannerError(Message.GEOCODE_TO_NOT_FOUND));
      } else {
        LOG.error("System error - unhandled error case?", e);
        response.setError(new PlannerError(Message.SYSTEM_ERROR));
      }
    } catch (OTPRequestTimeoutException | ResponseTooLargeException e) {
      response.setError(new PlannerError(Message.UNPROCESSABLE_REQUEST));
    } catch (Exception e) {
      LOG.error("System error", e);
      response.setError(new PlannerError(Message.SYSTEM_ERROR));
    }
    return Response.ok().entity(response).build();
  }
}
