package pawriro.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import static pawriro.backend.Api.*;

final class Guardian {
    private static final SecureRandom RANDOM = new SecureRandom();

    static void issue(Connection db,String name,String password) throws SQLException {
        if (!name.matches("[a-zA-Z0-9_.-]{3,60}") || password.length()<12)
            throw new IllegalArgumentException("Use a username of 3–60 characters and at least 12 password characters");
        byte[] salt=new byte[16];
        RANDOM.nextBytes(salt);
        db.setAutoCommit(false);
        update(db,"""
            INSERT INTO guardian_accounts VALUES (?,?,?)
            ON CONFLICT(username) DO UPDATE SET salt=EXCLUDED.salt,password_hash=EXCLUDED.password_hash
            """,name,salt,passwordHash(password,salt));
        update(db,"DELETE FROM guardian_sessions WHERE username=?",name);
        db.commit();
    }

    static Object dispatch(Connection db,HttpExchange request,String method,String[] path) throws IOException,SQLException {
        if (path.length==2 && path[1].equals("login") && method.equals("POST")) {
            var input=body(request);
            String username=text(input,"username"), password=text(input,"password");
            var accounts=rows(db,"SELECT salt,password_hash FROM guardian_accounts WHERE username=?",username);
            byte[] salt=accounts.isEmpty()?new byte[16]:(byte[])accounts.getFirst().get("salt");
            byte[] hash=passwordHash(password,salt);
            if (accounts.isEmpty() || !MessageDigest.isEqual(hash,(byte[])accounts.getFirst().get("password_hash")))
                throw new Problem(401,"Invalid guardian credentials");
            byte[] secret=new byte[32];
            RANDOM.nextBytes(secret);
            String token=HexFormat.of().formatHex(secret);
            update(db,"INSERT INTO guardian_sessions VALUES (?,?,now()+interval '12 hours')",digest(token),username);
            return Map.of("token",token);
        }
        String header=request.getRequestHeaders().getFirst("Authorization");
        if (header==null || !header.matches("Bearer [a-f0-9]{64}")) throw new Problem(401,"Guardian login required");
        var users=rows(db,"SELECT username FROM guardian_sessions WHERE token_hash=? AND expires_at>now()",digest(header.substring(7)));
        if (users.isEmpty()) throw new Problem(401,"Guardian session expired");
        Object owner=users.getFirst().get("username");
        if (path.length==2 && path[1].equals("logout") && method.equals("POST")) {
            update(db,"DELETE FROM guardian_sessions WHERE token_hash=?",digest(header.substring(7)));
            return Map.of("ok",true);
        }
        if (path.length==2 && path[1].equals("reports") && method.equals("GET"))
            return rows(db,"""
                SELECT id,name,kind,status,review_state,traits,coat,missing_at,photo_data,places,purged_at,
                    ST_AsGeoJSON(last_seen)::json AS last_seen, ST_AsGeoJSON(walk_route)::json AS walk_route
                FROM reports WHERE owner_name=? ORDER BY missing_at DESC
                """,owner);
        if (path.length<3 || !path[1].equals("reports")) throw new Problem(404,"Endpoint not found");
        UUID id=UUID.fromString(path[2]);
        if (path.length==3 && method.equals("PUT")) return save(db,owner,id,body(request));
        var owned=one(db,"SELECT status FROM reports WHERE id=? AND owner_name=? FOR UPDATE",id,owner);
        if (path.length==5 && path[3].equals("status") && method.equals("PATCH"))
            return one(db,"""
                UPDATE reports SET status=?,closed_at=COALESCE(closed_at,now()) WHERE id=? RETURNING id,status
                """,choice(path[4],"found","closed"),id);
        if (path.length==4 && path[3].equals("sightings") && method.equals("GET"))
            return rows(db,"""
                SELECT id,seen_at,photo_url,review_state,ST_AsGeoJSON(location)::json AS location FROM sightings
                WHERE report_id=? ORDER BY seen_at DESC
                """,id);
        if (path.length==4 && path[3].equals("areas") && method.equals("GET"))
            return rows(db,"""
                SELECT id,name,ST_AsGeoJSON(polygon)::json AS polygon,ST_AsGeoJSON(center::geometry)::json AS center,radius_m
                FROM search_areas WHERE report_id=? AND NOT retired ORDER BY name
                """,id);
        throw new Problem(404,"Endpoint not found");
    }

