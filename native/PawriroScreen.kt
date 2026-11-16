package kr.routerescue.demo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay

internal val ink = Color(0xff15201a)
internal val green = Color(0xff226b45)
internal val lime = Color(0xffc9ff62)
internal val dark = Color(0xff17221c)
internal val muted = Color(0xff5d6860)
internal val panel = Color(0xfff4f6f3)
internal val border = Color(0xffd8ded9)

@Composable
internal fun Label(
    text: String, size: Int = 16, color: Color = ink, bold: Boolean = false,
    modifier: Modifier = Modifier, center: Boolean = false
) = Text(text, modifier, color=color, fontSize=size.sp, lineHeight=(size*1.3).sp,
    fontWeight=if (bold) FontWeight.Bold else FontWeight.Normal,
    textAlign=if (center) TextAlign.Center else TextAlign.Start)

@Composable
internal fun Glyph(id: Int, size: Int = 24, tint: Color = ink, description: String? = null) {
    Icon(painterResource(id),description,Modifier.size(size.dp),tint)
}

@Composable
internal fun Action(
    modifier: Modifier = Modifier, radius: Int = 16, color: Color = Color.White,
    stroke: Color? = null, elevation: Int = 0, enabled: Boolean = true, click: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius.dp)
    Box(modifier.shadow(elevation.dp,shape).clip(shape).background(color)
        .then(if (stroke!=null) Modifier.border(1.dp,stroke,shape) else Modifier)
        .clickable(enabled=enabled,role=Role.Button,onClick=click),Alignment.Center,content=content)
}

@Composable
internal fun TextAction(text: String, modifier: Modifier = Modifier, primary: Boolean = false,
    height: Int = 58, enabled: Boolean = true, click: () -> Unit) {
    Action(modifier.heightIn(min=height.dp),color=if (primary) lime else Color(0xffe9ede9),
        enabled=enabled,click=click) {
        Label(text,17,bold=true,modifier=Modifier.padding(horizontal=20.dp,vertical=8.dp),center=true)
    }
}

@Composable
internal fun IconAction(id: Int, label: String, modifier: Modifier = Modifier, size: Int = 56,
    radius: Int = 17, elevation: Int = 0, stroke: Color? = null, enabled: Boolean = true, click: () -> Unit) {
    Action(modifier.size(size.dp),radius,stroke=stroke,elevation=elevation,enabled=enabled,click=click) {
        Glyph(id,description=label)
    }
}

@Composable
internal fun PawriroScreen(state: PawriroState) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.restricted) {
        if (state.restricted) {
            focus.clearFocus(force=true)
            keyboard?.hide()
        }
    }
    LaunchedEffect(state.toast) {
        if (state.toast.isNotEmpty()) {
            delay(2200)
            state.toast=""
        }
    }
    BackHandler(state.screen!=Screen.Home || state.restricted) { state.back() }
    BoxWithConstraints(Modifier.fillMaxSize().background(panel)) {
        val narrow=maxWidth<=820.dp
        val blocked=state.restricted && state.screen !in
            setOf(Screen.Home,Screen.Drive,Screen.Mine,Screen.GuardianCreate)
        if (!blocked) {
            when(state.screen) {
                Screen.Home -> Home(state,narrow)
                Screen.Search -> Search(state)
                Screen.Preview -> Preview(state,narrow)
                Screen.Drive -> Drive(state,narrow)
                Screen.Report -> Report(state,narrow)
                Screen.Mine, Screen.GuardianCreate -> GuardianScreen(state)
                Screen.Complete -> Complete(state)
                Screen.Participation -> ParticipationScreen(state)
            }
        }
        if (blocked) {
            Column(Modifier.fillMaxSize().background(Color(0xf5151e19)).padding(20.dp),
                verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                Glyph(R.drawable.paw_lock,32,lime)
                Spacer(Modifier.height(15.dp))
                Label("주행 중에는 이 기능을 사용할 수 없어요",22,Color.White,true,center=true)
                Label("작성 중인 내용은 임시 저장됐어요. 주차 후 계속할 수 있어요.",16,Color(0xffaeb8b1),center=true)
                Spacer(Modifier.height(16.dp))
                TextAction("지도 화면으로",Modifier.widthIn(min=180.dp),true,52) { state.screen=Screen.Home }
            }
        }
        if (state.showNaviDiagnostic && (!state.restricted || state.driving)) {
            Label(state.naviDiagnostic,13,Color.White,modifier=Modifier.align(Alignment.BottomCenter)
                .padding(bottom=if (state.driving) 115.dp else 96.dp)
                .background(dark,RoundedCornerShape(12.dp)).padding(horizontal=12.dp,vertical=8.dp))
        }
        if (state.toast.isNotEmpty()) {
            Label(state.toast,13,Color.White,modifier=Modifier.align(Alignment.BottomCenter).padding(bottom=25.dp)
                .shadow(8.dp,RoundedCornerShape(12.dp)).background(dark,RoundedCornerShape(12.dp))
                .padding(horizontal=20.dp,vertical=12.dp))
        }
    }
}

