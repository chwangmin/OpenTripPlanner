package org.opentripplanner.openstreetmap.spi;

import java.time.ZoneId;
import org.opentripplanner.openstreetmap.tagmapping.OsmTagMapper;
import org.opentripplanner.openstreetmap.wayproperty.WayPropertySet;

public interface OSMProvider {
  ZoneId getZoneId();

  OsmTagMapper getOsmTagMapper();

  WayPropertySet getWayPropertySet();
}
