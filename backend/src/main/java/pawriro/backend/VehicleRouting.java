package pawriro.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

final class VehicleRouting {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final String DIRECTIONS = System.getenv().getOrDefault("ORS_DIRECTIONS_URL",
        "https://api.openrouteservice.org/v2/directions");
    private static final Semaphore REQUESTS = new Semaphore(2);
    private static final int MAX_EXTRA_SECONDS = 600;
    private static final ZoneId PARKING_ZONE = ZoneId.of("Asia/Seoul");

    private record Route(JsonNode line, double seconds, double meters, JsonNode segments, JsonNode wayPoints) {}

    static Object plan(Connection db, JsonNode input) throws SQLException {
        if (!REQUESTS.tryAcquire()) throw new Api.Problem(429, "Routing busy");
        try {
            return calculate(db, input);
        } catch (IOException failure) {
            throw new Api.Problem(503, "Routing provider unavailable");
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new Api.Problem(503, "Routing interrupted");
        } finally {
            REQUESTS.release();
        }
    }

    static Object overview(JsonNode input) {
        JsonNode points = input.path("stops");
        if (!points.isArray() || points.size()<2 || points.size()>4)
            throw new Api.Problem(422,"Origin, destination and up to two waypoints required");
        List<JsonNode> stops = new ArrayList<>();
        points.forEach(point -> {
            Api.point(point);
            stops.add(point);
        });
        if (System.getenv("ORS_API_KEY") == null || System.getenv("ORS_API_KEY").isBlank())
            throw new Api.Problem(503,"Routing provider key unavailable");
        if (!REQUESTS.tryAcquire()) throw new Api.Problem(429,"Routing busy");
        try {
            Route result = route("driving-car",stops,100);
            if (result == null) throw new Api.Problem(404,"Route unavailable");
            return Map.of("line",result.line());
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new Api.Problem(503,"Routing interrupted");
        } catch (IOException failure) {
            throw new Api.Problem(503,"Routing provider unavailable");
        } finally {
            REQUESTS.release();
        }
    }

