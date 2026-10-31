package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.Tuning
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun time(ms: Long) = clock.format(Instant.ofEpochMilli(ms))

/**
 * The mirror's live view (refreshes every second): one card per mirrored car with its state, what the server holds,
 * the seals, and a readable history of what happened.
 */
fun dashboard(service: MirrorService, sms: DemoSmsSender? = null): String {
    val texts = sms?.recent()?.asReversed()?.joinToString("") { m ->
        "<li><span class='t'>${time(m.atMs)}</span> <b>${esc(m.to)}</b> ${esc(m.text)}</li>"
    }?.ifEmpty { "<li class='none'>아직 없음</li>" }
    val smsPanel = texts?.let {
        """<section class="card"><header><b>문자 (시연용)</b> <span class="muted">실제로 보내지 않고 여기에 표시합니다: 인증번호, 비상 연락처 초대, 비상 알림</span></header><ul class="history">$it</ul></section>"""
    } ?: ""
    val cards = service.statuses().sortedByDescending { it.events.lastOrNull()?.first ?: 0 }.joinToString("") { s ->
        val (label, color) = when (s.level) {
            EscalationLevel.TRIGGERED -> "사고 감지" to "#c62828"
            EscalationLevel.ALERT -> "경보" to "#ef6c00"
            EscalationLevel.NORMAL -> "정상" to "#2e7d32"
            null -> "기록 없음" to "#757575"
        }
        val silent = s.silentForMs?.let { ms ->
            val sec = ms / 1000.0
            if (s.level != EscalationLevel.NORMAL && sec >= 2) "<span class='warn'>%.0f초째 무응답</span>".format(sec) else "마지막 수신 %.0f초 전".format(sec)
        } ?: ""
        val kind = if (s.stream.stream == "replay") "데모 주행" else "실시간 차량"
        val history = s.events.asReversed().joinToString("") { (at, text) -> "<li><span class='t'>${time(at)}</span> ${esc(text)}</li>" }
        val seals = s.seals.takeLast(4).joinToString("") { l ->
            val f = l.split("|")
            "<li>${esc(f[0])} · ${esc(f[1])} · #${f[2]}–#${f[3]} · ${if (f[5] == "true") "최종" else "임시"}</li>"
        }.ifEmpty { "<li class='none'>없음</li>" }
        val attested = s.attested.takeLast(3).joinToString("") { l ->
            val f = l.substringBefore("|sig=").split("|")
            "<li>${time(f.last().toLong())} · #${f[2]}–#${f[3]} · 빠진 기록 ${f[6]}건</li>"
        }.ifEmpty { "<li class='none'>없음</li>" }
        val notices = s.notices.takeLast(3).joinToString("") { l -> "<li>${time(l.substringBefore('|').toLong())} · 비상 연락처 알림</li>" }
            .ifEmpty { "<li class='none'>없음</li>" }
        """
<section class="card">
  <header><span class="badge" style="background:$color">$label</span>
    <b>$kind</b> <code>${esc(s.stream.deviceId.take(12))}…</code> <span class="muted">$silent</span></header>
  <div class="grid">
    <div><h3>서버가 받은 기록</h3><p class="big">${s.records}건</p>
      <p class="muted">#0부터 빠짐없이 #${s.contiguousHead}까지 · 변조 의심 거부 ${s.conflicts}건</p></div>
    <div><h3>차량 봉인 <small>(차량 키 서명 확인됨)</small></h3><ul>$seals</ul></div>
    <div><h3>서버 봉인 <small>(데드맨 스위치)</small></h3><ul>$attested</ul></div>
    <div><h3>비상 알림</h3><ul>$notices</ul></div>
  </div>
  <h3>경과</h3><ul class="history">$history</ul>
</section>"""
    }.ifEmpty { "<p class='muted'>아직 연결된 차량이 없습니다. 다른 터미널에서 <code>./gradlew :server:demoCar</code>를 실행하거나 에뮬레이터 앱을 이 서버로 연결하세요.</p>" }

    return """<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta http-equiv="refresh" content="1">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>White Box 클라우드 미러</title>
<style>
body{font:15px/1.5 system-ui,-apple-system,"Apple SD Gothic Neo",sans-serif;margin:0 auto;max-width:1100px;padding:16px;background:#fafafa;color:#111}
h1{font-size:22px;margin:0 0 4px} h3{font-size:13px;margin:12px 0 4px;color:#555} small{font-weight:normal;color:#888}
.intro{background:#fff;border:1px solid #e0e0e0;border-radius:8px;padding:12px 16px;margin:12px 0}
.intro li{margin:2px 0}
.card{background:#fff;border:1px solid #e0e0e0;border-radius:8px;padding:12px 16px;margin:12px 0}
.badge{color:#fff;border-radius:4px;padding:2px 8px;margin-right:8px;font-weight:600}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px}
.big{font-size:22px;font-weight:600;margin:0} .muted{color:#757575} .warn{color:#c62828;font-weight:600}
ul{margin:0;padding-left:18px} .none{color:#9e9e9e;list-style:none;margin-left:-18px}
.history{max-height:220px;overflow:auto} .t{color:#757575;font-variant-numeric:tabular-nums}
code{font-size:13px}
</style></head>
<body><h1>White Box 클라우드 미러</h1>
<div class="intro"><ul>
<li>차량의 <b>로컬 체인이 원본</b>이고 이 서버는 사본을 받습니다. 기록마다 해시와 앞뒤 연결을 확인한 뒤 저장합니다.</li>
<li><b>정상</b>: 10초마다 묶어서 받습니다. <b>경보·사고 감지</b>: 브레이크·속도·조향·기어 기록이 생기는 즉시 도착하고, 차량 봉인이 가장 먼저 옵니다.</li>
<li><b>데드맨 스위치</b>: 경보 뒤 해제 없이 ${Tuning.DEADMAN_SEAL_SILENCE_S}초 무응답이면 서버가 받은 기록에 직접 서명해 봉인하고,
${Tuning.DEADMAN_NOTIFY_SILENCE_S}초가 지나고 충돌 수준 증거가 있을 때만 비상 연락처에 알립니다(지금은 알림 대신 기록).</li>
</ul></div>
$smsPanel
$cards
</body></html>"""
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
