package pawriro.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.Executors;

public final class Api {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DAY = "(now() AT TIME ZONE 'Asia/Seoul')::date";
    private static final String GEO = "ST_GeomFromGeoJSON(?::text)";

    public static void main(String[] args) throws Exception {
        if (Arrays.equals(args, new String[]{"migrate"})) {
            try (Connection db = connect(); Statement statement = db.createStatement()) {
                db.setAutoCommit(false);
                statement.execute(Schema.SQL);
                db.commit();
            }
            System.out.println("pawriro schema applied");
            return;
        }
        if (args.length == 2 && args[0].equals("guardian")) {
            try (Connection db = connect()) {
                Guardian.issue(db,args[1],requiredEnv("PAWRIRO_GUARDIAN_PASSWORD"));
            }
            return;
        }
        requiredEnv("PAWRIRO_API_KEY");
        requiredEnv("DATABASE_URL");
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8000"));
        var server = HttpServer.create(new InetSocketAddress(System.getenv().getOrDefault("PAWRIRO_BIND","127.0.0.1"), port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", Api::handle);
        server.start();
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(() -> {
            try (Connection db = connect()) {
                db.setAutoCommit(false);
                Guardian.purge(db);
                db.commit();
            } catch (SQLException error) {
                System.err.println("Retention cleanup failed: " + error.getSQLState());
            }
        }, 0, 1, java.util.concurrent.TimeUnit.HOURS);
        System.out.println("pawriro API listening on 127.0.0.1:" + port);
    }

    private static Connection connect() throws SQLException {
        Properties credentials = new Properties();
        if (System.getenv("DB_USER") != null) credentials.setProperty("user", System.getenv("DB_USER"));
        if (System.getenv("DB_PASSWORD") != null) credentials.setProperty("password", System.getenv("DB_PASSWORD"));
        credentials.setProperty("connectTimeout", "5");
        return DriverManager.getConnection(requiredEnv("DATABASE_URL"), credentials);
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + key);
        return value;
    }

    private static void handle(HttpExchange request) throws IOException {
        int status = 200;
        Object response;
        try {
            String path = request.getRequestURI().getPath();
            String method = request.getRequestMethod();
            String[] p = path.substring(1).split("/");
            if (!(path.equals("/vehicle/detour") && method.equals("POST")) && !path.startsWith("/guardian/") && !((path.equals("/health") || (path.equals("/vehicle/feed") || path.equals("/vehicle/candidates"))) && method.equals("GET"))) {
                authorize(request, path.startsWith("/review/") || p.length == 4 && p[0].equals("participations") &&
                    p[2].equals("points") && method.equals("PUT"));
            }
            try (Connection db = connect()) {
                db.setAutoCommit(false);
                response = dispatch(db, request, method, p);
                db.commit();
            }
            if (method.equals("POST") && !path.equals("/search/route") && !path.equals("/vehicle/detour") && !path.startsWith("/guardian/")) status = 201;
        } catch (Problem problem) {
            status = problem.status;
            response = Map.of("detail", problem.getMessage());
        } catch (IllegalArgumentException | java.time.DateTimeException | com.fasterxml.jackson.core.JsonProcessingException invalid) {
            status = 422;
            response = Map.of("detail", "Invalid request data");
        } catch (SQLException error) {
            String sqlState = Objects.toString(error.getSQLState(), "");
            status = switch (sqlState) {
                case "23503" -> 404;
                case "23505" -> 409;
                case "23514", "22023", "XX000" -> 422;
                default -> 503;
            };
            response = Map.of("detail", status == 503 ? "Database unavailable" : "Invalid or conflicting database record");
        } catch (RuntimeException error) {
            status = 500;
            response = Map.of("detail", "Request failed");
        }
        byte[] data = JSON.writeValueAsBytes(response);
        request.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        request.sendResponseHeaders(status, data.length);
        try (var output = request.getResponseBody()) { output.write(data); }
    }

    private static void authorize(HttpExchange request, boolean review) {
        String key = System.getenv(review ? "PAWRIRO_REVIEW_KEY" : "PAWRIRO_API_KEY");
        if (key == null || key.isBlank()) throw new Problem(503, "API key is not configured");
        String supplied = Objects.toString(request.getRequestHeaders().getFirst("Authorization"), "");
        if (!MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                ("Bearer " + key).getBytes(StandardCharsets.UTF_8))) {
            throw new Problem(review ? 403 : 401, "Invalid API credentials");
        }
    }

