package kr.routerescue.demo

import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.geometry.Offset
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

internal enum class Screen { Home, Search, Preview, Drive, Report, Mine, Complete, Participation, GuardianCreate }
internal data class Place(val name: String, val address: String, val time: Int, val distance: String)
internal data class DestinationResult(val name: String, val address: String, val latitude: Double, val longitude: Double)
internal val places = listOf(
    Place("서울숲 주차장", "서울 성동구 성수동1가 685", 24, "12.4 km"),
    Place("서울숲 가족마당", "서울 성동구 뚝섬로 273", 26, "13.1 km"),
    Place("서울숲역 3번 출구", "서울 성동구 왕십리로 77", 27, "13.5 km")
)
internal val neighborhoods = listOf("성수동1가", "성수동2가", "송정동")

internal class PawriroState {
    var guardianClient by mutableStateOf<GuardianClient?>(null)
    var guardianAddress by mutableStateOf("http://10.0.2.2:8000")
    var guardianReports by mutableStateOf(emptyList<PetReport>())
    var guardianFeedError by mutableStateOf("")
    var refreshGuardianFeed: () -> Unit = {}
    val searchableReports get() = reports + guardianReports.filter { synced -> reports.none { it.id==synced.id } }
    var screen by mutableStateOf(Screen.Home)
    var query by mutableStateOf("")
    var destinationStarting by mutableStateOf(false)
    var pendingNativeDestination by mutableStateOf<DestinationResult?>(null)
    var routeRevision by mutableIntStateOf(0)
    var showRouteOverview by mutableStateOf(false)
    var startDestination: (DestinationResult) -> Unit = {}

    var place by mutableStateOf(places.first())
    var parkedForResult by mutableStateOf(false)
    var guardianParked by mutableStateOf(false)
    var guardianCarRestricted by mutableStateOf(true)
    var participationRows by mutableStateOf(emptyList<org.json.JSONObject>())
    var submitParticipation: (String, Boolean, String, Offset?, String?) -> Unit = { _,_,_,_,_ -> }
    var passenger by mutableStateOf(false)
    var selectPassenger: (Boolean) -> Unit = {}
    var showPreview: (Place) -> Unit = {}
    var seatChecking by mutableStateOf(false)
    var restricted by mutableStateOf(false)
    var rescue by mutableStateOf(false)
    var prompt by mutableStateOf(false)
    var step by mutableIntStateOf(1)
    var areas by mutableStateOf(neighborhoods.take(2))
    var reports by mutableStateOf(emptyList<PetReport>())
    val registered get() = reports.any { it.status == ReportStatus.Active }
    var photo by mutableStateOf<String?>(null)
    var choosePhoto: () -> Unit = {}
    var routePosition by mutableIntStateOf(0)
    var offer by mutableStateOf<SearchOffer?>(null)
    var activeOffer by mutableStateOf<SearchOffer?>(null)
    var demoWaypoints by mutableStateOf(emptyList<Offset>())
    val driveRoute get() = demoRouteFor(place).dropLast(1) + demoWaypoints + demoRouteFor(place).last()
    var proposedEta by mutableStateOf<Pair<Int,Int>?>(null)
    var proposedRealStop by mutableStateOf<String?>(null)
    var appliedRealStop by mutableStateOf<String?>(null)
    var showNaviDiagnostic by mutableStateOf(false)
    var naviDiagnostic by mutableStateOf("경로 정보 미확보 · NaviHelper 연결 대기")
    var sdkDestination by mutableStateOf<ai.pleos.playground.navi.data.DestinationInfo?>(null)
    var sdkWaypointsReceived by mutableStateOf(false)
    var sdkWaypoints by mutableStateOf(emptyList<ai.pleos.playground.navi.data.WaypointInfo>())
    var sdkRouteState by mutableStateOf<ai.pleos.playground.navi.data.RouteStateInfo?>(null)
    var sdkTrip by mutableStateOf<ai.pleos.playground.navi.data.TripInfo?>(null)
    var sdkTurn by mutableStateOf<ai.pleos.playground.navi.data.TBTInfo?>(null)
    var sdkTurnAt = 0L
    var speechDiagnostic by mutableStateOf("Pleos 음성 연결 대기")
    var demoMode by mutableStateOf(false)
    var showingVehicleRoute by mutableStateOf(false)
    var navigationPosition by mutableStateOf<VisitPoint?>(null)
    var sdkLocation by mutableStateOf<ai.pleos.playground.location.fused.model.LocationData?>(null)
    private var store: ReportStore? = null
    private var draftId = UUID.randomUUID().toString()
    var editingId by mutableStateOf<String?>(null)
    var toast by mutableStateOf("")
    var kind by mutableStateOf("강아지")
    var name by mutableStateOf("콩이")
    var coat by mutableStateOf("갈색 털, 흰색 가슴 무늬")
    var time by mutableStateOf("2026-05-12T08:40")
    var traits by mutableStateOf("빨간 목줄을 하고 있으며 사람을 좋아해요.")
    var location by mutableIntStateOf(0)
    var pin by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
    var searchingLocation by mutableStateOf(false)
    var sightingPlace by mutableStateOf("서울숲 가족마당 앞")
    val asked = mutableSetOf<String>()
    var maneuver = false
    val driving get() = screen == Screen.Drive
    val locked get() = restricted || driving
    fun notify(message: String) { toast = message }
    fun back() {
        if (restricted && screen in setOf(Screen.Mine,Screen.GuardianCreate)) {
            screen=Screen.Home
            return
        }
        if (locked) return
        if (screen == Screen.Report) saveDraft()
        if (editingId != null) { cancelEdit(); return }
        if (searchingLocation) {
            searchingLocation = false
            screen = Screen.Report
        } else screen = Screen.Home
    }
    fun start() {
        if (restricted) return
        showingVehicleRoute = false
        asked.clear()
        routePosition = 0
        appliedRealStop = null
        activeOffer = null
        demoWaypoints = emptyList()
        rescue = false
        prompt = false
        screen = Screen.Drive
    }
    fun stop() {
        screen = Screen.Home
        showingVehicleRoute = false
        showRouteOverview = false
        pendingNativeDestination = null
        appliedRealStop = null
        activeOffer = null
        demoWaypoints = emptyList()
        prompt = false
        rescue = false
    }
    fun initialize(store: ReportStore) {
        this.store = store
        reports = store.reports()
        store.draft()?.let { draft ->
            draftId = draft.id
            kind = draft.kind
            name = draft.name
            coat = draft.coat
            time = draft.time
            traits = draft.traits
            photo = draft.photo
            pin = draft.pin
            sightingPlace = draft.place
            areas = draft.areas
            step = store.step().coerceIn(1,4)
        }
    }

