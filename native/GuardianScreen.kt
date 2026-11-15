package kr.routerescue.demo

import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.UUID

@Composable
internal fun GuardianScreen(state: PawriroState) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val client=state.guardianClient
    var reports by remember { mutableStateOf(emptyList<JSONObject>()) }
    val creating=state.screen==Screen.GuardianCreate
    var editor by remember { mutableStateOf(if (creating) GuardianDraft() else null) }
    var sightings by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(state.guardianParked,state.guardianCarRestricted) {
        if (!state.guardianParked || state.guardianCarRestricted) {
            focus.clearFocus(force=true)
            keyboard?.hide()
        }
    }
    fun work(action: suspend () -> Unit) {
        if (busy || !state.guardianParked || state.guardianCarRestricted || state.restricted) return
        busy=true
        error=""
        scope.launch {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (failure is GuardianHttpError && failure.status==401) state.guardianClient=null
                error=when {
                    failure is GuardianHttpError && failure.status==401 && client==null ->
                        "계정 또는 비밀번호를 확인해 주세요."
                    failure is GuardianHttpError && failure.status==401 ->
                        "로그인 세션이 만료됐어요. 다시 로그인해 주세요."
                    failure is java.io.IOException -> "서버에 연결할 수 없어요. 백엔드 실행 상태를 확인해 주세요."
                    else -> failure.message ?: "서버 연결을 확인해 주세요."
                }
            }
            finally { busy=false }
        }
    }
    suspend fun refresh(api: GuardianClient) {
        reports=(api.request("/guardian/reports") as JSONArray).objects()
        state.refreshGuardianFeed()
    }

    LaunchedEffect(client,state.guardianParked,state.restricted) {
        if (client != null) work { refresh(client) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        IconAction(R.drawable.paw_back,"뒤로") {
            if (state.restricted) state.screen=Screen.Home
            else if (creating) state.back()
            else if (editor!=null || sightings!=null) { editor=null; sightings=null } else state.back()
        }
        Label(if (creating) "실종 신고" else "내 신고",if (client==null) 32 else 26,bold=true)
        if (state.restricted || state.guardianCarRestricted || !state.guardianParked) {
            Label("차량 주차 상태를 확인한 후 이용할 수 있어요")
            return@Column
        }
        if (error.isNotEmpty()) Label(error,18,Color(0xffba315e))
        val api=client
        when {
            api==null -> {
                Column(Modifier.widthIn(max=560.dp).fillMaxWidth(),
                    verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    LoginField("테스트 계정",username,{ username=it },enabled=!busy)
                    LoginField("비밀번호",password,{ password=it },enabled=!busy,secret=true)
                    TextAction(if (busy) "로그인 중…" else "로그인",Modifier.fillMaxWidth(),
                        primary=true,height=64,enabled=!busy && username.isNotBlank() && password.isNotEmpty()) {
                        work {
                            val connection=GuardianClient(state.guardianAddress)
                            val response=connection.request("/guardian/login","POST",
                                JSONObject().put("username",username.trim()).put("password",password)) as JSONObject
                            connection.token=response.getString("token")
                            password=""
                            state.guardianClient=connection
                        }
                    }
                }
            }
            editor!=null -> {
                val draft=editor!!
                GuardianEditor(draft,busy) { work {
                    api.request("/guardian/reports/${draft.id}","PUT",draft.payload())
                    refresh(api)
                    editor=null
                    if (creating) state.screen=Screen.Mine
                } }
            }
            sightings!=null -> {
                Label("목격 제보",21,bold=true)
                if (sightings!!.isEmpty()) Label("등록된 제보가 없어요")
                for (item in sightings!!) {
                    Label(item.getString("seen_at"),16,bold=true)
                    val coords=item.getJSONObject("location").getJSONArray("coordinates")
                    PawriroMap(Modifier.fillMaxWidth().height(180.dp),pin=mapPoint(coords))
                }
            }
            else -> {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextAction("새 신고",primary=true,enabled=!busy) { editor=GuardianDraft() }
                    TextAction("새로고침",enabled=!busy) { work { refresh(api) } }
                    TextAction("로그아웃",enabled=!busy) {
                        work {
                            api.request("/guardian/logout","POST")
                            state.guardianClient=null
                            reports=emptyList()
                        }
                    }
                }
                for (report in reports) {
                    val id=report.getString("id")
                    val status=report.getString("status")
                    Label("${report.getString("name")} · ${when(status) {
                        "found" -> "발견 완료"; "closed" -> "신고 종료"; else -> "수색 중"
                    }}",21,bold=true)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextAction("제보 확인",enabled=!busy) { work {
                            sightings=(api.request("/guardian/reports/$id/sightings") as JSONArray).objects()
                        } }
                        if (status=="active") {
                            TextAction("수정",enabled=!busy) { work {
                                val areas=(api.request("/guardian/reports/$id/areas") as JSONArray).objects()
                                editor=GuardianDraft(report,areas)
                            } }
                            for ((value,title) in listOf("found" to "발견 완료","closed" to "신고 종료")) {
                                TextAction(title,enabled=!busy) { work {
                                    api.request("/guardian/reports/$id/status/$value","PATCH")
                                    refresh(api)
                                } }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class GuardianDraft(report: JSONObject? = null, areas: List<JSONObject> = emptyList()) {
    val id=report?.getString("id") ?: UUID.randomUUID().toString()
    var kind by mutableStateOf(report?.optString("kind") ?: "강아지")
    var name by mutableStateOf(report?.optString("name") ?: "")
    var coat by mutableStateOf(report?.optString("coat") ?: "")
    var traits by mutableStateOf(report?.optString("traits") ?: "")
    var time by mutableStateOf(report?.optString("missing_at") ?: OffsetDateTime.now().withNano(0).toString())
    var photo by mutableStateOf(report?.optString("photo_data")?.takeUnless { it.isBlank() || it=="null" })
    var pin by mutableStateOf(report?.optJSONObject("last_seen")?.getJSONArray("coordinates")?.let(::mapPoint))
    var walk by mutableStateOf(report?.optJSONObject("walk_route")?.getJSONArray("coordinates")?.let { arr ->
        (0 until arr.length()).map { mapPoint(arr.getJSONArray(it)) }
    } ?: emptyList())
    var places by mutableStateOf(report?.optJSONArray("places")?.objects() ?: emptyList())
    val shapes=mutableStateMapOf<String,JSONObject>().apply {
        areas.forEach { area ->
            val input=JSONObject().put("id",area.getString("id")).put("name",area.getString("name"))
            area.optJSONObject("polygon")?.let { input.put("polygon",it.getJSONArray("coordinates").getJSONArray(0)) }
            area.optJSONObject("center")?.let {
                input.put("center",it.getJSONArray("coordinates")).put("radius_m",area.getInt("radius_m"))
            }
            put(area.getString("name"),input)
        }
    }

    fun payload(): JSONObject {
        require(name.isNotBlank() && traits.isNotBlank() && pin!=null && shapes.isNotEmpty()) {
            "이름, 특징, 마지막 목격 핀과 수색 구역을 입력하세요"
        }
        require(walk.size!=1) { "산책 경로는 두 지점 이상 지정하세요" }
        OffsetDateTime.parse(time)
        return JSONObject().put("kind",kind).put("name",name).put("coat",coat).put("traits",traits)
            .put("missing_at",time).put("last_seen",geoPoint(pin!!)).put("photo_data",photo)
            .put("walk_route",walk.takeIf { it.isNotEmpty() }?.let { JSONArray(it.map(::geoPoint)) })
            .put("places",JSONArray(places)).put("areas",JSONArray(shapes.values.toList()))
    }
}

@Composable
private fun GuardianEditor(draft: GuardianDraft,busy: Boolean,save: () -> Unit) {
    var layer by remember { mutableStateOf(0) }
    var areaName by remember { mutableStateOf(neighborhoods.first()) }
    var placeName by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("100") }
    var vertices by remember { mutableStateOf(emptyList<Offset>()) }
    var circle by remember { mutableStateOf<Offset?>(null) }
    val storedLines=draft.shapes.values.mapNotNull { area ->
        area.optJSONArray("polygon")?.let { ring ->
            (0 until ring.length()).map { mapPoint(ring.getJSONArray(it)) }
        }
    }
    val storedCircles=draft.shapes.values.mapNotNull { area ->
        area.optJSONArray("center")?.let { mapPoint(it) to area.getInt("radius_m").toFloat() }
    }
    var localError by remember { mutableStateOf("") }
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri!=null) scope.launch {
            draft.photo=withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)) { decoder, info, _ ->
                        decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setTargetSampleSize(maxOf(1,(maxOf(info.size.width,info.size.height)+639)/640))
                    }
                    val bytes=java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,75,bytes)
                    android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP)
                }.getOrNull()
            }
            if (draft.photo==null) localError="사진을 불러올 수 없어요"
        }
    }
    Label("성수동 시연 지도 · 선택 위치를 확인해 주세요",14,muted)
    GuardianField("종류",draft.kind,{ draft.kind=it })
    GuardianField("이름",draft.name,{ draft.name=it })
    GuardianField("색상·무늬",draft.coat,{ draft.coat=it })
    GuardianField("특징",draft.traits,{ draft.traits=it })
    GuardianField("실종 시각 (시간대 포함)",draft.time,{ draft.time=it })
    TextAction(if (draft.photo==null) "사진 추가" else "사진 변경") {
        runCatching { photoPicker.launch(arrayOf("image/*")) }.onFailure { localError="사진 선택기를 열 수 없어요" }
    }
    if (localError.isNotEmpty()) Label(localError,16,Color(0xffba315e))
    listOf("목격 핀","산책 경로","자주 가는 장소","다각형 구역","원형 구역").forEachIndexed { index,title ->
        TextAction(title,primary=layer==index) { layer=index }
    }
    if (layer==2) GuardianField("장소 이름",placeName,{ placeName=it })
    if (layer>=3) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            neighborhoods.forEach { title -> TextAction(title,primary=areaName==title) {
                areaName=title; vertices=emptyList(); circle=null
            } }
        }
        if (layer==4) GuardianField("반경 (m)",radius,{ radius=it })
    }
    PawriroMap(Modifier.fillMaxWidth().height(320.dp),pin=draft.pin,
        reportPins=draft.places.map { mapPoint(it.getJSONArray("location")) },
        editLines=storedLines+listOf(draft.walk,vertices),
        savedCircles=storedCircles,
        editCircle=circle?.let { it to (radius.toFloatOrNull()?.coerceIn(1f,5000f) ?: 100f) },
        onPin={ point ->
            when(layer) {
                0 -> draft.pin=point
                1 -> draft.walk=draft.walk+point
                2 -> if (placeName.isNotBlank() && draft.places.size<20)
                    draft.places=draft.places+JSONObject().put("name",placeName).put("location",geoPoint(point))
                3 -> vertices=vertices+point
                4 -> circle=point
            }
        })
    if (layer==1) TextAction("산책 경로 지우기") { draft.walk=emptyList() }
    if (layer==2) TextAction("장소 목록 지우기") { draft.places=emptyList() }
    if (layer>=3) TextAction("선택 구역 추가·갱신") {
        val shape=JSONObject().put("id",draft.shapes[areaName]?.getString("id") ?: UUID.randomUUID().toString())
            .put("name",areaName)
        val meters=radius.toIntOrNull()
        if (layer==3 && vertices.distinct().size>=3)
            draft.shapes[areaName]=shape.put("polygon",JSONArray((vertices+vertices.first()).map(::geoPoint)))
        else if (layer==4 && circle!=null && meters!=null && meters in 1..5000)
            draft.shapes[areaName]=shape.put("center",geoPoint(circle!!)).put("radius_m",meters)
        else localError="지도에서 다각형 세 점 또는 원 중심과 반경을 선택하세요"
    }
    for (title in draft.shapes.keys.sorted()) TextAction("$title 구역 삭제") { draft.shapes.remove(title) }
    TextAction("신고 저장",primary=true,enabled=!busy,click=save)
}

@Composable
private fun GuardianField(label: String,value: String,change: (String)->Unit,secret: Boolean=false) {
    Label(label,14,muted,true)
    BasicTextField(value,change,Modifier.fillMaxWidth().background(Color.White).padding(14.dp),
        singleLine=true,visualTransformation=if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}


@Composable
private fun LoginField(label: String, value: String, change: (String) -> Unit, enabled: Boolean, secret: Boolean = false) {
    val shape = RoundedCornerShape(12.dp)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Label(label,20,bold=true)
        BasicTextField(value,change,Modifier.fillMaxWidth(),enabled=enabled,singleLine=true,
            textStyle=TextStyle(color=Color(0xff17221c), fontSize=23.sp),
            visualTransformation=if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions=KeyboardOptions(keyboardType=if (secret) KeyboardType.Password else KeyboardType.Ascii),
            decorationBox={ field ->
                Box(Modifier.fillMaxWidth().heightIn(min=60.dp).background(Color.White,shape)
                    .border(1.dp,Color(0xffb1beb5),shape).padding(horizontal=18.dp,vertical=12.dp),
                    contentAlignment=androidx.compose.ui.Alignment.CenterStart) { field() }
            })
    }
}
