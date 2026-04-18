package com.sting.virtualloc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.material3.Divider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure osmdroid
        Configuration.getInstance().userAgentValue = packageName

        setContent {
            VirtuaLocTheme {
                MainScreen()
            }
        }
    }
}

data class QuickSelectItem(
    val name: String,
    val lat: Double,
    val lng: Double,
    val cells: List<CellInfoData> = emptyList(),
    val wifis: List<WifiInfoData> = emptyList(),
    val accuracy: Float = 0f
)

@OptIn(ExperimentalMaterial3Api::class)
data class UiState(
    val isRunning: Boolean = false,
    val latText: String = "",
    val lngText: String = "",
    val devModeEnabled: Boolean = true,
    val hasLocationPermission: Boolean = false,
    val showDeveloperDialog: Boolean = false,
    val showInitDialog: Boolean = false,
    val initStep: Int = 0,         // 0=未开始, 1~5=执行中, 6=完成
    val initLog: String = "",     // 每一步的执行结果
    val statusMessage: String = "请输入坐标后点击「开启虚拟定位」",
    val quickSelectItems: List<QuickSelectItem> = emptyList(),
    val showEditDialog: Boolean = false,
    val editingItem: QuickSelectItem? = null,
    val showAddDialog: Boolean = false,
    val showGpsDialog: Boolean = false,
    val gpsLoading: Boolean = false,
    // Last captured location snapshot (GPS + cells + wifi)
    val lastSnapshot: LocationSnapshot? = null,
    // True when GPS coords just arrived and snapshot collection is pending
    val gpsCoordsJustSet: Boolean = false
)