    private static Object save(Connection db,Object owner,UUID id,JsonNode input) throws SQLException {
        var existing=rows(db,"SELECT owner_name,status FROM reports WHERE id=? FOR UPDATE",id);
        if (!existing.isEmpty() && !owner.equals(existing.getFirst().get("owner_name")))
            throw new Problem(404,"Record not found");
        if (!existing.isEmpty() && !"active".equals(existing.getFirst().get("status")))
            throw new Problem(409,"Closed report");
        var areas=input.path("areas");
        var places=input.path("places");
        if (!areas.isArray() || areas.isEmpty() || areas.size()>10 || !places.isArray() || places.size()>20)
            throw new Problem(422,"Provide 1–10 areas and up to 20 places");
        for (var place:places) {
            text(place,"name");
            point(place.get("location"));
        }
        String photo=input.hasNonNull("photo_data") ? input.path("photo_data").asText() : null;
        if (photo!=null && (photo.length()>500000 || !photo.matches("[A-Za-z0-9+/]+={0,2}")))
            throw new Problem(422,"Invalid photo data");
        var written=rows(db,"""
            INSERT INTO reports(id,owner_name,kind,name,coat,traits,missing_at,last_seen,walk_route,places,photo_data)
            VALUES (?,?,?,?,?,?,?,ST_GeomFromGeoJSON(?::text),ST_GeomFromGeoJSON(?::text),?::jsonb,?)
            ON CONFLICT(id) DO UPDATE SET kind=EXCLUDED.kind,name=EXCLUDED.name,coat=EXCLUDED.coat,
            traits=EXCLUDED.traits,missing_at=EXCLUDED.missing_at,last_seen=EXCLUDED.last_seen,
            walk_route=EXCLUDED.walk_route,places=EXCLUDED.places,
            photo_data=COALESCE(EXCLUDED.photo_data,reports.photo_data)
            WHERE reports.owner_name=EXCLUDED.owner_name AND reports.status='active' RETURNING id
            """,id,owner,text(input,"kind"),text(input,"name"),input.path("coat").asText(""),text(input,"traits"),
            OffsetDateTime.parse(text(input,"missing_at")),geometry("Point",input.get("last_seen")),
            input.hasNonNull("walk_route")?geometry("LineString",input.get("walk_route")):null,places.toString(),photo);
        if (written.isEmpty()) throw new Problem(409,"Report cannot be updated");
        update(db,"""
            UPDATE reports r SET review_state='pending'
            WHERE r.id=? AND r.review_state='clear' AND (r.missing_at>now()+interval '2 minutes' OR EXISTS (
                SELECT 1 FROM reports other WHERE other.id<>r.id AND other.owner_name=r.owner_name
                AND other.name=r.name AND other.status='active'
                AND abs(extract(epoch FROM other.missing_at-r.missing_at))<86400
                AND ST_DWithin(other.last_seen::geography,r.last_seen::geography,100)))
            """,id);
        List<UUID> kept=new ArrayList<>();
        for (var area:areas) {
            choice(text(area,"name"),"성수동1가","성수동2가","송정동");
            UUID areaId=uuid(area,"id");
            if (kept.contains(areaId)) throw new Problem(422,"Duplicate area");
            kept.add(areaId);
            setArea(db,id,area);
        }
        try (var stmt=db.prepareStatement("UPDATE search_areas SET retired=true WHERE report_id=? AND NOT(id=ANY(?))")) {
            stmt.setObject(1,id);
            stmt.setArray(2,db.createArrayOf("uuid",kept.toArray()));
            stmt.executeUpdate();
        }
        update(db,"UPDATE search_areas SET retired=false WHERE report_id=? AND id=ANY(?)",id,
            db.createArrayOf("uuid",kept.toArray()));
        return Map.of("id",id);
    }

    static void purge(Connection db) throws SQLException {
        update(db,"""
            UPDATE reports SET purged_at=now(),last_seen=NULL,walk_route=NULL,places='[]',photo_data=NULL,
            photo_url=NULL,traits='',coat='',name='종료된 신고',kind=''
            WHERE closed_at<=now()-interval '30 days' AND purged_at IS NULL
            """);
        update(db,"""
            UPDATE search_areas a SET retired=true,name='',polygon=NULL,center=NULL,radius_m=NULL
            FROM reports r WHERE r.id=a.report_id AND r.purged_at IS NOT NULL AND a.name<>''
            """);
        update(db,"UPDATE participations p SET sighting_id=NULL,sighting_redacted=true FROM search_areas a,reports r WHERE p.area_id=a.id AND r.id=a.report_id AND r.purged_at IS NOT NULL AND p.sighting_id IS NOT NULL");
        update(db,"DELETE FROM sightings s USING reports r WHERE s.report_id=r.id AND r.purged_at IS NOT NULL");
        update(db,"DELETE FROM visit_events v USING participations p,search_areas a,reports r WHERE v.participation_id=p.id AND p.area_id=a.id AND a.report_id=r.id AND r.purged_at IS NOT NULL");
        update(db,"DELETE FROM guardian_sessions WHERE expires_at<=now()");
    }

    private static void update(Connection db,String sql,Object... args) throws SQLException {
        try (var stmt=db.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++) stmt.setObject(i+1,args[i]);
            stmt.executeUpdate();
        }
    }

    private static byte[] passwordHash(String password,byte[] salt) {
        var spec=new PBEKeySpec(password.toCharArray(),salt,310_000,256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (java.security.GeneralSecurityException error) { throw new IllegalStateException(error); }
        finally { spec.clearPassword(); }
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.GeneralSecurityException error) { throw new IllegalStateException(error); }
    }

    private Guardian() {}
}
