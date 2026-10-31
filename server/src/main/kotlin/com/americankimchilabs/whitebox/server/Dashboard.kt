package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.Tuning
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun time(ms: Long) = clock.format(Instant.ofEpochMilli(ms))

private val TRIGGER = mapOf(
    "AIRBAG" to "에어백 전개", "DELTA_V" to "충격 감지", "SHORT_DELTA_V" to "짧은 충격 감지", "BRAKED_SPIKE" to "제동 중 충격",
    "REAR_SHOVE" to "후방 충격 (정차·제동 중)", "MANUAL" to "음성으로 보관", "HARD_BRAKE" to "급제동", "EVASIVE_STEER" to "급핸들 (회피)",
    "BRAKE_SWERVE" to "급제동 + 급핸들", "SHIFT_TO_NEUTRAL" to "주행 중 중립(N) 변속", "BRAKE_SPEED_RISE" to "브레이크를 밟는데 속도 상승",
    "BRAKE_NO_DECEL" to "브레이크를 밟는데 감속 없음", "BRAKE_PUMPING" to "브레이크 반복 조작", "PARKING_BRAKE_MOVING" to "주행 중 주차 브레이크",
    "LURCH" to "정차 중 급출발", "POST_EVENT_EXIT" to "위험 상황 직후 하차", "ALERT_RESOLVED" to "급제동 후 정차",
)

/**
 * The mirror's page for whoever runs or evaluates it: one card per mirrored car that says in plain words what is
 * happening and what the server holds, with the technical detail (ids, sequence numbers, seal lines, the event log)
 * behind "자세히". The cards refresh every second without reloading the page, so an opened "자세히" stays open.
 */
fun dashboard(service: MirrorService, sms: DemoSmsSender? = null): String = """<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>White Box 서버</title>
<link rel="stylesheet" href="/wb.css">
<style>
main{max-width:1100px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:12px}
.cars .card h2{display:flex;gap:8px;align-items:center}.history{max-height:220px;overflow:auto;padding-left:18px}.t{color:var(--muted);font-variant-numeric:tabular-nums}
</style></head>
<body><main>
<h1>White Box 서버</h1>
<div class="card">
  <p><b>차 안의 기록이 원본이고, 이 서버는 그 사본을 받아 안전하게 보관합니다.</b> 차가 부서지거나 전원이 끊겨도 서버가 받은 데까지는 남습니다.</p>
  <ul class="bullets">
    <li>평소에는 10초마다 모아서 받고, 위험한 순간에는 브레이크·속도·조향·기어 기록을 생기는 대로 바로 받습니다.</li>
    <li>주행 기록과 위치는 차에서 암호화되어 오기 때문에 서버도 읽을 수 없습니다. 큰 사고가 봉인되면 그 구간의 열쇠만 서버에 따로 보관해, 비상 연락처에 저장된 분이 열어 볼 수 있게 합니다.</li>
    <li>위험 신호 뒤 차에서 ${Tuning.DEADMAN_SEAL_SILENCE_S}초간 신호가 없으면, 서버가 받은 기록에 직접 서명해 봉인합니다. 문자는 보내지 않습니다.</li>
  </ul>
  <p class="msg">휴대폰 화면: <a href="/me">차주</a> · <a href="/contact">비상 연락처</a></p>
</div>
<div id="live">${dashboardCards(service, sms)}</div>
</main>
<script>
// Refresh the cards in place; any "자세히" the reader opened stays open.
setInterval(async () => {
  try {
    const html = await (await fetch('/dashboard/cards')).text();
    const open = [...document.querySelectorAll('details[open][data-key]')].map((d) => d.dataset.key);
    const live = document.getElementById('live');
    live.innerHTML = html;
    open.forEach((k) => { const d = live.querySelector('details[data-key="' + k + '"]'); if (d) d.open = true; });
  } catch (e) { /* the server is restarting */ }
}, 1000);
</script>
</body></html>"""

