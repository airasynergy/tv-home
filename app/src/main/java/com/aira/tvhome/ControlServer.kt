package com.aira.tvhome

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Calendar

/**
 * A tiny web server so the TV can be run from a phone on the same Wi-Fi.
 *
 * Open http://TV-IP:8099 in any phone browser for dimming, a warm tint, sleep
 * timers and a few remote buttons — no second app to install. Plain Java sockets
 * only, no libraries. Traffic is light, so one background thread handles requests
 * one at a time. Anything that touches the screen layer or the TV is posted to the
 * main thread, where it has to run.
 */
class ControlServer(private val svc: HomeOverrideService) {

    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "AiraControlServer").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) { }
        server = null
    }

    private fun loop() {
        try {
            server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(DimController.PORT))
            }
        } catch (e: Exception) {
            running = false
            return
        }
        while (running) {
            val socket = try { server?.accept() ?: break } catch (e: Exception) { break }
            try { handle(socket) } catch (e: Exception) { }
            try { socket.close() } catch (e: Exception) { }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 4000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val requestLine = reader.readLine() ?: return
        while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }

        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val target = parts[1]
        val path = target.substringBefore('?')
        val q = parseQuery(target.substringAfter('?', ""))
        val out = socket.getOutputStream()

        when (path) {
            "/" -> send(out, "200 OK", "text/html; charset=utf-8", page())
            "/state" -> send(out, "200 OK", "application/json", state())

            "/set" -> {
                q["level"]?.toFloatOrNull()?.let { lvl -> main.post { DimController.setLevel(svc, lvl) } }
                send(out, "200 OK", "application/json", state())
            }
            "/warm" -> {
                q["level"]?.toFloatOrNull()?.let { w -> main.post { DimController.setWarmth(svc, w) } }
                send(out, "200 OK", "application/json", state())
            }

            "/sleepnow" -> { main.post { svc.sleepTv() }; send(out, "200 OK", "application/json", state()) }
            "/wake" -> { main.post { svc.wakeTv() }; send(out, "200 OK", "application/json", state()) }
            "/sleep" -> {
                q["mins"]?.toIntOrNull()?.let { m ->
                    DimController.setOneshot(svc, System.currentTimeMillis() + m * 60_000L)
                }
                send(out, "200 OK", "application/json", state())
            }
            "/sleepat" -> {
                q["time"]?.let { t -> atMillis(t)?.let { DimController.setOneshot(svc, it) } }
                send(out, "200 OK", "application/json", state())
            }
            "/canceltimer" -> { DimController.setOneshot(svc, 0L); send(out, "200 OK", "application/json", state()) }

            "/autooff" -> {
                val en = q["enabled"] == "1"
                val time = q["time"]
                if (time != null && DimController.parseMinutes(time) != null) {
                    DimController.saveAutoOff(svc, en, time)
                }
                send(out, "200 OK", "application/json", state())
            }

            "/home" -> { main.post { svc.goHome() }; send(out, "200 OK", "application/json", "{}") }
            "/back" -> { main.post { svc.goBack() }; send(out, "200 OK", "application/json", "{}") }
            "/nomorflix" -> { main.post { svc.openNomorflix() }; send(out, "200 OK", "application/json", "{}") }

            "/schedule" -> {
                val ns = q["nightStart"]; val ne = q["nightEnd"]
                val nl = q["nightLevel"]?.toFloatOrNull(); val dl = q["dayLevel"]?.toFloatOrNull()
                if (ns != null && ne != null && nl != null && dl != null
                    && DimController.parseMinutes(ns) != null && DimController.parseMinutes(ne) != null
                ) {
                    DimController.saveSchedule(svc, ns, ne, nl, dl)
                    main.post { svc.reapplySchedule() }
                }
                send(out, "200 OK", "application/json", state())
            }

            else -> send(out, "404 Not Found", "text/plain", "not found")
        }
    }

    /** Next occurrence of HH:MM — today if still ahead, else tomorrow. */
    private fun atMillis(time: String): Long? {
        val mins = DimController.parseMinutes(time) ?: return null
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, mins / 60)
            set(Calendar.MINUTE, mins % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun state(): String {
        val c = svc
        fun pct(f: Float) = (f * 100f).toInt()
        return "{" +
            "\"level\":${pct(DimController.level(c))}," +
            "\"warmth\":${pct(DimController.warmth(c))}," +
            "\"nightStart\":\"${DimController.nightStart(c)}\"," +
            "\"nightEnd\":\"${DimController.nightEnd(c)}\"," +
            "\"nightLevel\":${pct(DimController.nightLevel(c))}," +
            "\"dayLevel\":${pct(DimController.dayLevel(c))}," +
            "\"autoOffEnabled\":${DimController.autoOffEnabled(c)}," +
            "\"autoOffTime\":\"${DimController.autoOffTime(c)}\"," +
            "\"oneshotAt\":${DimController.oneshotAt(c)}," +
            "\"asleep\":${DimController.isAsleep()}," +
            "\"max\":${(DimController.MAX * 100).toInt()}" +
            "}"
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in raw.split("&")) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val k = pair.substring(0, i)
            val v = try { URLDecoder.decode(pair.substring(i + 1), "UTF-8") } catch (e: Exception) { "" }
            map[k] = v
        }
        return map
    }

    private fun send(out: OutputStream, status: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    // The phone control page. No '$' anywhere, so it survives Kotlin's string rules.
    private fun page(): String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Jaiden's TV</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body { margin:0; font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
         background:#0b0b0f; color:#f2f2f7; padding:22px 18px 46px; max-width:520px; margin:0 auto; }
  h1 { font-size:24px; font-weight:700; letter-spacing:-0.02em; margin:4px 0 2px; }
  .sub { color:#8e8e93; font-size:13px; margin-bottom:20px; }
  .card { background:#1c1c1e; border:1px solid #2c2c2e; border-radius:18px; padding:18px; margin-bottom:14px; }
  .label { font-size:13px; color:#8e8e93; text-transform:uppercase; letter-spacing:0.04em; margin-bottom:10px; }
  .val { font-size:38px; font-weight:700; letter-spacing:-0.03em; }
  .val span { font-size:19px; color:#8e8e93; font-weight:500; }
  input[type=range]{ -webkit-appearance:none; width:100%; height:34px; background:transparent; margin-top:6px; }
  input[type=range]::-webkit-slider-runnable-track{ height:12px; border-radius:6px; }
  #slider::-webkit-slider-runnable-track{ background:linear-gradient(90deg,#3a3a3c,#0b0b0f); }
  #warm::-webkit-slider-runnable-track{ background:linear-gradient(90deg,#fff2cc,#ff9329); }
  input[type=range]::-webkit-slider-thumb{ -webkit-appearance:none; width:30px; height:30px; margin-top:-9px;
      border-radius:50%; background:#fff; box-shadow:0 2px 8px rgba(0,0,0,.5); }
  .grid4 { display:grid; grid-template-columns:repeat(4,1fr); gap:10px; margin-top:14px; }
  .grid3 { display:grid; grid-template-columns:repeat(3,1fr); gap:10px; }
  button { font:inherit; border:none; border-radius:12px; padding:14px 0; font-weight:600; font-size:15px;
      background:#2c2c2e; color:#f2f2f7; cursor:pointer; }
  button:active { transform:scale(0.96); }
  .blue { background:#0a84ff; } .amber { background:#ff9f0a; color:#1c1200; } .red { background:#ff453a; }
  .row { display:flex; align-items:center; justify-content:space-between; gap:12px; margin:12px 0; }
  .row label { font-size:15px; }
  input[type=time], input[type=number]{ font:inherit; background:#2c2c2e; border:1px solid #3a3a3c;
      color:#f2f2f7; border-radius:10px; padding:10px 12px; width:120px; text-align:center; }
  .wide { width:100%; margin-top:8px; }
  .ok { color:#30d158; font-size:13px; height:16px; margin-top:8px; text-align:center; }
  .note { color:#8e8e93; font-size:12px; margin-top:6px; text-align:center; min-height:15px; }
  .switch { width:52px; height:30px; }
</style>
</head>
<body>
  <h1>Jaiden's TV</h1>
  <div class="sub">Dimming &middot; sleep &middot; remote &middot; over Wi-Fi</div>

  <div class="card">
    <div class="label">Screen dim</div>
    <div class="val" id="lvl">0<span>%</span></div>
    <input type="range" id="slider" min="0" max="80" step="1" value="0">
    <div class="grid4">
      <button data-v="0">Day</button>
      <button data-v="40">Dim</button>
      <button data-v="60">Night</button>
      <button data-v="75">Dark</button>
    </div>
  </div>

  <div class="card">
    <div class="label">Warm tint</div>
    <div class="val" id="warmv">0<span>%</span></div>
    <input type="range" id="warm" min="0" max="100" step="1" value="0">
    <div class="note">Shifts the picture warmer at night, less blue light.</div>
  </div>

  <div class="card">
    <div class="label">Sleep</div>
    <div class="grid3">
      <button data-m="15">15 min</button>
      <button data-m="30">30 min</button>
      <button data-m="60">60 min</button>
    </div>
    <div class="row" style="margin-top:14px"><label>Or sleep at</label><input type="time" id="sat"></div>
    <button class="wide" id="setat">Set sleep time</button>
    <div class="grid3" style="margin-top:10px">
      <button class="red" id="snow">Sleep now</button>
      <button class="blue" id="wake">Wake</button>
      <button id="cancel">Cancel</button>
    </div>
    <div class="note" id="timernote"></div>
  </div>

  <div class="card">
    <div class="label">Auto-off every night</div>
    <div class="row"><label>Off at</label><input type="time" id="aot"></div>
    <div class="row"><label>Enabled</label><input type="checkbox" id="aoe" class="switch"></div>
    <button class="wide" id="saveoff">Save auto-off</button>
    <div class="ok" id="ok2"></div>
  </div>

  <div class="card">
    <div class="label">Remote</div>
    <div class="grid3">
      <button id="home">Home</button>
      <button id="back">Back</button>
      <button class="red" id="nomo">Nomorflix</button>
    </div>
  </div>

  <div class="card">
    <div class="label">Dim schedule</div>
    <div class="row"><label>Dim starts</label><input type="time" id="ns"></div>
    <div class="row"><label>Clears at</label><input type="time" id="ne"></div>
    <div class="row"><label>Night level</label><input type="number" id="nl" min="0" max="80" step="5"></div>
    <button class="wide blue" id="save">Save schedule</button>
    <div class="ok" id="ok"></div>
  </div>

<script>
  function g(id){ return document.getElementById(id); }
  var slider=g('slider'), lvl=g('lvl'), warm=g('warm'), warmv=g('warmv');
  var t1=null, t2=null;

  function pct(el,v){ el.innerHTML = v + '<span>%</span>'; }
  function hhmm(ms){ var d=new Date(ms); var h=d.getHours(), m=d.getMinutes();
    return (h<10?'0':'')+h + ':' + (m<10?'0':'')+m; }

  function load(){
    fetch('/state').then(function(r){return r.json();}).then(function(s){
      slider.max=s.max; slider.value=s.level; pct(lvl,s.level);
      warm.value=s.warmth; pct(warmv,s.warmth);
      g('ns').value=s.nightStart; g('ne').value=s.nightEnd; g('nl').value=s.nightLevel; g('nl').max=s.max;
      g('aot').value=s.autoOffTime; g('aoe').checked=s.autoOffEnabled;
      var note = g('timernote');
      if(s.asleep){ note.textContent='Asleep — tap Wake'; }
      else if(s.oneshotAt>0){ note.textContent='Sleeping at ' + hhmm(s.oneshotAt); }
      else { note.textContent=''; }
    });
  }

  slider.addEventListener('input', function(){
    var v=parseInt(slider.value,10); pct(lvl,v);
    if(t1) clearTimeout(t1); t1=setTimeout(function(){ fetch('/set?level=' + (v/100)); }, 90);
  });
  warm.addEventListener('input', function(){
    var v=parseInt(warm.value,10); pct(warmv,v);
    if(t2) clearTimeout(t2); t2=setTimeout(function(){ fetch('/warm?level=' + (v/100)); }, 90);
  });

  var dimBtns=document.querySelectorAll('.grid4 button');
  for(var i=0;i<dimBtns.length;i++){ dimBtns[i].addEventListener('click', function(){
    var v=parseInt(this.getAttribute('data-v'),10); slider.value=v; pct(lvl,v); fetch('/set?level=' + (v/100)); }); }

  var sleepBtns=document.querySelectorAll('[data-m]');
  for(var j=0;j<sleepBtns.length;j++){ sleepBtns[j].addEventListener('click', function(){
    fetch('/sleep?mins=' + this.getAttribute('data-m')).then(load); }); }

  g('setat').addEventListener('click', function(){ if(g('sat').value) fetch('/sleepat?time=' + encodeURIComponent(g('sat').value)).then(load); });
  g('snow').addEventListener('click', function(){ fetch('/sleepnow').then(load); });
  g('wake').addEventListener('click', function(){ fetch('/wake').then(load); });
  g('cancel').addEventListener('click', function(){ fetch('/canceltimer').then(load); });

  g('saveoff').addEventListener('click', function(){
    var en=g('aoe').checked?'1':'0';
    fetch('/autooff?enabled=' + en + '&time=' + encodeURIComponent(g('aot').value)).then(function(){
      g('ok2').textContent='Saved'; setTimeout(function(){g('ok2').textContent='';},1500); });
  });

  g('home').addEventListener('click', function(){ fetch('/home'); });
  g('back').addEventListener('click', function(){ fetch('/back'); });
  g('nomo').addEventListener('click', function(){ fetch('/nomorflix'); });

  g('save').addEventListener('click', function(){
    var qy='/schedule?nightStart=' + encodeURIComponent(g('ns').value)
         + '&nightEnd=' + encodeURIComponent(g('ne').value)
         + '&nightLevel=' + (parseInt(g('nl').value,10)/100) + '&dayLevel=0';
    fetch(qy).then(function(){ g('ok').textContent='Saved'; setTimeout(function(){g('ok').textContent='';},1500); });
  });

  load();
  setInterval(load, 15000);
</script>
</body>
</html>
""".trimIndent()
}
