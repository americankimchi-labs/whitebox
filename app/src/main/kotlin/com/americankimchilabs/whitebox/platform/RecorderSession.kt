package com.americankimchilabs.whitebox.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.americankimchilabs.whitebox.domain.RecorderPermission
import com.americankimchilabs.whitebox.domain.SessionRecord
import com.americankimchilabs.whitebox.domain.Timestamp
import java.io.File
import java.io.FileOutputStream

private const val CAR_SPEED = "android.car.permission.CAR_SPEED"

/** Current runtime grants, for the grant guard (`checkGrants`). */
fun Context.recorderGrants(): Set<RecorderPermission> = buildSet {
    fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    if (has(Manifest.permission.ACCESS_FINE_LOCATION)) add(RecorderPermission.LOCATION)
    if (has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) add(RecorderPermission.BACKGROUND_LOCATION)
    if (has(CAR_SPEED)) add(RecorderPermission.CAR_SPEED)
}

/** Settings.Global.BOOT_COUNT tells "same boot" from "power cycle" when classifying a start. */
fun Context.bootCount(): Int = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)

/**
 * The recorder's [SessionRecord], one small file. Writes go to a temp file, fsync, then rename, so a power cut
 * leaves either the old or the new record, never a torn one.
 */
class SessionFile(private val file: File) {

    fun read(): SessionRecord? = runCatching {
        val v = file.readText().trim().split(",")
        SessionRecord(
            bootCount = v[0].toInt(),
            lastAlive = Timestamp(elapsedNanos = v[1].toLong(), wallMillis = v[2].toLong()),
            cleanStop = v[3].toBoolean(),
            restartCount = v[4].toInt(),
        )
    }.getOrNull()

    fun write(r: SessionRecord) {
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use {
            it.write("${r.bootCount},${r.lastAlive.elapsedNanos},${r.lastAlive.wallMillis},${r.cleanStop},${r.restartCount}".toByteArray())
            it.fd.sync()
        }
        check(tmp.renameTo(file)) { "rename failed" }
    }
}
