package kr.routerescue.demo;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import static kr.routerescue.demo.Scenario.*;

public final class MainActivity extends Activity {
    private enum Screen { HOME, SEARCH, PREVIEW, DRIVE, REPORT, MINE, DETAIL, AREAS, SIGHTING }
    private static final int INK=0xff152019, MUTED=0xff526057, GREEN=0xff226b45,
        LIME=0xffc9ff62, BG=0xfff8faf8, LINE=0xffd8ded9, DARK=0xff17221c;
    private final DemoNavigation navigation = new DemoNavigation();
    private final Scenario model = new Scenario(new DemoServices(),navigation);
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Runnable expiry = () -> respond(null);
    private Screen screen=Screen.HOME;
    private LinearLayout root, panel;
    private Route preview;
    private Report draft, selected;
    private int step;
    private boolean restricted, demo, automotive, choosingPlace;
    private Point sightingPin;
    private String sightingTime="", sightingNotes="", query="";
    private final List<Integer> editingAreas=new ArrayList<>();
    private Storage storage;
    private VoiceOutput voice;
    private VehicleRestrictions vehicle;
    private Dialog detailDialog;
    private BroadcastReceiver controls;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        automotive=getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
        demo=getIntent().getBooleanExtra("demo",false) &&
            (Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.MODEL.contains("Emulator"));
        restricted=automotive && !demo;
        storage=new Storage(getSharedPreferences("route-rescue",MODE_PRIVATE));
        storage.loadReports(model.services.reports());
        draft=storage.draft(); step=storage.step(); model.passenger=storage.passenger();
        voice=new VoiceOutput(this);
        if (automotive && !demo) {
            try { vehicle=new VehicleRestrictions(this, value -> runOnUiThread(() -> restrict(value))); }
            catch (LinkageError | RuntimeException unavailable) { restricted=true; }
        }
        if (demo) {
            controls=new BroadcastReceiver() {
                @Override public void onReceive(Context context,Intent intent) {
                    command(intent.getStringExtra("event"),intent.getStringExtra("answer"));
                }
            };
            registerReceiver(controls,new IntentFilter("kr.routerescue.demo.CONTROL"),"android.permission.DUMP",null);
        }
        render();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        render();
    }
    @Override protected void onPause() { persist(); super.onPause(); }
    @Override protected void onDestroy() {
        timer.removeCallbacksAndMessages(null);
        if (controls!=null) unregisterReceiver(controls);
        if (vehicle!=null) vehicle.close();
        voice.close();
        super.onDestroy();
    }
    private void command(String event,String answer) {
        if (event==null) return;
        switch(event) {
            case "drive" -> { restrict(true); checkOffer(); }
            case "park" -> { respond(null); restrict(false); }
            case "tick" -> { model.advance(); checkOffer(); render(); }
            case "turn" -> { model.maneuver=true; voice.stop(); respond(null); }
            case "clear-turn" -> { model.maneuver=false; checkOffer(); }
            case "reply" -> respond(answer);
            case "timeout" -> respond(null);
            case "fail" -> navigation.failNext=true;
            case "stop" -> { model.stop(); model.maneuver=false; model.position=START; restrict(false); open(Screen.HOME); }
        }
    }
    private void checkOffer() {
        if (!demo || !restricted) return;
        Offer offer=model.offer();
        if (offer==null) return;
        String prompt="이동 경로 근처에 실종 반려동물 수색 요청이 있어요. 약 "
            +offer.extraMinutes()+"분 더 걸려요. 참여할까요?";
        android.util.Log.i("PawriroDemo",prompt);
        voice.speak(prompt);
        timer.postDelayed(expiry,15000);
    }
    private void respond(String answer) {
        if (model.pending==null) return;
        timer.removeCallbacks(expiry);
        voice.stop();
        Reply result=model.reply(answer);
        android.util.Log.i("PawriroDemo","reply="+result);
        if (result==Reply.FAILED) render();
        if (result==Reply.APPLIED) render();
    }
    private void restrict(boolean value) {
        if (restricted==value) return;
        restricted=value;
        if (value) {
            persist(); hideKeyboard();
            if (detailDialog!=null) detailDialog.dismiss();
            choosingPlace=false;
            screen=model.route==null?Screen.HOME:Screen.DRIVE;
        }
        render();
    }
    private void persist() {
        if (storage!=null) storage.save(model.services.reports(),draft,step,model.passenger);
    }
    private void hideKeyboard() {
        View focus=getCurrentFocus();
        if (focus!=null) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focus.getWindowToken(),0);
            focus.clearFocus();
        }
    }
    private void open(Screen next) {
        hideKeyboard();
        screen=next;
        render();
    }
    @Override public void onBackPressed() {
        if (restricted || screen==Screen.HOME) return;
        if (screen==Screen.SEARCH && choosingPlace) {
            choosingPlace=false; open(Screen.REPORT); return;
        }
        if (screen==Screen.REPORT && step>0) { step--; open(Screen.REPORT); return; }
        if (screen==Screen.AREAS || screen==Screen.SIGHTING) { open(Screen.DETAIL); return; }
        if (screen==Screen.DETAIL && selected.mine) { open(Screen.MINE); return; }
        if (screen==Screen.PREVIEW) { open(Screen.SEARCH); return; }
        open(model.route!=null && screen!=Screen.DRIVE?Screen.DRIVE:Screen.HOME);
    }

    private void render() {
        boolean wide=getResources().getConfiguration().screenWidthDp>=720;
        root=column();
        root.setBackgroundColor(BG);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        if (screen==Screen.HOME || screen==Screen.DRIVE) {
            FrameLayout canvas=new FrameLayout(this);
            root.addView(canvas,new LinearLayout.LayoutParams(-1,0,1));
            canvas.addView(map(),new FrameLayout.LayoutParams(-1,-1));
            if (screen==Screen.HOME) home(canvas);
            else guidance(canvas);
            return;
        }

        boolean mapScreen=switch(screen) {
            case PREVIEW,AREAS,SIGHTING -> true;
            case REPORT -> step>0;
            default -> false;
        };
        String heading=switch(screen) {
            case SEARCH -> choosingPlace?"마지막 목격 장소 검색":"목적지 검색";
            case PREVIEW -> "경로 미리보기";
            case REPORT -> "실종 신고  ·  "+(step+1)+" / 4";
            case MINE -> "내 신고";
            case DETAIL -> "수색 요청";
            case AREAS -> "신고 관리";
            case SIGHTING -> "목격 제보";
            default -> "";
        };
        toolbar(heading);
        if (screen==Screen.REPORT) stepper();
        LinearLayout body=new LinearLayout(this);
        body.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        if (mapScreen) {
            body.addView(map(),wide?new LinearLayout.LayoutParams(0,-1,1)
                :new LinearLayout.LayoutParams(-1,0,1));
        }
        if (wide && screen==Screen.REPORT && step==0) {
            LinearLayout intro=column();
            intro.setPadding(dp(30),dp(24),dp(24),dp(24));
            intro.addView(text("반려동물 정보",18,GREEN,true));
            intro.addView(text("찾는 데 필요한 정보를 알려주세요",32,INK,true));
            body.addView(intro,new LinearLayout.LayoutParams(0,-1,1));
        }
        ScrollView scroll=new ScrollView(this);
        boolean split=wide && (mapScreen || screen==Screen.REPORT);
        LinearLayout.LayoutParams panelSize=split?new LinearLayout.LayoutParams(dp(410),-1)
            :mapScreen?new LinearLayout.LayoutParams(-1,0,1):new LinearLayout.LayoutParams(-1,-1);
        body.addView(scroll,panelSize);
        panel=column();
        panel.setBackgroundColor(mapScreen?Color.WHITE:BG);
        panel.setPadding(dp(24),dp(18),dp(24),dp(24));
        scroll.addView(panel);
        switch(screen) {
            case SEARCH -> search();
            case PREVIEW -> preview();
            case REPORT -> report();
            case MINE -> mine();
            case DETAIL -> detail();
            case AREAS -> areas(true);
            case SIGHTING -> sighting();
            default -> { }
        }
    }
    private void toolbar(String heading) {
        LinearLayout bar=new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20),dp(6),dp(20),dp(6));
        root.addView(bar,new LinearLayout.LayoutParams(-1,-2));
        Button back=button(bar,"←",false,this::onBackPressed);
        back.setContentDescription("뒤로");
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(60),dp(60)));
        TextView title=text(heading,21,INK,true);
        title.setGravity(Gravity.CENTER);
        bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        View spacer=new View(this);
        bar.addView(spacer,new LinearLayout.LayoutParams(dp(60),1));
    }
    private void stepper() {
        LinearLayout progress=new LinearLayout(this);
        progress.setPadding(dp(20),dp(3),dp(20),dp(6));
        root.addView(progress,new LinearLayout.LayoutParams(-1,-2));
        progress.setContentDescription("신고 "+(step+1)+"단계 / 4단계");
        for (int i=0;i<4;i++) {
            View bar=new View(this);
            bar.setBackground(surface(i<=step?GREEN:LINE,3,0));
            LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(0,dp(5),1);
            size.setMargins(dp(3),0,dp(3),0);
            progress.addView(bar,size);
        }
    }
    private void overlay(FrameLayout canvas, View child, int width, int gravity, int margin) {
        FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(width,-2,gravity);
        position.setMargins(dp(margin),dp(margin),dp(margin),dp(margin));
        canvas.addView(child,position);
    }
    private LinearLayout card(int color) {
        LinearLayout card=column();
        card.setBackground(surface(color,20,0));
        card.setPadding(dp(16),dp(12),dp(16),dp(12));
        card.setElevation(dp(4));
        return card;
    }

    private View map() {
        Collection<Integer> selection=List.of();
        Point pin=null;
        boolean editing=false;
        Consumer<Point> tap=null;
        Route displayed=screen==Screen.PREVIEW?preview:screen==Screen.DRIVE?model.route:null;
        if (screen==Screen.HOME && !restricted) tap=this::inspect;
        if (screen==Screen.REPORT && step>0) {
            pin=draft.lastSeen;
            if (step==1 && !restricted) tap=point -> { draft.lastSeen=point; persist(); render(); };
            if (step>=2) selection=draft.areas;
            if (step==2 && !restricted) { editing=true; tap=point -> selectArea(point,draft.areas); }
        }
        if (screen==Screen.AREAS) {
            selection=editingAreas; editing=true; pin=selected.lastSeen;
            if (!restricted) tap=point -> selectArea(point,editingAreas);
        }
        if (screen==Screen.SIGHTING) {
            pin=sightingPin;
            if (!restricted) tap=point -> { sightingPin=point; render(); };
        }
        return new MapView(this,model,displayed,selection,editing,pin,tap);
    }
    private void home(FrameLayout canvas) {
        int available=getResources().getConfiguration().screenWidthDp;
        if (!restricted) {
            LinearLayout top=column();
            TextView brand=text("🐾  pawriro",19,INK,true);
            brand.setBackground(surface(LIME,12,0));
            brand.setPadding(dp(12),dp(6),dp(12),dp(6));
            top.addView(brand,new LinearLayout.LayoutParams(-2,-2));
            Button search=button(top,"⌕   목적지를 입력하세요",false,() -> {
                query=""; open(Screen.SEARCH);
            });
            search.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            search.setElevation(dp(4));
            overlay(canvas,top,dp(Math.min(620,available-40)),Gravity.TOP|Gravity.START,20);
        }
        LinearLayout bottom=column();
        TextView legend=text("▧  수색 동네     •  마지막 목격",16,INK,true);
        legend.setPadding(dp(14),dp(5),dp(14),dp(5));
        legend.setBackground(surface(Color.WHITE,12,LINE));
        bottom.addView(legend,new LinearLayout.LayoutParams(-2,-2));
        if (!restricted) {
            LinearLayout actions=row(bottom);
            button(actions,draft==null?"＋  실종 신고":"신고 초안 이어쓰기",false,this::openReport);
            button(actions,"🐾  내 신고",false,() -> open(Screen.MINE));
            Button mode=button(bottom,model.passenger?"탑승 모드 · 동승자 참여":"탑승 모드 · 운전자 단독",false,this::mode);
            mode.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        } else {
            TextView locked=text("주행 중 목적지 검색과 신고 편집을 잠갔어요.",18,INK,true);
            locked.setBackground(surface(Color.WHITE,12,LINE));
            locked.setPadding(dp(12),dp(5),dp(12),dp(5));
            bottom.addView(locked);
        }
        overlay(canvas,bottom,dp(Math.min(400,available-40)),Gravity.BOTTOM|Gravity.START,20);
    }
    private void openReport() {
        if (draft==null) { draft=new Report(); draft.mine=true; step=0; }
        open(Screen.REPORT);
    }
    private void mode() {
        if (restricted) return;
        detailDialog=new AlertDialog.Builder(this).setTitle("탑승 모드").setSingleChoiceItems(
            new String[]{"운전자 단독 · 주차 후 도보 수색","동승자 참여 · 일반 도로 경유"},
            model.passenger?1:0,(dialog,index) -> {
                if (!restricted) setMode(index==1);
                dialog.dismiss();
            }).setNegativeButton("닫기",null).show();
    }
    private void setMode(boolean passenger) {
        model.passenger=passenger;
        persist();
        render();
    }
    private void search() {
        EditText field=input(panel,"목적지를 입력하세요",query,value -> query=value);
        LinearLayout results=column();
        panel.addView(results);
        Consumer<String> update=value -> {
            results.removeAllViews();
            List<Place> places=model.services.search(value);
            results.addView(text(places.isEmpty()?"검색 결과가 없어요":value.isBlank()?"시연 목적지":"검색 결과",18,MUTED,true));
            for (Place place:places) button(results,place.name()+"\n"+place.address()+"   ›",false,() -> {
                if (restricted) return;
                if (choosingPlace) {
                    draft.lastSeen=place.point(); choosingPlace=false; persist(); open(Screen.REPORT);
                } else {
                    preview=model.services.plan(model.position,place,List.of()); open(Screen.PREVIEW);
                }
            });
        };
        watch(field,update);
        update.accept(query);
        button(panel,"뒤로",false,this::onBackPressed);
    }
    private void preview() {
        title(preview.destination().name());
        note(preview.destination().address());
        stats(preview.minutes(),preview.km());
        note("━  추천 경로");
        panel.addView(text("탑승 모드",19,INK,true));
        LinearLayout modes=row(panel);
        modeButton(modes,"운전자 단독",false);
        modeButton(modes,"동승자 참여",true);
        button(panel,"안내 시작",true,() -> {
            if (restricted) return;
            if (model.start(preview)) { open(Screen.DRIVE); checkOffer(); }
            else message("안내를 시작할 수 없어요.");
        });
        button(panel,"다른 목적지 검색",false,() -> open(Screen.SEARCH));
    }
    private void modeButton(LinearLayout parent,String label,boolean passenger) {
        boolean active=model.passenger==passenger;
        Button mode=button(parent,label,false,() -> setMode(passenger));
        mode.setSelected(active);
        mode.setBackground(surface(active?0xffeaf7ef:BG,15,active?GREEN:LINE));
        mode.setTextColor(active?GREEN:INK);
    }
    private void guidance(FrameLayout canvas) {
        Route route=model.route;
        LinearLayout turnCard=card(DARK);
        LinearLayout turnRow=row(turnCard);
        turnRow.setGravity(Gravity.CENTER_VERTICAL);
        Turn turn=route==null?new Turn("!","경로 확인 중",""):nextTurn(route);
        TextView glyph=text(turn.arrow,40,DARK,true);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(surface(LIME,16,0));
        turnRow.addView(glyph,new LinearLayout.LayoutParams(dp(68),dp(68)));
        LinearLayout instructions=column();
        instructions.setPadding(dp(16),0,dp(6),0);
        instructions.addView(text(turn.distance,32,Color.WHITE,true));
        if (!turn.label.isEmpty()) instructions.addView(text(turn.label,19,0xffd8e2da,false));
        turnRow.addView(instructions,new LinearLayout.LayoutParams(-2,-2));
        overlay(canvas,turnCard,-2,Gravity.TOP|Gravity.START,20);
        if (route==null) return;

        LinearLayout summary=card(DARK);
        int segments=Math.max(1,route.line().size()-1);
        double remaining=Math.max(0,1-(double)model.segment/segments);
        LinearLayout metrics=row(summary);
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        metrics.addView(text((int)Math.ceil(route.minutes()*remaining)+"분   ·   "+km(route.km()*remaining),
            28,Color.WHITE,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView destination=text("목적지  "+route.destination().name(),18,0xffd8e2da,true);
        destination.setGravity(Gravity.END);
        metrics.addView(destination,new LinearLayout.LayoutParams(-2,-2));
        for (Waypoint stop:route.stops()) {
            if (stop.rescue()) summary.addView(text("◇  수색 경유지 · "+stop.name(),18,0xffe3d8ff,true));
        }
        if (!restricted) {
            LinearLayout actions=row(summary);
            if (model.mission!=null && !model.mission.found) {
                button(actions,"수색 요청 확인",true,() -> showReport(model.mission));
            }
            button(actions,"안내 종료",false,() -> { model.stop(); open(Screen.HOME); });
        }
        overlay(canvas,summary,-1,Gravity.BOTTOM,20);
    }
    private record Turn(String arrow,String distance,String label) {}
    private Turn nextTurn(Route route) {
        if (model.segment>=route.line().size()-1) return new Turn("⚑","목적지 도착","");
        Point a=route.line().get(model.segment), b=route.line().get(model.segment+1);
        int meters=(int)Math.max(20,Math.round(distance(a,b)*40));
        if (model.segment+2>=route.line().size()) return new Turn("↑",meters+" m","목적지 방향으로 직진");
        Point c=route.line().get(model.segment+2);
        float cross=(b.x()-a.x())*(c.y()-b.y())-(b.y()-a.y())*(c.x()-b.x());
        return new Turn(cross>0?"↱":cross<0?"↰":"↑",meters+" m",cross>0?"우회전":cross<0?"좌회전":"직진");
    }
    private void inspect(Point point) {
        if (restricted) return;
        for (Report report:model.services.reports()) {
            if (!report.found && distance(point,report.lastSeen)<5) { showReport(report); return; }
        }
        for (Report report:model.services.reports()) {
            if (report.found) continue;
            for (int id:report.areas) if (model.services.areas().get(id).contains(point)) { showReport(report); return; }
        }
    }
    private void showReport(Report report) {
        if (restricted) return;
        selected=report;
        open(Screen.DETAIL);
    }
    private void report() {
        switch(step) {
            case 0 -> {
                if (getResources().getConfiguration().screenWidthDp<720) title("반려동물 정보");
                input(panel,"종류",draft.kind,value -> draft.kind=value);
                input(panel,"이름",draft.name,value -> draft.name=value);
                input(panel,"색상·무늬",draft.coat,value -> draft.coat=value);
                input(panel,"특징",draft.traits,value -> draft.traits=value);
                input(panel,"실종 시각 (YYYY-MM-DD HH:mm)",draft.time,value -> draft.time=value);
                button(panel,"마지막 목격 위치 지정",true,() -> {
                    if (draft.kind.isBlank() || draft.name.isBlank() || draft.coat.isBlank()
                        || draft.traits.isBlank() || !validTime(draft.time)) {
                        message("반려동물 정보와 실종 시각을 입력해 주세요."); return;
                    }
                    moveStep(1);
                });
            }
            case 1 -> {
                title("마지막 목격 위치");
                note(draft.lastSeen==null?"지도에서 핀을 선택하세요":"분홍색 핀 위치를 확인하세요");
                LinearLayout actions=row(panel);
                button(actions,"현재 위치",false,() -> { draft.lastSeen=model.position; persist(); render(); });
                button(actions,"장소 검색",false,() -> { choosingPlace=true; query=""; open(Screen.SEARCH); });
                button(panel,"수색 동네 선택",true,() -> {
                    if (draft.lastSeen==null) message("마지막 목격 위치를 먼저 지정하세요.");
                    else moveStep(2);
                });
            }
            case 2 -> areas(false);
            case 3 -> {
                title("신고 확인");
                note(draft.name+" · "+draft.kind+" · "+draft.coat);
                note(draft.traits+" · "+draft.time);
                note("수색 동네 · "+areaNames(draft.areas));
                button(panel,"신고 등록",true,() -> {
                    if (restricted) return;
                    model.services.reports().add(draft); draft=null; step=0; persist();
                    open(Screen.HOME); message("실종 신고를 등록했어요.");
                });
            }
        }
        button(panel,"뒤로",false,this::onBackPressed);
    }
    private void moveStep(int next) { step=next; persist(); open(Screen.REPORT); }
    private void selectArea(Point point,List<Integer> ids) {
        if (restricted) return;
        for (int i=0;i<model.services.areas().size();i++) {
            if (model.services.areas().get(i).contains(point)) { toggle(ids,i); return; }
        }
    }
    private void toggle(List<Integer> ids,int id) {
        if (restricted) return;
        if (!ids.remove(Integer.valueOf(id))) ids.add(id);
        persist();
        render();
    }
    private void areas(boolean existing) {
        List<Integer> ids=existing?editingAreas:draft.areas;
        title("수색 동네 선택");
        for (int i=0;i<model.services.areas().size();i+=2) {
            LinearLayout pair=row(panel);
            for (int j=i;j<Math.min(i+2,model.services.areas().size());j++) {
                int id=j;
                button(pair,(ids.contains(id)?"✓ ":"＋ ")+model.services.areas().get(id).name(),
                    ids.contains(id),() -> toggle(ids,id));
            }
        }
        button(panel,existing?"수색 동네 저장":"확인 및 등록",true,() -> {
            if (restricted) return;
            if (ids.isEmpty()) { message("수색 동네를 한 곳 이상 선택하세요."); return; }
            if (existing) {
                selected.areas.clear(); selected.areas.addAll(ids); areaDraftReportId=null; persist(); open(Screen.DETAIL);
            } else moveStep(3);
        });
    }
    private String areaNames(List<Integer> ids) {
        List<String> names=new ArrayList<>();
        for (int id:ids) names.add(model.services.areas().get(id).name());
        return String.join(" · ",names);
    }
    private void mine() {
        boolean any=false;
        for (Report report:model.services.reports()) {
            if (!report.mine) continue;
            any=true;
            LinearLayout entry=card(Color.WHITE);
            LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,-2);
            size.setMargins(0,0,0,dp(14));
            panel.addView(entry,size);
            entry.addView(text(report.found?"발견 완료":"수색 중",17,GREEN,true));
            entry.addView(text(report.name+" · "+report.kind,25,INK,true));
            entry.addView(text(areaNames(report.areas)+"  ·  제보 "+report.sightings.size()+"건",18,MUTED,false));
            button(entry,"신고 보기   ›",false,() -> showReport(report));

        }
        if (!any) {
            title("등록한 신고가 없어요");
            button(panel,draft==null?"실종 신고":"신고 초안 이어쓰기",true,this::openReport);
        }
        button(panel,"지도",false,() -> open(Screen.HOME));
    }
    private void detail() {
        title(selected.name+" · "+selected.kind);
        note(selected.coat+" · "+selected.traits);
        note("마지막 목격 "+selected.time);
        note("수색 동네 · "+areaNames(selected.areas));
        if (selected.mine) {
            note("목격 제보 "+selected.sightings.size()+"건 · 목격 없는 수색 "+selected.completed+"건");
            for (Sighting item:selected.sightings) {
                button(panel,item.time()+" · "+item.notes(),false,() -> {
                    MapView map=new MapView(this,model,null,List.of(),false,item.point(),null);
                    map.setMinimumHeight(dp(300));
                    detailDialog=new AlertDialog.Builder(this).setTitle(item.time()).setView(map).setPositiveButton("닫기",null).show();
                });
            }
            if (!selected.found) {
                button(panel,"수색 동네 수정",false,() -> {
                    if (!selected.id.equals(areaDraftReportId)) { editingAreas.clear(); editingAreas.addAll(selected.areas); }
                    areaDraftReportId=selected.id;
                    open(Screen.AREAS);
                });
                button(panel,"발견 완료",false,() -> {
                    selected.found=true; persist(); open(Screen.MINE);
                });
            }
        }
        if (!selected.found) {
            button(panel,"목격 제보",true,() -> {
                if (!selected.id.equals(sightingReportId)) {
                    sightingReportId=selected.id; sightingPin=null; sightingTime=LocalDateTime.now().format(TIME); sightingNotes="";
                }
                open(Screen.SIGHTING);
            });
            button(panel,"목격 없이 수색 완료",false,() -> {
                selected.completed++; persist(); message("수색 완료를 기록했어요.");
                open(model.route==null?Screen.HOME:Screen.DRIVE);
            });
        }
        button(panel,"뒤로",false,this::onBackPressed);
    }
    private String sightingReportId, areaDraftReportId;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
        .withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private boolean validTime(String value) {
        try { LocalDateTime.parse(value.strip(),TIME); return true; }
        catch (java.time.format.DateTimeParseException invalid) { return false; }
    }
    private void sighting() {
        title("목격 제보 · "+selected.name);
        note(sightingPin==null?"지도에서 실제 목격 위치를 선택하세요":"분홍색 목격 핀과 시각을 확인하세요");
        input(panel,"목격 시각",sightingTime,value -> sightingTime=value);
        input(panel,"목격 내용",sightingNotes,value -> sightingNotes=value);
        button(panel,"제보 제출",true,() -> {
            if (restricted || selected.found) return;
            if (sightingPin==null || !validTime(sightingTime)) {
                message("목격 핀과 시각을 확인하세요."); return;
            }
            selected.sightings.add(new Sighting(sightingPin,sightingTime.strip(),sightingNotes.strip()));
            sightingReportId=null; persist(); message("목격 제보를 등록했어요."); open(Screen.DETAIL);
        });
    }
    private void stats(int minutes,double distance) { panel.addView(text(minutes+"분   ·   "+km(distance),36,GREEN,true)); }
    private String km(double distance) { return String.format(Locale.KOREA,"%.1f km",distance); }
    private void title(String value) { panel.addView(text(value,29,INK,true)); }
    private void note(String value) { panel.addView(text(value,19,MUTED,false)); }
    private void message(String value) { Toast.makeText(this,value,Toast.LENGTH_SHORT).show(); }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView view=new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
        view.setPadding(0,dp(3),0,dp(3));
        return view;
    }
    private LinearLayout column() { LinearLayout result=new LinearLayout(this); result.setOrientation(1); return result; }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout result=new LinearLayout(this); parent.addView(result,new LinearLayout.LayoutParams(-1,-2)); return result;
    }
    private Button button(LinearLayout parent,String label,boolean primary,Runnable click) {
        Button view=new Button(this);
        view.setAllCaps(false); view.setText(label); view.setTextSize(19); view.setMinHeight(dp(60));
        view.setTextColor(INK);
        view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        view.setPadding(dp(16),dp(8),dp(16),dp(8));
        view.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x22226b45),
            surface(primary?LIME:Color.WHITE,16,primary?0:LINE),null));
        LinearLayout.LayoutParams params=parent.getOrientation()==0
            ?new LinearLayout.LayoutParams(0,-2,1):new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(0,dp(4),parent.getOrientation()==0?dp(8):0,dp(4));
        parent.addView(view,params);
        view.setOnClickListener(clicked -> { if (!restricted) click.run(); });
        return view;
    }
    private GradientDrawable surface(int color,int radius,int stroke) {
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (stroke!=0) shape.setStroke(dp(1),stroke);
        return shape;
    }
    private EditText input(LinearLayout parent,String label,String value,Consumer<String> change) {
        EditText view=new EditText(this);
        view.setTextSize(20); view.setTextColor(INK); view.setHintTextColor(MUTED);
        view.setSingleLine(true); view.setMinHeight(dp(60)); view.setHint(label); view.setText(value);
        view.setContentDescription(label);
        view.setBackground(surface(Color.WHITE,12,LINE));
        view.setPadding(dp(15),dp(8),dp(15),dp(8));
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,-2);
        size.setMargins(0,dp(5),0,dp(5));
        parent.addView(view,size);
        watch(view,change);
        return view;
    }
    private void watch(EditText input,Consumer<String> change) {
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text,int start,int count,int after) {}
            public void onTextChanged(CharSequence text,int start,int before,int count) {
                if (!restricted) change.accept(text.toString());
            }
            public void afterTextChanged(Editable text) {}
        });
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
