package kr.routerescue.demo;

import android.content.SharedPreferences;
import java.util.*;
import org.json.*;
import static kr.routerescue.demo.Scenario.*;

final class Storage {
    private final SharedPreferences prefs;
    Storage(SharedPreferences prefs) { this.prefs = prefs; }
    void save(List<Report> reports, Report draft, int step, boolean passenger) {
        try {
            JSONArray array = new JSONArray();
            for (Report r : reports) array.put(encode(r));
            SharedPreferences.Editor edit = prefs.edit().putString("reports",array.toString())
                .putInt("step",step).putBoolean("passenger",passenger);
            if (draft == null) edit.remove("draft");
            else edit.putString("draft",encode(draft).toString());
            edit.apply();
        } catch (JSONException failure) { throw new IllegalStateException(failure); }
    }
    void loadReports(List<Report> reports) {
        try {
            JSONArray array = new JSONArray(prefs.getString("reports","[]"));
            if (prefs.contains("reports")) reports.clear();
            for (int i=0;i<array.length();i++) reports.add(decode(array.getJSONObject(i)));
        } catch (JSONException ignored) {}
    }
    Report draft() {
        try { return decode(new JSONObject(prefs.getString("draft",""))); }
        catch (JSONException ignored) { return null; }
    }
    int step() { return prefs.getInt("step",0); }
    boolean passenger() { return prefs.getBoolean("passenger",false); }
    private JSONObject encode(Report r) throws JSONException {
        JSONArray sightings = new JSONArray();
        for (Sighting s : r.sightings) sightings.put(new JSONObject().put("pin",point(s.point()))
            .put("time",s.time()).put("notes",s.notes()));
        return new JSONObject().put("id",r.id).put("kind",r.kind).put("name",r.name)
            .put("coat",r.coat).put("traits",r.traits).put("time",r.time).put("pin",point(r.lastSeen))
            .put("mine",r.mine).put("found",r.found).put("areas",new JSONArray(r.areas))
            .put("completed",r.completed).put("sightings",sightings);
    }
    private Report decode(JSONObject json) throws JSONException {
        Report r = new Report();
        r.id=json.getString("id"); r.kind=json.getString("kind"); r.name=json.getString("name");
        r.coat=json.getString("coat"); r.traits=json.getString("traits"); r.time=json.getString("time");
        r.lastSeen=point(json.optJSONArray("pin")); r.mine=json.getBoolean("mine");
        r.found=json.getBoolean("found"); r.completed=json.optInt("completed");
        JSONArray areas=json.getJSONArray("areas"), sightings=json.getJSONArray("sightings");
        for(int i=0;i<areas.length();i++) r.areas.add(areas.getInt(i));
        for(int i=0;i<sightings.length();i++){
            JSONObject s=sightings.getJSONObject(i);
            r.sightings.add(new Sighting(point(s.getJSONArray("pin")),s.getString("time"),s.getString("notes")));
        }
        return r;
    }
    private JSONArray point(Point p) throws JSONException {
        return p == null ? null : new JSONArray().put((double)p.x()).put((double)p.y());
    }
    private Point point(JSONArray array) throws JSONException {
        return array == null ? null : p((float)array.getDouble(0),(float)array.getDouble(1));
    }
}