    private static Object calculate(Connection db, JsonNode input)
            throws SQLException, IOException, InterruptedException {
        String mode = input.path("mode").asText();
        if (!List.of("solo", "passenger").contains(mode)) throw new Api.Problem(422, "Invalid mode");
        JsonNode stops = input.path("stops");
        if (!stops.isArray() || stops.size() < 2 || stops.size() > 3)
            throw new Api.Problem(422, "Origin, destination and at most one existing waypoint required");
        stops.forEach(Api::point);
        JsonNode excluded = input.path("excluded");
        if (!excluded.isArray() || excluded.size() > 100) throw new Api.Problem(422, "Invalid exclusions");
        String report = input.path("report_id").asText("");
        String area = input.path("area").asText("");
        String poi = input.path("poi_id").asText("");
        if (System.getenv("ORS_API_KEY") == null || System.getenv("ORS_API_KEY").isBlank())
            throw new Api.Problem(503, "Routing provider key unavailable");
        if (mode.equals("passenger")) return passengerPlan(db, stops, excluded, report, area);
        var candidates = Api.rows(db, """
            SELECT c.*, r.id AS report_id,
                ST_AsGeoJSON(ST_Envelope(ST_SnapToGrid(ST_Expand(COALESCE(a.polygon,a.center::geometry),
                    COALESCE(a.radius_m,0)/85000.0+0.001),0.001))) AS coverage,
                ST_AsGeoJSON(ST_PointOnSurface(COALESCE(a.polygon,a.center::geometry))) AS walk_target,
                ST_AsGeoJSON(COALESCE(a.polygon,ST_Buffer(a.center,a.radius_m)::geometry)) AS area_shape
            FROM verified_candidates c JOIN search_areas a ON a.name=c.area
                JOIN reports r ON r.id=a.report_id
            WHERE c.mode=? AND c.expires_at>now() AND NOT a.retired
                AND r.status='active' AND r.review_state='clear' AND r.owner_name IS NOT NULL
                AND NOT jsonb_exists(?::jsonb,r.id::text)
                AND (?='' OR r.id::text=?) AND (?='' OR c.area=?) AND (?='' OR c.poi_id=?)
                AND ST_DWithin(ST_SetSRID(ST_MakePoint(c.longitude,c.latitude),4326)::geography,
                    ST_GeomFromGeoJSON(?::text)::geography,15000)
            ORDER BY (SELECT max(s.seen_at) FROM sightings s WHERE s.report_id=r.id
                AND s.review_state='clear' AND s.seen_at<=now()) DESC NULLS LAST,
                ST_Distance(ST_SetSRID(ST_MakePoint(c.longitude,c.latitude),4326)::geography,
                    ST_GeomFromGeoJSON(?::text)::geography), c.poi_id, r.id
            LIMIT 6
            """, mode, excluded.toString(), report, report,
                area, area, poi, poi, pointGeometry(stops.get(0)), pointGeometry(stops.get(0)));
        if (candidates.isEmpty()) return Map.of("reason", "지원 지역의 안전 후보 없음");

        List<JsonNode> original = new ArrayList<>();
        stops.forEach(original::add);
        Route baseline = route("driving-car", original);
        if (baseline == null) return Map.of("reason", "기존 경로 ETA 미확보");
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        for (var candidate : candidates) {
            if (System.nanoTime() > deadline) break;
            JsonNode entry = coordinate(candidate, "longitude", "latitude");
            if (!Boolean.TRUE.equals(Api.one(db, """
                WITH data AS (SELECT ST_GeomFromGeoJSON(?::text) AS route,
                    ST_GeomFromGeoJSON(?::text) AS area, ST_GeomFromGeoJSON(?::text) AS entry),
                metrics AS (SELECT *,ST_Length(route::geography) AS meters FROM data)
                SELECT CASE WHEN meters<=100 THEN false ELSE
                    ST_DWithin(area::geography,ST_LineSubstring(route,50/meters,1)::geography,250)
                    AND ST_DWithin(entry::geography,route::geography,800)
                    AND ST_LineLocatePoint(route,entry)>50/meters END AS eligible FROM metrics
                """, lineGeometry(baseline.line()), candidate.get("area_shape"), pointGeometry(entry)).get("eligible"))) continue;
            JsonNode target = JSON.readTree(candidate.get("walk_target").toString()).path("coordinates");
            Route walking = route("foot-walking", List.of(entry, target));
            if (walking == null || walking.meters()>800 || walking.seconds()>900) continue;
            for (int index = 1; index < original.size() && System.nanoTime() < deadline; index++) {
                var via = new ArrayList<>(original);
                via.add(index, entry);
                Route detour = route("driving-car", via);
                if (detour == null || detour.seconds()-baseline.seconds()>MAX_EXTRA_SECONDS) continue;
                if (!parkingOpen(candidate, arrivalSeconds(detour, index))) continue;

                var result = JSON.createObjectNode().put("report_id", candidate.get("report_id").toString())
                    .put("area", candidate.get("area").toString()).put("poi_id", candidate.get("poi_id").toString())
                    .put("name", candidate.get("display_name").toString().isBlank() ? candidate.get("poi_id").toString() : candidate.get("display_name").toString()).put("expires_at", candidate.get("expires_at").toString())
                    .put("index", index-1).put("baseline_seconds", (int)Math.ceil(baseline.seconds()))
                    .put("detour_seconds", (int)Math.ceil(detour.seconds()))
                    .put("extra_minutes", (int)Math.ceil(Math.max(0, detour.seconds()-baseline.seconds())/60));
                result.set("entry", entry);
                result.putNull("exit");
                result.set("line", detour.line());
                result.set("coverage", JSON.readTree(candidate.get("coverage").toString()).path("coordinates").get(0));
                return result;
            }
        }
        return Map.of("reason", "수색 구역 접근 또는 10분 이내 경유 경로 미확보");
    }

