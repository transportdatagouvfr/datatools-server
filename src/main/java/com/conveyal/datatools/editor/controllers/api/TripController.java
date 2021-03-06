package com.conveyal.datatools.editor.controllers.api;

import com.conveyal.datatools.editor.controllers.Base;
import com.conveyal.datatools.editor.datastore.FeedTx;
import com.conveyal.datatools.editor.datastore.VersionedDataStore;
import com.conveyal.datatools.editor.models.transit.StopTime;
import com.conveyal.datatools.editor.models.transit.Trip;
import com.conveyal.datatools.editor.models.transit.TripPattern;
import com.conveyal.datatools.editor.models.transit.TripPatternStop;
import com.conveyal.datatools.manager.models.JsonViews;
import com.conveyal.datatools.manager.utils.json.JsonManager;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import spark.HaltException;
import spark.Request;
import spark.Response;

import static spark.Spark.*;


public class TripController {
    public static final Logger LOG = LoggerFactory.getLogger(TripController.class);

    public static final JsonManager<Trip> json =
            new JsonManager<>(Trip.class, JsonViews.UserInterface.class);

    public static Object getTrip(Request req, Response res) {
        String id = req.params("id");
        String feedId = req.queryParams("feedId");
        String patternId = req.queryParams("patternId");
        String calendarId = req.queryParams("calendarId");

        if (feedId == null) {
            halt(400);
        }

        FeedTx tx = null;

        try {
            tx = VersionedDataStore.getFeedTx(feedId);
            if (id != null) {
                if (tx.trips.containsKey(id))
                    return Base.toJson(tx.trips.get(id), false);
                else
                    halt(404);
            }
            else if (patternId != null && calendarId != null) {
                if (!tx.tripPatterns.containsKey(patternId) || !tx.calendars.containsKey(calendarId)) {
                    halt(404);
                }
                else {
                    LOG.info("requesting trips for pattern/cal");
                    return tx.getTripsByPatternAndCalendar(patternId, calendarId);
                }
            }

            else if(patternId != null) {
                return tx.getTripsByPattern(patternId);
            }
            else {
                return new ArrayList<>(tx.trips.values());
            }
                
        } catch (IOException e) {
            e.printStackTrace();
            halt(400);
        } catch (HaltException e) {
            LOG.error("Halt encountered", e);
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            halt(400);
        } finally {
            if (tx != null) tx.rollbackIfOpen();
        }
        return null;
    }

    public static Object createTrip(Request req, Response res) {
        FeedTx tx = null;
        String createMultiple = req.queryParams("multiple");
        try {
            List<Trip> trips = new ArrayList<>();
            if (createMultiple != null && createMultiple.equals("true")) {
                trips = Base.mapper.readValue(req.body(), new TypeReference<List<Trip>>(){});
            } else {
                Trip trip = Base.mapper.readValue(req.body(), Trip.class);
                trips.add(trip);
            }
            for (Trip trip : trips) {
                if (req.session().attribute("feedId") != null && !req.session().attribute("feedId").equals(trip.feedId))
                    halt(400);

                if (!VersionedDataStore.feedExists(trip.feedId)) {
                    halt(400);
                }

                tx = VersionedDataStore.getFeedTx(trip.feedId);

                if (tx.trips.containsKey(trip.id)) {
                    halt(400);
                }

                if (!tx.tripPatterns.containsKey(trip.patternId) || trip.stopTimes.size() != tx.tripPatterns.get(trip.patternId).patternStops.size()) {
                    halt(400);
                }

                tx.trips.put(trip.id, trip);
            }
            tx.commit();

            return trips;
        } catch (IOException e) {
            e.printStackTrace();
            halt(400);
        } catch (HaltException e) {
            LOG.error("Halt encountered", e);
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            halt(400);
        } finally {
            if (tx != null) tx.rollbackIfOpen();
        }
        return null;
    }
    
    public static Object updateTrip(Request req, Response res) {
        FeedTx tx = null;

        try {
            Trip trip = Base.mapper.readValue(req.body(), Trip.class);

            if (req.session().attribute("feedId") != null && !req.session().attribute("feedId").equals(trip.feedId))
                halt(400);

            if (!VersionedDataStore.feedExists(trip.feedId)) {
                halt(400);
            }

            tx = VersionedDataStore.getFeedTx(trip.feedId);

            if (!tx.trips.containsKey(trip.id)) {
                halt(400);
            }

            if (!tx.tripPatterns.containsKey(trip.patternId) || trip.stopTimes.size() != tx.tripPatterns.get(trip.patternId).patternStops.size()) {
                halt(400);
            }

            TripPattern patt = tx.tripPatterns.get(trip.patternId);

            // confirm that each stop in the trip matches the stop in the pattern

            for (int i = 0; i < trip.stopTimes.size(); i++) {
                TripPatternStop ps = patt.patternStops.get(i);
                StopTime st =  trip.stopTimes.get(i);

                if (st == null)
                    // skipped stop
                    continue;

                if (!st.stopId.equals(ps.stopId)) {
                    LOG.error("Mismatch between stop sequence in trip and pattern at position {}, pattern: {}, stop: {}", i, ps.stopId, st.stopId);
                    halt(400);
                }
            }

            tx.trips.put(trip.id, trip);
            tx.commit();

            return trip;
        } catch (IOException e) {
            e.printStackTrace();
            halt(400);
        } catch (HaltException e) {
            LOG.error("Halt encountered", e);
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            halt(400);
        } finally {
            if (tx != null) tx.rollbackIfOpen();
        }
        return null;
    }

    public static Object deleteTrip(Request req, Response res) {
        String id = req.params("id");
        String feedId = req.queryParams("feedId");
        String[] idList = req.queryParams("tripIds").split(",");

        if (feedId == null) {
            halt(400);
        }

        FeedTx tx = null;
        try {
            tx = VersionedDataStore.getFeedTx(feedId);

            // for a single trip
            if (id != null) {
                Trip trip = tx.trips.remove(id);
                return trip;
            }
            if (idList.length > 0) {
                Set<Trip> trips = new HashSet<>();
                for (String tripId : idList) {
                    Trip trip = tx.trips.remove(tripId);
                    trips.add(trip);
                }
                tx.commit();
                return trips;
            }

        } catch (HaltException e) {
            LOG.error("Halt encountered", e);
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            halt(400);
        } finally {
            if (tx != null) tx.rollbackIfOpen();
        }
        return null;
    }

    public static void register (String apiPrefix) {
        get(apiPrefix + "secure/trip/:id", TripController::getTrip, json::write);
        options(apiPrefix + "secure/trip", (q, s) -> "");
        get(apiPrefix + "secure/trip", TripController::getTrip, json::write);
        post(apiPrefix + "secure/trip", TripController::createTrip, json::write);
        put(apiPrefix + "secure/trip/:id", TripController::updateTrip, json::write);
        delete(apiPrefix + "secure/trip", TripController::deleteTrip, json::write);
        delete(apiPrefix + "secure/trip/:id", TripController::deleteTrip, json::write);
    }
}
