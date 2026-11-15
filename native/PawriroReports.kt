package kr.routerescue.demo

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun TitleBar(title: String, detail: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xfff8faf8)).padding(horizontal=24.dp,vertical=18.dp),
        verticalAlignment=Alignment.CenterVertically) {
        IconAction(R.drawable.paw_back,"홈으로",size=54,stroke=border,click=back)
        Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
            Label(title,18,bold=true)
            Label(detail,11,muted)
        }
        Spacer(Modifier.width(54.dp))
    }
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(border))
}

@Composable
internal fun Report(state: PawriroState,narrow: Boolean) {
    Column(Modifier.fillMaxSize()) {
        TitleBar("실종 신고","${state.step} / 4",state::back)
        Row(Modifier.fillMaxWidth().background(Color(0xfff8faf8)).padding(horizontal=30.dp,vertical=12.dp),
            horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            for (step in 1..4) Box(Modifier.weight(1f).height(4.dp)
                .background(if (step<=state.step) green else Color(0xffdfe4df),RoundedCornerShape(4.dp)))
        }
        val content=Modifier.weight(1f).verticalScroll(rememberScrollState()).fillMaxWidth()
            .padding(horizontal=if (narrow) 20.dp else 34.dp,vertical=if (narrow) 22.dp else 28.dp)
        if (narrow) Column(content,verticalArrangement=Arrangement.spacedBy(24.dp)) {
            ReportCopy(state,true,Modifier.fillMaxWidth())
            ReportRight(state,true,Modifier.fillMaxWidth())
        } else Row(content,horizontalArrangement=Arrangement.spacedBy(38.dp)) {
            ReportCopy(state,false,Modifier.weight(.75f))
            ReportRight(state,false,Modifier.weight(1.4f))
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0xffdde2dd)))
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal=28.dp,vertical=13.dp),
            horizontalArrangement=Arrangement.spacedBy(10.dp,Alignment.End)) {
            TextAction("이전",Modifier.widthIn(min=140.dp),height=if (narrow) 52 else 58) {
                if (state.editingId!=null) state.cancelEdit() else if (state.step==1) state.screen=Screen.Home else state.step--
            }
            TextAction(if (state.editingId!=null) "저장" else if (state.step==4) "신고 등록" else "다음",Modifier.widthIn(min=140.dp),true,
                if (narrow) 52 else 58) {
                when {
                    state.step == 1 && (state.name.isBlank() || state.traits.isBlank() ||
                        runCatching { java.time.LocalDateTime.parse(state.time) }.isFailure) ->
                        state.notify("이름, 특징과 실종 시각(YYYY-MM-DDTHH:mm)을 입력하세요")
                    state.step == 2 && state.pin == null -> state.notify("마지막 목격 위치를 선택하세요")
                    state.step == 3 && (state.areas.isEmpty() || state.editingId != null) -> state.submit()
                    state.step < 4 -> state.step++
                    else -> state.submit()
                }
            }
        }
    }
}