    private static Object dispatch(Connection db, HttpExchange request, String method, String[] p)
            throws SQLException, IOException {
        if (Arrays.equals(p,new String[]{"vehicle","detour"}) && method.equals("POST"))
            return VehicleRouting.plan(db,body(request));
        if (Arrays.equals(p,new String[]{"vehicle","candidates"}) && method.equals("GET"))
            return rows(db,"SELECT * FROM verified_candidates WHERE expires_at>now() ORDER BY area,poi_id");
        if (Arrays.equals(p,new String[]{"review","candidates"}) && method.equals("PUT")) {
            var input = readJson(request);
            if (!input.isArray()) return saveCandidate(db, input);
            if (input.isEmpty() || input.size() > 500) throw new Problem(422,"Expected 1 to 500 candidates");
            var saved = new ArrayList<Map<String,Object>>();
            for (JsonNode candidate : input) saved.add(saveCandidate(db, candidate));
            return saved;
        }
        if (Arrays.equals(p,new String[]{"review","candidates","revoke"}) && method.equals("POST")) {
            var b=body(request);
            return one(db,"DELETE FROM verified_candidates WHERE poi_id=? AND area=? AND mode=? RETURNING poi_id",
                text(b,"poi_id"),text(b,"area"),choice(text(b,"mode"),"solo","passenger"));
        }
        if (Arrays.equals(p,new String[]{"review","queue"}) && method.equals("GET"))
            return Map.of("reports",rows(db,"SELECT id,name,missing_at FROM reports WHERE review_state='pending'"),
                "sightings",rows(db,"SELECT id,report_id,seen_at FROM sightings WHERE review_state='pending'"));
        if (p.length==4 && p[0].equals("review") && method.equals("PATCH")) {
            String table=choice(p[1],"reports","sightings");
            return one(db,"UPDATE "+table+" SET review_state=? WHERE id=? RETURNING id,review_state",
                choice(p[3],"clear","pending","rejected"),UUID.fromString(p[2]));
        }
        if (Arrays.equals(p,new String[]{"vehicle","feed"}) && method.equals("GET"))
            return rows(db,"""
                SELECT r.id, array_to_json(array_agg(DISTINCT a.name ORDER BY a.name)) AS areas,
                    (SELECT max(s.seen_at) FROM sightings s WHERE s.report_id=r.id AND s.seen_at<=now() AND s.review_state='clear') AS latest_sighting,
                    json_agg(json_build_object('name',a.name,'shape',ST_AsGeoJSON(ST_Envelope(
                        ST_SnapToGrid(ST_Expand(COALESCE(a.polygon,a.center::geometry),
                            COALESCE(a.radius_m,0)/85000.0 + 0.001),0.001)))::json)) AS coverage
                FROM reports r JOIN search_areas a ON a.report_id=r.id
                WHERE r.status='active' AND r.review_state='clear' AND r.owner_name IS NOT NULL AND NOT a.retired
                GROUP BY r.id
                """);
        if (p.length >= 2 && p[0].equals("guardian")) return Guardian.dispatch(db,request,method,p);
        if (p.length == 1 && p[0].equals("health") && method.equals("GET"))
            return one(db, "SELECT PostGIS_Lib_Version() AS postgis");
        if (p.length == 1 && p[0].equals("reports")) {
            if (method.equals("GET")) return rows(db, """
                SELECT id,kind,name,coat,traits,missing_at,photo_url,status,
                    ST_AsGeoJSON(last_seen)::json AS last_seen, ST_AsGeoJSON(walk_route)::json AS walk_route
                FROM reports WHERE owner_name IS NULL ORDER BY missing_at DESC,id
                """);
            if (method.equals("POST")) return createReport(db, body(request));
        }
        if (p.length == 4 && p[0].equals("reports") && p[2].equals("status") && method.equals("PATCH"))
            return one(db, "UPDATE reports SET status=? WHERE id=? AND owner_name IS NULL RETURNING id,status",
                choice(p[3], "active", "found", "closed"), UUID.fromString(p[1]));
        if (p.length == 3 && p[0].equals("reports")) {
            UUID id = UUID.fromString(p[1]);
            if (p[2].equals("areas")) {
                if (method.equals("GET")) return rows(db, """
                    SELECT id,name,ST_AsGeoJSON(polygon)::json AS polygon,
                        ST_AsGeoJSON(center::geometry)::json AS center,radius_m
                    FROM search_areas WHERE report_id=? AND EXISTS (SELECT 1 FROM reports WHERE id=report_id AND owner_name IS NULL) ORDER BY name,id
                    """, id);
                if (method.equals("PUT")) {
                    one(db,"SELECT id FROM reports WHERE id=? AND owner_name IS NULL FOR UPDATE",id);
                    return setArea(db, id, body(request));
                }
            }
            if (p[2].equals("sightings")) {
                if (method.equals("GET")) return rows(db, """
                    SELECT id,seen_at,photo_url,ST_AsGeoJSON(location)::json AS location
                    FROM sightings WHERE report_id=? AND EXISTS (SELECT 1 FROM reports WHERE id=report_id AND owner_name IS NULL) ORDER BY seen_at DESC,id
                    """, id);
                if (method.equals("POST")) {
                    one(db,"SELECT id FROM reports WHERE id=? AND status='active' FOR UPDATE",id);
                    JsonNode b = body(request);
                    var seen=OffsetDateTime.parse(text(b,"seen_at"));
                    var position=geometry("Point",b.get("location"));
                    boolean suspicious=seen.isAfter(OffsetDateTime.now().plusMinutes(2)) ||
                        !rows(db,"""
                            SELECT 1 FROM sightings WHERE report_id=? AND abs(extract(epoch FROM seen_at-?::timestamptz))<60
                            AND ST_DWithin(location::geography,ST_GeomFromGeoJSON(?::text)::geography,20)
                            """,id,seen,position).isEmpty();
                    return one(db, "INSERT INTO sightings(id,report_id,location,seen_at,photo_url,review_state) VALUES (?,?,"+
                        GEO+",?,?,?) RETURNING id,review_state", uuid(b,"id"),id,position,seen,optional(b,"photo_url"),
                        suspicious?"pending":"clear");
                }
            }
        }
        if (Arrays.equals(p, new String[]{"search","route"}) && method.equals("POST")) {
            JsonNode b = body(request);
            int distance = integer(b,"distance_m",0,10000,100);
            return rows(db, """
                WITH route AS (SELECT ST_GeomFromGeoJSON(?::text)::geography AS line)
                SELECT a.id,a.report_id,a.name FROM search_areas a JOIN reports r ON r.id=a.report_id CROSS JOIN route
                WHERE r.status='active' AND r.review_state='clear' AND r.owner_name IS NULL AND (ST_DWithin(a.polygon::geography,route.line,?)
                    OR (ST_DWithin(a.center,route.line,?+5000) AND ST_DWithin(a.center,route.line,?+a.radius_m)))
                ORDER BY a.report_id,a.id
                """,geometry("LineString",b.get("remaining_route")),distance,distance,distance);
        }
        if (p.length >= 1 && p[0].equals("participations")) return participation(db,request,method,p);
        if (p.length == 3 && p[0].equals("devices") && p[2].equals("points") && method.equals("GET")) {
            var entries = rows(db, """
                SELECT participation_id,day,kind,amount FROM points WHERE device_id=? ORDER BY day DESC,participation_id
                """, UUID.fromString(p[1]));
            return Map.of("total",entries.stream().mapToInt(item -> (int)item.get("amount")).sum(),"entries",entries);
        }
        throw new Problem(404,"Endpoint not found");
    }