/** The refreshing part of the page: the demo text messages and one card per car. */
fun dashboardCards(service: MirrorService, sms: DemoSmsSender? = null): String {
    val texts = sms?.recent()?.asReversed()?.joinToString("") { m ->
        "<li><span class='t'>${time(m.atMs)}</span> <b>${esc(m.to)}</b> ${esc(m.text)}</li>"
    }?.ifEmpty { "<li class='msg'>아직 없음</li>" }
    val smsPanel = texts?.let {
        """<details class="card" data-key="sms" ${if (sms.recent().isNotEmpty()) "open" else ""}><summary><b>문자 (시연용)</b> — 실제로 보내지 않고 여기에 보여 줍니다: 인증번호, 차주 변경 확인</summary><ul class="history">$it</ul></details>"""
    } ?: ""
    val cards = service.statuses().sortedByDescending { it.events.lastOrNull()?.first ?: 0 }.joinToString("") { s ->
        val capsules = service.capsules(s.stream)
        val silentS = s.silentForMs?.div(1000)
        val (badge, cls, sentence) = when {
            s.severeSilent.isNotEmpty() && s.level != EscalationLevel.NORMAL ->
                Triple("사고 뒤 신호 끊김", "bad", "충돌 수준의 사고 뒤 차에서 신호가 없습니다. 서버가 받은 기록을 봉인해 두었습니다.")
            s.level == EscalationLevel.TRIGGERED -> Triple("사고 감지", "bad", "사고가 감지되어 차가 기록을 봉인하고 바로 보내는 중입니다.")
            s.level == EscalationLevel.ALERT -> Triple("위험 신호", "warn", "급제동 같은 위험 신호가 있어 기록을 생기는 대로 바로 받고 있습니다.")
            silentS != null && silentS < 30 -> Triple("주행 중", "good", "정상적으로 기록을 받고 있습니다.")
            s.records > 0 -> Triple("꺼짐", "", "차가 꺼져 있습니다. 받은 기록은 안전하게 보관하고 있습니다.")
            else -> Triple("기록 없음", "", "아직 받은 기록이 없습니다.")
        }
        val kind = if (s.stream.stream == "replay") "데모 차량" else "실제 차량"
        val moments = capsules.take(5).joinToString("") { c ->
            val name = if (c.trigger == "SERVER_SEAL") "사고 뒤 신호 끊김 (서버 봉인)" else TRIGGER[c.trigger] ?: c.trigger
            val tag = if (c.severe) "<span class='badge bad'>큰 사고</span>" else "<span class='badge warn'>위험 순간</span>"
            "<div class='row'><span>${esc(name)}<br><span class='sub'>${time(c.sealedAtMs)}${if (c.escrowed) " · 비상 연락처 열람 가능" else ""}</span></span>$tag</div>"
        }.ifEmpty { "<p class='msg'>보관된 기록이 없습니다.</p>" }
        val history = s.events.asReversed().joinToString("") { (at, text) -> "<li><span class='t'>${time(at)}</span> ${esc(text)}</li>" }
        val seals = s.seals.takeLast(4).joinToString("<br>") { l -> l.split("|").let { f -> esc("${f[0]} ${f[1]} #${f[2]}–#${f[3]} ${if (f[5] == "true") "최종" else "임시"}") } }
        val attested = s.attested.takeLast(3).joinToString("<br>") { l -> l.substringBefore("|sig=").split("|").let { f -> esc("${time(f.last().toLong())} #${f[2]}–#${f[3]} 빠진 기록 ${f[6]}건") } }
        val key = esc("${s.stream.deviceId}-${s.stream.stream}")
        """
<section class="card">
  <h2><span class="badge $cls">$badge</span> $kind</h2>
  <p>$sentence ${silentS?.let { "<span class='msg'>마지막 수신 ${it}초 전</span>" } ?: ""}</p>
  <div class="stats">
    <div class="stat"><b>${s.records}건</b><span>받은 기록</span></div>
    <div class="stat"><b>${capsules.size}건</b><span>보관 기록</span></div>
  </div>
  <h3>보관 기록</h3>$moments
  <details data-key="$key"><summary>자세히</summary><div class="tech">
    기기 ${esc(s.stream.deviceId)} · 스트림 ${esc(s.stream.stream)} · 단계 ${s.level ?: "-"}<br>
    #0부터 빠짐없이 #${s.contiguousHead}까지 · 최고 #${s.highestSeq} · 변조 의심으로 거부 ${s.conflicts}건<br>
    차량 봉인 (차량 키 서명 확인됨):<br>${seals.ifEmpty { "없음" }}<br>
    서버 봉인 (데드맨 스위치):<br>${attested.ifEmpty { "없음" }}<br>
    마지막 서버 서명: ${esc(s.lastAnchor?.substringBefore("|sig=") ?: "없음")}
    <h3>경과</h3><ul class="history">$history</ul>
  </div></details>
</section>"""
    }.ifEmpty { "<div class='card'><p class='msg'>아직 연결된 차량이 없습니다. 다른 터미널에서 <code>./gradlew :server:demoCar</code>를 실행하거나 에뮬레이터 앱을 이 서버로 연결하세요.</p></div>" }
    return "$smsPanel<div class='grid cars'>$cards</div>"
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