@Composable
private fun ReportCopy(state: PawriroState,narrow: Boolean,modifier: Modifier) {
    val titles=listOf("반려동물 정보","마지막 목격 위치","수색 동네 선택","확인 및 등록")
    val prompts=listOf("찾는 데 필요한 정보를\n알려주세요","어디에서 마지막으로\n보셨나요?",
        "수색할 동네를 선택하세요","신고 내용을 확인하세요")
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Label(titles[state.step-1],13,green,true)
        Label(prompts[state.step-1],if (narrow) 25 else 30,bold=true)
        when (state.step) {
            1 -> Label("사진 없이도 신고할 수 있어요.",14,muted)
            2 -> {
                val options=listOf(
                    Triple("현재 위치","서울 성동구 성수동1가 685",R.drawable.paw_locate),
                    Triple("지도에서 핀 선택","지도를 눌러 정확한 위치 지정",R.drawable.paw_plus),
                    Triple("장소 검색","도로명 또는 건물명으로 검색",R.drawable.paw_search)
                )
                Spacer(Modifier.height(8.dp))
                options.forEachIndexed { index, (title,description,icon) ->
                    val active=state.location==index
                    Action(Modifier.fillMaxWidth().heightIn(min=66.dp),14,
                        if (active) Color(0xffeaf7ef) else Color.White,if (active) green else border,
                        click={
                            state.location=index
                            if (index==0) {
                                state.pin=demoRoute.first()
                                state.sightingPlace="서울 성동구 성수동1가 685"
                            } else if (index==2) {
                                state.query=""
                                state.searchingLocation=true
                                state.screen=Screen.Search
                            }
                        }) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Glyph(icon,tint=green)
                            Column(Modifier.weight(1f)) { Label(title,16,bold=true); Label(description,13,muted) }
                            if (active) Glyph(R.drawable.paw_check,tint=green)
                        }
                    }
                }
            }
            3 -> {
                Label("여러 동네를 선택할 수 있어요.",14,muted)
                neighborhoods.forEach { area ->
                    val selected=area in state.areas
                    Action(Modifier.fillMaxWidth().height(62.dp),13,if (selected) Color(0xffffedf3) else Color.White,
                        if (selected) pink else border,click={ state.toggle(area) }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(13.dp).background(if (selected) pink else Color(0xffd7ded8),RoundedCornerShape(50)))
                            Label(area,16,bold=true,modifier=Modifier.weight(1f))
                            if (selected) Glyph(R.drawable.paw_check,tint=pink)
                        }
                    }
                }
            }
            4 -> {
                Row(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(18.dp)).padding(16.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(15.dp)) {
                    Box(Modifier.size(64.dp).background(Color(0xffe3f4e8),RoundedCornerShape(18.dp)),Alignment.Center) {
                        Glyph(R.drawable.paw_paw,34,green)
                    }
                    Column {
                        Label("${state.name} · ${state.kind}",17,bold=true)
                        Label(state.coat,14,muted)
                        Label(if (state.time=="2026-05-12T08:40") "오늘 오전 8:40 실종" else state.time.replace('T',' '),12,muted)
                    }
                }
                ReportPhoto(state.photo)
                Label("마지막 목격",13,muted,true)
                Label(state.sightingPlace,16,bold=true)
                Label("수색 동네",13,muted,true)
                Label(state.areas.joinToString(" · "),16,bold=true)
            }
        }
    }
}

@Composable
private fun ReportRight(state: PawriroState,narrow: Boolean,modifier: Modifier) {
    if (state.step!=1) {
        PawriroMap(modifier.height(if (narrow) 300.dp else 420.dp).clip(RoundedCornerShape(20.dp)),
            pin=state.pin,visibleAreas=if (state.step>=3) state.areas else emptyList(),
            onArea=if (state.step==3) state::toggle else null,
            onPin=if (state.step==2 && state.location==1) ({ point ->
                state.pin=point
                state.sightingPlace="지도에서 선택한 위치"
            }) else null)
        return
    }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(14.dp)) {
        if (narrow) {
            KindField(state,Modifier.fillMaxWidth())
            FormField("이름",state.name,{ state.name=it })
            FormField("색상·무늬",state.coat,{ state.coat=it })
            FormField("실종 시각",state.time,{ state.time=it })
        } else {
            Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                KindField(state,Modifier.weight(1f))
                FormField("이름",state.name,{ state.name=it },Modifier.weight(1f))
            }
            Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                FormField("색상·무늬",state.coat,{ state.coat=it },Modifier.weight(1f))
                FormField("실종 시각",state.time,{ state.time=it },Modifier.weight(1f))
            }
        }
        FormField("특징",state.traits,{ state.traits=it },multiline=true)
        TextAction(if (state.photo==null) "사진 추가" else "사진 변경",click=state.choosePhoto)
        ReportPhoto(state.photo)
    }
}

@Composable
private fun KindField(state: PawriroState,modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Label("종류",13,muted,true)
        Action(Modifier.fillMaxWidth().height(52.dp),12,stroke=border,click={ open=true }) {
            Row(Modifier.fillMaxWidth().padding(horizontal=14.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Label(state.kind)
                Label("⌄")
            }
        }
        DropdownMenu(open,{ open=false }) {
            listOf("강아지","고양이","기타").forEach { kind ->
                DropdownMenuItem({ Label(kind) },{ state.kind=kind; open=false })
            }
        }
    }
}

@Composable
private fun FormField(label: String,value: String,change: (String) -> Unit,
    modifier: Modifier = Modifier,multiline: Boolean = false) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Label(label,13,muted,true)
        BasicTextField(value,change,Modifier.fillMaxWidth().heightIn(min=if (multiline) 80.dp else 52.dp)
            .background(Color.White,RoundedCornerShape(12.dp)).border(1.dp,border,RoundedCornerShape(12.dp))
            .padding(horizontal=14.dp,vertical=if (multiline) 12.dp else 15.dp),
            textStyle=TextStyle(color=ink,fontSize=16.sp),singleLine=!multiline)
    }
}