    private static Map<String,Object> saveCandidate(Connection db, JsonNode b) throws SQLException {
        String mode=choice(text(b,"mode"),"solo","passenger");
        var entry=b.get("location");
        point(entry);
        var exit=b.get("exit");
        if (mode.equals("passenger")) {
            point(exit);
            if (!Boolean.TRUE.equals(one(db,"SELECT ST_Distance(ST_GeomFromGeoJSON(?::text)::geography,ST_GeomFromGeoJSON(?::text)::geography)>60 AS separated",
                geometry("Point",entry),geometry("Point",exit)).get("separated")))
                throw new Problem(422,"Entry and exit must be more than 60 meters apart");
        } else exit=null;
        for (String key:List.of("legal_access","normal_traffic"))
            if (!b.path(key).isBoolean() || !b.path(key).booleanValue()) throw new Problem(422,key+" required");
        for (String key:List.of("requires_slowdown","repeated_road"))
            if (!b.path(key).isBoolean() || b.path(key).booleanValue()) throw new Problem(422,key+" must be false");
        if (mode.equals("solo") && (!b.path("legal_parking").booleanValue() || !b.path("walkable").booleanValue()))
            throw new Problem(422,"Legal parking and walk access required");
        var expiry=OffsetDateTime.parse(text(b,"expires_at"));
        if (!expiry.isAfter(OffsetDateTime.now())) throw new Problem(422,"Verification already expired");
        return one(db,"""
            INSERT INTO verified_candidates(poi_id,area,mode,latitude,longitude,exit_latitude,exit_longitude,expires_at,display_name,opening_hours)
            VALUES(?,?,?,?,?,?,?,?,?,?::jsonb)
            ON CONFLICT(poi_id,area,mode) DO UPDATE SET latitude=EXCLUDED.latitude,
                longitude=EXCLUDED.longitude,exit_latitude=EXCLUDED.exit_latitude,
                exit_longitude=EXCLUDED.exit_longitude,expires_at=EXCLUDED.expires_at,
                display_name=EXCLUDED.display_name,opening_hours=EXCLUDED.opening_hours
            RETURNING poi_id,area,mode,expires_at
            """,text(b,"poi_id"),text(b,"area"),mode,entry.get(1).doubleValue(),entry.get(0).doubleValue(),
            exit==null?null:exit.get(1).doubleValue(),exit==null?null:exit.get(0).doubleValue(),expiry,
            b.path("display_name").asText(text(b,"poi_id")),
            b.path("opening_hours").isObject()?b.get("opening_hours").toString():null);
    }