class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var prefs: SharedPreferences? = null
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context
        prefs = context.getSharedPreferences("virtualloc_prefs", Context.MODE_PRIVATE)
        loadState()
    }

    private fun loadState() {
        val p = prefs ?: return
        val lat = p.getString("last_lat", "") ?: ""
        val lng = p.getString("last_lng", "") ?: ""
        val items = loadQuickSelectItems(p)
        _state.value = _state.value.copy(
            latText = lat,
            lngText = lng,
            quickSelectItems = items
        )
    }

    private fun loadQuickSelectItems(p: SharedPreferences): List<QuickSelectItem> {
        val json = p.getString("quick_select_json", null)
        if (json.isNullOrEmpty()) {
            // First install: default to Beijing and Shanghai
            return listOf(
                QuickSelectItem("北京", 39.9042, 116.4074),
                QuickSelectItem("上海", 31.2304, 121.4737)
            )
        }
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                QuickSelectItem(
                    name = obj.getString("name"),
                    lat = obj.getDouble("lat"),
                    lng = obj.getDouble("lng"),
                    accuracy = obj.optDouble("accuracy", 0.0).toFloat(),
                    cells = parseCellList(obj.optJSONArray("cells")),
                    wifis = parseWifiList(obj.optJSONArray("wifis"))
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseCellList(arr: JSONArray?): List<CellInfoData> {
        if (arr == null) return emptyList()
        return try {
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CellInfoData(
                    cid = o.getInt("cid"),
                    lac = o.getInt("lac"),
                    mcc = o.getInt("mcc"),
                    mnc = o.getInt("mnc"),
                    pci = o.optInt("pci", -1),
                    rsrp = o.optInt("rsrp", -1),
                    type = o.optString("type", "unknown")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseWifiList(arr: JSONArray?): List<WifiInfoData> {
        if (arr == null) return emptyList()
        return try {
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WifiInfoData(
                    bssid = o.optString("bssid", ""),
                    ssid = o.optString("ssid", ""),
                    rssi = o.optInt("rssi", -1),
                    frequency = o.optInt("frequency", -1)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveQuickSelectItems(items: List<QuickSelectItem>) {
        val p = prefs ?: return
        val arr = JSONArray()
        items.forEach { item ->
            val obj = JSONObject().apply {
                put("name", item.name)
                put("lat", item.lat)
                put("lng", item.lng)
                put("accuracy", item.accuracy.toDouble())
                put("cells", cellListToJson(item.cells))
                put("wifis", wifiListToJson(item.wifis))
            }
            arr.put(obj)
        }
        p.edit().putString("quick_select_json", arr.toString()).apply()
        _state.value = _state.value.copy(quickSelectItems = items)
    }

    private fun cellListToJson(cells: List<CellInfoData>): JSONArray {
        val arr = JSONArray()
        cells.forEach { c ->
            arr.put(JSONObject().apply {
                put("cid", c.cid)
                put("lac", c.lac)
                put("mcc", c.mcc)
                put("mnc", c.mnc)
                put("pci", c.pci)
                put("rsrp", c.rsrp)
                put("type", c.type)
            })
        }
        return arr
    }

    private fun wifiListToJson(wifis: List<WifiInfoData>): JSONArray {
        val arr = JSONArray()
        wifis.forEach { w ->
            arr.put(JSONObject().apply {
                put("bssid", w.bssid)
                put("ssid", w.ssid)
                put("rssi", w.rssi)
                put("frequency", w.frequency)
            })
        }
        return arr
    }

    fun saveLastCoords(lat: String, lng: String) {
        val p = prefs ?: return
        p.edit().putString("last_lat", lat).putString("last_lng", lng).apply()
    }

    fun updateLat(text: String) {
        _state.value = _state.value.copy(latText = text)
    }

    fun updateLng(text: String) {
        _state.value = _state.value.copy(lngText = text)
    }

    fun setRunning(running: Boolean) {
        _state.value = _state.value.copy(isRunning = running)
    }

    fun setDevModeEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(devModeEnabled = enabled)
    }

    fun setHasPermission(has: Boolean) {
        _state.value = _state.value.copy(hasLocationPermission = has)
    }

    fun setStatus(msg: String) {
        _state.value = _state.value.copy(statusMessage = msg)
    }

    fun showDeveloperDialog() {
        _state.value = _state.value.copy(showDeveloperDialog = true)
    }

    fun hideDeveloperDialog() {
        _state.value = _state.value.copy(showDeveloperDialog = false)
    }

    fun showInitDialog() {
        _state.value = _state.value.copy(showInitDialog = true, initStep = 0, initLog = "")
    }

    fun hideInitDialog() {
        _state.value = _state.value.copy(showInitDialog = false, initStep = 0, initLog = "")
    }

    fun runInitSteps() {
        _state.value = _state.value.copy(
            initStep = 1,
            initLog = "请在电脑终端依次执行以下命令：\n\n" +
                "  adb shell cmd appops set com.sting.virtualloc android:mock_location allow\n" +
                "  adb shell settings put global development_enabled 0\n" +
                "  adb shell settings put secure mock_location_app com.sting.virtualloc\n" +
                "  adb reboot\n\n" +
                "执行完成后，即使关闭开发者选项，VirtuaLoc 也能正常使用。"
        )
    }

    fun showEditDialog(item: QuickSelectItem) {
        _state.value = _state.value.copy(showEditDialog = true, editingItem = item)
    }

    fun hideEditDialog() {
        _state.value = _state.value.copy(showEditDialog = false, editingItem = null)
    }

    fun showAddDialog() {
        _state.value = _state.value.copy(showAddDialog = true)
    }

    fun hideAddDialog() {
        _state.value = _state.value.copy(showAddDialog = false)
    }

    fun showGpsDialog() {
        _state.value = _state.value.copy(showGpsDialog = true)
    }

    fun hideGpsDialog() {
        _state.value = _state.value.copy(showGpsDialog = false, lastSnapshot = null)
    }

    fun setGpsLoading(loading: Boolean) {
        _state.value = _state.value.copy(gpsLoading = loading)
    }

    fun updateQuickSelectItem(oldItem: QuickSelectItem, newItem: QuickSelectItem) {
        val items = _state.value.quickSelectItems.toMutableList()
        val idx = items.indexOfFirst { it.name == oldItem.name && it.lat == oldItem.lat && it.lng == oldItem.lng }
        if (idx >= 0) {
            items[idx] = newItem
            saveQuickSelectItems(items)
        }
        hideEditDialog()
    }

    fun deleteQuickSelectItem(item: QuickSelectItem) {
        val items = _state.value.quickSelectItems.filterNot {
            it.name == item.name && it.lat == item.lat && it.lng == item.lng
        }
        saveQuickSelectItems(items)
        hideEditDialog()
    }

    fun addQuickSelectItem(item: QuickSelectItem) {
        val items = _state.value.quickSelectItems + item
        saveQuickSelectItems(items)
        hideAddDialog()
    }

    fun addCurrentAsQuickSelect(name: String, snapshot: LocationSnapshot? = null) {
        val lat = _state.value.latText.toDoubleOrNull() ?: return
        val lng = _state.value.lngText.toDoubleOrNull() ?: return
        val item = QuickSelectItem(
            name = name,
            lat = lat,
            lng = lng,
            accuracy = snapshot?.accuracy ?: 0f,
            cells = snapshot?.cells ?: emptyList(),
            wifis = snapshot?.wifis ?: emptyList()
        )
        addQuickSelectItem(item)
    }

    fun setGpsCoords(lat: Double, lng: Double) {
        _state.value = _state.value.copy(
            latText = "%.6f".format(lat),
            lngText = "%.6f".format(lng),
            gpsLoading = false,
            showGpsDialog = true,
            lastSnapshot = null
        )
        saveLastCoords("%.6f".format(lat), "%.6f".format(lng))
    }

    fun setGpsError() {
        _state.value = _state.value.copy(gpsLoading = false, showGpsDialog = false)
    }

    fun setLastSnapshot(snapshot: LocationSnapshot?) {
        _state.value = _state.value.copy(lastSnapshot = snapshot)
    }

    fun setGpsCoordsJustSet() {
        _state.value = _state.value.copy(gpsCoordsJustSet = true)
    }

    fun clearGpsCoordsJustSet() {
        _state.value = _state.value.copy(gpsCoordsJustSet = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val vm = remember { MainViewModel() }
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) {
        vm.init(context)
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        vm.setHasPermission(granted)
        if (!granted) {
            vm.setStatus("需要位置权限才能使用")
        }
    }

    // Notification permission launcher (Android 13+)
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // Check permissions on launch
    LaunchedEffect(Unit) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        vm.setHasPermission(hasPerm)

        if (!hasPerm) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Check dev mode
    val mockMgr = remember { MockLocationManager(context) }
    val locationCollector = remember { LocationCollector(context) }
    // Re-check mock location when app resumes (handles user enabling it in developer settings)
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (state.hasLocationPermission) {
                    vm.setDevModeEnabled(mockMgr.isDeveloperMockEnabled())
                }
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        onDispose {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
        }
    }

    // When GPS coords are set, also collect cell + wifi snapshot
    LaunchedEffect(state.gpsCoordsJustSet, state.latText, state.lngText) {
        if (state.gpsCoordsJustSet) {
            val lat = state.latText.toDoubleOrNull() ?: return@LaunchedEffect
            val lng = state.lngText.toDoubleOrNull() ?: return@LaunchedEffect
            val snapshot = locationCollector.collectSnapshot(lat, lng, 0f)
            val cellCount = snapshot.cells.size
            val wifiCount = snapshot.wifis.size
            vm.setLastSnapshot(snapshot)
            vm.setStatus(buildString {
                append("GPS坐标已获取: %.6f, %.6f".format(lat, lng))
                if (cellCount > 0) append("，基站${cellCount}个")
                if (wifiCount > 0) append("，WiFi${wifiCount}个")
            })
            vm.clearGpsCoordsJustSet()
        }
    }

    // Get version info
    val versionInfo = remember {
        try {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pkg.versionName} (${pkg.versionCode})"
        } catch (e: Exception) {
            "未知版本"
        }
    }

    // Dialog: Developer mode instructions
    if (state.showDeveloperDialog) {
        AlertDialog(
            onDismissRequest = { vm.hideDeveloperDialog() },
            title = { Text("VirtuaLoc 帮助 (v$versionInfo)", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("步骤 1: 开启开发者选项")
                    Text("设置 → 关于手机 → 连续点击「版本号」7次 → 输入解锁密码 → 开启成功")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("步骤 2: 开启模拟位置")
                    Text("设置 → 系统 → 开发者选项 → 找到「选择模拟位置信息应用」→ 选择 VirtuaLoc")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("步骤 3: 确认权限")
                    Text("若弹出权限请求，点击「允许」即可")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.hideDeveloperDialog()
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_SETTINGS)
                        context.startActivity(intent)
                    }
                }) {
                    Text("去设置")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.hideDeveloperDialog() }) {
                    Text("知道了")
                }
            }
        )
    }

    // Dialog: 一键初始化（永久开启模拟定位）
    if (state.showInitDialog) {
        InitDialog(
            step = state.initStep,
            log = state.initLog,
            onDismiss = { vm.hideInitDialog() },
            onStart = { vm.runInitSteps() }
        )
    }

    // Dialog: Edit quick select item
    if (state.showEditDialog && state.editingItem != null) {
        EditQuickSelectDialog(
            item = state.editingItem!!,
            onDismiss = { vm.hideEditDialog() },
            onSave = { newItem -> vm.updateQuickSelectItem(state.editingItem!!, newItem) },
            onDelete = { vm.deleteQuickSelectItem(state.editingItem!!) }
        )
    }

    // Dialog: Add quick select item
    if (state.showAddDialog) {
        AddQuickSelectDialog(
            currentLat = state.latText,
            currentLng = state.lngText,
            onDismiss = { vm.hideAddDialog() },
            onAdd = { vm.addQuickSelectItem(it) }
        )
    }

    // Dialog: GPS capture name dialog (show when GPS captured OR when snapshot is ready)
    if (state.showGpsDialog || state.lastSnapshot != null) {
        GpsCaptureDialog(
            onDismiss = { vm.hideGpsDialog() },
            onSave = { name -> vm.addCurrentAsQuickSelect(name, state.lastSnapshot) },
            cellCount = state.lastSnapshot?.cells?.size ?: 0,
            wifiCount = state.lastSnapshot?.wifis?.size ?: 0
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VirtuaLoc 虚拟定位") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    TextButton(onClick = { vm.showDeveloperDialog() }) {
                        Text("帮助")
                    }

                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.isRunning)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (state.isRunning) "🟢 虚拟定位运行中" else "⚪ 已停止",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.statusMessage,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Coordinate inputs
            Text(
                text = "目标坐标",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1: Lat and Lng inputs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.latText,
                        onValueChange = {
                            vm.updateLat(it)
                            vm.saveLastCoords(it, state.lngText)
                        },
                        label = { Text("纬度 (-90~90)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.lngText,
                        onValueChange = {
                            vm.updateLng(it)
                            vm.saveLastCoords(state.latText, it)
                        },
                        label = { Text("经度 (-180~180)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        singleLine = true
                    )
                }

                // Row 2: Save and GPS buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Save button
                    Button(
                        onClick = {
                            val lat = state.latText.toDoubleOrNull()
                            val lng = state.lngText.toDoubleOrNull()
                            if (lat != null && lng != null) {
                                vm.showGpsDialog()
                            } else {
                                Toast.makeText(context, "请先输入有效坐标", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !state.isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "保存",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("保存")
                    }

                    // GPS capture button
                    FilledTonalButton(
                        onClick = {
                            if (!state.hasLocationPermission) {
                                vm.setStatus("需要位置权限")
                                return@FilledTonalButton
                            }
                            vm.setGpsLoading(true)
                            vm.setStatus("正在获取GPS坐标和周边信号...")
                            mockMgr.getCurrentLocation { lat, lng ->
                                if (lat != null && lng != null) {
                                    vm.setGpsCoords(lat, lng)
                                    vm.setGpsCoordsJustSet()
                                } else {
                                    vm.setGpsError()
                                    vm.setStatus("无法获取GPS坐标，请检查定位设置")
                                    Toast.makeText(context, "无法获取GPS坐标", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !state.isRunning && !state.gpsLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (state.gpsLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("获取中")
                        } else {
                            Icon(
                                Icons.Default.MyLocation,
                                contentDescription = "获取当前GPS",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("获取本地经纬度")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            // Quick select section
            Text(
                text = "快速选择",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            // Quick select chips row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Existing quick select items
                state.quickSelectItems.forEach { item ->
                    EditableQuickSelectChip(
                        item = item,
                        onSelect = {
                            if (!state.isRunning) {
                                vm.updateLat(item.lat.toString())
                                vm.updateLng(item.lng.toString())
                                vm.saveLastCoords(item.lat.toString(), item.lng.toString())
                            }
                        },
                        onEdit = { vm.showEditDialog(item) },
                        enabled = !state.isRunning
                    )
                }

                // Add new quick select chip
                if (!state.isRunning) {
                    FilterChip(
                        selected = false,
                        onClick = { vm.showAddDialog() },
                        label = { Text("+") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "添加",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }

            // Map preview
            Text(
                text = "地图预览",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            val mv = MapView(ctx)
                            // 使用高德地图瓦片（国内可访问）
                            mv.setTileSource(AMapTileSource())
                            mv.setMultiTouchControls(true)
                            mv.controller.setZoom(15.0)
                            val lat = state.latText.toDoubleOrNull() ?: 39.9042
                            val lng = state.lngText.toDoubleOrNull() ?: 116.4074
                            mv.controller.setCenter(GeoPoint(lat, lng))
                            val m = Marker(mv)
                            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            mv.overlays.add(m)
                            mv.tag = m
                            mv
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { map ->
                            val lat = state.latText.toDoubleOrNull()
                            val lng = state.lngText.toDoubleOrNull()
                            if (lat != null && lng != null) {
                                val point = GeoPoint(lat, lng)
                                map.controller.animateTo(point)
                                val marker = map.tag as? Marker
                                if (marker != null) {
                                    marker.position = point
                                    marker.title = "虚拟位置"
                                    marker.snippet = "%.6f, %.6f".format(lat, lng)
                                }
                                map.invalidate()
                            }
                        }
                    )
                }
            }

            // Start/Stop button
            Button(
                onClick = {
                    val lat = state.latText.toDoubleOrNull()
                    val lng = state.lngText.toDoubleOrNull()

                    if (lat == null || lng == null) {
                        vm.setStatus("请输入有效的经纬度")
                        Toast.makeText(context, "请输入有效的经纬度", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (lat < -90 || lat > 90) {
                        vm.setStatus("纬度必须在 -90 到 90 之间")
                        Toast.makeText(context, "纬度必须在 -90 到 90 之间", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (lng < -180 || lng > 180) {
                        vm.setStatus("经度必须在 -180 到 180 之间")
                        Toast.makeText(context, "经度必须在 -180 到 180 之间", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    if (!state.hasLocationPermission) {
                        vm.setStatus("需要位置权限")
                        return@Button
                    }

                    if (state.isRunning) {
                        LocationService.stop(context)
                        vm.setRunning(false)
                        vm.setStatus("已停止虚拟定位")
                    } else {
                        vm.saveLastCoords(state.latText, state.lngText)
                        LocationService.start(context, lat, lng)
                        vm.setRunning(true)
                        vm.setStatus("虚拟定位已开启: %.6f, %.6f".format(lat, lng))
                        Toast.makeText(
                            context,
                            "虚拟定位已开启",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isRunning)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (state.isRunning) "停止虚拟定位" else "开启虚拟定位",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // Footer hint
            Text(
                text = "注意：部分 App 会检测 Mock Location，请知悉。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditableQuickSelectChip(
    item: QuickSelectItem,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    enabled: Boolean
) {
    Box {
        FilterChip(
            selected = false,
            onClick = onSelect,
            label = { Text(item.name, maxLines = 1) },
            enabled = enabled
        )
        IconButton(
            onClick = onEdit,
            modifier = Modifier
                .size(24.dp)
                .align(Alignment.TopEnd),
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.primary
            ),
            enabled = enabled
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "编辑",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun EditQuickSelectDialog(
    item: QuickSelectItem,
    onDismiss: () -> Unit,
    onSave: (QuickSelectItem) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(item.name) }
    var lat by remember { mutableStateOf(item.lat.toString()) }
    var lng by remember { mutableStateOf(item.lng.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑快捷位置", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it },
                    label = { Text("纬度") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lng,
                    onValueChange = { lng = it },
                    label = { Text("经度") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newLat = lat.toDoubleOrNull() ?: return@TextButton
                    val newLng = lng.toDoubleOrNull() ?: return@TextButton
                    onSave(QuickSelectItem(name.ifBlank { "未命名" }, newLat, newLng))
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除")
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

@Composable
fun AddQuickSelectDialog(
    currentLat: String,
    currentLng: String,
    onDismiss: () -> Unit,
    onAdd: (QuickSelectItem) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf(currentLat) }
    var lng by remember { mutableStateOf(currentLng) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加快捷位置", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如: 公司、家") }
                )
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it },
                    label = { Text("纬度") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lng,
                    onValueChange = { lng = it },
                    label = { Text("经度") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newLat = lat.toDoubleOrNull() ?: return@TextButton
                    val newLng = lng.toDoubleOrNull() ?: return@TextButton
                    onAdd(QuickSelectItem(name.ifBlank { "未命名" }, newLat, newLng))
                }
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun InitDialog(
    step: Int,
    log: String,
    onDismiss: () -> Unit,
    onStart: () -> Unit
) {
    val stepLabels = mapOf(
        0 to "",
        1 to "➊ 授予模拟定位权限",
        2 to "➋ 关闭开发者选项",
        3 to "➌ 设置模拟定位应用",
        4 to "➍ 重启手机中...",
        5 to "✓ 完成",
        99 to "✗ 出错"
    )

    AlertDialog(
        onDismissRequest = {
            if (step == 0 || step == 5 || step == 99) onDismiss()
        },
        title = { Text("永久开启模拟定位", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step == 0) {
                    Text("本功能将自动完成以下步骤：")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("① 授予 VirtuaLoc 模拟定位权限")
                    Text("② 关闭开发者选项")
                    Text("③ 将 VirtuaLoc 设为默认模拟定位应用")
                    Text("④ 重启手机")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("执行完成后，即使关闭开发者选项，VirtuaLoc 也能正常使用。", color = MaterialTheme.colorScheme.outline)
                } else {
                    Text(stepLabels[step] ?: "", fontWeight = FontWeight.Bold)
                    if (log.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(log, style = MaterialTheme.typography.bodySmall)
                    }
                    if (step == 4) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("手机即将重启，请稍候...", color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                0 -> TextButton(onClick = onStart) { Text("开始执行") }
                5, 99 -> TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (step == 0) TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun GpsCaptureDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    cellCount: Int = 0,
    wifiCount: Int = 0
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存当前位置", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GPS坐标已获取，请输入名称保存为快捷位置")
                if (cellCount > 0 || wifiCount > 0) {
                    Text(
                        text = buildString {
                            if (cellCount > 0) append("📡 基站: $cellCount 个")
                            if (cellCount > 0 && wifiCount > 0) append("  ")
                            if (wifiCount > 0) append("📶 WiFi: $wifiCount 个")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如: 当前所在位置") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(name.ifBlank { "当前位置" })
                    onDismiss()
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("跳过")
            }
        }
    )
}

@Composable
fun VirtuaLocTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = MaterialTheme.colorScheme.primary,
            primaryContainer = MaterialTheme.colorScheme.primaryContainer,
            errorContainer = MaterialTheme.colorScheme.errorContainer
        ),
        content = content
    )
}
