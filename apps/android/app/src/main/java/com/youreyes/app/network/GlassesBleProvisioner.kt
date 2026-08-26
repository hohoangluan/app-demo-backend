package com.youreyes.app.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Cấp Wi-Fi cho kính qua BLE — đường CỨU HỘ khi kính không vào được mạng nào.
 *
 * Vì sao cần đường thứ ba, khi màn hình đã có hai đường
 * ---------------------------------------------------
 * Hai đường cũ đều có một điều kiện mà đúng lúc hỏng thì không thoả:
 *
 *   qua máy chủ  cần kính ĐANG online — nhưng ca cần cấp mạng nhất là ca
 *                kính đã mất mạng
 *   qua SoftAP   cần người dùng RỜI mạng của họ để nối vào `VisionCare-Setup`,
 *                tức mất Internet giữa chừng, và với người khiếm thị thì thao
 *                tác "vào Cài đặt → Wi-Fi → chọn mạng lạ" là chỗ bỏ cuộc
 *
 * BLE không đòi cái nào: điện thoại giữ nguyên mạng của nó, kính không cần
 * mạng nào cả.
 *
 * Kính chỉ quảng bá BLE khi KHÔNG vào được mạng
 * --------------------------------------------
 * Đang online mà quét thì không thấy gì — đó là hành vi ĐÚNG, không phải hỏng.
 * NimBLE tốn 40-60 KB RAM liền, còn khối liền trên board sau khi phiên TLS mở
 * chỉ còn 31 732 B (đo 2026-08-26). Hai bên không bao giờ được sống cùng lúc.
 *
 * Và kính phải trượt hết `WIFI_CONNECT_TRIES` (3 lần × 30 s) rồi mới bật BLE.
 * Quét sớm hơn là không thấy. [SCAN_TIMEOUT_MS] để rộng chính vì vậy.
 */
class GlassesBleProvisioner(private val context: Context) {

    /**
     * 🔴 Lọc theo UUID DỊCH VỤ, KHÔNG theo tên.
     *
     * Bản đầu của tool laptop lọc theo tên và nó hỏng im lặng: hệ điều hành trả
     * `name = null` cho mọi thiết bị chưa ghép đôi, nên chiếc kính đang quảng
     * bá ngay bên cạnh vẫn bị bỏ qua — nhìn từ ngoài giống hệt "kính không bật
     * BLE". Android cũng vậy: `ScanResult.device.name` thường null cho tới khi
     * đọc được scan response.
     *
     * Tên vẫn dùng được để HIỂN THỊ, lấy từ `scanRecord.deviceName`, nhưng
     * không bao giờ dùng để lọc.
     */
    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Rộng vì kính mất 3 × 30 s thử Wi-Fi trước khi bật BLE. Người dùng
         * thường mở màn hình này NGAY khi thấy kính im, tức trước lúc đó.
         */
        const val SCAN_TIMEOUT_MS = 40_000L
        const val GATT_TIMEOUT_MS = 20_000L