    fun draft() = PetReport(draftId,kind,name,coat,time,traits,photo,pin,sightingPlace,areas)
    fun saveDraft() {
        if (editingId == null) store?.saveDraft(draft(),step)
    }
    fun submit() {
        if (areas.isEmpty()) { notify("수색 동네를 한 곳 이상 선택하세요"); return }
        val id = editingId
        if (id != null) {
            reports = reports.map { if (it.id == id && it.status == ReportStatus.Active) it.copy(areas=areas) else it }
            store?.saveReports(reports)
            cancelEdit()
            return
        }
        if (name.isBlank() || traits.isBlank() || runCatching { java.time.LocalDateTime.parse(time) }.isFailure) {
            notify("반려동물 정보와 실종 시각을 확인하세요"); return
        }
        if (pin == null) { notify("마지막 목격 위치를 선택하세요"); return }
        reports = reports + draft()
        store?.saveReports(reports,clearDraft=true)
        draftId = UUID.randomUUID().toString()
        step = 1; pin = null; photo = null; location = 0
        name = ""; coat = ""; traits = ""; time = ""; areas = emptyList(); sightingPlace = ""
        screen = Screen.Complete
    }
    private var previousAreas: List<String> = emptyList()
    private var previousStep = 1
    fun cancelEdit() {
        areas=previousAreas; step=previousStep; editingId=null; screen=Screen.Mine
    }
    fun editAreas(report: PetReport) {
        saveDraft()
        previousAreas=areas; previousStep=step
        editingId = report.id; areas = report.areas; step = 3; screen = Screen.Report
    }
    fun setStatus(report: PetReport,status: ReportStatus) {
        reports = reports.map { if (it.id == report.id) it.copy(status=status) else it }
        store?.saveReports(reports)
    }

    fun toggle(area: String) {
        areas = if (area in areas) areas - area else areas + area
    }
}

class PawriroComposeActivity : ComponentActivity() {
    private val state = PawriroState()
    private lateinit var voice: VoiceOutput
    private var pleosSpeech: PleosSpeech? = null
    private var vehicle: VehicleRestrictions? = null
    private var receiver: BroadcastReceiver? = null
    private lateinit var participation: SearchParticipation
    private var participationId: String? = null
    private var parkingCheck: Job? = null
    private var demoParked = true
    private var simulatedElapsed = 0L
    private val evidenceSession = UUID.randomUUID().toString()
    private var failNext = false

    private fun refreshParticipation() { state.participationRows = participation.entries() }

    private fun recordEvidence(event: String) {
        val id = participationId ?: return
        if (participation.evidence(id,event,demoParked,android.os.SystemClock.elapsedRealtime()+simulatedElapsed,evidenceSession)) {
            refreshParticipation()
        }
    }
    private var seat: PassengerSeat? = null
    private var demoSeat: Boolean? = null
    private var seatCheck: Job? = null
    private var pendingDestination: Place? = null
    private var originalOffer: SearchOffer? = null
    private data class AppliedSearch(val address: String, val offer: SearchOffer, val plan: RealDetour)
    private var appliedSearch: AppliedSearch? = null
    private var removingSearch = false

    private var offeredVehicleDetour: VehicleDetour? = null
    private var resumed by mutableStateOf(false)
    private var openGuidanceOnResume by mutableStateOf(false)
    private var nextVehicleSearch = 0L
    private var realPlan: RealDetour? = null
    private var approvedStop: ApprovedStop? = null
    private var evidenceJob: Job? = null

