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

/**
 * A tiny web server so the TV can be controlled from a phone on the same Wi-Fi.
 *
 * Open http://TV-IP:8099 in any phone browser to get a brightness slider and a
 * day/night schedule editor — no second app to install. Plain Java sockets only,
 * no libraries, so the app stays small. Traffic is light (one control page), so
 * requests are handled one at a time on a single background thread.
 *
 * Anything that touches the dim layer is posted to the main thread, because the
 * window it lives in must be changed there.
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
        // Drain headers so the client is happy; we don't need them.
        while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }

        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val out = socket.getOutputStream()

        when (path) {
            "/" -> send(out, "200 OK", "text/html; charset=utf-8", page())
            "/state" -> send(out, "200 OK", "application/json", state())
            "/set" -> {
                val lvl = query["level"]?.toFloatOrNull()
                if (lvl != null) main.post { DimController.setLevel(svc, lvl) }
                send(out, "200 OK", "application/json", state())
            }
            "/schedule" -> {
                val ns = query["nightStart"]
                val ne = query["nightEnd"]
                val nl = query["nightLevel"]?.toFloatOrNull()
                val dl = query["dayLevel"]?.toFloatOrNull()
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

    private fun state(): String {
        val c = svc
        fun pct(f: Float) = (f * 100f).toInt()
        return "{" +
            "\"level\":${pct(DimController.level(c))}," +
            "\"nightStart\":\"${DimController.nightStart(c)}\"," +
            "\"nightEnd\":\"${DimController.nightEnd(c)}\"," +
            "\"nightLevel\":${pct(DimController.nightLevel(c))}," +
            "\"dayLevel\":${pct(DimController.dayLevel(c))}," +
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
<title>Aira TV Control</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body { margin:0; font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
         background:#0b0b0f; color:#f2f2f7; padding:22px 18px 40px; max-width:520px; margin:0 auto; }
  h1 { font-size:22px; font-weight:700; letter-spacing:-0.02em; margin:4px 0 2px; }
  .sub { color:#8e8e93; font-size:13px; margin-bottom:22px; }
  .card { background:#1c1c1e; border:1px solid #2c2c2e; border-radius:18px; padding:18px; margin-bottom:16px; }
  .label { font-size:13px; color:#8e8e93; text-transform:uppercase; letter-spacing:0.04em; margin-bottom:12px; }
  .val { font-size:40px; font-weight:700; letter-spacing:-0.03em; }
  .val span { font-size:20px; color:#8e8e93; font-weight:500; }
  input[type=range]{ -webkit-appearance:none; width:100%; height:34px; background:transparent; margin-top:8px; }
  input[type=range]::-webkit-slider-runnable-track{ height:12px; border-radius:6px;
      background:linear-gradient(90deg,#ff9f0a,#ff375f); }
  input[type=range]::-webkit-slider-thumb{ -webkit-appearance:none; width:30px; height:30px; margin-top:-9px;
      border-radius:50%; background:#fff; box-shadow:0 2px 8px rgba(0,0,0,.5); }
  .presets { display:grid; grid-template-columns:repeat(4,1fr); gap:10px; margin-top:16px; }
  .presets button, .save { font:inherit; border:none; border-radius:12px; padding:14px 0; font-weight:600;
      font-size:15px; background:#2c2c2e; color:#f2f2f7; cursor:pointer; }
  .presets button:active, .save:active { transform:scale(0.96); }
  .row { display:flex; align-items:center; justify-content:space-between; gap:12px; margin:12px 0; }
  .row label { font-size:15px; }
  input[type=time], input[type=number]{ font:inherit; background:#2c2c2e; border:1px solid #3a3a3c;
      color:#f2f2f7; border-radius:10px; padding:10px 12px; width:120px; text-align:center; }
  .save { width:100%; margin-top:10px; background:#0a84ff; }
  .ok { color:#30d158; font-size:13px; height:16px; margin-top:8px; text-align:center; }
</style>
</head>
<body>
  <h1>Aira TV Control</h1>
  <div class="sub">Dimming for the whole screen &middot; works over Wi-Fi</div>

  <div class="card">
    <div class="label">Screen dim</div>
    <div class="val" id="lvl">0<span>%</span></div>
    <input type="range" id="slider" min="0" max="80" step="1" value="0">
    <div class="presets">
      <button data-v="0">Day</button>
      <button data-v="40">Dim</button>
      <button data-v="60">Night</button>
      <button data-v="75">Dark</button>
    </div>
  </div>

  <div class="card">
    <div class="label">Schedule</div>
    <div class="row"><label>Dim starts</label><input type="time" id="ns"></div>
    <div class="row"><label>Clears at</label><input type="time" id="ne"></div>
    <div class="row"><label>Night level</label><input type="number" id="nl" min="0" max="80" step="5"></div>
    <button class="save" id="save">Save schedule</button>
    <div class="ok" id="ok"></div>
  </div>

<script>
  var slider=document.getElementById('slider'), lvl=document.getElementById('lvl');
  var ns=document.getElementById('ns'), ne=document.getElementById('ne'), nl=document.getElementById('nl');
  var ok=document.getElementById('ok'), timer=null;

  function show(v){ lvl.innerHTML = v + '<span>%</span>'; }
  function load(){
    fetch('/state').then(function(r){return r.json();}).then(function(s){
      slider.value=s.level; show(s.level);
      slider.max=s.max; nl.max=s.max;
      ns.value=s.nightStart; ne.value=s.nightEnd; nl.value=s.nightLevel;
    });
  }
  function push(v){
    show(v);
    if(timer) clearTimeout(timer);
    timer=setTimeout(function(){ fetch('/set?level=' + (v/100)); }, 90);
  }
  slider.addEventListener('input', function(){ push(parseInt(slider.value,10)); });
  var btns=document.querySelectorAll('.presets button');
  for(var i=0;i<btns.length;i++){
    btns[i].addEventListener('click', function(){
      var v=parseInt(this.getAttribute('data-v'),10);
      slider.value=v; push(v);
    });
  }
  document.getElementById('save').addEventListener('click', function(){
    var q='/schedule?nightStart='+encodeURIComponent(ns.value)
         +'&nightEnd='+encodeURIComponent(ne.value)
         +'&nightLevel='+(parseInt(nl.value,10)/100)
         +'&dayLevel=0';
    fetch(q).then(function(){ ok.textContent='Saved'; setTimeout(function(){ok.textContent='';},1500); });
  });
  load();
</script>
</body>
</html>
""".trimIndent()
}