    static Object createReport(Connection db, JsonNode b) throws SQLException {
        return one(db,"INSERT INTO reports(id,kind,name,coat,traits,missing_at,photo_url,last_seen,walk_route) "+
            "VALUES (?,?,?,?,?,?,?,"+GEO+","+GEO+") RETURNING id",
            uuid(b,"id"),text(b,"kind"),text(b,"name"),b.path("coat").asText(""),text(b,"traits"),
            OffsetDateTime.parse(text(b,"missing_at")),optional(b,"photo_url"),geometry("Point",b.get("last_seen")),
            b.hasNonNull("walk_route") ? geometry("LineString",b.get("walk_route")) : null);
    }

    static Object setArea(Connection db, UUID id, JsonNode b) throws SQLException {
        boolean polygon=b.hasNonNull("polygon");
        if (polygon ? b.hasNonNull("center") || b.hasNonNull("radius_m") :
                !b.hasNonNull("center") || !b.hasNonNull("radius_m")) throw new Problem(422,"Provide one polygon or circle");
        var result = rows(db, """
            INSERT INTO search_areas(id,report_id,name,polygon,center,radius_m)
            VALUES (?,?,?,ST_GeomFromGeoJSON(?::text),ST_GeomFromGeoJSON(?::text)::geography,?)
            ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name,polygon=EXCLUDED.polygon,
                center=EXCLUDED.center,radius_m=EXCLUDED.radius_m
            WHERE search_areas.report_id=EXCLUDED.report_id RETURNING id
            """,uuid(b,"id"),id,text(b,"name"),polygon ? geometry("Polygon",b.get("polygon")) : null,
            polygon ? null : geometry("Point",b.get("center")),polygon ? null : integer(b,"radius_m",1,5000,0));
        if (result.isEmpty()) throw new Problem(409,"Area belongs to another report");
        return result.getFirst();
    }