@Composable
private fun SearchBar(state: PawriroState, modifier: Modifier, inline: Boolean, narrow: Boolean) {
    Row(modifier.height(if (inline || narrow) 58.dp else 64.dp)
        .shadow(if (inline) 0.dp else 8.dp,RoundedCornerShape(18.dp))
        .background(Color.White,RoundedCornerShape(18.dp))
        .border(1.dp,Color(0xffd5dbd5),RoundedCornerShape(18.dp))
        .padding(start=if (narrow) 15.dp else 20.dp,end=10.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Glyph(R.drawable.paw_search,if (inline) 24 else 28)
        val placeholder=if (inline) "어디로 갈까요?" else "목적지를 입력하세요"
        if (inline) {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            BasicTextField(state.query,{ state.query=it },Modifier.weight(1f).focusRequester(focusRequester),singleLine=true,
                textStyle=TextStyle(color=ink,fontSize=18.sp,fontWeight=FontWeight.SemiBold),
                cursorBrush=SolidColor(green),decorationBox={ inner ->
                    if (state.query.isEmpty()) Label(placeholder,18,muted,true)
                    inner()
                })
        } else {
            Label(state.query.ifEmpty { placeholder },if (narrow) 16 else 20,muted,true,
                Modifier.weight(1f).fillMaxHeight().wrapContentHeight()
                    .clickable(enabled=!state.locked) { state.screen=Screen.Search })
        }
        Action(Modifier.size(48.dp),14,Color(0xfff1f4f1),click={}) {
            Glyph(R.drawable.paw_mic,26,Color(0xff3b4940),"음성 검색")
        }
    }
}

@Composable
private fun Home(state: PawriroState, narrow: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin=if (narrow) 14 else 24
        val searchWidth=minOf(630.dp,maxWidth-(margin*2+76).dp)
        val active=state.searchableReports.filter { it.status==ReportStatus.Active }
        PawriroMap(Modifier.matchParentSize(),geographic=true,
            position=if (state.orsDemo) state.demoPosition else state.sdkLocation?.let { mapPoint(org.json.JSONArray().put(it.longitude).put(it.latitude)) },
            visibleAreas=active.filter { it.publicCoverage.isEmpty() }.flatMap { it.areas },
            publicCoverage=active.flatMap { it.publicCoverage.values },
            reportPins=if (state.restricted) emptyList() else active.mapNotNull { it.pin },
            onArea=if (state.restricted) null else { area ->
                active.firstOrNull { area in it.areas }?.let { state.notify("${it.name} · ${it.time} · $area") }
            })
        if (!state.restricted) Row(Modifier.align(Alignment.TopStart).padding(margin.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            SearchBar(state,Modifier.width(searchWidth),false,narrow)
            IconAction(R.drawable.paw_menu,"메뉴",size=if (narrow) 58 else 64,elevation=8,click={})
        }
        Row(Modifier.align(Alignment.BottomStart).padding(margin.dp)
            .then(if (narrow) Modifier.fillMaxWidth() else Modifier),
            horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            val size=if (narrow) Modifier.weight(1f) else Modifier.width(174.dp)
            HomeAction("실종 신고","주차 중 작성",R.drawable.paw_plus,size,narrow) { state.screen=Screen.GuardianCreate }
            HomeAction("내 신고","제보 확인 · 수정 · 종료",
                R.drawable.paw_paw,size,narrow) { state.screen=Screen.Mine }
        }
        if (!state.orsDemo && (state.sdkDestination != null || !state.demoMode)) {
            TextAction("차량 내비 경로",Modifier.align(Alignment.TopEnd).padding(top=90.dp,end=24.dp)) {
                state.asked.clear()
                state.showingVehicleRoute=true
                state.screen=Screen.Drive
            }
        }
        if (!state.restricted && state.participationRows.isNotEmpty()) {
            TextAction("수색 참여 내역",Modifier.align(Alignment.TopEnd).padding(24.dp)) {
                state.screen=Screen.Participation
            }
        }
        if (!narrow) {
            Row(Modifier.align(Alignment.BottomEnd).padding(24.dp).height(38.dp)
                .background(Color(0xddffffff),RoundedCornerShape(10.dp)).padding(horizontal=14.dp),
                horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(18.dp,12.dp).background(Color(0x30ff5d94)).border(1.dp,pink))
                Label("수색 동네",11,muted)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(11.dp).background(pink,RoundedCornerShape(50)))
                Label("마지막 목격",11,muted)
            }
        }
    }
}

