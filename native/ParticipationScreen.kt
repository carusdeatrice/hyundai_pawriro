package kr.routerescue.demo

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime

@Composable
internal fun ParticipationScreen(state: PawriroState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        IconAction(R.drawable.paw_back,"홈으로",click=state::back)
        Label("수색 참여 내역",26,bold=true)
        Label("가상 포인트 ${state.participationRows.sumOf { it.optInt("base")+it.optInt("bonus") }}P",24,green,true)
        if (state.participationRows.isEmpty()) Label("수색 참여 내역이 없어요",16,muted)
        state.participationRows.asReversed().forEach { row ->
            key(row.getString("id")) {
                Column(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(18.dp)).padding(16.dp),
                    verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Label(row.getString("area"),21,bold=true)
                    Label(if (row.optBoolean("simulated")) "가상 주행 기록" else "Pleos 경로 참여",14,muted)
                    if (row.has("result")) {
                        Label(row.getString("result"),16,bold=true)
                        if (row.has("hold")) Label("지급 보류 · ${row.getString("hold")}",16,muted)
                        if (row.optInt("base") > 0) Label("기본 ${row.optInt("base")}P",16,green,true)
                        if (row.optInt("bonus") > 0) Label("사진 확인 보너스 +${row.optInt("bonus")}P",16,green,true)
                        else if (row.optString("photo").isNotBlank() && row.optInt("base") > 0)
                            Label("사진 확인 대기",16,muted)
                    } else if (state.parkedForResult) {
                        SightingForm(state,row.getString("id"))
                    } else Label("주차 확인 후 수색 결과를 제출할 수 있어요",16,muted)
                }
            }
        }
    }
}

@Composable
private fun SightingForm(state: PawriroState,id: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    var x by rememberSaveable { mutableStateOf<Float?>(null) }
    var y by rememberSaveable { mutableStateOf<Float?>(null) }
    var time by rememberSaveable { mutableStateOf(LocalDateTime.now().withSecond(0).withNano(0).toString()) }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    val context=LocalContext.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !state.restricted) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onSuccess { photo=uri.toString() }.onFailure { state.notify("사진 접근 권한을 저장할 수 없어요") }
        }
    }
    TextAction("목격 없이 수색 완료",primary=!open) { state.submitParticipation(id,false,"",null,null) }
    TextAction(if (open) "목격 제보 닫기" else "목격 제보") { open=!open }
    if (!open) return
    val pin=x?.let { horizontal -> y?.let { Offset(horizontal,it) } }
    Label(if (pin==null) "지도에서 실제 목격 위치를 선택하세요" else "선택한 분홍색 목격 핀을 확인하세요")
    PawriroMap(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp)),
        pin=pin,onPin={ x=it.x; y=it.y })
    Label("목격 시각 (YYYY-MM-DDTHH:mm)",14,muted)
    BasicTextField(time,{ time=it },Modifier.fillMaxWidth().background(panel,RoundedCornerShape(12.dp)).padding(16.dp),
        singleLine=true)
    TextAction(if (photo==null) "목격 사진 추가" else "목격 사진 변경") {
        runCatching { picker.launch(arrayOf("image/*")) }.onFailure { state.notify("사진 선택기를 열 수 없어요") }
    }
    TextAction("핀과 시각 확인 후 제출",primary=true) {
        val timestamp=runCatching { LocalDateTime.parse(time) }.getOrNull()
        if (pin==null || timestamp==null || timestamp.isAfter(LocalDateTime.now()))
            state.notify("목격 핀과 시각을 확인해 주세요")
        else state.submitParticipation(id,true,time,pin,photo)
    }
}
