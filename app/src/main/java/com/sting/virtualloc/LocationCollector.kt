package com.sting.virtualloc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 位置快照数据：包含 GPS 坐标 + 基站信息 + WiFi 信息。
 * 采集时同时获取三者，模拟时一起使用。
 */
data class LocationSnapshot(
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val timestamp: Long,
    val cells: List<CellInfoData>,
    val wifis: List<WifiInfoData>
)

data class CellInfoData(
    val cid: Int,       // Cell ID
    val lac: Int,       // Location Area Code (GSM/LTE) / NAC (CDMA)
    val mcc: Int,       // Mobile Country Code
    val mnc: Int,       // Mobile Network Code
    val pci: Int,       // Physical Cell ID (LTE)
    val rsrp: Int,      // Signal strength (LTE, dBm)
    val type: String    // GSM / CDMA / LTE / NR
)

data class WifiInfoData(
    val bssid: String,  // MAC 地址
    val ssid: String,   // 网络名称
    val rssi: Int,      // 信号强度 (dBm)
    val frequency: Int  // 频率 (MHz)
)

/**
 * 采集器：获取 GPS 坐标、基站信息、WiFi 信息。
 * 需要的权限：
 * - ACCESS_FINE_LOCATION（GPS + 基站 + WiFi）
 * - READ_PHONE_STATE（基站信息，Android 10+ 可能不需要）
 * - ACCESS_WIFI_STATE / CHANGE_WIFI_STATE（WiFi 扫描）
 * - CHANGE_NETWORK_STATE（获取网络连接状态）
 */
class LocationCollector(private val context: Context) {

    private val tm: TelephonyManager by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    private val wm: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val cm: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    /**
     * 检查是否有定位权限（GPS + 网络定位）。
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 采集当前位置的完整快照（GPS + 基站 + WiFi）。
     * 返回 null 如果无法获取 GPS 坐标。
     */
    @SuppressLint("MissingPermission")
    suspend fun collectSnapshot(lat: Double, lng: Double, accuracy: Float): LocationSnapshot {
        return withContext(Dispatchers.IO) {
            LocationSnapshot(
                lat = lat,
                lng = lng,
                accuracy = accuracy,
                timestamp = System.currentTimeMillis(),
                cells = collectCellInfo(),
                wifis = collectWifiInfo()
            )
        }
    }

    /**
     * 采集当前可见的基站信息。
     */
    @SuppressLint("MissingPermission")
    private fun collectCellInfo(): List<CellInfoData> {
        if (!hasLocationPermission()) return emptyList()

        return try {
            val cellInfoList = tm.allCellInfo ?: return emptyList()
            cellInfoList.mapNotNull { cell -> cellToData(cell) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    private fun cellToData(cell: CellInfo): CellInfoData? {
        if (!cell.isRegistered) return null

        return try {
            when (cell) {
                is CellInfoGsm -> {
                    val ci = cell.cellIdentity
                    val cs = cell.cellSignalStrength
                    CellInfoData(
                        cid = ci.cid,
                        lac = ci.lac,
                        mcc = ci.mcc,
                        mnc = ci.mnc,
                        pci = -1,
                        rsrp = cs.dbm,
                        type = "GSM"
                    )
                }
                is CellInfoCdma -> {
                    val ci = cell.cellIdentity
                    val cs = cell.cellSignalStrength
                    CellInfoData(
                        cid = ci.basestationId,
                        lac = ci.networkId,
                        mcc = ci.systemId / 100,
                        mnc = ci.systemId % 100,
                        pci = -1,
                        rsrp = cs.dbm,
                        type = "CDMA"
                    )
                }
                is CellInfoWcdma -> {
                    val ci = cell.cellIdentity
                    val cs = cell.cellSignalStrength
                    CellInfoData(
                        cid = ci.cid,
                        lac = ci.lac,
                        mcc = ci.mcc,
                        mnc = ci.mnc,
                        pci = -1,
                        rsrp = cs.dbm,
                        type = "WCDMA"
                    )
                }
                is CellInfoLte -> {
                    val ci = cell.cellIdentity
                    val cs = cell.cellSignalStrength
                    CellInfoData(
                        cid = ci.ci,
                        lac = ci.tac,
                        mcc = ci.mcc,
                        mnc = ci.mnc,
                        pci = ci.pci,
                        rsrp = cs.dbm,
                        type = "LTE"
                    )
                }
                else -> {
                    // Android 11+ NR (5G) - CellInfoNr
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val nrCell = cell.javaClass.getDeclaredMethod("getCellIdentity").invoke(cell)
                            val nrSignal = cell.javaClass.getDeclaredMethod("getCellSignalStrength").invoke(cell)
                            if (nrCell != null && nrSignal != null) {
                                val ciClass = nrCell.javaClass
                                val csClass = nrSignal.javaClass
                                CellInfoData(
                                    cid = ciClass.getDeclaredMethod("getNci").invoke(nrCell) as? Int ?: -1,
                                    lac = ciClass.getDeclaredMethod("getTac").invoke(nrCell) as? Int ?: -1,
                                    mcc = ciClass.getDeclaredMethod("getMccString").invoke(nrCell)?.toString()?.toIntOrNull() ?: 0,
                                    mnc = ciClass.getDeclaredMethod("getMncString").invoke(nrCell)?.toString()?.toIntOrNull() ?: 0,
                                    pci = ciClass.getDeclaredMethod("get pci").invoke(nrCell) as? Int ?: -1,
                                    rsrp = (csClass.getDeclaredMethod("getDbm").invoke(nrSignal) as? Int) ?: -1,
                                    type = "NR"
                                )
                            } else null
                        } catch (_: Exception) {
                            null
                        }
                    } else null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 采集当前可见的 WiFi 热点信息。
     */
    @SuppressLint("MissingPermission")
    private fun collectWifiInfo(): List<WifiInfoData> {
        if (!hasLocationPermission()) return emptyList()

        // 需要打开 WiFi 扫描
        val scanEnabled = wm.isWifiEnabled || tryToggleWifi(true)
        if (!scanEnabled) return emptyList()

        return try {
            // 触发一次 WiFi 扫描
            val started = wm.startScan()
            if (!started) {
                // 如果扫描启动失败，尝试读取缓存结果
                return readCachedWifiResults()
            }
            // 读取扫描结果
            val results = wm.scanResults ?: emptyList()
            results.map { it.toWifiInfoData() }
        } catch (_: Exception) {
            readCachedWifiResults()
        }
    }

    @SuppressLint("MissingPermission")
    private fun readCachedWifiResults(): List<WifiInfoData> {
        return try {
            (wm.scanResults ?: emptyList()).map { it.toWifiInfoData() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryToggleWifi(enable: Boolean): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 不能直接开关 WiFi，只能提示用户
                false
            } else {
                @Suppress("DEPRECATION")
                wm.isWifiEnabled = enable
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun ScanResult.toWifiInfoData(): WifiInfoData {
        return WifiInfoData(
            bssid = BSSID ?: "00:00:00:00:00:00",
            ssid = (SSID?.takeIf { it.isNotBlank() }) ?: "<hidden>",
            rssi = level,
            frequency = frequency
        )
    }
}