    private static Object participation(Connection db,HttpExchange request,String method,String[] p)
            throws SQLException,IOException {
        if (p.length==1 && method.equals("POST")) {
            JsonNode b=body(request);
            var result=rows(db,"""
                INSERT INTO participations(id,area_id,device_id,mode)
                SELECT ?,a.id,?,? FROM search_areas a JOIN reports r ON r.id=a.report_id
                WHERE a.id=? AND r.status='active' AND r.review_state='clear' AND NOT a.retired AND r.owner_name IS NULL ON CONFLICT DO NOTHING RETURNING id
                """,uuid(b,"id"),uuid(b,"device_id"),choice(text(b,"mode"),"solo","passenger"),uuid(b,"area_id"));
            if (result.isEmpty()) throw new Problem(409,"Participation already exists or search is unavailable");
            return result.getFirst();
        }
        if (p.length<2) throw new Problem(404,"Endpoint not found");
        UUID id=UUID.fromString(p[1]);
        if (p.length==2 && method.equals("GET")) {
            var row=one(db,"SELECT * FROM participations WHERE id=?",id);
            row.put("visits",rows(db,"""
                SELECT id,event,recorded_at,ST_AsGeoJSON(location)::json AS location
                FROM visit_events WHERE participation_id=? ORDER BY recorded_at,id
                """,id));
            return row;
        }
        if (p.length==3 && p[2].equals("visits") && method.equals("POST")) {
            var row=one(db,"SELECT * FROM participations WHERE id=? FOR UPDATE",id);
            if (row.get("result")!=null) throw new Problem(409,"Participation already submitted");
            JsonNode b=body(request);
            return one(db,"INSERT INTO visit_events(id,participation_id,event,location) VALUES (?,?,?,"+GEO+") RETURNING id",
                uuid(b,"id"),id,choice(text(b,"event"),"arrived","entered","passed","parked","door_open","exited","returned","unparked"),
                geometry("Point",b.get("location")));
        }
        if (p.length==3 && p[2].equals("result") && method.equals("PUT")) {
            var row=one(db,"SELECT * FROM participations WHERE id=? FOR UPDATE",id);
            JsonNode b=body(request);
            String result=choice(text(b,"result"),"no_sighting","sighting");
            UUID sighting=b.hasNonNull("sighting_id") ? uuid(b,"sighting_id") : null;
            if (row.get("result")!=null) {
                if (result.equals(row.get("result")) && Objects.equals(sighting,row.get("sighting_id"))) return row;
                throw new Problem(409,"Participation already submitted");
            }
            if (result.equals("sighting")!=(sighting!=null)) throw new Problem(422,"Check sighting_id");
            if (sighting!=null && rows(db,"""
                SELECT 1 FROM sightings s JOIN search_areas a ON a.report_id=s.report_id WHERE s.id=? AND a.id=?
                """,sighting,row.get("area_id")).isEmpty()) throw new Problem(422,"Sighting must belong to the same report");
            return one(db,"""
                UPDATE participations SET result=?,sighting_id=?,submitted_at=now(),
                    hold_reason='trusted_visit_evidence_required' WHERE id=? RETURNING *
                """,result,sighting,id);
        }
        if (p.length==4 && p[2].equals("points") && method.equals("PUT"))
            return award(db,id,choice(p[3],"base","photo"));
        throw new Problem(404,"Endpoint not found");
    }

