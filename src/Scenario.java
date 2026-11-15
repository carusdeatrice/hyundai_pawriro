package kr.routerescue.demo;

import java.util.*;

final class Scenario {
    record Point(float x, float y) {}
    record Place(String name, String address, Point point, int minutes, double km) {}
    record Area(String name, List<Point> outline, Point parking, Point road) {
        boolean contains(Point p) {
            boolean inside = false;
            for (int i = 0, j = outline.size() - 1; i < outline.size(); j = i++) {
                Point a = outline.get(i), b = outline.get(j);
                if ((a.y > p.y) != (b.y > p.y)
                    && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside;
            }
            return inside;
        }
    }
    record Sighting(Point point, String time, String notes) {}
    static final class Report {
        String id = UUID.randomUUID().toString(), kind = "", name = "", coat = "", traits = "", time = "";
        Point lastSeen;
        boolean mine, found;
        int completed;
        final List<Integer> areas = new ArrayList<>();
        final List<Sighting> sightings = new ArrayList<>();
    }
    record Waypoint(String name, Point point, boolean rescue) {}
    record Route(Place destination, List<Waypoint> stops, List<Point> line, int minutes, double km) {}
    record Offer(Report report, int areaId, Route previous, Route proposed) {
        int extraMinutes() { return proposed.minutes - previous.minutes; }
    }
    interface Services {
        List<Place> search(String query);
        List<Area> areas();
        List<Report> reports();
        Route plan(Point from, Place place, List<Waypoint> stops);
    }
    interface Navigation {
        boolean apply(Route route);
        Route current();
        void stop();
    }
    static final Point START = new Point(29, 66);
    static final class DemoServices implements Services {
        final List<Area> areas = List.of(
            new Area("망원동", List.of(p(10,41),p(24,32),p(39,37),p(44,53),p(30,64),p(13,58)), p(25,53), p(34,49)),
            new Area("합정동", List.of(p(32,65),p(44,55),p(61,59),p(62,74),p(48,84),p(32,78)), p(44,73), p(51,65)),
            new Area("서교동", List.of(p(44,28),p(65,23),p(77,34),p(72,52),p(59,57),p(45,52)), p(56,39), p(61,48)),
            new Area("연남동", List.of(p(66,8),p(86,5),p(95,19),p(86,31),p(76,30),p(65,22)), null, p(79,23))
        );
        final List<Place> places = List.of(
            new Place("홍대입구역", "서울 마포구 양화로", p(79,32), 24, 5.2),
            new Place("연남동 주민센터", "서울 마포구 성미산로", p(83,17), 27, 6.1),
            new Place("상수역", "서울 마포구 독막로", p(72,72), 18, 3.8),
            new Place("망원시장", "서울 마포구 포은로", p(24,42), 10, 2.2)
        );
        final List<Report> reports = new ArrayList<>();
        DemoServices() {
            Report sample = new Report();
            sample.id = "sample";
            sample.kind = "반려견"; sample.name = "모카"; sample.coat = "갈색 · 흰색 가슴";
            sample.traits = "파란 목줄을 착용한 소형견"; sample.time = "2026-11-12 17:30";
            sample.lastSeen = p(22,46); sample.areas.add(0);
            reports.add(sample);
        }
        public List<Place> search(String query) {
            String key = query.strip().replace(" ", "");
            List<Place> matches = new ArrayList<>();
            for (Place place : places) {
                if ((place.name + place.address).replace(" ", "").contains(key)) matches.add(place);
            }
            return matches;
        }
        public List<Area> areas() { return areas; }
        public List<Report> reports() { return reports; }
        public Route plan(Point from, Place place, List<Waypoint> stops) {
            List<Point> line = new ArrayList<>();
            line.add(from);
            if (from.equals(START) && stops.isEmpty() && place.point.x > 60) {
                line.add(p(34,66)); line.add(p(34,55)); line.add(p(53,55));
            }
            for (Waypoint stop : stops) connect(line, stop.point);
            connect(line, place.point);
            int extra = 0;
            double distance = place.km;
            for (Waypoint stop : stops) {
                if (!stop.rescue) continue;
                boolean parking = stop.name.endsWith("주차장");
                extra += parking ? 6 : 3;
                distance += parking ? 1.1 : 0.6;
            }
            return new Route(place, List.copyOf(stops), List.copyOf(line), place.minutes + extra, distance);
        }
        private void connect(List<Point> line, Point destination) {
            Point from = line.get(line.size() - 1);
            if (distance(from, destination) < 0.1) return;
            line.add(p((from.x + destination.x) / 2, from.y));
            line.add(p((from.x + destination.x) / 2, destination.y));
            line.add(destination);
        }
    }
    static final class DemoNavigation implements Navigation {
        boolean failNext;
        private Route active;
        public boolean apply(Route route) {
            if (failNext) { failNext = false; return false; }
            active = route;
            return true;
        }
        public Route current() { return active; }
        public void stop() { active = null; failNext = false; }
    }

