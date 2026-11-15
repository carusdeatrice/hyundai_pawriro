package kr.routerescue.demo;

import android.content.Context;
import android.graphics.*;
import kr.routerescue.demo.Scenario.Point;
import android.view.*;
import java.util.*;
import java.util.function.Consumer;
import static kr.routerescue.demo.Scenario.*;

final class MapView extends View {
    private static final int INK = 0xff22394b, BLUE = 0xff1677ff, PINK = 0xffd72d67, VIOLET = 0xff6c4cff;
    private final Paint paint = new Paint(3);
    private final Scenario scenario;
    private final Route route;
    private final Collection<Integer> selection;
    private final boolean editAreas;
    private final Point pin;
    private final Consumer<Point> tap;
    private float scale, left, top;
    MapView(Context context, Scenario scenario, Route route, Collection<Integer> selection,
            boolean editAreas, Point pin, Consumer<Point> tap) {
        super(context);
        this.scenario = scenario; this.route = route; this.selection = selection;
        this.editAreas = editAreas; this.pin = pin; this.tap = tap;
        setContentDescription("마포구 시연 지도");
        setClickable(tap != null);
    }
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xffedf1e8);
        scale = Math.min(getWidth(), getHeight()) / 100f;
        Point center = route == null && !editAreas && pin == null ? scenario.position : p(50,48);
        left = getWidth()/2f - center.x()*scale;
        top = getHeight()/2f - center.y()*scale;
        canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale,scale);
        shape(canvas, List.of(p(-100,60),p(10,70),p(27,91),p(76,101),p(160,108),p(160,160),p(-100,160)),
            0xffbfdfe9, true, 0);
        shape(canvas, List.of(p(5,18),p(25,12),p(37,25),p(22,36),p(7,33)), 0xffdce8d7, true, 0);
        for (int i = 0; i < scenario.services.areas().size(); i++) {
            Area area = scenario.services.areas().get(i);
            boolean marked = selection.contains(i);
            if (!editAreas && pin == null) {
                final int id = i;
                marked |= scenario.services.reports().stream().anyMatch(r -> !r.found && r.areas.contains(id));
            }
            if (marked || editAreas) {
                shape(canvas, area.outline(), marked ? 0x66f287b3 : 0x24718da1, true, 0);
                paint.setPathEffect(new DashPathEffect(new float[]{1.6f,1.1f},0));
                shape(canvas, area.outline(), marked ? 0xffd63870 : 0xff697a71, false, .58f);
                paint.setPathEffect(null);
            }
        }
        for (int i = -2; i < 13; i++) {
            float y = i * 8f;
            road(canvas, List.of(p(-30,y+14),p(13,y+5),p(49,y),p(93,y-11),p(145,y-16)), .95f);
        }
        for (int i = -2; i < 15; i++) {
            float x = i * 8f;
            road(canvas, List.of(p(x+14,-25),p(x+4,30),p(x,60),p(x-7,83)), .82f);
        }
        road(canvas, List.of(p(-18,51),p(16,56),p(39,56),p(57,48),p(78,32),p(110,25),p(144,22)), 2.1f);
        road(canvas, List.of(p(15,71),p(29,66),p(48,66),p(59,70),p(90,72),p(140,66)), 1.7f);
        label(canvas, "한강", p(8,86), 3.7f, 0xff39647b);
        label(canvas, "양화로", p(67,55), 2.9f, INK);
        label(canvas, "독막로", p(73,77), 2.9f, INK);
        for (Area area : scenario.services.areas()) {
            Point a = area.outline().get(0), b = area.outline().get(2);
            label(canvas, area.name(), p((a.x()+b.x())/2,(a.y()+b.y())/2+1), 3.5f, INK);
        }
        if (route != null) {
            line(canvas, route.line(), 0xffffffff, 3.2f);
            boolean rescue = route.stops().stream().anyMatch(Waypoint::rescue);
            line(canvas, route.line(), rescue ? VIOLET : BLUE, 2.1f);
            Point destination = route.destination().point();
            marker(canvas, destination, 0xff18231d, "⚑");
            label(canvas, route.destination().name(), p(destination.x(),destination.y()-5), 3.4f, INK);
            for (Waypoint stop : route.stops()) {
                marker(canvas, stop.point(), VIOLET, "◇");
                if (stop.rescue()) label(canvas,"수색 경유지",p(stop.point().x(),stop.point().y()-5),3.4f,0xff513891);
            }
        }
        if (!editAreas && pin == null) {
            for (Report report : scenario.services.reports()) {
                if (!report.found) paw(canvas, report.lastSeen);
            }
        }
        if (pin != null) paw(canvas,pin);
        circle(canvas,scenario.position,3.3f,0xffffffff);
        circle(canvas,scenario.position,2.5f,BLUE);
        circle(canvas,scenario.position,.8f,Color.WHITE);
        canvas.restore();
    }
    private void road(Canvas c, List<Point> points, float width) {
        line(c,points,0xffd7e0d9,width+.32f);
        line(c,points,0xfffafbf8,width);
    }
    private void shape(Canvas c, List<Point> points, int color, boolean fill, float width) {
        drawPath(c,points,color,fill,width,true);
    }
    private void line(Canvas c, List<Point> points, int color, float width) {
        drawPath(c,points,color,false,width,false);
    }
    private void drawPath(Canvas c, List<Point> points, int color, boolean fill, float width, boolean close) {
        if (points.isEmpty()) return;
        Path path = new Path();
        path.moveTo(points.get(0).x(),points.get(0).y());
        for (Point point : points.subList(1,points.size())) path.lineTo(point.x(),point.y());
        if (close) path.close();
        paint.setColor(color); paint.setStrokeWidth(width); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND); paint.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
        c.drawPath(path,paint);
        paint.setStyle(Paint.Style.FILL);
    }
    private void label(Canvas c, String label, Point at, float size, int color) {
        paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(size);
        paint.setColor(0xfff7faf7); paint.setStrokeWidth(.85f); paint.setStyle(Paint.Style.STROKE);
        c.drawText(label,at.x(),at.y(),paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(color);
        c.drawText(label,at.x(),at.y(),paint);
    }
    private void circle(Canvas c, Point at, float radius, int color) {
        paint.setColor(color);
        c.drawCircle(at.x(),at.y(),radius,paint);
    }
    private void marker(Canvas c, Point point, int color, String symbol) {
        circle(c,point,3.5f,0xffffffff);
        circle(c,point,2.8f,color);
        paint.setTextAlign(Paint.Align.CENTER); paint.setColor(Color.WHITE); paint.setTextSize(3.2f);
        c.drawText(symbol,point.x(),point.y()+1.12f,paint);
    }
    private void paw(Canvas c, Point point) {
        if (point == null) return;
        float x=point.x(), y=point.y();
        shape(c,List.of(p(x-2.4f,y+1),p(x+2.4f,y+1),p(x,y+5)),PINK,true,0);
        circle(c,point,3.65f,Color.WHITE);
        circle(c,point,3.15f,PINK);
        circle(c,p(x,y+.65f),.98f,Color.WHITE);
        for (int i=0;i<4;i++) circle(c,p(x-1.5f+i, y-(i==0||i==3?.55f:1.28f)),.47f,Color.WHITE);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (tap == null) return false;
        if (event.getAction() == MotionEvent.ACTION_UP && scale > 0) {
            performClick();
            float x=(event.getX()-left)/scale, y=(event.getY()-top)/scale;
            if (x>=0 && x<=100 && y>=0 && y<=100) tap.accept(p(x,y));
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
