package com.americankimchilabs.whitebox.tamper

import com.americankimchilabs.whitebox.data.CapsuleCheck
import com.americankimchilabs.whitebox.data.EvidenceFile
import com.americankimchilabs.whitebox.data.FileEventStore
import com.americankimchilabs.whitebox.data.SealStore
import com.americankimchilabs.whitebox.data.verifyCapsule
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.VehicleSignal
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Tamper demo: the recorded chain verifies; the same chain with ONE byte changed does not — even when the forger
 * also recomputes every later hash, because the signed seal pins the head.
 *
 *   ./gradlew -q :tamper-demo:run [--args=<chain dir>]      (default: docs/artifacts/live-run2-demo)
 *
 * Given an evidence file downloaded on the owner's phone (`whitebox-<id>.txt`), it checks that file instead.
 * Works on copies; the input directory is never written.
 */
fun main(args: Array<String>) {
    val arg = args.firstOrNull()?.takeIf { it.isNotBlank() }
    if (arg != null && File(arg).isFile) return checkEvidence(File(arg))
    val source = File(arg ?: "docs/artifacts/live-run2-demo")
    require(File(source, "chain.log").exists() && File(source, "seals.log").exists()) { "no chain.log + seals.log in $source" }
    val work = Files.createTempDirectory("whitebox-tamper").toFile()

    val original = copy(source, File(work, "original"))
    val seal = SealStore(original).all().last { it.final }
    val c = seal.capsule
    println("White Box 변조 검증 데모 / tamper demo")
    println("  기록: $source  ·  캡슐 ${c.id} ${c.trigger}  #${c.firstSeq}–#${c.lastSeq}")
    println()
    report("원본 / original", verifyCapsule(seal, FileEventStore(original)))

    // Pick the speed record a few samples before the trigger — "how fast was I going before the crash".
    val store = FileEventStore(original)
    val cause = store.read(c.firstSeq, c.lastSeq)
        .firstNotNullOfOrNull { (it.entry as? ChainEntry.Escalation)?.event as? EscalationEvent.Triggered }?.causeSeq ?: c.lastSeq
    val target = store.read(c.firstSeq, cause).last { e ->
        e.seq < cause - 3 && (e.entry as? ChainEntry.Signal)?.sample?.signal is VehicleSignal.Speed
    }
    store.close()

    val edited = copy(source, File(work, "tampered"))
    val (before, after) = flipOneDigit(File(edited, "chain.log"), target.seq, rehash = false)
    println()
    println("  레코드 #${target.seq} 의 속도 값에서 1바이트 변경: $before → $after")
    report("1바이트 변조 / one byte changed", verifyCapsule(seal, FileEventStore(edited)))

    val forged = copy(source, File(work, "forged"))
    flipOneDigit(File(forged, "chain.log"), target.seq, rehash = true)
    println()
    println("  같은 변경 + 이후 모든 해시 재계산 (체인 자체는 자기모순 없음)")
    report("해시 재계산 위조 / re-hashed forgery", verifyCapsule(seal, FileEventStore(forged)))

    work.deleteRecursively()
}

/** An evidence file from the phone: every check, then what the records say once opened with the keys inside. */
private fun checkEvidence(file: File) {
    val r = EvidenceFile.verify(file.readText())
    println("White Box 증거 파일 검증 / evidence file check")
    println("  파일: ${file.path}  ·  캡슐 ${r.capsuleId} ${r.trigger}  #${r.firstSeq}–#${r.lastSeq}")
    println("  체인: ${if (r.chainOk) "[PASS] 기록 ${r.recordsChecked}건 해시·연결 일치" else "[FAIL] ${r.chainProblem}"}")
    println("  차량 봉인: ${if (r.seals > 0 && r.sealsValid == r.seals) "[PASS]" else "[FAIL]"} ${r.sealsValid}/${r.seals}건 서명 유효, 헤드 일치")
    println("  서버 서명: ${if (r.anchorsValid == r.anchors) "[PASS]" else "[FAIL]"} ${r.anchorsValid}/${r.anchors}건")
    println("  열린 기록: ${r.opened}건 (파일 안의 열쇠로)")
    println(if (r.ok) "  결과: 원본 그대로입니다 / original, unaltered" else "  결과: 원본과 다릅니다 / NOT the original")
    r.entries.filter { (_, e) -> e is ChainEntry.Signal && (e.sample.signal is VehicleSignal.Speed || e.sample.signal is VehicleSignal.BrakePedal) }.takeLast(8).forEach { (seq, e) ->
        val s = (e as ChainEntry.Signal).sample.signal
        println("    #$seq ${if (s is VehicleSignal.Speed) "속도 %.1f km/h".format(s.metersPerSecond * 3.6f) else "브레이크 ${if ((s as VehicleSignal.BrakePedal).pressed) "밟음" else "뗌"}"}")
    }
}

private fun report(label: String, check: CapsuleCheck) {
    when (check) {
        CapsuleCheck.Verified -> println("  [PASS] $label — 체인 연결·해시 일치, 서명된 봉인과 헤드 일치, 서명 유효")
        is CapsuleCheck.Failed -> println("  [FAIL] $label — ${check.reason}")
    }
}

private fun copy(from: File, to: File): File {
    to.mkdirs()
    listOf("chain.log", "seals.log").forEach { File(from, it).copyTo(File(to, it)) }
    return to
}

/**
 * Changes one digit of the speed value in record [seq] (one byte in the file). With [rehash], also recomputes that
 * record's hash and every later link, as a forger with file access would. Returns the value before and after, in km/h.
 */
private fun flipOneDigit(chain: File, seq: Long, rehash: Boolean): Pair<String, String> {
    val lines = chain.readLines().toMutableList()
    val f = lines[seq.toInt()].split("\t").toMutableList()
    val payload = f[2].split("|").toMutableList() // S|SPEED|<m/s>|...
    val value = payload[2]
    val i = value.indexOfFirst { it.isDigit() && it != '0' }.let { if (it < 0) value.indexOfFirst(Char::isDigit) else it }
    val digit = value[i]
    val changed = value.substring(0, i) + (if (digit == '9') '8' else digit + 1) + value.substring(i + 1)
    payload[2] = changed
    f[2] = payload.joinToString("|")
    lines[seq.toInt()] = f.joinToString("\t")
    if (rehash) {
        var prev = f[1]
        for (k in seq.toInt() until lines.size) {
            val g = lines[k].split("\t")
            val body = "${g[0]}\t$prev\t${g[2]}"
            prev = sha256Hex(body)
            lines[k] = "$body\t$prev"
        }
    }
    chain.writeText(lines.joinToString("\n", postfix = "\n"))
    fun kmh(ms: String) = "%.1f km/h".format(ms.toFloat() * 3.6f)
    return kmh(value) to kmh(changed)
}

private fun sha256Hex(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