    final Services services;
    final Navigation navigation;
    final Set<String> asked = new HashSet<>();
    Point position = START;
    Route route;
    Offer pending;
    Report mission;
    boolean passenger, maneuver;
    int segment;
    Scenario(Services services, Navigation navigation) {
        this.services = services;
        this.navigation = navigation;
    }
    boolean start(Route preview) {
        if (!navigation.apply(preview)) return false;
        asked.clear(); pending = null; mission = null; segment = 0;
        route = navigation.current();
        return route != null;
    }
    void stop() {
        navigation.stop();
        route = null; pending = null; mission = null; segment = 0;
    }
    void advance() {
        if (route != null && segment < route.line.size() - 1) position = route.line.get(++segment);
    }
    Offer offer() {
        if (route == null || pending != null || maneuver || mission != null) return null;
        for (Report report : services.reports()) {
            if (report.found || asked.contains(report.id)) continue;
            for (int id : report.areas) {
                Area area = services.areas().get(id);
                Point waypoint = passenger ? area.road : area.parking;
                if (waypoint == null || !ahead(area)) continue;
                List<Waypoint> stops = new ArrayList<>();
                stops.add(new Waypoint(area.name + (passenger ? " 일반 도로" : " 주차장"), waypoint, true));
                stops.addAll(route.stops);
                Route proposed = services.plan(position, route.destination, stops);
                if (proposed == null) continue;
                asked.add(report.id);
                pending = new Offer(report, id, route, proposed);
                return pending;
            }
        }
        return null;
    }
    private boolean ahead(Area area) {
        for (int i = segment; i < route.line.size() - 1; i++) {
            Point a = route.line.get(i), b = route.line.get(i + 1);
            int steps = Math.max(1, (int) Math.ceil(distance(a,b) / 2));
            for (int step = 1; step <= steps; step++) {
                float t = (float) step / steps;
                if (area.contains(p(a.x + (b.x-a.x)*t, a.y + (b.y-a.y)*t))) return true;
            }
        }
        return false;
    }
    enum Reply { KEPT, APPLIED, FAILED }
    Reply reply(String command) {
        Offer offer = pending;
        pending = null;
        String normalized = command == null ? "" : command.strip().replaceAll("[.!?。]", "");
        if (offer == null || !Set.of("참여할게", "참여", "응").contains(normalized)) return Reply.KEPT;
        Route active = navigation.current();
        if (active == null || route != offer.previous || !active.destination.equals(offer.previous.destination)
            || !active.stops.equals(offer.previous.stops) || !services.reports().contains(offer.report)
            || offer.report.found || !offer.report.areas.contains(offer.areaId)
            || !ahead(services.areas().get(offer.areaId))) return Reply.KEPT;
        boolean success = navigation.apply(offer.proposed);
        Route actual = navigation.current();
        if (!Objects.equals(route, actual)) segment = 0;
        route = actual;
        if (!success || route == null || !route.destination.equals(offer.proposed.destination)
            || !route.stops.equals(offer.proposed.stops)) return Reply.FAILED;
        mission = offer.report;
        return Reply.APPLIED;
    }
    static Point p(float x, float y) { return new Point(x,y); }
    static double distance(Point a, Point b) { return Math.hypot(a.x-b.x, a.y-b.y); }
}