    private static Object passengerPlan(Connection db, JsonNode stops, JsonNode excluded, String report, String area)
            throws SQLException, IOException, InterruptedException {
        // Two slots are required to pin the entry and exit without replacing a driver's waypoint.
        if (stops.size() != 2) return Map.of("reason", "수색 진입·통과점을 넣을 경유 슬롯 부족");
        List<JsonNode> original = List.of(stops.get(0), stops.get(1));
        Route baseline = route("driving-car", original);
        if (baseline == null) return Map.of("reason", "기존 경로 ETA 미확보");
        var areas = Api.rows(db, """
            WITH path AS (SELECT ST_GeomFromGeoJSON(?::text)::geography AS line),
            areas AS (
                SELECT a.*, COALESCE(a.polygon,ST_Buffer(a.center,a.radius_m)::geometry) AS shape
                FROM search_areas a JOIN reports r ON r.id=a.report_id
                WHERE NOT a.retired AND r.status='active' AND r.review_state='clear' AND r.owner_name IS NOT NULL
                    AND NOT jsonb_exists(?::jsonb,r.id::text)
                    AND (?='' OR r.id::text=?) AND (?='' OR a.name=?)
            )
            SELECT report_id,name,ST_AsGeoJSON(shape) AS shape,
                ST_AsGeoJSON(ST_PointOnSurface(shape)) AS target,
                ST_AsGeoJSON(ST_Envelope(ST_SnapToGrid(ST_Expand(COALESCE(polygon,center::geometry),
                    COALESCE(radius_m,0)/85000.0+0.001),0.001))) AS coverage
            FROM areas CROSS JOIN path WHERE ST_DWithin(shape::geography,path.line,250)
            ORDER BY (SELECT max(s.seen_at) FROM sightings s WHERE s.report_id=areas.report_id
                AND s.review_state='clear' AND s.seen_at<=now()) DESC NULLS LAST, report_id, name, id
            LIMIT 3
            """, lineGeometry(baseline.line()), excluded.toString(), report, report, area, area);
        long deadline = System.nanoTime()+Duration.ofSeconds(9).toNanos();
        for (var candidate : areas) {
            if (System.nanoTime()>=deadline) break;
            String shape = candidate.get("shape").toString();
            String visitArea = Api.one(db, """
                WITH d AS (SELECT ST_Transform(ST_GeomFromGeoJSON(?::text),5179) AS area)
                SELECT ST_AsGeoJSON(ST_Transform(ST_UnaryUnion(ST_Collect(g.geom)),4326)) AS geometry
                FROM d CROSS JOIN LATERAL ST_SquareGrid(20,ST_Intersection(d.area,ST_Buffer(ST_PointOnSurface(d.area),1500))) g
                WHERE ST_Covers(d.area,g.geom)
                """,shape).get("geometry") instanceof String geometry ? geometry : null;
            if (visitArea == null) continue;
            var segment = searchSegment(db, baseline, visitArea);
            if (segment.isEmpty()) {
                var target = JSON.readTree(candidate.get("target").toString()).path("coordinates");
                Route probe = route("driving-car", List.of(original.getFirst(), target, original.getLast()));
                if (probe == null || probe.seconds()-baseline.seconds()>MAX_EXTRA_SECONDS) continue;
                segment = searchSegment(db, probe, visitArea);
            }
            if (segment.isEmpty() || System.nanoTime()>=deadline) continue;
            var bounds = segment.getFirst();
            JsonNode entry = JSON.readTree(bounds.get("entry").toString()).path("coordinates");
            JsonNode exit = JSON.readTree(bounds.get("exit").toString()).path("coordinates");
            Route detour = route("driving-car", List.of(original.getFirst(), entry, exit, original.getLast()));
            if (detour == null || detour.seconds()-baseline.seconds()>MAX_EXTRA_SECONDS) continue;
            if (detour.segments().size()!=3 || detour.wayPoints().size()!=4 ||
                    detour.segments().get(1).path("duration").asDouble(-1)<12) continue;
            int start = detour.wayPoints().get(1).asInt(-1);
            int end = detour.wayPoints().get(2).asInt(-1);
            if (start<0 || end<=start || end>=detour.line().size()) continue;
            var leg = JSON.createArrayNode();
            for (int i=start; i<=end; i++) leg.add(detour.line().get(i));
            if (!Boolean.TRUE.equals(Api.one(db, """
                WITH d AS (SELECT ST_GeomFromGeoJSON(?::text) AS leg,ST_GeomFromGeoJSON(?::text) AS area)
                SELECT ST_IsSimple(leg) AND ST_Length(leg::geography) BETWEEN 200 AND 1000
                    AND ST_Covers(area,leg) AS ok FROM d
                """, lineGeometry(leg),visitArea).get("ok"))) continue;
            var result = JSON.createObjectNode().put("report_id", candidate.get("report_id").toString())
                .put("area", candidate.get("name").toString()).put("poi_id", "pawriro-search-"+candidate.get("report_id"))
                .put("name", candidate.get("name")+" 수색 도로 진입")
                .put("expires_at", java.time.OffsetDateTime.now().plusMinutes(2).toString())
                .put("index", 0).put("baseline_seconds",(int)Math.ceil(baseline.seconds()))
                .put("detour_seconds",(int)Math.ceil(detour.seconds()))
                .put("extra_minutes",(int)Math.ceil(Math.max(0,detour.seconds()-baseline.seconds())/60));
            result.set("entry",entry);
            result.set("exit",exit);
            result.set("line",detour.line());
            result.set("visit_area",JSON.readTree(visitArea));
            result.set("coverage",JSON.readTree(candidate.get("coverage").toString()).path("coordinates").get(0));
            return result;
        }
        return Map.of("reason","10분 이내에 주행 가능한 수색 구간 없음");
    }

