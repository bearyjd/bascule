package com.ventouxlabs.hwprobe

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "HWPROBE"
private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private val CURRENT_TIME_CHAR_UUID = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")
private val USER_CONTROL_POINT_UUID = UUID.fromString("00002a9f-0000-1000-8000-00805f9b34fb")

// Bluetooth SIG User Data Service, User Control Point op codes (GATT Service spec).
// Provenance: Bluetooth SIG "User Data Service" spec (public), cross-checked against
// openScale's StandardWeightProfileHandler.kt (GPL-3.0) op-code constants — reimplemented
// here from protocol understanding, no source copied.
private const val UDS_CP_REGISTER_NEW_USER = 0x01
private const val UDS_CP_CONSENT = 0x02
private const val UDS_CP_DELETE_USER_DATA = 0x03 // UDS: deletes the *currently consented* user only
private const val UDS_CP_LIST_ALL_USERS = 0x04
private const val UDS_CP_RESPONSE = 0x20

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var deviceList: LinearLayout
    private var gatt: BluetoothGatt? = null
    private lateinit var logFile: File
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val seenDevices = LinkedHashMap<String, BluetoothDevice>()
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()

    // dumpprop: the 0xFFFF / 0xFF00 proprietary services. Descriptors first
    // (0x2901 carries a human-readable name), then every readable value.
    private val propDescQueue = ArrayDeque<BluetoothGattDescriptor>()
    private val propReadQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var propDumpActive = false
    private var connectionBusy = false
    private var activeButton: Button? = null
    private var gotRealMeasurement = false
    private var setupComplete = false
    private var lastRegisteredConsent = 1234
    private val measurementCharacteristics = setOf(
        UUID.fromString("00002a9d-0000-1000-8000-00805f9b34fb"), // Weight Measurement
        UUID.fromString("00002a9c-0000-1000-8000-00805f9b34fb"), // Body Composition Measurement
        UUID.fromString("00000001-0000-1000-8000-00805f9b34fb"), // Beurer custom notify
        UUID.fromString("00000006-0000-1000-8000-00805f9b34fb"), // Beurer custom notify
    )
    private val interestingReads = setOf(
        UUID.fromString("00002a9a-0000-1000-8000-00805f9b34fb"), // User Index
        UUID.fromString("00002a9e-0000-1000-8000-00805f9b34fb"), // Weight Scale Feature
        UUID.fromString("00002a9b-0000-1000-8000-00805f9b34fb"), // Body Composition Feature
        UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"), // Battery Level
        UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"), // Device Name
    )

    private val permissions = arrayOf(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.logView)
        deviceList = findViewById(R.id.deviceList)
        logFile = File(getExternalFilesDir(null), "capture.txt")

        findViewById<Button>(R.id.scanButton).setOnClickListener { requestPermissionsThenScan() }
        findViewById<Button>(R.id.clearButton).setOnClickListener { resetAll() }
        findViewById<Button>(R.id.syncTimeButton).setOnClickListener { syncTime() }
        findViewById<Button>(R.id.listUsersButton).setOnClickListener { writeUserControlPoint(byteArrayOf(UDS_CP_LIST_ALL_USERS.toByte()), "LIST_ALL_USERS") }
        findViewById<Button>(R.id.registerUserButton).setOnClickListener {
            lastRegisteredConsent = 1234
            val payload = byteArrayOf(
                UDS_CP_REGISTER_NEW_USER.toByte(),
                (lastRegisteredConsent and 0xFF).toByte(),
                ((lastRegisteredConsent shr 8) and 0xFF).toByte(),
            )
            writeUserControlPoint(payload, "REGISTER_NEW_USER consent=$lastRegisteredConsent")
        }

        registerReceiver(cmdReceiver, IntentFilter("com.ventouxlabs.hwprobe.CMD"), Context.RECEIVER_EXPORTED)

        appendLog("=== HW Probe started. Log file: ${logFile.absolutePath} ===")
        ActivityCompat.requestPermissions(this, permissions, 1)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(cmdReceiver)
    }

    // Remote-control channel so this can be driven via `adb shell am broadcast` without
    // touching the screen — the only thing that genuinely needs a human is stepping on the scale.
    // adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd scan
    // adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd connect --es addr E7:DB:51:F1:36:91
    // adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd synctime
    // adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd listusers
    // adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd register --ei consent 1234
    // adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd consent --ei idx 2 --ei consent 1234
    // adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd deleteuser   (consent first; deletes that user)
    // adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd dumpprop
    // adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd writeprop --es uuid 0005 --es hex 01
    // adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd readprop --es uuid 0004
    // adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd reset
    @SuppressLint("MissingPermission")
    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cmd = intent.getStringExtra("cmd") ?: return
            appendLog(">>> remote cmd: $cmd")
            when (cmd) {
                "scan" -> requestPermissionsThenScan()
                "connect" -> {
                    val addr = intent.getStringExtra("addr") ?: return
                    val adapter = getSystemService(BluetoothManager::class.java).adapter
                    val device = seenDevices[addr] ?: adapter?.getRemoteDevice(addr)
                    if (device == null) {
                        appendLog("connect: no device for $addr")
                    } else {
                        connectTo(device, null)
                    }
                }
                "synctime" -> syncTime()
                "listusers" -> writeUserControlPoint(byteArrayOf(UDS_CP_LIST_ALL_USERS.toByte()), "LIST_ALL_USERS")
                // Destructive: frees the scale slot of whichever user this session last
                // consented as. Consent first, deliberately, so the target is explicit.
                "deleteuser" -> writeUserControlPoint(byteArrayOf(UDS_CP_DELETE_USER_DATA.toByte()), "DELETE_USER_DATA (consented user)")
                "register" -> {
                    lastRegisteredConsent = intent.getIntExtra("consent", 1234)
                    val payload = byteArrayOf(
                        UDS_CP_REGISTER_NEW_USER.toByte(),
                        (lastRegisteredConsent and 0xFF).toByte(),
                        ((lastRegisteredConsent shr 8) and 0xFF).toByte(),
                    )
                    writeUserControlPoint(payload, "REGISTER_NEW_USER consent=$lastRegisteredConsent")
                }
                "consent" -> {
                    val idx = intent.getIntExtra("idx", -1)
                    val code = intent.getIntExtra("consent", -1)
                    if (idx >= 0 && code >= 0) sendConsent(idx, code)
                }
                "dumpprop" -> dumpProprietary()
                "writeprop" -> {
                    val uuid = intent.getStringExtra("uuid")
                    val hex = intent.getStringExtra("hex")
                    if (uuid != null && hex != null) writeProprietary(uuid, hex) else appendLog("writeprop: need --es uuid and --es hex")
                }
                "readprop" -> {
                    val uuid = intent.getStringExtra("uuid")
                    if (uuid != null) readProprietary(uuid) else appendLog("readprop: need --es uuid")
                }
                "reset" -> resetAll()
                else -> appendLog("unknown remote cmd: $cmd")
            }
        }
    }

    /**
     * Finds one characteristic in the proprietary 0xFFFF / 0xFF00 services by its
     * 16-bit short form (`0005`, `ff01`). Only those two services are searched so
     * a typo cannot land on a SIG characteristic.
     */
    private fun findProprietary(shortUuid: String): BluetoothGattCharacteristic? {
        val wanted = "0000" + shortUuid.lowercase().padStart(4, '0')
        return gatt?.services
            ?.filter { it.uuid.toString().startsWith("0000ffff") || it.uuid.toString().startsWith("0000ff00") }
            ?.flatMap { it.characteristics }
            ?.firstOrNull { it.uuid.toString().startsWith(wanted) }
    }

    /**
     * Writes raw bytes to a proprietary characteristic. This is the one command
     * that can change scale state, so the log line names exactly what went where.
     * Whatever the scale pushes back arrives on the normal NOTIFY path — the
     * proprietary notify channels (0x0001, 0x0006) are subscribed on connect.
     */
    @SuppressLint("MissingPermission")
    private fun writeProprietary(shortUuid: String, hex: String) {
        val g = gatt ?: run { appendLog("writeprop: not connected"); return }
        val ch = findProprietary(shortUuid) ?: run { appendLog("writeprop: no proprietary characteristic $shortUuid"); return }
        val digits = hex.replace(" ", "")
        // An odd digit count does not crash: the trailing lone nibble parses as
        // its own byte and a different payload goes on the wire. Refuse it.
        if (digits.isEmpty() || digits.length % 2 != 0 || !digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            appendLog("writeprop: --es hex must be an even number of hex digits, got \"$hex\"")
            return
        }
        val bytes = digits.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        appendLog("→ writeprop ${ch.uuid} bytes=${bytes.toHex()} type=$type")
        val result = g.writeCharacteristic(ch, bytes, type)
        appendLog("writeprop ${ch.uuid}: writeCharacteristic call result=$result")
    }

    /** Set for one read so its completion does not fall into the post-connect read queue, whose empty branch writes Current Time. */
    private var standaloneReadActive = false

    @SuppressLint("MissingPermission")
    private fun readProprietary(shortUuid: String) {
        val g = gatt ?: run { appendLog("readprop: not connected"); return }
        val ch = findProprietary(shortUuid) ?: run { appendLog("readprop: no proprietary characteristic $shortUuid"); return }
        appendLog("→ readprop ${ch.uuid}")
        val result = g.readCharacteristic(ch)
        appendLog("readprop ${ch.uuid}: readCharacteristic call result=$result")
        // Only once the stack accepted it: a rejected read (another op in
        // flight) never calls back, and a flag left set would swallow the next
        // legitimate completion and silently truncate a read chain.
        standaloneReadActive = result
    }

    /**
     * Reads what the proprietary services will volunteer without being written
     * to: the 0x2901 Characteristic User Description of every characteristic in
     * 0xFFFF and 0xFF00, then every readable value. Strictly read-only — no
     * writes, so nothing here can change scale state or burn a user slot.
     */
    @SuppressLint("MissingPermission")
    private fun dumpProprietary() {
        val g = gatt
        if (g == null) {
            appendLog("dumpprop: not connected")
            return
        }
        propDescQueue.clear()
        propReadQueue.clear()
        for (svc in g.services) {
            val name = svc.uuid.toString()
            if (!name.startsWith("0000ffff") && !name.startsWith("0000ff00")) continue
            for (ch in svc.characteristics) {
                ch.descriptors
                    .firstOrNull { it.uuid.toString().startsWith("00002901") }
                    ?.let { propDescQueue.addLast(it) }
                if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    propReadQueue.addLast(ch)
                }
            }
        }
        appendLog(
            "=== dumpprop: ${propDescQueue.size} user-description descriptors, " +
                "${propReadQueue.size} readable characteristics ===",
        )
        propDumpActive = true
        drainProp(g)
    }

    @SuppressLint("MissingPermission")
    private fun drainProp(g: BluetoothGatt) {
        val desc = propDescQueue.removeFirstOrNull()
        if (desc != null) {
            g.readDescriptor(desc)
            return
        }
        val ch = propReadQueue.removeFirstOrNull()
        if (ch != null) {
            g.readCharacteristic(ch)
            return
        }
        propDumpActive = false
        appendLog("=== dumpprop complete ===")
    }

    @SuppressLint("MissingPermission")
    private fun resetAll() {
        logView.text = ""
        logFile.writeText("")
        gatt?.close()
        gatt = null
        connectionBusy = false
        gotRealMeasurement = false
        standaloneReadActive = false
        activeButton?.setBackgroundColor(Color.LTGRAY)
        activeButton = null
        for (i in 0 until deviceList.childCount) {
            deviceList.getChildAt(i).isEnabled = true
        }
        appendLog("=== reset: log cleared, connection closed ===")
    }

    @SuppressLint("MissingPermission")
    private fun syncTime() {
        val g = gatt
        if (g == null) {
            appendLog("Sync Time: not connected.")
            return
        }
        val ch = g.services?.flatMap { it.characteristics }
            ?.firstOrNull { it.uuid == CURRENT_TIME_CHAR_UUID }
        if (ch == null) {
            appendLog("Sync Time: Current Time characteristic (2a2b) not found on this device.")
            return
        }
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        // Calendar.DAY_OF_WEEK: Sunday=1..Saturday=7. BLE Day of Week: Monday=1..Sunday=7, 0=unknown.
        val cwd = cal.get(Calendar.DAY_OF_WEEK)
        val bleDayOfWeek = if (cwd == Calendar.SUNDAY) 7 else cwd - 1
        val payload = byteArrayOf(
            (year and 0xFF).toByte(),
            ((year shr 8) and 0xFF).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            cal.get(Calendar.DAY_OF_MONTH).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.SECOND).toByte(),
            bleDayOfWeek.toByte(),
            0, // Fractions256
            0, // Adjust Reason: manual time update
        )
        appendLog("Sync Time: writing ${payload.toHex()} to 2a2b")
        val result = g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        appendLog("Sync Time: writeCharacteristic call result=$result")
    }

    @SuppressLint("MissingPermission")
    private fun sendConsent(scaleIndex: Int, consent: Int) {
        val payload = byteArrayOf(
            UDS_CP_CONSENT.toByte(),
            (scaleIndex and 0xFF).toByte(),
            (consent and 0xFF).toByte(),
            ((consent shr 8) and 0xFF).toByte(),
        )
        writeUserControlPoint(payload, "CONSENT idx=$scaleIndex consent=$consent")
    }

    @SuppressLint("MissingPermission")
    private fun writeUserControlPoint(payload: ByteArray, label: String) {
        val g = gatt
        if (g == null) {
            appendLog("$label: not connected.")
            return
        }
        val ch = g.services?.flatMap { it.characteristics }
            ?.firstOrNull { it.uuid == USER_CONTROL_POINT_UUID }
        if (ch == null) {
            appendLog("$label: User Control Point characteristic (2a9f) not found.")
            return
        }
        appendLog("→ UCP $label bytes=${payload.toHex()}")
        val result = g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        appendLog("$label: writeCharacteristic call result=$result")
    }

    private fun requestPermissionsThenScan() {
        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions, 1)
            appendLog("Permissions not granted yet — tap Scan again after granting.")
            return
        }
        startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val btManager = getSystemService(BluetoothManager::class.java)
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            appendLog("Bluetooth adapter not available/enabled.")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        seenDevices.clear()
        deviceList.removeAllViews()
        appendLog("--- scan start ---")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val addr = device.address
                if (seenDevices.containsKey(addr)) return
                seenDevices[addr] = device
                val name = device.name ?: result.scanRecord?.deviceName ?: "(no name)"
                val uuids = result.scanRecord?.serviceUuids?.joinToString(", ") { it.toString() } ?: "(none)"
                val manuf = result.scanRecord?.manufacturerSpecificData
                val manufStr = if (manuf != null && manuf.size() > 0) {
                    (0 until manuf.size()).joinToString(", ") { i ->
                        val key = manuf.keyAt(i)
                        val bytes = manuf.valueAt(i)
                        "id=0x${key.toString(16)} bytes=${bytes.toHex()}"
                    }
                } else "(none)"
                appendLog("FOUND name=\"$name\" addr=$addr rssi=${result.rssi} serviceUuids=$uuids manufacturer=$manufStr")
                addDeviceButton(device, name)
            }

            override fun onScanFailed(errorCode: Int) {
                appendLog("SCAN FAILED code=$errorCode")
            }
        }

        scanner.startScan(null, settings, callback)
        Handler(Looper.getMainLooper()).postDelayed({
            scanner.stopScan(callback)
            appendLog("--- scan stop (${seenDevices.size} devices) ---")
        }, 15000)
    }

    @SuppressLint("MissingPermission")
    private fun addDeviceButton(device: BluetoothDevice, name: String) {
        val btn = Button(this)
        btn.text = "Connect: $name (${device.address})"
        btn.gravity = Gravity.START
        btn.setBackgroundColor(Color.LTGRAY)
        btn.setOnClickListener { connectTo(device, btn) }
        deviceList.addView(btn)
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice, btn: Button?) {
        if (connectionBusy) {
            appendLog("Ignoring connect to ${device.address} — a connection is already in progress or active. Reset first.")
            return
        }
        connectionBusy = true
        gotRealMeasurement = false
        activeButton?.setBackgroundColor(Color.LTGRAY)
        activeButton = btn
        btn?.setBackgroundColor(Color.parseColor("#FFA500")) // orange = connecting
        appendLog("--- connecting to ${device.address} ---")
        gatt?.close()
        gatt = device.connectGatt(this, false, gattCallback)
    }

    private fun markActiveButton(color: Int) {
        runOnUiThread { activeButton?.setBackgroundColor(color) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            appendLog("onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                connectionBusy = false
                if (status != 0) markActiveButton(Color.parseColor("#E53935")) // red = failed/dropped
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            appendLog("onServicesDiscovered status=$status")
            notifyQueue.clear()
            readQueue.clear()
            for (service in g.services) {
                appendLog("  SERVICE ${service.uuid}")
                for (ch in service.characteristics) {
                    val props = describeProps(ch.properties)
                    appendLog("    CHAR ${ch.uuid} props=$props")
                    for (desc in ch.descriptors) {
                        appendLog("      DESC ${desc.uuid}")
                    }
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                        ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                    ) {
                        notifyQueue.addLast(ch)
                    }
                    if (ch.uuid in interestingReads && ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        readQueue.addLast(ch)
                    }
                }
            }
            appendLog("Queuing notify/indicate enablement for ${notifyQueue.size} characteristics (one at a time — GATT allows only one outstanding op).")
            enableNextInQueue(g)
        }

        @SuppressLint("MissingPermission")
        private fun enableNextInQueue(g: BluetoothGatt) {
            val ch = notifyQueue.removeFirstOrNull()
            if (ch == null) {
                appendLog("All notify/indicate characteristics enabled.")
                enableNextRead(g)
                return
            }
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCC_DESCRIPTOR_UUID)
            if (cccd == null) {
                appendLog("  WARN ${ch.uuid} has no CCC descriptor — skipping, cannot enable server-side push")
                enableNextInQueue(g)
                return
            }
            val isIndicate = ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            val value = if (isIndicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            appendLog("  enabling ${if (isIndicate) "INDICATE" else "NOTIFY"} on ${ch.uuid}")
            g.writeDescriptor(cccd, value)
        }

        @SuppressLint("MissingPermission")
        private fun enableNextRead(g: BluetoothGatt) {
            val ch = readQueue.removeFirstOrNull()
            if (ch == null) {
                appendLog("All diagnostic reads issued. Auto-syncing time…")
                syncTime()
                return
            }
            g.readCharacteristic(ch)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            appendLog(
                "READ ${ch.uuid} status=$status len=${value.size} bytes=${value.toHex()} ascii=\"${value.printable()}\"",
            )
            when {
                standaloneReadActive -> standaloneReadActive = false
                propDumpActive -> drainProp(g)
                else -> enableNextRead(g)
            }
        }

        override fun onDescriptorRead(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray,
        ) {
            appendLog(
                "DESC-READ char=${descriptor.characteristic.uuid} desc=${descriptor.uuid} " +
                    "status=$status bytes=${value.toHex()} name=\"${value.printable()}\"",
            )
            if (propDumpActive) drainProp(g)
        }

        @SuppressLint("MissingPermission")
        private fun handleUcpIndication(value: ByteArray) {
            if (value.size < 3 || (value[0].toInt() and 0xFF) != UDS_CP_RESPONSE) return
            val reqOp = value[1].toInt() and 0xFF
            val result = value[2].toInt() and 0xFF
            if (reqOp == UDS_CP_REGISTER_NEW_USER && result == 0x01 && value.size >= 4) {
                val scaleIndex = value[3].toInt() and 0xFF
                appendLog("=== registration succeeded, scaleIndex=$scaleIndex — auto-sending CONSENT ===")
                sendConsent(scaleIndex, lastRegisteredConsent)
            } else if (reqOp == UDS_CP_CONSENT) {
                appendLog(if (result == 0x01) "=== CONSENT accepted — scale should now recognize this session ===" else "=== CONSENT rejected, result=$result ===")
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            appendLog("NOTIFY ${ch.uuid} len=${value.size} bytes=${value.toHex()}")
            if (ch.uuid == USER_CONTROL_POINT_UUID) {
                handleUcpIndication(value)
            }
            if (!gotRealMeasurement && ch.uuid in measurementCharacteristics) {
                gotRealMeasurement = true
                appendLog("=== real measurement data received on ${ch.uuid} — locking connection, no further connects until Clear ===")
                runOnUiThread {
                    activeButton?.setBackgroundColor(Color.parseColor("#43A047")) // green = tx success
                    activeButton?.isEnabled = false
                    for (i in 0 until deviceList.childCount) {
                        deviceList.getChildAt(i).isEnabled = false
                    }
                }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            appendLog("onCharacteristicWrite ${ch.uuid} status=$status")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            appendLog("onDescriptorWrite ${descriptor.uuid} status=$status")
            enableNextInQueue(g)
        }
    }

    private fun describeProps(props: Int): String {
        val flags = mutableListOf<String>()
        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) flags.add("READ")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) flags.add("WRITE")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) flags.add("WRITE_NR")
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) flags.add("NOTIFY")
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) flags.add("INDICATE")
        return flags.joinToString("|").ifEmpty { "none" }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { String.format("%02x", it) }

    /** Printable ASCII only; anything else becomes '.' so a name stays legible next to the hex. */
    private fun ByteArray.printable(): String =
        map { b -> (b.toInt() and 0xFF).let { if (it in 0x20..0x7e) it.toChar() else '.' } }.joinToString("")

    private fun appendLog(line: String) {
        val stamped = "[${timeFmt.format(Date())}] $line"
        Log.i(TAG, stamped)
        runOnUiThread {
            logView.append(stamped + "\n")
        }
        logFile.appendText(stamped + "\n")
    }
}