@Composable
private fun HomeAction(title: String, subtitle: String, icon: Int, modifier: Modifier, narrow: Boolean, click: () -> Unit) {
    Action(modifier.height(if (narrow) 62.dp else 68.dp),18,stroke=Color(0xffd9dfda),elevation=6,click=click) {
        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(13.dp)) {
            Glyph(icon,tint=green)
            Column { Label(title,16,bold=true); Label(subtitle,12,Color(0xff657168)) }
        }
    }
}

@Composable
private fun Search(state: PawriroState) {
    val real = (!state.demoMode || state.orsDemo) && !state.searchingLocation
    val query = state.query.trim()
    var results by remember(query) { mutableStateOf(emptyList<DestinationResult>()) }
    var status by remember(query) { mutableStateOf("") }
    LaunchedEffect(real,query,state.guardianAddress) {
        if (!real || query.length !in 2..120) return@LaunchedEffect
        results=emptyList()
        status="ORS에서 장소 검색 중…"
        delay(450)
        try {
            val body=org.json.JSONObject().put("query",query)
            val rows=GuardianClient(state.guardianAddress).request("/vehicle/destinations","POST",body) as org.json.JSONArray
            results=rows.objects().map { row -> DestinationResult(row.getString("name"),row.getString("address"),
                row.getDouble("latitude"),row.getDouble("longitude")) }
            status=if (results.isEmpty()) "검색 결과가 없어요" else ""
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            status="목적지 검색을 사용할 수 없어요 · 서버와 ORS 설정 확인"
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xfff8faf8)).padding(horizontal=24.dp,vertical=18.dp),
            horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
            IconAction(R.drawable.paw_back,"홈으로",size=54,stroke=border,click=state::back)
            SearchBar(state,Modifier.weight(1f).widthIn(max=760.dp),true,false)
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(border))
        Column(Modifier.widthIn(max=860.dp).verticalScroll(rememberScrollState()).padding(24.dp,30.dp)) {
            Label(if (real) "ORS 장소 검색 결과" else "검색 결과",13,muted,true)
            if (real) {
                if (query.length<2) Label("장소명 또는 주소를 두 글자 이상 입력하세요",16,muted)
                else if (query.length>120) Label("검색어를 120자 이내로 입력하세요",16,muted)
                if (status.isNotEmpty()) Label(status,16,muted)
                if (!state.orsDemo && state.pendingNativeDestination != null)
                    Label("Pleos에서 경로 선택 후 pawriro로 돌아오세요",16,muted)
                if (state.destinationStarting) Label(
                    if (state.orsDemo) "ORS 예상 경로 계산 중…" else "Pleos 안내 시작 확인 중…",16,muted)
                else results.forEachIndexed { index, result ->
                    SearchRow(result.name,result.address,index+1) {
                        state.startDestination(result)
                    }
                }
            } else places.filter { (it.name+it.address).contains(query) }.forEachIndexed { index, place ->
                SearchRow(place.name,place.address,index+1) {
                    if (state.searchingLocation) {
                        state.sightingPlace=place.name
                        state.pin=demoDestinationPins[places.indexOf(place)]
                        state.searchingLocation=false
                        state.screen=Screen.Report
                    } else {
                        state.showPreview(place)
                    }
                }
            }
            if (!real) {
                Spacer(Modifier.height(30.dp))
                Label("최근 목적지",13,muted,true)
                SearchRow("그랜드 워커힐 서울","어제 · 광진구 워커힐로 177",null) {}
            }
        }
    }
}