    private static List<Map<String,Object>> searchSegment(Connection db, Route route, String shape) throws SQLException {
        return Api.rows(db, """
            WITH d AS (SELECT ST_GeomFromGeoJSON(?::text) AS line, ST_GeomFromGeoJSON(?::text) AS area),
            parts AS (SELECT line,(ST_Dump(ST_CollectionExtract(ST_Intersection(line,area),2))).geom AS leg
                FROM d WHERE ST_IsSimple(line)),
            ordered AS (SELECT line,LEAST(ST_LineLocatePoint(line,ST_StartPoint(leg)),
                    ST_LineLocatePoint(line,ST_EndPoint(leg))) AS first,
                GREATEST(ST_LineLocatePoint(line,ST_StartPoint(leg)),
                    ST_LineLocatePoint(line,ST_EndPoint(leg))) AS last
                FROM parts WHERE ST_Length(leg::geography) BETWEEN 240 AND 1000)
            SELECT ST_AsGeoJSON(ST_LineInterpolatePoint(line,first+(last-first)*0.075)) AS entry,
                ST_AsGeoJSON(ST_LineInterpolatePoint(line,last-(last-first)*0.075)) AS exit
            FROM ordered WHERE first*ST_Length(line::geography)>50 AND last<1 ORDER BY first LIMIT 1
            """,lineGeometry(route.line()),shape);
    }

    private static double arrivalSeconds(Route route, int index) {
        if (route.segments().size() < index) return route.seconds();
        double seconds = 0;
        for (int i=0; i<index; i++) {
            double leg = route.segments().get(i).path("duration").asDouble(-1);
            if (!Double.isFinite(leg) || leg < 0) return route.seconds();
            seconds += leg;
        }
        return seconds;
    }

    private static boolean parkingOpen(Map<String,Object> candidate, double seconds) throws IOException {
        Object hours = candidate.get("opening_hours");
        if (hours == null) return false;
        JsonNode schedule = JSON.readTree(hours.toString());
        ZonedDateTime now = ZonedDateTime.now(PARKING_ZONE);
        // Require access from arrival through the minimum search stay.
        ZonedDateTime until = now.plusSeconds((long)Math.ceil(seconds)+180);
        for (JsonNode period : schedule.path(Integer.toString(now.getDayOfWeek().getValue()))) {
            int start = period.path(0).asInt(-1);
            int end = period.path(1).asInt(-1);
            ZonedDateTime midnight = now.toLocalDate().atStartOfDay(PARKING_ZONE);
            if (start >= 0 && end > start && end <= 1440 &&
                    !now.plusSeconds((long)Math.ceil(seconds)).isBefore(midnight.plusMinutes(start)) && !until.isAfter(midnight.plusMinutes(end))) return true;
        }
        return false;
    }

    private static Route route(String profile, List<JsonNode> stops) throws IOException, InterruptedException {
        return route(profile,stops,35);
    }

    private static Route route(String profile, List<JsonNode> stops, int radius) throws IOException, InterruptedException {
        var coordinates = JSON.createArrayNode();
        var radiuses = JSON.createArrayNode();
        stops.forEach(stop -> {
            coordinates.add(stop);
            radiuses.add(radius);
        });
        var body = JSON.createObjectNode();
        body.set("coordinates", coordinates);
        body.set("radiuses", radiuses);
        body.put("instructions", false);
        var request = HttpRequest.newBuilder(URI.create(DIRECTIONS+"/"+profile+"/geojson"))
            .timeout(Duration.ofSeconds(3))
            .header("Authorization", System.getenv("ORS_API_KEY"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()==404 || response.statusCode()==400) return null;
        if (response.statusCode()!=200) throw new IOException("Routing provider HTTP "+response.statusCode());
        var feature = JSON.readTree(response.body()).path("features").path(0);
        var line = feature.path("geometry").path("coordinates");
        var summary = feature.path("properties").path("summary");
        double seconds = summary.path("duration").asDouble(-1);
        double meters = summary.path("distance").asDouble(-1);
        if (!line.isArray() || line.size()<2 || line.size()>20000 || !Double.isFinite(seconds)
                || !Double.isFinite(meters) || seconds<=0 || meters<=0) return null;
        return new Route(line,seconds,meters,feature.path("properties").path("segments"),feature.path("properties").path("way_points"));
    }

    private static ArrayNode coordinate(Map<String,Object> row, String longitude, String latitude) {
        return JSON.createArrayNode().add(((Number)row.get(longitude)).doubleValue()).add(((Number)row.get(latitude)).doubleValue());
    }

    private static String pointGeometry(JsonNode coordinates) {
        return geometry("Point", coordinates);
    }

    private static String lineGeometry(JsonNode coordinates) {
        return geometry("LineString", coordinates);
    }

    private static String geometry(String type, JsonNode coordinates) {
        var geometry = JSON.createObjectNode().put("type", type);
        geometry.set("coordinates", coordinates);
        return geometry.toString();
    }

    private VehicleRouting() {}
}