@Composable
internal fun Complete(state: PawriroState) {
    Column(Modifier.fillMaxSize().background(Color(0xfff5f8f5)).padding(20.dp),
        verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).background(lime,RoundedCornerShape(28.dp)),Alignment.Center) {
            Glyph(R.drawable.paw_check,42,Color(0xff173820))
        }
        Spacer(Modifier.height(22.dp))
        Label("수색 요청을 등록했어요",30,bold=true,center=true)
        Spacer(Modifier.height(8.dp))
        Label("선택한 동네가 pawriro 지도에 표시돼요.\n새 제보는 내 신고에서 확인할 수 있어요.",
            16,muted,center=true)
        Spacer(Modifier.height(25.dp))
        TextAction("홈 지도로 돌아가기",Modifier.widthIn(min=270.dp),true) { state.screen=Screen.Home }
    }
}

@Composable
internal fun Mine(state: PawriroState,narrow: Boolean) {
    Column(Modifier.fillMaxSize().background(Color(0xffeff3ef))) {
        TitleBar("내 신고","수색 요청 관리",state::back)
        if (state.reports.isNotEmpty()) {
            val card=Modifier.fillMaxWidth().padding(if (narrow) 16.dp else 26.dp)
                .shadow(8.dp,RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Color.White)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                state.reports.asReversed().forEach { report ->
                    if (narrow) Column(card) {
                        PawriroMap(Modifier.fillMaxWidth().height(240.dp),pin=report.pin,visibleAreas=report.areas)
                        CaseDetails(state,report,Modifier.fillMaxWidth(),20)
                    } else Row(card.heightIn(min=380.dp)) {
                        PawriroMap(Modifier.weight(1f).height(380.dp),pin=report.pin,visibleAreas=report.areas)
                        CaseDetails(state,report,Modifier.weight(1f),28)
                    }
                }
            }
        } else Column(Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            Box(Modifier.size(76.dp).background(Color(0xffdceddf),RoundedCornerShape(24.dp)),Alignment.Center) {
                Glyph(R.drawable.paw_paw,38,green)
            }
            Spacer(Modifier.height(18.dp))
            Label("등록한 신고가 없어요",24,bold=true)
            Spacer(Modifier.height(7.dp))
            Label("실종 신고를 등록하면 수색 현황과\n목격 제보를 여기서 확인할 수 있어요.",16,muted,center=true)
            Spacer(Modifier.height(20.dp))
            TextAction("실종 신고하기",primary=true) { state.screen=Screen.Report }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaseDetails(state: PawriroState,report: PetReport,modifier: Modifier,padding: Int) {
    Column(modifier.padding(padding.dp)) {
        Label(report.status.label,11,Color(0xffa92c57),true,Modifier.background(Color(0xffffe5ed),RoundedCornerShape(50))
            .padding(horizontal=10.dp,vertical=5.dp))
        Spacer(Modifier.height(14.dp))
        Label(if (report.status==ReportStatus.Active) "${report.name}를 찾고 있어요" else report.name,26,bold=true)
        Label(report.areas.joinToString(" · "),16,muted)
        Label(report.traits,14,muted)
        ReportPhoto(report.photo)
        Spacer(Modifier.height(22.dp))
        CaseStat(report.time.replace('T',' '),"마지막 목격",Modifier.fillMaxWidth())
        Spacer(Modifier.height(22.dp))
        if (report.status==ReportStatus.Active) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                CaseButton("수색 동네 수정") { state.editAreas(report) }
                CaseButton("발견 완료",Color(0xffba315e)) { state.setStatus(report,ReportStatus.Found) }
                CaseButton("신고 종료",Color(0xffba315e)) { state.setStatus(report,ReportStatus.Closed) }
            }
        }
    }
}

@Composable
private fun CaseStat(number: String,label: String,modifier: Modifier) {
    Column(modifier.background(Color(0xfff1f5f2),RoundedCornerShape(12.dp)).padding(13.dp)) {
        Label(number,17,bold=true)
        Label(label,11,muted)
    }
}

@Composable
private fun CaseButton(text: String,color: Color = Color(0xff274032),click: () -> Unit) {
    Action(Modifier.height(48.dp),12,stroke=border,click=click) {
        Label(text,16,color,true,Modifier.padding(horizontal=16.dp))
    }
}

@Composable
private fun ReportPhoto(uri: String?) {
    if (uri == null) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null,uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(context.contentResolver,android.net.Uri.parse(uri))
                ) { decoder, info, _ ->
                    val factor=maxOf(1,(maxOf(info.size.width,info.size.height)+799)/800)
                    decoder.setTargetSampleSize(factor)
                    decoder.allocator=android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(it.asImageBitmap(),"반려동물 사진",Modifier.fillMaxWidth().height(120.dp)
            .clip(RoundedCornerShape(12.dp)),contentScale=androidx.compose.ui.layout.ContentScale.Fit)
    }
}