@Composable
private fun SearchRow(title: String, subtitle: String, index: Int?, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=76.dp).clickable(role=Role.Button,onClick=click)
        .padding(horizontal=10.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(40.dp).background(Color(0xffdff4e6),RoundedCornerShape(12.dp)),Alignment.Center) {
            if (index==null) Glyph(R.drawable.paw_clock,tint=green) else Label(index.toString(),16,green,true)
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Label(title,18,bold=true)
            Label(subtitle,13,muted)
        }
        Glyph(R.drawable.paw_chevron)
    }
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0xffdfe4df)))
}

@Composable
private fun Preview(state: PawriroState,narrow: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side=minOf(430.dp,maxWidth*.38f)
        val mapHeight=maxHeight*.48f
        if (narrow) Column {
            PreviewMap(state,Modifier.fillMaxWidth().height(mapHeight))
            PreviewCard(state,true,Modifier.fillMaxWidth().weight(1f))
        } else Row {
            PreviewMap(state,Modifier.weight(1f).fillMaxHeight())
            PreviewCard(state,false,Modifier.width(side).fillMaxHeight())
        }
    }
}

@Composable
private fun PreviewMap(state: PawriroState,modifier: Modifier) {
    Box(modifier) {
        PawriroMap(Modifier.matchParentSize(),geographic=true,route=true,routePoints=state.orsBaseline?.line ?: demoRouteFor(state.place),
            position=if (state.orsDemo) state.demoPosition else demoRoute.first(),
            fitRoute=state.orsDemo,
            visibleAreas=state.searchableReports.filter { it.status==ReportStatus.Active && it.publicCoverage.isEmpty() }.flatMap { it.areas },
            publicCoverage=state.guardianReports.flatMap { it.publicCoverage.values })
        IconAction(R.drawable.paw_back,"검색 결과로",Modifier.padding(22.dp),elevation=8) { state.screen=Screen.Search }
    }
}