        /**
         * 🔴 PHẢI xin MTU trước khi ghi. Firmware đọc `chr->getValue()` MỘT LẦN
         * và không ghép mảnh (`RxCallbacks::onWrite()` trong ble_provision.cpp).
         *
         * MTU mặc định 23 ⟹ thân ghi được 20 byte. Tin nhắn thật:
         *
         *     {"ssid":"Redmi Note 14","password":"29102006"}   = 46 byte
         *
         * Không nâng thì Android cắt thành nhiều gói, kính nhận mảnh đầu
         * (`{"ssid":"Redmi No`) và trả `MISSING_SSID` cho một lệnh hoàn toàn
         * đúng. Tool laptop không dính vì `bleak` tự thương lượng MTU giúp.
         */
        const val WANT_MTU = 247
    }

    sealed interface Result {
        /** Kính đã lưu và đang khởi động lại vào mạng mới. [reply] là câu kính nói. */
        data class Saved(val reply: String) : Result

        /** Không thấy kính nào quảng bá. Gần như luôn là "kính đang online". */
        data object NotFound : Result

        /** Thiếu quyền Bluetooth. [missing] là danh sách để màn hình đi xin. */
        data class MissingPermissions(val missing: List<String>) : Result

        /** Bluetooth tắt, hoặc máy không có BLE. */
        data class Unavailable(val reason: String) : Result

        data class Failed(val reason: String) : Result
    }

    /** Quyền cần có, khác nhau theo bản Android. */
    fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: hai quyền Bluetooth runtime riêng. KHÔNG cần vị trí
            // nếu khai `neverForLocation` trong manifest — và ta có khai, vì
            // quét ở đây chỉ để tìm một thiết bị của chính người dùng.
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 6-11: quét BLE bị coi là suy ra được vị trí, nên bắt buộc
            // có quyền vị trí. Thiếu nó thì `startScan()` chạy và **không bao
            // giờ trả kết quả nào** — không lỗi, không callback. Đây là chỗ
            // gây hiểu nhầm "kính không bật BLE" nhiều nhất trên máy cũ.
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missingPermissions(): List<String> = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * Tìm kính, ghi SSID/mật khẩu, đợi kính xác nhận.
     *
     * Hàm treo (suspend), gọi từ `Dispatchers.IO`. Tự dọn: mọi đường thoát đều
     * đóng GATT và dừng quét.
     */
    @SuppressLint("MissingPermission") // đã kiểm bằng missingPermissions() ở đầu hàm
    suspend fun provision(ssid: String, password: String): Result {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) return Result.MissingPermissions(missing)

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return Result.Unavailable("Máy không có Bluetooth.")
        val adapter: BluetoothAdapter = manager.adapter
            ?: return Result.Unavailable("Máy không có Bluetooth.")
        if (!adapter.isEnabled) return Result.Unavailable("Bluetooth đang tắt. Bật lên rồi thử lại.")
        val scanner = adapter.bluetoothLeScanner
            ?: return Result.Unavailable("Bluetooth đang tắt. Bật lên rồi thử lại.")

        val device = try {
            withTimeout(SCAN_TIMEOUT_MS) { scanForGlasses(scanner) }
        } catch (_: TimeoutCancellationException) {
            null
        } ?: return Result.NotFound

        return try {
            withTimeout(GATT_TIMEOUT_MS) { writeConfig(device, ssid, password) }
        } catch (_: TimeoutCancellationException) {
            // Hết giờ ở đây KHÔNG có nghĩa là thất bại — xem ghi chú ở
            // [writeConfig]. Nói đúng thứ ta biết, đừng khẳng định thứ ta không
            // biết.
            Result.Failed(
                "Kính không trả lời kịp. Kiểm tra xem kính đã vào mạng " +
                    "\"$ssid\" chưa trước khi thử lại."
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanForGlasses(scanner: android.bluetooth.le.BluetoothLeScanner):
        BluetoothDevice = suspendCancellableCoroutine { cont ->
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(NUS_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!cont.isActive) return
                scanner.stopScan(this)
                cont.resume(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                if (cont.isActive) cont.cancel(IllegalStateException("Quét BLE lỗi $errorCode"))
            }
        }

        scanner.startScan(listOf(filter), settings, callback)
        cont.invokeOnCancellation { runCatching { scanner.stopScan(callback) } }
    }

    /**
     * Nối GATT, nâng MTU, ghi JSON, đợi kính notify.
     *
     * 🔴 Kính notify RỒI mới ngắt, và nó CỐ Ý chờ [BLE_APPLY_GRACE_MS] trước
     * khi khởi động lại (600 ms bên firmware). Lý do ghi thẳng trong
     * `ble_provision.cpp`: gọi `BLEDevice::deinit()` ngay trong `onWrite()` là
     * giết ngăn xếp TRƯỚC khi nó kịp gửi write-response — bên này sẽ thấy LỖI
     * cho một việc đã THÀNH CÔNG, người dùng bấm lại, trong khi kính đã khởi
     * động lại và không còn quảng bá nữa.
     *
     * Vì vậy: không có notify KHÔNG kết luận là hỏng. Câu báo phải nói "kiểm
     * tra xem kính đã vào mạng chưa", đừng nói "thất bại".
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun writeConfig(
        device: BluetoothDevice,
        ssid: String,
        password: String,
    ): Result = suspendCancellableCoroutine { cont ->
        val body = JSONObject().put("ssid", ssid).put("password", password)
            .toString().toByteArray(Charsets.UTF_8)

        var gatt: BluetoothGatt? = null

        fun finish(result: Result) {
            if (!cont.isActive) return
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            cont.resume(result)
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothDevice.BOND_NONE) return
                when (newState) {
                    android.bluetooth.BluetoothProfile.STATE_CONNECTED -> {
                        // MTU TRƯỚC discoverServices(): đổi MTU sau khi đã tìm
                        // service làm một số ngăn xếp Android huỷ cache service.
                        if (!g.requestMtu(WANT_MTU)) g.discoverServices()
                    }
                    android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> {
                        finish(Result.Failed("Kính ngắt kết nối giữa chừng."))
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                // Không kiểm `status`: MTU nhỏ hơn xin vẫn chạy được miễn đủ
                // chỗ, và ta kiểm chỗ THẬT ở onServicesDiscovered.
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(NUS_SERVICE)
                    ?: return finish(Result.Failed("Kính không có dịch vụ cấu hình."))
                val tx = svc.getCharacteristic(NUS_TX)
                val rx = svc.getCharacteristic(NUS_RX)
                    ?: return finish(Result.Failed("Kính không có kênh ghi cấu hình."))

                // Bật notify TRƯỚC khi ghi, nếu không câu trả lời của kính tới
                // trước lúc ta đăng ký và biến mất.
                if (tx != null) {
                    g.setCharacteristicNotification(tx, true)
                    tx.getDescriptor(CCCD)?.let { d ->
                        writeCccd(g, d)
                        // Ghi xong CCCD mới ghi cấu hình — onDescriptorWrite.
                        return
                    }
                }
                writeBody(g, rx)
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: android.bluetooth.BluetoothGattDescriptor,
                status: Int,
            ) {
                val rx = g.getService(NUS_SERVICE)?.getCharacteristic(NUS_RX)
                    ?: return finish(Result.Failed("Kính không có kênh ghi cấu hình."))
                writeBody(g, rx)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(Result.Failed("Ghi cấu hình lên kính không thành công."))
                }
                // Thành công thì KHÔNG kết thúc ở đây — đợi câu kính notify.
                // Hết giờ sẽ do withTimeout() ở [provision] lo.
            }

            // API 33+
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                finish(Result.Saved(String(value, Charsets.UTF_8)))
            }

            // API < 33
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val v = characteristic.value ?: ByteArray(0)
                finish(Result.Saved(String(v, Charsets.UTF_8)))
            }

            @Suppress("DEPRECATION")
            private fun writeCccd(
                g: BluetoothGatt,
                d: android.bluetooth.BluetoothGattDescriptor,
            ) {
                val on = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(d, on)
                } else {
                    d.value = on
                    g.writeDescriptor(d)
                }
            }

            @Suppress("DEPRECATION")
            private fun writeBody(g: BluetoothGatt, rx: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(
                        rx, body, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                } else {
                    // WRITE_TYPE_DEFAULT (có response), KHÔNG phải NO_RESPONSE:
                    // firmware dựa vào write-response để biết lúc nào được phép
                    // khởi động lại.
                    rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    rx.value = body
                    g.writeCharacteristic(rx)
                }
            }
        }

        // `autoConnect=false`: nối NGAY và hết giờ nhanh. `true` xếp hàng chờ
        // vô hạn cho tới khi thiết bị xuất hiện — sai hẳn ở đây, vì kính có thể
        // đã khởi động lại và không bao giờ quảng bá lại nữa.
        //
        // 🔴 Bản API 31+ có thêm tham số `transport`; bản ba tham số bị đánh
        // dấu deprecated nhưng vẫn là bản DUY NHẤT chạy từ minSdk 24. Giữ.
        val opened = device.connectGatt(context, /*autoConnect=*/false, callback)
        if (opened == null) {
            cont.resume(Result.Failed("Không mở được kết nối tới kính."))
            return@suspendCancellableCoroutine
        }
        gatt = opened
        cont.invokeOnCancellation {
            runCatching { opened.disconnect() }
            runCatching { opened.close() }
        }
    }
}
