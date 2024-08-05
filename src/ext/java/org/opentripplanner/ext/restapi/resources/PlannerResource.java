package org.opentripplanner.ext.restapi.resources;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
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
// final element needed here rather than on method to distinguish from routers API
public class PlannerResource extends RoutingResource {

    private static final Logger LOG = LoggerFactory.getLogger(PlannerResource.class);

    /**
     * @deprecated The support for multiple routers are removed from OTP2. See
     * https://github.com/opentripplanner/OpenTripPlanner/issues/2760
     */
    @Deprecated
    @PathParam("ignoreRouterId")
    private String ignoreRouterId;

    // We inject info about the incoming request so we can include the incoming query
    // parameters in the outgoing response. This is a TriMet requirement.
    // Jersey uses @Context to inject internal types and @InjectParam or @Resource for DI objects.
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response plan(@Context UriInfo uriInfo, @Context Request grizzlyRequest) {
        /*
         * TODO: add Lang / Locale parameter, and thus get localized content (Messages & more...)
         * TODO: from/to inputs should be converted / geocoded / etc... here, and maybe send coords
         *       or vertex ids to planner (or error back to user)
         * TODO: org.opentripplanner.routing.module.PathServiceImpl has COOORD parsing. Abstract that
         *       out so it's used here too...
         */

        // Create response object, containing a copy of all request parameters. Maybe they should be in the debug section of the response.
        TripPlannerResponse response = new TripPlannerResponse(uriInfo);
        try {
            List<RouteRequest> requests = buildRequestsForPlaces(uriInfo);

            for (RouteRequest request : requests) {
                RoutingResponse res = serverContext.routingService().route(request);

                // Map to API
                TripPlanMapper tripPlanMapper = new TripPlanMapper(request.locale(), showIntermediateStops);
                response.setPlan(tripPlanMapper.mapTripPlan(res.getTripPlan()));
                if (res.getPreviousPageCursor() != null) {
                    response.setPreviousPageCursor(res.getPreviousPageCursor().encode());
                }
                if (res.getNextPageCursor() != null) {
                    response.setNextPageCursor(res.getNextPageCursor().encode());
                }
                response.setMetadata(TripSearchMetadataMapper.mapTripSearchMetadata(res.getMetadata()));
                if (!res.getRoutingErrors().isEmpty()) {
                    // The api can only return one error message, so the first one is mapped
                    response.setError(PlannerErrorMapper.mapMessage(res.getRoutingErrors().get(0)));
                }

                /* Populate up the elevation metadata */
                response.elevationMetadata = new ElevationMetadata();
                response.elevationMetadata.ellipsoidToGeoidDifference =
                        serverContext.graph().ellipsoidToGeoidDifference;
                response.elevationMetadata.geoidElevation = request.preferences().system().geoidElevation();

                response.debugOutput = res.getDebugTimingAggregator().finishedRendering();
            }
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

    private List<RouteRequest> buildRequestsForPlaces(UriInfo uriInfo) {
        return buildRequests(uriInfo.getQueryParameters());
    }

    /**
     * Range/sanity check the query parameter fields and build a Request object from them.
     *
     * @param queryParameters incoming request parameters
     */
    protected List<RouteRequest> buildRequests(MultivaluedMap<String, String> queryParameters) {
        List<RouteRequest> requests = new ArrayList<>();
        List<String> fromPlaces = queryParameters.get("fromPlace");
        List<String> toPlaces = queryParameters.get("toPlace");

        if (fromPlaces == null || toPlaces == null || fromPlaces.size() != toPlaces.size()) {
            throw new IllegalArgumentException("The number of fromPlace and toPlace parameters must be equal and non-null.");
        }

        for (int i = 0; i < fromPlaces.size(); i++) {
            RouteRequest request = defaultRouteRequest();
            request.setFrom(fromOldStyleString(fromPlaces.get(i)));
            request.setTo(fromOldStyleString(toPlaces.get(i)));
            populateRequestParameters(queryParameters, request);
            requests.add(request);
        }

        return requests;
    }

    private void populateRequestParameters(MultivaluedMap<String, String> queryParameters, RouteRequest request) {
        ZoneId tz = ZoneIdFallback.zoneId(serverContext.transitService().getTimeZone());
        String date = queryParameters.getFirst("date");
        String time = queryParameters.getFirst("time");

        if (date == null && time != null) { // Time was provided but not date
            try {
                // If the time query param doesn't specify a timezone, use the graph's default. See issue #1373.
                DatatypeFactory df = javax.xml.datatype.DatatypeFactory.newInstance();
                XMLGregorianCalendar xmlGregCal = df.newXMLGregorianCalendar(time);
                ZonedDateTime dateTime = xmlGregCal.toGregorianCalendar().toZonedDateTime();
                if (xmlGregCal.getTimezone() == DatatypeConstants.FIELD_UNDEFINED) {
                    dateTime = dateTime.withZoneSameLocal(tz);
                }
                request.setDateTime(dateTime.toInstant());
            } catch (DatatypeConfigurationException e) {
                request.setDateTime(date, time, tz);
            }
        } else {
            request.setDateTime(date, time, tz);
        }

        String bookingTime = queryParameters.getFirst("bookingTime");
        if (bookingTime != null) {
            request.setBookingTime(LocalDateTime.parse(bookingTime).atZone(tz).toInstant());
        }

        // Populate other request parameters as needed
    }
}