    private static Object award(Connection db,UUID id,String kind) throws SQLException {
        var row=one(db,"SELECT * FROM participations WHERE id=? FOR UPDATE",id);
        var existing=rows(db,"SELECT * FROM points WHERE participation_id=? AND kind=?",id,kind);
        if (!existing.isEmpty()) return existing.getFirst();
        if (row.get("result")==null) throw new Problem(409,"Submit a result first");
        if (rows(db,"SELECT 1 FROM search_areas a JOIN reports r ON r.id=a.report_id WHERE a.id=? AND r.review_state='clear'",
                row.get("area_id")).isEmpty()) throw new Problem(409,"Report is under review");
        boolean base=kind.equals("base");
        if (!base && rows(db,"""
            SELECT 1 FROM points p JOIN sightings s ON s.id=?
            WHERE p.participation_id=? AND p.kind='base' AND NULLIF(s.photo_url,'') IS NOT NULL AND s.review_state='clear'
            """,row.get("sighting_id"),id).isEmpty()) throw new Problem(409,"Base points and sighting photo required");
        Object device=row.get("device_id"), area=row.get("area_id");
        rows(db,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",device.toString());
        if (base && !rows(db,"SELECT 1 FROM points WHERE device_id=? AND area_id=? AND kind='base' AND day="+DAY,
                device,area).isEmpty()) throw new Problem(409,"Already rewarded for this area today");
        int amount=base?50:25;
        Number total=(Number)one(db,"SELECT COALESCE(sum(amount),0) AS total FROM points WHERE device_id=? AND day="+DAY,
            device).get("total");
        if (total.longValue()+amount>150) throw new Problem(409,"Daily limit reached");
        var created=one(db,"INSERT INTO points(participation_id,device_id,area_id,kind,amount) VALUES (?,?,?,?,?) RETURNING *",
            id,device,area,kind,amount);
        if (base) {
            try (var update=db.prepareStatement("UPDATE participations SET hold_reason=NULL WHERE id=?")) {
                update.setObject(1,id);
                update.executeUpdate();
            }
        }
        return created;
    }

    static Map<String,Object> one(Connection db,String sql,Object... args) throws SQLException {
        var result=rows(db,sql,args);
        if (result.isEmpty()) throw new Problem(404,"Record not found");
        return result.getFirst();
    }

    static List<Map<String,Object>> rows(Connection db,String sql,Object... args) throws SQLException {
        try (var statement=db.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            try (var result=statement.executeQuery()) {
                var columns=result.getMetaData();
                List<Map<String,Object>> records=new ArrayList<>();
                while (result.next()) {
                    Map<String,Object> row=new LinkedHashMap<>();
                    for (int i=1;i<=columns.getColumnCount();i++) {
                        Object value=result.getObject(i);
                        if (value instanceof org.postgresql.util.PGobject pg) {
                            if (Set.of("json","jsonb").contains(pg.getType())) {
                                try { value=JSON.readTree(pg.getValue()); }
                                catch (IOException invalid) { throw new SQLException("Invalid stored JSON",invalid); }
                            } else value=pg.getValue();
                        }
                        if (value instanceof Timestamp) value=result.getObject(i,OffsetDateTime.class).toString();
                        if (value instanceof java.sql.Date date) value=date.toLocalDate().toString();
                        row.put(columns.getColumnLabel(i),value);
                    }
                    records.add(row);
                }
                return records;
            }
        }
    }

    static JsonNode body(HttpExchange request) throws IOException {
        JsonNode value=readJson(request);
        if (!value.isObject()) throw new Problem(422,"JSON object required");
        return value;
    }

    private static JsonNode readJson(HttpExchange request) throws IOException {
        byte[] bytes=request.getRequestBody().readNBytes(1_048_577);
        if (bytes.length>1_048_576) throw new Problem(413,"Body too large");
        JsonNode value=JSON.readTree(bytes);
        if (value==null) throw new Problem(422,"JSON required");
        return value;
    }

    static String optional(JsonNode b,String field) {
        if (!b.hasNonNull(field)) return null;
        return text(b,field);
    }
    static String text(JsonNode b,String field) {
        JsonNode value=b.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length()>4000)
            throw new Problem(422,"Invalid "+field);
        return value.asText();
    }
    static UUID uuid(JsonNode b,String field) { return UUID.fromString(text(b,field)); }
    static String choice(String value,String... allowed) {
        if (!Arrays.asList(allowed).contains(value)) throw new Problem(422,"Unsupported value");
        return value;
    }
    private static int integer(JsonNode b,String field,int min,int max,int fallback) {
        if (!b.hasNonNull(field)) return fallback;
        JsonNode node=b.get(field);
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue()<min || node.intValue()>max)
            throw new Problem(422,"Invalid "+field);
        return node.intValue();
    }
    static String geometry(String type,JsonNode coordinates) {
        if (type.equals("Point")) point(coordinates);
        else {
            int minimum=type.equals("Polygon")?4:2;
            if (coordinates==null || !coordinates.isArray() || coordinates.size()<minimum || coordinates.size()>10000)
                throw new Problem(422,"Invalid geometry size");
            coordinates.forEach(Api::point);
            if (type.equals("Polygon")) {
                if (!coordinates.get(0).equals(coordinates.get(coordinates.size()-1)))
                    throw new Problem(422,"Polygon must be closed");
                coordinates=JSON.createArrayNode().add(coordinates);
            }
        }
        return JSON.createObjectNode().put("type",type).set("coordinates",coordinates).toString();
    }
    static void point(JsonNode point) {
        if (point==null || !point.isArray() || point.size()!=2 || !point.get(0).isNumber() || !point.get(1).isNumber()
                || !(Math.abs(point.get(0).asDouble())<=180) || !(Math.abs(point.get(1).asDouble())<=90))
            throw new Problem(422,"Expected [longitude, latitude]");
    }
    static final class Problem extends RuntimeException {
        final int status;
        Problem(int status,String message) { super(message); this.status=status; }
    }
}
