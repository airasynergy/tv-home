package com.aira.tvhome

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Calendar

/**
 * A small web server that turns any phone on the same Wi-Fi into a full TV remote.
 *
 * Open http://TV-IP:8099 for a tabbed app — Remote (D-pad, volume, media), Screen
 * (dimming, warm tint, sleep, schedule) and Apps (launch what's installed). No second
 * app to install, plain Java sockets, no libraries. Traffic is light, so one
 * background thread handles requests one at a time; anything that touches the screen
 * layer or the TV runs on the main thread.
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
            "/apps" -> send(out, "200 OK", "application/json", svc.appsJson())

            // ── Screen: dim / warmth ───────────────────────────────────────────
            "/set" -> { q["level"]?.toFloatOrNull()?.let { l -> main.post { DimController.setLevel(svc, l) } }; send(out, "200 OK", "application/json", state()) }
            "/warm" -> { q["level"]?.toFloatOrNull()?.let { w -> main.post { DimController.setWarmth(svc, w) } }; send(out, "200 OK", "application/json", state()) }

            // ── Sleep ──────────────────────────────────────────────────────────
            "/sleepnow" -> { main.post { svc.sleepTv() }; send(out, "200 OK", "application/json", state()) }
            "/wake" -> { main.post { svc.wakeTv() }; send(out, "200 OK", "application/json", state()) }
            "/sleep" -> { q["mins"]?.toIntOrNull()?.let { m -> DimController.setOneshot(svc, System.currentTimeMillis() + m * 60_000L) }; send(out, "200 OK", "application/json", state()) }
            "/sleepat" -> { q["time"]?.let { t -> atMillis(t)?.let { DimController.setOneshot(svc, it) } }; send(out, "200 OK", "application/json", state()) }
            "/canceltimer" -> { DimController.setOneshot(svc, 0L); send(out, "200 OK", "application/json", state()) }
            "/autooff" -> {
                val time = q["time"]
                if (time != null && DimController.parseMinutes(time) != null) DimController.saveAutoOff(svc, q["enabled"] == "1", time)
                send(out, "200 OK", "application/json", state())
            }
            "/schedule" -> {
                val ns = q["nightStart"]; val ne = q["nightEnd"]
                val nl = q["nightLevel"]?.toFloatOrNull(); val dl = q["dayLevel"]?.toFloatOrNull()
                if (ns != null && ne != null && nl != null && dl != null
                    && DimController.parseMinutes(ns) != null && DimController.parseMinutes(ne) != null) {
                    DimController.saveSchedule(svc, ns, ne, nl, dl)
                    main.post { svc.reapplySchedule() }
                }
                send(out, "200 OK", "application/json", state())
            }

            // ── Remote: navigation ─────────────────────────────────────────────
            "/nav" -> {
                val code = when (q["k"]) {
                    "up" -> KeyEvent.KEYCODE_DPAD_UP
                    "down" -> KeyEvent.KEYCODE_DPAD_DOWN
                    "left" -> KeyEvent.KEYCODE_DPAD_LEFT
                    "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
                    "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
                    else -> -1
                }
                if (code != -1) main.post { svc.navKey(code) }
                send(out, "200 OK", "application/json", "{}")
            }
            "/home" -> { main.post { svc.goHome() }; send(out, "200 OK", "application/json", "{}") }
            "/back" -> { main.post { svc.goBack() }; send(out, "200 OK", "application/json", "{}") }

            // ── Remote: volume + media ─────────────────────────────────────────
            "/volup" -> { main.post { svc.volUp() }; send(out, "200 OK", "application/json", state()) }
            "/voldown" -> { main.post { svc.volDown() }; send(out, "200 OK", "application/json", state()) }
            "/mute" -> { main.post { svc.toggleMute() }; send(out, "200 OK", "application/json", state()) }
            "/vol" -> { q["pct"]?.toIntOrNull()?.let { p -> main.post { svc.setVolumePct(p) } }; send(out, "200 OK", "application/json", state()) }
            "/media" -> {
                val code = when (q["k"]) {
                    "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    "ff" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                    "rew" -> KeyEvent.KEYCODE_MEDIA_REWIND
                    else -> -1
                }
                if (code != -1) main.post { svc.mediaKey(code) }
                send(out, "200 OK", "application/json", "{}")
            }

            // ── Apps ───────────────────────────────────────────────────────────
            "/launch" -> { q["pkg"]?.let { p -> main.post { svc.launchApp(p) } }; send(out, "200 OK", "application/json", "{}") }
            "/web" -> {
                val url = q["url"]; val name = q["name"] ?: "Web"
                if (url != null) main.post { svc.openWeb(url, name) }
                send(out, "200 OK", "application/json", "{}")
            }
            "/nomorflix" -> { main.post { svc.openNomorflix() }; send(out, "200 OK", "application/json", "{}") }

            else -> send(out, "404 Not Found", "text/plain", "not found")
        }
    }

    private fun atMillis(time: String): Long? {
        val mins = DimController.parseMinutes(time) ?: return null
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, mins / 60); set(Calendar.MINUTE, mins % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
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
            "\"volume\":${svc.volumePct()}," +
            "\"muted\":${svc.isMuted()}," +
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

    // The control app. No '$' anywhere, so it survives Kotlin's string rules.
    private fun page(): String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">
<title>Jaiden's TV</title>
<style>
  :root{
    color-scheme:dark;
    --bg:#070708; --panel:#141418; --panel2:#1c1c22; --line:#2a2a31;
    --ink:#f4f4f6; --dim:#8a8a93; --accent:#5e9dff; --accentgrad:linear-gradient(135deg,#6aa2ff,#9b6bff);
    --warm:linear-gradient(90deg,#ffe6b0,#ff9329); --red:#ff4d4a; --green:#30d667;
    --r:20px; --ease:cubic-bezier(.22,.61,.36,1);
  }
  *{box-sizing:border-box;-webkit-tap-highlight-color:transparent;-webkit-user-select:none;user-select:none;}
  html,body{height:100%;}
  body{margin:0;background:
      radial-gradient(1200px 600px at 50% -10%, #17171f 0%, var(--bg) 55%);
      color:var(--ink);font-family:-apple-system,BlinkMacSystemFont,"SF Pro Display","Segoe UI",Roboto,sans-serif;
      display:flex;flex-direction:column;overflow:hidden;}
  .top{padding:20px 20px 6px;display:flex;align-items:center;justify-content:space-between;}
  .top h1{font-size:21px;font-weight:700;letter-spacing:-.02em;margin:0;}
  .pill{display:flex;align-items:center;gap:7px;font-size:12px;color:var(--dim);}
  .dot{width:8px;height:8px;border-radius:50%;background:var(--green);box-shadow:0 0 10px var(--green);}
  main{flex:1;overflow-y:auto;padding:8px 18px 110px;-webkit-overflow-scrolling:touch;}
  .tab{display:none;animation:rise .34s var(--ease);}
  .tab.on{display:block;}
  @keyframes rise{from{opacity:0;transform:translateY(14px);}to{opacity:1;transform:none;}}
  @keyframes pop{from{opacity:0;transform:scale(.9) translateY(8px);}to{opacity:1;transform:none;}}

  .card{background:linear-gradient(180deg,var(--panel2),var(--panel));border:1px solid var(--line);
      border-radius:var(--r);padding:18px;margin-bottom:14px;box-shadow:0 8px 30px rgba(0,0,0,.35);}
  .label{font-size:12px;color:var(--dim);text-transform:uppercase;letter-spacing:.06em;margin-bottom:12px;font-weight:600;}
  .val{font-size:34px;font-weight:700;letter-spacing:-.03em;}
  .val span{font-size:17px;color:var(--dim);font-weight:500;}

  button{font:inherit;border:none;color:var(--ink);background:#26262d;border-radius:14px;
      font-weight:600;font-size:15px;cursor:pointer;transition:transform .12s var(--ease),background .2s;}
  button:active{transform:scale(.93);background:#30303a;}
  .b-accent{background:var(--accentgrad);} .b-red{background:var(--red);} .b-ghost{background:#20202733;border:1px solid var(--line);}

  input[type=range]{-webkit-appearance:none;width:100%;height:30px;background:transparent;margin-top:6px;}
  input[type=range]::-webkit-slider-runnable-track{height:12px;border-radius:7px;background:#2a2a31;}
  #slider::-webkit-slider-runnable-track{background:linear-gradient(90deg,#3a3a42,#0b0b0f);}
  #warm::-webkit-slider-runnable-track{background:var(--warm);}
  input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:28px;height:28px;margin-top:-8px;
      border-radius:50%;background:#fff;box-shadow:0 3px 10px rgba(0,0,0,.6);}

  .presets{display:grid;grid-template-columns:repeat(4,1fr);gap:9px;margin-top:14px;}
  .presets button,.g3 button{padding:13px 0;}
  .g3{display:grid;grid-template-columns:repeat(3,1fr);gap:9px;}
  .row{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:12px 0;}
  input[type=time],input[type=number]{font:inherit;background:#26262d;border:1px solid var(--line);color:var(--ink);
      border-radius:11px;padding:10px 12px;width:120px;text-align:center;}
  .wide{width:100%;padding:14px 0;margin-top:8px;}
  .note{color:var(--dim);font-size:12px;margin-top:8px;text-align:center;min-height:16px;}

  /* D-pad */
  .pad{position:relative;width:230px;height:230px;margin:6px auto 4px;}
  .pad button{position:absolute;background:#22222a;border:1px solid var(--line);}
  .pad .up{top:0;left:65px;width:100px;height:72px;border-radius:30px 30px 10px 10px;}
  .pad .dn{bottom:0;left:65px;width:100px;height:72px;border-radius:10px 10px 30px 30px;}
  .pad .lf{left:0;top:65px;width:72px;height:100px;border-radius:30px 10px 10px 30px;}
  .pad .rt{right:0;top:65px;width:72px;height:100px;border-radius:10px 30px 30px 10px;}
  .pad .ok{left:65px;top:65px;width:100px;height:100px;border-radius:50%;background:var(--accentgrad);
      border:none;font-weight:700;box-shadow:0 6px 20px rgba(94,157,255,.35);}
  .pad .arrow{position:absolute;color:var(--dim);font-size:19px;pointer-events:none;}
  .ctl2{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:6px;}
  .vol{display:grid;grid-template-columns:1fr 1.1fr 1fr;gap:10px;align-items:center;margin-top:6px;}
  .vol button{padding:18px 0;font-size:20px;}
  .volbar{height:8px;border-radius:5px;background:#2a2a31;overflow:hidden;margin-top:14px;}
  .volfill{height:100%;background:var(--accentgrad);width:0%;transition:width .2s var(--ease);}
  .media{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:12px;}
  .media button{padding:16px 0;font-size:18px;}

  /* Apps */
  .appgrid{display:grid;grid-template-columns:repeat(2,1fr);gap:10px;}
  .appgrid button{padding:16px 14px;text-align:left;background:linear-gradient(180deg,#24242b,#1a1a20);
      border:1px solid var(--line);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;
      animation:pop .3s var(--ease) both;}

  /* Tab bar */
  nav{position:fixed;left:0;right:0;bottom:0;display:flex;justify-content:center;gap:6px;
      padding:10px 14px calc(14px + env(safe-area-inset-bottom));
      background:rgba(12,12,14,.72);backdrop-filter:blur(22px) saturate(1.4);-webkit-backdrop-filter:blur(22px) saturate(1.4);
      border-top:1px solid var(--line);}
  nav button{flex:1;max-width:150px;background:transparent;color:var(--dim);padding:10px 0;font-size:13px;font-weight:600;
      display:flex;flex-direction:column;align-items:center;gap:4px;border-radius:14px;}
  nav button .ic{font-size:19px;line-height:1;}
  nav button.on{color:var(--ink);background:#23232b;}
  .ok{color:var(--green);font-size:13px;height:16px;margin-top:8px;text-align:center;}
</style>
</head>
<body>
  <div class="top"><h1>Jaiden's TV</h1><div class="pill"><span class="dot"></span><span id="status">Connected</span></div></div>

  <main>
    <!-- REMOTE -->
    <section id="t-remote" class="tab on">
      <div class="card">
        <div class="label">Navigation</div>
        <div class="pad">
          <button class="up" data-nav="up"></button>
          <button class="dn" data-nav="down"></button>
          <button class="lf" data-nav="left"></button>
          <button class="rt" data-nav="right"></button>
          <button class="ok" data-nav="ok">OK</button>
          <span class="arrow" style="top:22px;left:108px">&#9650;</span>
          <span class="arrow" style="bottom:22px;left:108px">&#9660;</span>
          <span class="arrow" style="left:24px;top:103px">&#9664;</span>
          <span class="arrow" style="right:24px;top:103px">&#9654;</span>
        </div>
        <div class="ctl2">
          <button class="b-ghost" data-act="back">Back</button>
          <button class="b-ghost" data-act="home">Home</button>
        </div>
        <div class="note">Arrows &amp; OK drive the open app (Nomorflix etc.). Back / Home work anywhere.</div>
      </div>

      <div class="card">
        <div class="label">Volume</div>
        <div class="vol">
          <button id="vdn">&#8722;</button>
          <button id="vmute" class="b-ghost">Mute</button>
          <button id="vup">+</button>
        </div>
        <div class="volbar"><div class="volfill" id="vfill"></div></div>
      </div>

      <div class="card">
        <div class="label">Media</div>
        <div class="media">
          <button data-m="rew">&#9194;</button>
          <button class="b-accent" data-m="playpause">&#9199;</button>
          <button data-m="ff">&#9193;</button>
        </div>
      </div>
    </section>

    <!-- SCREEN -->
    <section id="t-screen" class="tab">
      <div class="card">
        <div class="label">Screen dim</div>
        <div class="val" id="lvl">0<span>%</span></div>
        <input type="range" id="slider" min="0" max="80" step="1" value="0">
        <div class="presets">
          <button data-v="0">Day</button><button data-v="40">Dim</button>
          <button data-v="60">Night</button><button data-v="75">Dark</button>
        </div>
      </div>
      <div class="card">
        <div class="label">Warm tint</div>
        <div class="val" id="warmv">0<span>%</span></div>
        <input type="range" id="warm" min="0" max="100" step="1" value="0">
        <div class="note">Warmer picture, less blue light at night.</div>
      </div>
      <div class="card">
        <div class="label">Sleep</div>
        <div class="g3">
          <button data-slp="15">15 min</button><button data-slp="30">30 min</button><button data-slp="60">60 min</button>
        </div>
        <div class="row" style="margin-top:14px"><label>Or sleep at</label><input type="time" id="sat"></div>
        <button class="wide b-ghost" id="setat">Set sleep time</button>
        <div class="g3" style="margin-top:10px">
          <button class="b-red" id="snow">Sleep now</button><button class="b-accent" id="wake">Wake</button><button id="cancel">Cancel</button>
        </div>
        <div class="note" id="timernote"></div>
      </div>
      <div class="card">
        <div class="label">Auto-off every night</div>
        <div class="row"><label>Off at</label><input type="time" id="aot"></div>
        <div class="row"><label>Enabled</label><input type="checkbox" id="aoe" style="width:48px;height:28px"></div>
        <button class="wide" id="saveoff">Save auto-off</button>
        <div class="ok" id="ok2"></div>
      </div>
      <div class="card">
        <div class="label">Dim schedule</div>
        <div class="row"><label>Dim starts</label><input type="time" id="ns"></div>
        <div class="row"><label>Clears at</label><input type="time" id="ne"></div>
        <div class="row"><label>Night level</label><input type="number" id="nl" min="0" max="80" step="5"></div>
        <button class="wide b-accent" id="save">Save schedule</button>
        <div class="ok" id="ok"></div>
      </div>
    </section>

    <!-- APPS -->
    <section id="t-apps" class="tab">
      <div class="card">
        <div class="label">Quick web</div>
        <div class="appgrid">
          <button onclick="fetch('/nomorflix')">Nomorflix</button>
          <button onclick="web('https://twitch.tv','Twitch')">Twitch</button>
          <button onclick="web('https://kick.com','Kick')">Kick</button>
          <button onclick="web('https://youtube.com','YouTube')">YouTube Web</button>
        </div>
      </div>
      <div class="card">
        <div class="label">Installed apps</div>
        <div class="appgrid" id="applist"><button class="b-ghost">Loading…</button></div>
      </div>
    </section>
  </main>

  <nav>
    <button class="on" data-tab="remote"><span class="ic">&#9673;</span>Remote</button>
    <button data-tab="screen"><span class="ic">&#9681;</span>Screen</button>
    <button data-tab="apps"><span class="ic">&#9638;</span>Apps</button>
  </nav>

<script>
  function g(id){return document.getElementById(id);}
  function tap(url){ fetch(url).catch(function(){}); }

  // Tabs
  var tabBtns=document.querySelectorAll('nav button');
  for(var i=0;i<tabBtns.length;i++){ tabBtns[i].addEventListener('click', function(){
    var name=this.getAttribute('data-tab');
    for(var k=0;k<tabBtns.length;k++) tabBtns[k].classList.remove('on');
    this.classList.add('on');
    var secs=document.querySelectorAll('.tab');
    for(var s=0;s<secs.length;s++) secs[s].classList.remove('on');
    var el=g('t-'+name); el.classList.add('on');
    if(name==='apps') loadApps();
  }); }

  // Remote
  var nav=document.querySelectorAll('[data-nav]');
  for(var n=0;n<nav.length;n++){ nav[n].addEventListener('click', function(){ tap('/nav?k='+this.getAttribute('data-nav')); }); }
  var acts=document.querySelectorAll('[data-act]');
  for(var a=0;a<acts.length;a++){ acts[a].addEventListener('click', function(){ tap('/'+this.getAttribute('data-act')); }); }
  var meds=document.querySelectorAll('[data-m]');
  for(var m=0;m<meds.length;m++){ meds[m].addEventListener('click', function(){ tap('/media?k='+this.getAttribute('data-m')); }); }
  g('vup').addEventListener('click', function(){ fetch('/volup').then(r=>r.json()).then(vol); });
  g('vdn').addEventListener('click', function(){ fetch('/voldown').then(r=>r.json()).then(vol); });
  g('vmute').addEventListener('click', function(){ fetch('/mute').then(r=>r.json()).then(vol); });
  function vol(s){ if(s&&typeof s.volume!=='undefined'){ g('vfill').style.width=(s.muted?0:s.volume)+'%'; } }

  // Screen
  var slider=g('slider'), lvl=g('lvl'), warm=g('warm'), warmv=g('warmv'), t1=null, t2=null;
  function pct(el,v){ el.innerHTML=v+'<span>%</span>'; }
  function hhmm(ms){ var d=new Date(ms); return (d.getHours()<10?'0':'')+d.getHours()+':'+(d.getMinutes()<10?'0':'')+d.getMinutes(); }
  slider.addEventListener('input', function(){ var v=parseInt(slider.value,10); pct(lvl,v); if(t1)clearTimeout(t1); t1=setTimeout(function(){ tap('/set?level='+(v/100)); },90); });
  warm.addEventListener('input', function(){ var v=parseInt(warm.value,10); pct(warmv,v); if(t2)clearTimeout(t2); t2=setTimeout(function(){ tap('/warm?level='+(v/100)); },90); });
  var dimB=document.querySelectorAll('[data-v]');
  for(var d=0;d<dimB.length;d++){ dimB[d].addEventListener('click', function(){ var v=parseInt(this.getAttribute('data-v'),10); slider.value=v; pct(lvl,v); tap('/set?level='+(v/100)); }); }
  var slp=document.querySelectorAll('[data-slp]');
  for(var p=0;p<slp.length;p++){ slp[p].addEventListener('click', function(){ fetch('/sleep?mins='+this.getAttribute('data-slp')).then(load); }); }
  g('setat').addEventListener('click', function(){ if(g('sat').value) fetch('/sleepat?time='+encodeURIComponent(g('sat').value)).then(load); });
  g('snow').addEventListener('click', function(){ fetch('/sleepnow').then(load); });
  g('wake').addEventListener('click', function(){ fetch('/wake').then(load); });
  g('cancel').addEventListener('click', function(){ fetch('/canceltimer').then(load); });
  g('saveoff').addEventListener('click', function(){ fetch('/autooff?enabled='+(g('aoe').checked?'1':'0')+'&time='+encodeURIComponent(g('aot').value)).then(function(){ flash('ok2'); }); });
  g('save').addEventListener('click', function(){ fetch('/schedule?nightStart='+encodeURIComponent(g('ns').value)+'&nightEnd='+encodeURIComponent(g('ne').value)+'&nightLevel='+(parseInt(g('nl').value,10)/100)+'&dayLevel=0').then(function(){ flash('ok'); }); });
  function flash(id){ g(id).textContent='Saved'; setTimeout(function(){ g(id).textContent=''; },1400); }

  // Apps
  function web(u,n){ tap('/web?url='+encodeURIComponent(u)+'&name='+encodeURIComponent(n)); }
  var appsLoaded=false;
  function loadApps(){ if(appsLoaded) return; appsLoaded=true;
    fetch('/apps').then(function(r){return r.json();}).then(function(list){
      var box=g('applist'); box.innerHTML='';
      if(!list.length){ box.innerHTML='<button class="b-ghost">No apps found</button>'; return; }
      list.forEach(function(app,idx){
        var b=document.createElement('button'); b.textContent=app.n; b.style.animationDelay=(idx*22)+'ms';
        b.addEventListener('click', function(){ tap('/launch?pkg='+encodeURIComponent(app.p)); });
        box.appendChild(b);
      });
    }).catch(function(){ appsLoaded=false; });
  }

  function load(){ fetch('/state').then(function(r){return r.json();}).then(function(s){
    slider.max=s.max; slider.value=s.level; pct(lvl,s.level);
    warm.value=s.warmth; pct(warmv,s.warmth);
    g('ns').value=s.nightStart; g('ne').value=s.nightEnd; g('nl').value=s.nightLevel; g('nl').max=s.max;
    g('aot').value=s.autoOffTime; g('aoe').checked=s.autoOffEnabled;
    vol(s);
    var note=g('timernote');
    note.textContent = s.asleep ? 'Asleep — tap Wake' : (s.oneshotAt>0 ? 'Sleeping at '+hhmm(s.oneshotAt) : '');
  }).catch(function(){ g('status').textContent='Reconnecting…'; }); }

  load();
  setInterval(load, 12000);
</script>
</body>
</html>
""".trimIndent()
}