    private suspend fun approvedCandidate(candidate: VerifiedCandidate): ApprovedStop? {
        return try {
            val rows=GuardianClient(state.guardianAddress).request("/vehicle/candidates") as org.json.JSONArray
            val entry=VisitPoint(candidate.waypoint.latitude,candidate.waypoint.longitude)
            rows.objects().firstNotNullOfOrNull { row ->
                val point=VisitPoint(row.getDouble("latitude"),row.getDouble("longitude"))
                val expiry=java.time.OffsetDateTime.parse(row.getString("expires_at")).toInstant().toEpochMilli()
                if (row.getString("poi_id")!=candidate.waypoint.poiId || row.getString("area")!=candidate.area ||
                    row.getString("mode")!=(if (candidate.passenger) "passenger" else "solo") ||
                    entry.distance(point)>10 || expiry<=System.currentTimeMillis()) null
                else ApprovedStop(point,if (candidate.passenger) VisitPoint(row.getDouble("exit_latitude"),
                    row.getDouble("exit_longitude")) else null,expiry)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { null }
    }

    private fun trackVisit(id: String, stop: ApprovedStop) {
        evidenceJob?.cancel()
        evidenceJob=lifecycleScope.launch {
            val verifier=VisitVerifier(stop)
            val deadline=android.os.SystemClock.elapsedRealtime()+60*60_000L
            while (!verifier.verified && verifier.hold==null && android.os.SystemClock.elapsedRealtime()<deadline) {
                val gps=state.sdkLocation
                verifier.gps(gps?.let { VisitPoint(it.latitude,it.longitude) },gps?.timestampMillis ?: 0,
                    System.currentTimeMillis())
                if (stop.exit==null) {
                    val parked=readParked()
                    verifier.vehicle(parked,seat?.driverDoorOpen(),seat?.driverOccupied(),
                        android.os.SystemClock.elapsedRealtime())
                }
                if (!verifier.verified && verifier.hold==null) delay(1000)
            }
            participation.recordActual(id,verifier.verified,verifier.hold ?: if (verifier.verified) null else "방문 확인 시간 초과")
            refreshParticipation()
        }
    }

    private var realCandidate: VerifiedCandidate? = null

    private data class VerifiedCandidate(
        val area: String, val passenger: Boolean, val waypoint: NaviStop, val extraMinutes: Int,
        val route: NaviSnapshot, val expiresAt: Long
    )

    private fun loadCandidate(value: String?) {
        realCandidate = null
        state.naviDiagnostic = "경로 정보 미확보 · 검증된 경유 후보와 ETA 필요"
        val raw = value?.split("|") ?: return
        if (raw.size != 8 || raw[0] !in neighborhoods || raw[1] !in listOf("solo","passenger")) return
        val lat = raw[2].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return
        val lon = raw[3].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return
        val base = raw[6].toIntOrNull()?.takeIf { it > 0 } ?: return
        val detour = raw[7].toIntOrNull()?.takeIf { it >= base } ?: return
        val extra = (detour-base)/60 + if ((detour-base)%60 == 0) 0 else 1
        if (extra > MAX_EXTRA_MINUTES || raw[4].isBlank() || raw[5].isBlank()) return
        cancelSeatCheck()
        seatCheck = lifecycleScope.launch {
            val active = pleos?.readActiveRoute()
            if (active == null) {
                state.naviDiagnostic = "경로 정보 미확보 · 활성 목적지 또는 기존 경유지 응답 없음"
                return@launch
            }
            if (active.waypoints.size >= 2) {
                state.naviDiagnostic = "경로 정보 미확보 · 기존 경유지를 보존할 슬롯 없음"
                return@launch
            }
            realCandidate = VerifiedCandidate(raw[0],raw[1]=="passenger",
                NaviStop(lat,lon,raw[4],"",raw[5],"0",null),extra,active,
                android.os.SystemClock.elapsedRealtime()+120_000)
            state.naviDiagnostic = "Pleos 실제 경유 후보 준비됨"
        }
    }
    private var reconfirmed = false
    private var pleos: PleosReadClient? = null
    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !state.locked) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onSuccess { state.photo=uri.toString(); state.saveDraft() }
                .onFailure { state.notify("사진 접근 권한을 저장할 수 없어요") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        participation = SearchParticipation(getSharedPreferences("pawriro-participation",MODE_PRIVATE))
        refreshParticipation()
        state.submitParticipation = { id,sighting,time,pin,photo ->
            if (!state.restricted && state.parkedForResult && state.screen == Screen.Participation) {
                lifecycleScope.launch {
                    if (receiver == null && packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
                        readParked() != true) {
                        state.parkedForResult = false
                        state.notify("차량 주차 상태를 확인할 수 없어요")
                        return@launch
                    }
                    if (state.restricted || !state.parkedForResult || state.screen != Screen.Participation) return@launch
                    if (participation.submit(id,sighting,time,pin?.x,pin?.y,photo,System.currentTimeMillis())) refreshParticipation()
                    else state.notify("제출 내용을 확인해 주세요")
                }
            }
        }
        val settings=getSharedPreferences("pawriro-guardian",MODE_PRIVATE)
        state.guardianAddress=settings.getString("url","http://10.0.2.2:8000")!!
        state.refreshGuardianFeed={ lifecycleScope.launch { refreshGuardianFeed() } }
        state.initialize(ReportStore(getSharedPreferences("pawriro-reports",MODE_PRIVATE)))
        state.choosePhoto = {
            if (!state.locked) {
                runCatching { photoPicker.launch(arrayOf("image/*")) }
                    .onFailure { state.notify("이 환경에서 사진 선택을 사용할 수 없어요") }
            }
        }
        val emulator = Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")
        val demo = emulator && intent.getBooleanExtra("demo", false)
        state.showNaviDiagnostic = demo
        state.demoMode = demo
        val automotive = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        state.restricted = automotive && !demo
        state.parkedForResult = demo || !automotive
        state.guardianParked = !automotive
        state.guardianCarRestricted = automotive
        voice = VoiceOutput(this)
        if (automotive) {
            runCatching {
                pleosSpeech = PleosSpeech(this) { state.speechDiagnostic=it }.also { it.initialize() }
            }.onFailure { state.speechDiagnostic="Pleos 음성 초기화 실패"; Log.w("PawriroPleos","Speech initialization failed",it) }
            seat = runCatching { PassengerSeat(this) }
                .onFailure { Log.w("PawriroPleos","Vehicle seat service unavailable",it) }.getOrNull()
        }
        state.startDestination = { result ->
            if (state.screen==Screen.Search && !state.restricted && !state.destinationStarting) {
                state.destinationStarting=true
                state.pendingNativeDestination=result
                lifecycleScope.launch {
                    try {
                        if (pleos?.startDestination(result)!=true && state.pendingNativeDestination==result) {
                            state.pendingNativeDestination=null
                            state.notify("Pleos에서 목적지 안내를 요청할 수 없어요")
                        }
                    } finally {
                        state.destinationStarting=false
                    }
                }
            }
        }
        state.showPreview = { place ->
            state.place = place
            state.passenger = false
            state.screen = Screen.Preview
            checkPreviewSeat()
        }
        state.selectPassenger = { enabled ->
            if (state.screen == Screen.Preview && !state.restricted) {
                if (enabled) checkPreviewSeat(manual=true) else {
                    cancelSeatCheck()
                    state.passenger = false
                }
            }
        }
        if (automotive) {
            try {
                vehicle = VehicleRestrictions(this) { restricted ->
                    runOnUiThread {
                        state.guardianCarRestricted = restricted
                        if (restricted) state.guardianParked = false
                        if (!demo) setRestriction(restricted)
                    }
                }
            } catch (_: LinkageError) {
                state.restricted = true
            } catch (_: RuntimeException) {
                state.restricted = true
            }
        }
        if (automotive) {
            pleos = PleosReadClient(this,state).also { it.connect() }
        }
        if (demo) {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.getStringExtra("event")) {
                        "navi-refresh" -> pleos?.refresh()
                        "navi-candidate" -> if (!state.prompt && seatCheck?.isActive != true) loadCandidate(intent.getStringExtra("answer"))
                        "drive" -> { recordEvidence("unpark"); demoParked = false; setRestriction(true); offer() }
                        "seat" -> { demoSeat = when (intent.getStringExtra("answer")) {
                            "occupied-buckled" -> true
                            "empty", "unbuckled" -> false
                            "vehicle" -> null
                            else -> demoSeat
                        } }
                        "tick" -> {
                            val previous = state.routePosition
                            if (!demoParked && state.driving) {
                                state.routePosition = minOf(previous+1,state.driveRoute.lastIndex)
                                state.activeOffer?.takeIf { state.appliedRealStop == null }?.let { active ->
                                    val index = state.driveRoute.indexOf(active.waypoint)
                                    if (index >= 0 && previous < index && state.routePosition == index) {
                                        recordEvidence(if (active.passenger) "entered" else "arrived")
                                    }
                                    if (active.passenger && previous == index && state.routePosition > index) recordEvidence("passed")
                                }
                                offer()
                            }
                        }
                        "park" -> { demoParked = true; setRestriction(false); cancelSeatCheck(); dismissPrompt() }
                        "walk-minutes" -> intent.getStringExtra("answer")?.toLongOrNull()?.takeIf { it in 1..60 }
                            ?.let { if (demoParked) simulatedElapsed += it*60_000 }
                        "driver-door" -> recordEvidence("door")
                        "driver-exit" -> recordEvidence("exit")
                        "driver-return" -> recordEvidence("return")
                        "photo-approve" -> {
                            val id = intent.getStringExtra("answer") ?: state.participationRows.lastOrNull()?.optString("id")
                            if (id != null && participation.approveDemoPhoto(id,System.currentTimeMillis())) refreshParticipation()
                            else state.notify("사진 보너스 승인 조건을 확인해 주세요")
                        }
                        "stop" -> {
                            demoParked = true
                            setRestriction(false)
                            cancelSeatCheck()
                            state.maneuver = false
                            failNext = false
                            dismissPrompt()
                            state.stop()
                        }
                        "turn" -> { state.maneuver = true; cancelSeatCheck(); dismissPrompt() }
                        "clear-turn" -> { state.maneuver = false; offer() }
                        "timeout" -> dismissPrompt()
                        "fail" -> failNext = true
                        "reply" -> reply(intent.getStringExtra("answer"))
                    }
                }
            }.also {
                registerReceiver(it, IntentFilter("kr.routerescue.demo.CONTROL"), "android.permission.DUMP", null)
            }
        }
        setContent {
            LaunchedEffect(state.guardianAddress) {
                while (true) {
                    refreshGuardianFeed()
                    delay(10_000)
                }
            }
            LaunchedEffect(Unit) {
                snapshotFlow { if (state.screen == Screen.Report && state.editingId == null) state.draft() to state.step else null }
                    .collect { if (it != null) state.saveDraft() }
            }
            LaunchedEffect(state.screen,state.guardianCarRestricted,state.restricted) {
                if (automotive && state.screen in setOf(Screen.GuardianCreate, Screen.Mine)) {
                    state.guardianParked = false
                    while (!state.guardianCarRestricted && !state.restricted) {
                        val parked = readParked()
                        state.guardianParked = parked == true && !state.guardianCarRestricted && !state.restricted
                        delay(500)
                    }
                }
                if (state.screen==Screen.Participation && !state.restricted && !state.demoMode && automotive)
                    refreshParkedState()
            }
            LaunchedEffect(state.pendingNativeDestination,state.sdkDestination,state.sdkRouteState,
                resumed,openGuidanceOnResume,state.screen) {
                val destination = state.sdkDestination ?: return@LaunchedEffect
                if (state.demoMode || !state.sdkRouteState.isGuiding()) return@LaunchedEffect
                val pending = state.pendingNativeDestination
                val selectedDestination = pending != null &&
                    VisitPoint(destination.latitude,destination.longitude)
                        .distance(VisitPoint(pending.latitude,pending.longitude))<=150
                val returnToGuidance = openGuidanceOnResume && resumed &&
                    state.screen in setOf(Screen.Home,Screen.Search)
                if (!selectedDestination && !returnToGuidance) return@LaunchedEffect
                val bringForward = selectedDestination && !resumed && state.screen==Screen.Search
                openGuidanceOnResume=false
                state.stop()
                state.asked.clear()
                state.showingVehicleRoute=true
                state.screen=Screen.Drive
                if (bringForward) {
                    runCatching {
                        startActivity(Intent(this@PawriroComposeActivity,PawriroComposeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }.onFailure { Log.w("PawriroPleos","Unable to return to pawriro",it) }
                }
            }
            LaunchedEffect(state.driving,state.showingVehicleRoute,state.pendingNativeDestination) {
                while (state.driving || state.pendingNativeDestination!=null) {
                    pleos?.refresh()
                    if (state.showingVehicleRoute && !voiceWindowAvailable()) dismissPrompt()
                    else if (state.showingVehicleRoute) offer()
                    delay(3500)
                }
            }
            LaunchedEffect(state.prompt,state.offer) {
                if (state.prompt) {
                    delay(15_000)
                    dismissPrompt()
                }
            }
            val density = LocalDensity.current
            // Pleos WebView renders the reference layout at 220 dpi on the 160 dpi application window.
            val scale = if (automotive) 1.375f else 1f
            CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
                PawriroScreen(state)
            }
        }
    }

    private fun setRestriction(restricted: Boolean) {
        if (restricted && state.screen == Screen.Report) state.saveDraft()
        state.restricted = restricted
        state.parkedForResult = !restricted && (receiver != null && demoParked ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
        parkingCheck?.cancel()
        if (!restricted && receiver == null && !state.parkedForResult) {
            refreshParkedState()
        }
        if (restricted && state.screen != Screen.Drive) cancelSeatCheck()
        if (!restricted) dismissPrompt()
    }

    private suspend fun readParked(): Boolean? {
        val source = vehicle ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { source.parked() }
    }

    private fun refreshParkedState() {
        parkingCheck?.cancel()
        parkingCheck = lifecycleScope.launch {
            state.parkedForResult = readParked() == true && !state.restricted
        }
    }

    private suspend fun readPassenger() = demoSeat ?: seat?.confirmed() ?: false

    private fun cancelSeatCheck() {
        seatCheck?.cancel()
        seatCheck = null
        state.seatChecking = false
    }

    private fun checkPreviewSeat(manual: Boolean = false) {
        cancelSeatCheck()
        state.seatChecking = true
        seatCheck = lifecycleScope.launch {
            try {
                val confirmed = readPassenger()
                if (state.screen == Screen.Preview && !state.restricted) {
                    state.passenger = confirmed
                    if (manual && !confirmed) state.notify("조수석 착석과 안전벨트 체결을 확인할 수 없어요")
                }
            } finally {
                state.seatChecking = false
            }
        }
    }

    private fun canOffer() = state.driving && state.restricted && !state.maneuver && !state.rescue &&
        (!state.showingVehicleRoute || voiceWindowAvailable())

    private var feedRequest = 0L

    private suspend fun refreshGuardianFeed() {
        val request=++feedRequest
        val address=state.guardianAddress
        try {
            val feed=GuardianClient(address).request("/vehicle/feed") as org.json.JSONArray
            val reports=feed.objects().map { item ->
                val areas=item.getJSONArray("areas")
                val coverage=item.getJSONArray("coverage").objects().mapNotNull { area ->
                    val ring=area.optJSONObject("shape")?.optJSONArray("coordinates")?.optJSONArray(0)
                        ?: return@mapNotNull null
                    val points=(0 until ring.length()).map { mapPoint(ring.getJSONArray(it)) }
                    area.getString("name") to androidx.compose.ui.geometry.Rect(points.minOf { it.x },
                        points.minOf { it.y },points.maxOf { it.x },points.maxOf { it.y })
                }.toMap()
                PetReport(item.getString("id"),"반려동물","수색 요청","","","",null,null,"",
                    (0 until areas.length()).map(areas::getString),latestSighting=item.optString("latest_sighting"),publicCoverage=coverage)
            }
            if (address!=state.guardianAddress || request!=feedRequest) return
            state.guardianReports=reports
            state.guardianFeedError=""
            val offered=originalOffer
            if (state.prompt && offered!=null && !state.searchableReports.any {
                it.id==offered.reportId && it.status==ReportStatus.Active && offered.area in it.areas
            }) dismissPrompt()
            removeUnavailableSearch(address, reports)
            if (address != state.guardianAddress || request != feedRequest) return
            if (receiver!=null) offer()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (request!=feedRequest || address!=state.guardianAddress) return
            val removed=state.guardianReports.map { it.id }
            state.guardianReports=emptyList()
            if (originalOffer?.reportId in removed) dismissPrompt()
            state.guardianFeedError="보호자 신고 동기화 연결 대기"
        }
    }

    private suspend fun removeUnavailableSearch(address: String, reports: List<PetReport>) {
        val applied = appliedSearch ?: return
        if (applied.address != address || seatCheck?.isActive == true || removingSearch) return
        if (reports.any { it.id == applied.offer.reportId && applied.offer.area in it.areas }) return
        removingSearch = true
        val result = try {
            pleos?.removeSearchWaypoint(applied.plan) ?: PleosReadClient.Removal.Unconfirmed
        } finally {
            removingSearch = false
        }
        if (result == PleosReadClient.Removal.Unconfirmed) {
            state.naviDiagnostic = "수색 종료 · 내비 경유지 제거 확인 대기"
            return
        }
        appliedSearch = null
        if (state.activeOffer?.reportId == applied.offer.reportId) {
            state.activeOffer = null
            state.appliedRealStop = null
            state.rescue = false
        }
        state.naviDiagnostic = if (result == PleosReadClient.Removal.Removed) "수색 종료 · 수색 경유지 제거 완료"
            else "수색 종료 · 현재 차량 안내 유지"
        state.notify(state.naviDiagnostic)
    }

    private fun candidate(passenger: Boolean, reportId: String? = null): SearchOffer? {
        val reports = if (reportId == null) state.searchableReports else state.searchableReports.filter { it.id == reportId }
        if (state.showingVehicleRoute) {
            val supplied=realCandidate ?: return null
            if (supplied.passenger!=passenger || android.os.SystemClock.elapsedRealtime()>=supplied.expiresAt) return null
            val report=reports.firstOrNull {
                it.status==ReportStatus.Active && supplied.area in it.areas &&
                    (reportId!=null || it.id !in state.asked)
            } ?: return null
            return SearchOffer(report.id,supplied.area,supplied.extraMinutes,passenger)
        }
        if (!state.demoMode) return null
        return matchSearch(reports,demoRouteFor(state.place).drop(state.routePosition),passenger,
            if (reportId == null) state.asked else emptySet())
    }

    private fun offer() {
        if (!canOffer() || state.prompt || seatCheck?.isActive == true || removingSearch) return
        if (state.showingVehicleRoute && !state.demoMode) {
            offerVehicleDetour()
            return
        }
        offeredVehicleDetour = null
        if (state.showingVehicleRoute) {
            if (realCandidate==null) return
        } else if (!state.demoMode ||
            !hasSearchAreaAhead(state.searchableReports,demoRouteFor(state.place).drop(state.routePosition),state.asked)) return
        val destination = state.place
        seatCheck = lifecycleScope.launch {
            val passenger = readPassenger()
            if (!canOffer() || state.place != destination) return@launch
            state.passenger = passenger
            val found = candidate(passenger) ?: return@launch
            pendingDestination = destination
            reconfirmed = false
            realPlan = findRealPlan(found)
            if (state.showingVehicleRoute && realPlan==null) return@launch
            if (!canOffer() || state.place != destination) return@launch
            if (candidate(passenger,found.reportId)!=found) return@launch
            state.asked.add(found.reportId)
            showOffer(found)
        }
    }

    private fun offerVehicleDetour() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < nextVehicleSearch || state.guardianReports.none { it.id !in state.asked }) return
        nextVehicleSearch = now + 20_000
        seatCheck = lifecycleScope.launch {
            val passenger = readPassenger()
            state.passenger = passenger
            val found = fetchVehicleDetour(passenger) ?: return@launch
            if (!canOffer() || state.prompt || !isActiveVehicleOffer(found)) return@launch
            pendingDestination = state.place
            offeredVehicleDetour = found
            realPlan = found.plan
            reconfirmed = false
            state.asked.add(found.offer.reportId)
            showOffer(found.offer)
        }
    }

    private fun isActiveVehicleOffer(found: VehicleDetour) = state.guardianReports.any {
        it.id == found.offer.reportId && it.status == ReportStatus.Active && found.offer.area in it.areas
    }

    private suspend fun fetchVehicleDetour(passenger: Boolean, offered: VehicleDetour? = null): VehicleDetour? {
        if (!canOffer() || !state.showingVehicleRoute) return null
        val active = pleos?.readActiveRoute()
        val gps = state.sdkLocation?.takeIf { System.currentTimeMillis()-it.timestampMillis in 0..15_000 }
        if (active == null || active.waypoints.size >= 2 || gps == null) {
            state.naviDiagnostic = "경로 정보 미확보 · 활성 경로, 경유 슬롯 또는 최신 위치 확인 필요"
            return null
        }
        if (offered != null && offered.plan.route != active) return null
        return try {
            GuardianClient(state.guardianAddress).vehicleDetour(active, gps.latitude, gps.longitude, passenger,
                state.asked - setOfNotNull(offered?.offer?.reportId), offered) { state.naviDiagnostic = it }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) {
            state.naviDiagnostic = "경로 정보 미확보 · 실시간 경로 서버 응답 없음"
            null
        }
    }

    private suspend fun applyVehicleDetour(offered: VehicleDetour, passenger: Boolean) {
        val fresh = fetchVehicleDetour(passenger, offered)
        if (fresh == null || !isActiveVehicleOffer(fresh) || !canOffer()) {
            state.notify("경유 조건 확인 불가 · 기존 안내 유지")
            return
        }
        if (fresh.offer != offered.offer || fresh.plan.index != offered.plan.index ||
            fresh.plan.stop.poiId != offered.plan.stop.poiId || fresh.proof.entry.distance(offered.proof.entry)>20 ||
            fresh.proof.exit?.let { current -> offered.proof.exit?.let { current.distance(it)>20 } ?: true } == true) {
            if (reconfirmed) {
                state.notify("경유 조건이 다시 바뀌어 기존 안내를 유지해요")
                return
            }
            reconfirmed = true
            offeredVehicleDetour = fresh
            realPlan = fresh.plan
            showOffer(fresh.offer)
            return
        }
        val address = state.guardianAddress
        if (fresh.proof.expiresAt <= System.currentTimeMillis() || pleos?.applyDetour(fresh.plan) != true) {
            state.naviDiagnostic = "Pleos 경유 적용 미확인 · 활성 경로 재조회"
            state.notify(state.naviDiagnostic)
            return
        }
        appliedSearch = AppliedSearch(address, fresh.offer, fresh.plan)
        if (!state.driving || !state.showingVehicleRoute) return
        state.activeOffer = fresh.offer
        state.appliedRealStop = fresh.plan.stop.name
        state.rescue = true
        state.naviDiagnostic = "Pleos 경유 적용 확인 · 계산 경로 표시"
        participation.begin(fresh.offer, false)?.let { trackVisit(it,fresh.proof) }
        refreshParticipation()
    }

    private suspend fun findRealPlan(candidate: SearchOffer): RealDetour? {
        if (!state.showingVehicleRoute && state.guardianReports.any { it.id == candidate.reportId }) {
            state.naviDiagnostic="실제 경로 정보 미확보 · 공개 구역은 시연 매칭으로 확인"
            return null
        }
        val active = pleos?.readActiveRoute()
        val supplied = realCandidate
        val reason = when {
            active == null -> "활성 목적지 또는 기존 경유지 응답 없음"
            active.waypoints.size >= 2 -> "기존 경유지를 보존할 슬롯 없음"
            !state.showingVehicleRoute && active.destination.name != state.place.name -> "차량 내비 목적지와 앱 목적지가 다름"
            supplied == null -> "검증된 경유 후보와 사전 ETA 없음"
            supplied.route != active -> "활성 경로 변경"
            android.os.SystemClock.elapsedRealtime() >= supplied.expiresAt -> "후보 ETA 만료"
            supplied.area != candidate.area || supplied.passenger != candidate.passenger -> "현재 탑승 모드에 맞는 실제 후보 없음"
            state.showingVehicleRoute && state.sdkLocation?.let {
                System.currentTimeMillis()-it.timestampMillis in 0..15_000
            } != true -> "최신 차량 위치 없음"
            else -> null
        }
        if (reason != null || active == null || supplied == null) {
            state.naviDiagnostic = "경로 정보 미확보 · ${reason ?: "후보 없음"}${if (state.showingVehicleRoute) "" else " · 시연 경로 사용"}"
            return null
        }
        val verified=approvedCandidate(supplied)
        if (verified==null) {
            state.naviDiagnostic="운영자가 검증한 안전 경유 후보 없음"
            return null
        }
        approvedStop=verified
        state.naviDiagnostic = "Pleos 실제 경유 후보 · 약 ${supplied.extraMinutes}분 추가"
        return RealDetour(active,supplied.waypoint,supplied.extraMinutes)
    }

    private fun voiceWindowAvailable(): Boolean {
        val turn=state.sdkTurn ?: return false
        // TBT is a proximity hint, not an audio-playback indicator.
        return android.os.SystemClock.elapsedRealtime()-state.sdkTurnAt <= 5000 && turn.distance > 300
    }

    private fun showOffer(candidate: SearchOffer) {
        state.proposedEta = offeredVehicleDetour?.let { (it.baselineSeconds+59)/60 to (it.detourSeconds+59)/60 }
        state.proposedRealStop = realPlan?.stop?.name
        originalOffer = candidate
        state.offer = candidate.copy(extraMinutes=realPlan?.extraMinutes ?: candidate.extraMinutes)
        state.prompt = true
        val name = realPlan?.stop?.name ?: candidate.waypointName
        val extra = realPlan?.extraMinutes ?: candidate.extraMinutes
        val message = "$name 경유 시 약 ${extra}분 더 걸려요. 참여할까요?"
        Log.i("PawriroDemo", message)
        if (realPlan != null && !state.demoMode) {
            val started=pleosSpeech?.ask(message,{ canOffer() && state.prompt && voiceWindowAvailable() },::reply)==true
            if (!started) {
                state.speechDiagnostic="Pleos 음성 사용 불가 · 기존 안내 유지"
                dismissPrompt()
            }
        } else if (state.demoMode && realPlan != null && voiceWindowAvailable()) {
            if (pleosSpeech?.ask(message,{ canOffer() && state.prompt && voiceWindowAvailable() },
                    { answer -> if (answer!=null) reply(answer) })!=true) voice.speak(message)
        } else if (state.demoMode) voice.speak(message)
    }

    private fun dismissPrompt() {
        if (!state.prompt) return
        state.prompt = false
        pleosSpeech?.stop()
        voice.stop()
    }

    private fun reply(answer: String?) {
        if (!state.prompt || !canOffer()) return
        val offered = originalOffer ?: return
        val offeredReal = realPlan
        val vehicleOffer = offeredVehicleDetour
        val destination = pendingDestination ?: return
        dismissPrompt()
        if (answer?.filterNot(Char::isWhitespace) !in listOf("참여할게", "참여", "응")) {
            state.notify("기존 경로로 안내를 계속해요")
            return
        }
        seatCheck = lifecycleScope.launch {
            val passenger = readPassenger()
            if (!canOffer() || state.place != destination) return@launch
            state.passenger = passenger
            if (vehicleOffer != null) {
                applyVehicleDetour(vehicleOffer,passenger)
                return@launch
            }
            val current = candidate(passenger,offered.reportId)
            val changed = current?.copy(sightingPriority=offered.sightingPriority) != offered
            if (current == null || (changed && reconfirmed)) {
                state.notify("기존 경로로 안내를 계속해요")
                return@launch
            }
            if (changed) {
                realPlan = findRealPlan(current)
                if (state.showingVehicleRoute && realPlan==null) return@launch
                if (!canOffer() || state.place != destination) return@launch
                reconfirmed = true
                showOffer(current)
                return@launch
            }
            if (offeredReal != null) {
                val refreshedPlan = findRealPlan(current)
                if (refreshedPlan != offeredReal || !canOffer() || state.place != destination) {
                    state.notify("실제 경유 조건이 바뀌어 기존 안내를 유지해요")
                    return@launch
                }
                val applied = if (failNext || (approvedStop?.expiresAt ?: 0)<=System.currentTimeMillis()) false
                    else pleos?.applyDetour(offeredReal) == true
                failNext = false
                state.naviDiagnostic = if (applied) "Pleos 실제 경유 적용 확인"
                    else "Pleos 경유 적용 미확인 · 활성 경로 재조회"
                state.notify(state.naviDiagnostic)
                if (applied) {
                    state.showingVehicleRoute=true
                    state.activeOffer = current.copy(extraMinutes=offeredReal.extraMinutes)
                    state.appliedRealStop = offeredReal.stop.name
                    if (state.guardianReports.any { it.id == current.reportId })
                        appliedSearch = AppliedSearch(state.guardianAddress, current, offeredReal)
                    val proof=approvedStop
                    participation.begin(current,false)?.let { id ->
                        if (proof!=null) trackVisit(id,proof)
                    }
                    refreshParticipation()
                }
                state.rescue = applied
                return@launch
            }
            if (!state.demoMode || state.showingVehicleRoute) return@launch
            if (!failNext) state.demoWaypoints = state.demoWaypoints + current.waypoint
            state.rescue = current.waypoint in state.demoWaypoints
            state.activeOffer = current.takeIf { state.rescue }
            if (state.rescue) {
                participationId = participation.begin(current,true)
                refreshParticipation()
            }
            failNext = false
            state.notify(if (state.rescue) "모의 수색 경유지를 반영했어요" else "경로 적용 실패 · 기존 경로로 동기화했어요")
        }
    }

    override fun onResume() {
        super.onResume()
        resumed=true
        openGuidanceOnResume=state.screen in setOf(Screen.Home,Screen.Search)
        pleos?.refresh()
    }

    override fun onPause() {
        resumed=false
        if (state.screen == Screen.Report) state.saveDraft()
        dismissPrompt()
        super.onPause()
    }

    override fun onDestroy() {
        cancelSeatCheck()
        evidenceJob?.cancel()
        seat?.close()
        receiver?.let(::unregisterReceiver)
        vehicle?.close()
        pleos?.close()
        pleosSpeech?.close()
        voice.close()
        super.onDestroy()
    }
}
