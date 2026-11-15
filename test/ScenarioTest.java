package kr.routerescue.demo;

import java.util.List;
import static kr.routerescue.demo.Scenario.*;

public final class ScenarioTest {
    public static void main(String[] args) {
        DemoServices services = new DemoServices();
        DemoNavigation nav = new DemoNavigation();
        Scenario app = new Scenario(services,nav);
        Place destination = services.search("홍대").get(0);
        check(services.search("없는 목적지").isEmpty(), "search filtering");
        Route base = services.plan(START,destination,List.of());
        check(app.start(base) && app.route == base,"start");
        app.maneuver = true;
        check(app.offer() == null,"defer during maneuver");
        app.maneuver = false;
        Offer solo = app.offer();
        check(solo != null && solo.extraMinutes() == 6,"solo ETA");
        check(app.reply("참여할게") == Reply.APPLIED,"accept");
        check(app.route.destination().equals(destination) && app.route.stops().get(0).name().endsWith("주차장"),"solo destination and waypoint");
        check(app.offer()==null,"one offer per session");
        app.passenger=true;
        app.start(base);
        Offer passenger = app.offer();
        check(passenger != null && passenger.extraMinutes()==3,"passenger ETA");
        check(app.reply("응") == Reply.APPLIED && !app.route.stops().equals(solo.proposed().stops()),"passenger road");
        for (String reply : new String[]{"아니","건너뛸게","취소","글쎄",null}) {
            app.start(base);
            check(app.offer()!=null,"offer before reply");
            check(app.reply(reply)==Reply.KEPT && app.route==base && app.offer()==null,"retain route");
        }
        app.start(base);
        app.offer();
        nav.failNext=true;
        check(app.reply("참여")==Reply.FAILED && app.route==nav.current() && app.route==base,"failed apply sync");
        app.start(base);
        Offer invalidated=app.offer();
        invalidated.report().found=true;
        check(app.reply("참여")==Reply.KEPT && app.route==base,"revalidate report");
        invalidated.report().found=false;
        app.start(base);
        while (app.segment<base.line().size()-1) app.advance();
        check(app.offer()==null,"exclude passed areas");
        Waypoint previous = new Waypoint("기존 경유지", p(34,49), false);
        app.position=START;
        Route withStop=services.plan(START,destination,List.of(previous));
        app.start(withStop);
        check(app.offer()!=null && app.reply("참여")==Reply.APPLIED,"accept with previous waypoint");
        check(app.route.stops().contains(previous) && app.route.destination().equals(destination),"preserve existing waypoint");
        System.out.println("PASS: search, start, offer, deferral, both modes, acceptance, decline, silence, failure, validation, progress, existing waypoint");
    }
    private static void check(boolean value,String label) {
        if (!value) throw new AssertionError(label);
    }
}