@Composable
private fun PreviewCard(state: PawriroState,narrow: Boolean,modifier: Modifier) {
    Column(modifier.background(Color.White).padding(
        start=if (narrow) 20.dp else 30.dp,end=if (narrow) 20.dp else 30.dp,
        top=if (narrow) 20.dp else 34.dp,bottom=if (narrow) 20.dp else 28.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Label(if (state.orsDemo) "ORS 예상 경로 · 가상 주행" else "경로 미리보기",13,green,true)
            Spacer(Modifier.height(8.dp))
            Label(state.place.name,if (narrow) 24 else 30,bold=true)
            Label(state.place.address,14,muted,modifier=Modifier.padding(top=8.dp,bottom=24.dp))
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(border))
            Row(Modifier.padding(vertical=if (narrow) 12.dp else 22.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment=Alignment.Bottom) { Label("${state.place.time}",if (narrow) 34 else 42,green,true); Label("분",20,green,true) }
                Label(state.place.distance,14,muted,modifier=Modifier.align(Alignment.Bottom))
                if (!state.orsDemo) Label("오후 10:48 도착",14,muted,modifier=Modifier.align(Alignment.Bottom))
            }
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(border))
            Row(Modifier.padding(vertical=if (narrow) 10.dp else 17.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(26.dp,5.dp).background(Color(0xff1677ff),RoundedCornerShape(5.dp)))
                Label(if (state.orsDemo) "ORS 자동차 경로" else "추천 경로 · 통행료 0원",13,muted)
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Label("탑승 모드",16,bold=true)
            Label(if (state.seatChecking) "조수석 확인 중" else "출발 전에 선택하세요",13,muted)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            for (passenger in listOf(false,true)) {
                val active=state.passenger==passenger
                Action(Modifier.weight(1f).height(58.dp),15,
                    if (active) Color(0xffeaf7ef) else Color(0xfff7f9f7),if (active) green else border,
                    click={ state.selectPassenger(passenger) }) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Glyph(if (passenger) R.drawable.paw_person else R.drawable.paw_car,tint=if (active) green else muted)
                        Label(if (passenger) "동승자 참여" else "운전자 단독",16,if (active) green else muted,true)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        TextAction("안내 시작",Modifier.fillMaxWidth(),true,if (narrow) 52 else 58,enabled=!state.seatChecking,click=state::start)
    }
}

@Composable
private fun Drive(state: PawriroState,narrow: Boolean) {
    val live=state.showingVehicleRoute
    val destination=state.sdkDestination
    val turn=state.sdkTurn
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width=maxWidth
        val height=maxHeight
        if (live) {
            RouteOverview(state,Modifier.matchParentSize()) { hasLine,status ->
                Column(Modifier.align(Alignment.BottomStart).padding(start=22.dp,bottom=108.dp)
                    .background(Color.White,RoundedCornerShape(8.dp)).padding(8.dp),
                    verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    if (hasLine) Label("예상 경로 · Pleos 안내와 다를 수 있어요",14,ink,true)
                    else Label(status.ifBlank { "활성 경로선 미확보" },14,ink,true)
                    if (!state.showRouteOverview)
                        TextAction("예상 경로 보기",primary=true) { state.showRouteOverview=true }
                }
            }
        } else {
            PawriroMap(Modifier.matchParentSize(),geographic=true,route=true,rescue=state.rescue,
                position=if (state.orsDemo) state.demoPosition else state.movementFrames[state.routePosition],
                routePoints=state.displayedRoute,fitRoute=state.orsDemo,rescueStop=state.activeOffer?.waypoint,
                rescueStopName=state.activeOffer?.waypointName,
                visibleAreas=state.searchableReports.filter { it.status==ReportStatus.Active && it.publicCoverage.isEmpty() }.flatMap { it.areas },
                publicCoverage=state.guardianReports.flatMap { it.publicCoverage.values })
        }
        Row(Modifier.padding(if (narrow) 14.dp else 22.dp).widthIn(min=if (narrow) 0.dp else 365.dp)
            .shadow(8.dp,RoundedCornerShape(20.dp)).background(dark,RoundedCornerShape(20.dp))
            .padding(horizontal=if (narrow) 14.dp else 20.dp,vertical=if (narrow) 11.dp else 16.dp),
            horizontalArrangement=Arrangement.spacedBy(18.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(if (narrow) 54.dp else 68.dp).background(lime,RoundedCornerShape(17.dp)),Alignment.Center) {
                Glyph(if (live) R.drawable.paw_flag else R.drawable.paw_turn,48)
            }
            Column {
                Label(if (live) "차량 내비" else if (state.orsDemo) "시연 주행" else "300 m",if (narrow) 27 else 34,Color.White,true)
                Label(if (live) turn?.let { "${it.distance.toInt()} m · ${it.description}" }
                    ?: "회전 안내 정보 미확보" else if (state.orsDemo) "" else "성수대교 방면 좌회전",15,Color(0xffcad2cd))
            }
        }
        val margin=if (narrow) 12.dp else 20.dp
        Row(Modifier.align(Alignment.BottomCenter).padding(margin).fillMaxWidth()
            .height(if (narrow) 78.dp else 88.dp).shadow(8.dp,RoundedCornerShape(22.dp))
            .background(Color(0xf217221c),RoundedCornerShape(22.dp))
            .padding(horizontal=if (narrow) 8.dp else 18.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Action(Modifier.size(if (narrow) 58.dp else 90.dp,64.dp),14,Color(0xff303b34),click=state::stop) {
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Glyph(R.drawable.paw_close,tint=Color(0xffe7ece8)); Label("종료",11,Color(0xffe7ece8))
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(if (narrow) 8.dp else 14.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Label(if (live) destination?.remainTime?.takeIf { it>=0 }?.let { "${(it.toLong()+59)/60}분" } ?: "시간 미확보" else "${state.orsRoute?.minutes ?: (state.place.time + (state.activeOffer?.extraMinutes ?: 0))}분",if (narrow) 25 else 33,Color.White,true)
                Label(if (live) destination?.remainDistance?.takeIf { it>=0 }?.let { "%.1f km".format(it/1000.0) } ?: "거리 미확보" else state.orsRoute?.let { "%.1f km".format(it.meters / 1000) } ?: state.place.distance,13,Color(0xffc2cbc5))
                if (!live) Label(if (state.orsDemo) "ORS 예상 · 가상 주행"
                    else "오후 10:${48 + (state.activeOffer?.extraMinutes ?: 0)} 도착",13,Color(0xffc2cbc5))
            }
            if (!narrow) {
                Spacer(Modifier.width(1.dp).height(48.dp).background(Color(0xff435047)))
                Spacer(Modifier.weight(1f))
                Glyph(R.drawable.paw_flag,tint=Color.White)
                Column(horizontalAlignment=Alignment.End) {
                    Label("목적지",11,Color(0xffc2cbc5))
                    Label(if (live) destination?.name ?: "목적지 미확보" else state.place.name,16,Color.White,true)
                }
            }
        }
        if (live) {
            Column(Modifier.align(Alignment.TopEnd).padding(22.dp)
                .background(Color.White,RoundedCornerShape(12.dp)).padding(12.dp)) {
                Label("목적지 · ${destination?.name ?: "정보 미확보"}",15,bold=true)
                Label("경유지 · ${if (state.sdkWaypointsReceived) state.sdkWaypoints.joinToString(" · ") { it.name }.ifBlank { "없음" } else "정보 미확보"}",13,muted)
                if (!state.restricted) Label("Trip 좌표 ${state.sdkTrip?.summary?.routePoints?.size ?: 0}개 · 전체 경로 확인 필요",13,muted)
                if (!state.restricted) Label(if (state.sdkLocation == null) "현재 위치 미확보" else "FusedLocation 위치 수신",13,muted)
                Label(state.naviDiagnostic,13,muted)
                if (!state.restricted) Label(state.speechDiagnostic,13,muted)
            }
        }
        if (!live && !state.restricted) {
            TextAction("목적지 변경", Modifier.align(Alignment.TopEnd).padding(22.dp), height=54) {
                state.stop()
                state.query=""
                state.screen=Screen.Search
            }
        }
        if (state.prompt) {
            Row(Modifier.align(Alignment.Center).offset(y=height*(if (narrow) -.06f else -.04f))
                .width(if (narrow) width-28.dp else minOf(700.dp,width*.88f))
                .shadow(16.dp,RoundedCornerShape(22.dp)).background(Color(0xf517221c),RoundedCornerShape(22.dp))
                .border(1.dp,Color(0xff536059),RoundedCornerShape(22.dp)).padding(horizontal=28.dp,vertical=28.dp),
                horizontalArrangement=Arrangement.spacedBy(18.dp),verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.size(68.dp).background(lime,RoundedCornerShape(50)),Arrangement.Center,Alignment.CenterVertically) {
                    listOf(11,24,17,8).forEach {
                        Box(Modifier.padding(horizontal=1.5.dp).size(3.dp,it.dp).background(dark,RoundedCornerShape(3.dp)))
                    }
                }
                Column {
                    Label(state.proposedRealStop ?: state.offer?.waypointName ?: "수색 요청이 경로 근처에 있어요",24,Color.White,true)
                    state.proposedEta?.let { (original,detour) ->
                        Label("기존 약 ${original}분 · 경유 약 ${detour}분",19,Color.White)
                    }
                    Label("약 ${state.offer?.extraMinutes}분 더 걸려요. 참여할까요?",23,Color.White,true)
                    Label(if (state.orsDemo) "가상 주행 · 시연 응답 대기" else "음성으로 “참여할게” 또는 “건너뛸게”라고 말하세요",15,Color(0xffacb7af))
                }
            }
        }
    }
}
