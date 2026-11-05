package com.buddy.assistant

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private val main = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            status.text = intent.getStringExtra("text") ?: ""
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun text(s: String, size: Float) = TextView(this).apply {
            this.text = s; textSize = size; setPadding(0, pad / 2, 0, pad / 2)
        }
        fun button(s: String, action: () -> Unit) = Button(this).apply {
            this.text = s; setOnClickListener { action() }
        }

        root.addView(text("Buddy", 30f))
        root.addView(text("Your voice assistant. It only wakes up when you say \"hey buddy\".", 15f))
        root.addView(button("1. Allow permissions") { askPermissions() })
        root.addView(button("2. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("3. Keep running in background (tap Allow)") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
        toggle = button("Start assistant") { toggleService() }
        root.addView(toggle)
        status = text("Not running", 18f)
        root.addView(status)
        root.addView(
            text(
                "Say \"hey buddy\" and then:\n\n" +
                    "• call mom\n" +
                    "• open YouTube\n" +
                    "• send message to Rahul saying I will be late\n" +
                    "• WhatsApp Rahul saying good morning\n" +
                    "• remind me to drink water at 8 pm every day\n" +
                    "• remind me in 10 minutes to check the food\n" +
                    "• flashlight on / off\n" +
                    "• what time is it\n" +
                    "• play lofi music\n" +
                    "• search weather today\n" +
                    "• clear all reminders\n" +
                    "• go to sleep", 15f
            )
        )
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun askPermissions() {
        val p = mutableListOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(p.toTypedArray(), 1)
    }

    private fun toggleService() {
        if (VoiceService.running) {
            startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Tap step 1 first and allow the microphone", Toast.LENGTH_LONG).show()
                return
            }
            startForegroundService(Intent(this, VoiceService::class.java))
        }
        main.postDelayed({ refresh() }, 700)
    }

    private fun refresh() {
        toggle.text = if (VoiceService.running) "Stop assistant" else "Start assistant"
        if (!VoiceService.running && status.text == "") status.text = "Not running"
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(VoiceService.STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
        status.text = if (VoiceService.running) "Listening for \"hey buddy\"..." else "Not running"
        refresh()
    }

    override fun onPause() {
        unregisterReceiver(receiver)
        super.onPause()
    }
}

/** Always-on listener. Does nothing until it hears the wake phrase "hey buddy". */
class VoiceService : Service() {

    companion object {
        @Volatile var running = false
        const val ACTION_STOP = "com.buddy.assistant.STOP"
        const val STATUS = "com.buddy.assistant.STATUS"
        private val WAKE = listOf("hey buddy", "hi buddy", "ok buddy", "okay buddy", "hey body", "hey bhai")
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var awakeUntil = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        running = true
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale("en", "IN")
            main.post { say("Buddy is ready. Say hey buddy, then your command.") }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { afterSpeech() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { afterSpeech() }
        })
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("buddy_service", "Buddy listening", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "buddy_service")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Buddy is listening")
            .setContentText("Say \"hey buddy\" then your command")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    // ---------- speaking ----------

    private fun say(msg: String) {
        broadcast(msg)
        if (!ttsReady) {
            main.postDelayed({ listen() }, 500)
            return
        }
        speaking = true
        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "buddy")
        // safety net in case the speech engine never reports "done"
        main.postDelayed({ if (speaking) afterSpeech() }, 15000)
    }

    private fun afterSpeech() {
        main.post {
            if (!speaking) return@post
            speaking = false
            main.postDelayed({ listen() }, 300)
        }
    }

    // ---------- listening ----------

    private fun listen() {
        if (!running || speaking) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
            r.setRecognitionListener(listener)
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
            )
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                broadcast("Microphone permission missing")
                stopSelf()
                return
            }
            val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500L else 400L
            main.postDelayed({ listen() }, delay)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: ""
            handle(text)
        }
    }

    private fun handle(text: String) {
        val t = text.lowercase(Locale.ROOT).trim()
        if (t.isEmpty()) { main.postDelayed({ listen() }, 200); return }
        val wake = WAKE.firstOrNull { t.contains(it) }
        when {
            wake != null -> {
                val cmd = t.substringAfter(wake).trim()
                if (cmd.isEmpty()) {
                    awakeUntil = SystemClock.elapsedRealtime() + 12000
                    say("Yes?")
                } else run(cmd)
            }
            SystemClock.elapsedRealtime() < awakeUntil -> run(t)
            else -> main.postDelayed({ listen() }, 200) // ignore everything without the wake word
        }
    }

    private fun run(cmd: String) {
        awakeUntil = 0
        broadcast("Heard: $cmd")
        if (Regex("stop (the )?(assistant|listening)|go to sleep|goodbye").containsMatchIn(cmd)) {
            say("Okay, going to sleep. Open the app to wake me again.")
            main.postDelayed({ stopSelf() }, 3500)
            return
        }
        say(Commands.execute(this, cmd))
    }

    private fun broadcast(text: String) {
        sendBroadcast(Intent(STATUS).setPackage(packageName).putExtra("text", text))
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        broadcast("Stopped")
        super.onDestroy()
    }
}

/** Turns a spoken sentence into an action. Returns what Buddy should say back. */
object Commands {

    private val msgRe = Regex(
        "^(?:send\\s+)?(?:a\\s+)?(?:whatsapp\\s+)?(?:message|msg|sms|text)?\\s*(?:to\\s+)?(.+?)\\s+" +
            "(?:saying|that says|says|say|with message)\\s+(.+)$"
    )
    private val dailyRe = Regex("every ?day|daily|everyday")
    private val atRe = Regex("\\bat\\s+(\\d{1,2})(?:[: ](\\d{2}))?\\s*(am|pm|a m|p m)?")
    private val inRe = Regex("\\bin\\s+(\\d+)\\s+(minute|minutes|min|mins|hour|hours)")

    fun execute(ctx: Context, raw: String): String {
        val t = raw.lowercase(Locale.ROOT).trim().replace(Regex("[.,!?]"), "")
        return try {
            when {
                t.startsWith("call ") || t.startsWith("phone ") -> call(ctx, t.substringAfter(" ").trim())
                t.startsWith("open ") || t.startsWith("launch ") || t.startsWith("start ") ->
                    openApp(ctx, t.substringAfter(" ").trim())
                t.contains("whatsapp") && msgRe.containsMatchIn(t) -> message(ctx, t, true)
                t.startsWith("send ") || t.startsWith("message ") || t.startsWith("text ") ||
                    t.startsWith("sms ") -> message(ctx, t, false)
                t.startsWith("remind") -> remind(ctx, t)
                t.contains("clear") && t.contains("reminder") -> {
                    Reminders.clearAll(ctx); "All reminders cleared"
                }
                t.contains("how many") && t.contains("reminder") -> "You have ${Reminders.count(ctx)} reminders"
                t.contains("flashlight") || t.contains("torch") -> torch(ctx, !t.contains("off"))
                t.contains("time") && !t.contains("remind") ->
                    "It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                t.contains("date") || t.contains("what day") ->
                    "Today is " + SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
                t.startsWith("play ") -> {
                    val q = t.removePrefix("play ").replace(" on youtube", "").trim()
                    launch(ctx, Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
                    "Playing $q on YouTube"
                }
                t.startsWith("search ") || t.startsWith("google ") -> {
                    val q = t.substringAfter(" ").trim()
                    launch(ctx, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
                    "Searching for $q"
                }
                t.contains("what can you do") || t.contains("help") ->
                    "I can call people, open apps, send messages, set reminders, switch the flashlight, " +
                        "tell the time, and search the web."
                else -> "Sorry, I did not understand: $t"
            }
        } catch (e: SecurityException) {
            "I do not have permission for that. Please open the Buddy app and allow permissions."
        } catch (e: Exception) {
            "Something went wrong: ${e.message}"
        }
    }

    private fun launch(ctx: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun has(ctx: Context, perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun findNumber(ctx: Context, name: String): String? {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) throw SecurityException()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"),
            null
        )?.use { return if (it.moveToFirst()) it.getString(0) else null }
        return null
    }

    private fun numberOrContact(ctx: Context, who: String): String? {
        val compact = who.replace(" ", "")
        return if (compact.matches(Regex("\\+?\\d{5,}"))) compact else findNumber(ctx, who)
    }

    private fun call(ctx: Context, who: String): String {
        val num = numberOrContact(ctx, who) ?: return "I could not find $who in your contacts"
        if (!has(ctx, Manifest.permission.CALL_PHONE)) throw SecurityException()
        launch(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num))))
        return "Calling $who"
    }

    private fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val want = name.replace(" ", "")
        fun label(i: android.content.pm.ResolveInfo) =
            i.loadLabel(pm).toString().lowercase(Locale.ROOT).replace(" ", "")
        val m = apps.firstOrNull { label(it) == want }
            ?: apps.firstOrNull { label(it).contains(want) }
            ?: return "I could not find an app called $name"
        val intent = pm.getLaunchIntentForPackage(m.activityInfo.packageName)
            ?: return "I could not open $name"
        launch(ctx, intent)
        return "Opening $name"
    }

    @Suppress("DEPRECATION")
    private fun message(ctx: Context, t: String, whatsapp: Boolean): String {
        val m = msgRe.find(t) ?: return "Say it like: send message to NAME saying YOUR MESSAGE"
        val (name, body) = m.destructured
        val num = numberOrContact(ctx, name.trim()) ?: return "I could not find $name in your contacts"
        if (whatsapp) {
            var n = num.filter { it.isDigit() }.trimStart('0')
            if (n.length == 10) n = "91$n"
            launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n?text=" + Uri.encode(body))))
            return "WhatsApp is open for $name. Tap send."
        }
        if (!has(ctx, Manifest.permission.SEND_SMS)) throw SecurityException()
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()
        sms.sendMultipartTextMessage(num, null, sms.divideMessage(body), null, null)
        return "Message sent to $name"
    }

    private fun remind(ctx: Context, t0: String): String {
        val daily = dailyRe.containsMatchIn(t0)
        val now = Calendar.getInstance()
        val at: Long
        val spoken: String

        val rel = inRe.find(t0)
        val abs = atRe.find(t0)
        if (rel != null) {
            val n = rel.groupValues[1].toLong()
            val mins = if (rel.groupValues[2].startsWith("h")) n * 60 else n
            at = System.currentTimeMillis() + mins * 60_000
            spoken = "in $n ${rel.groupValues[2]}"
        } else if (abs != null) {
            var h = abs.groupValues[1].toInt()
            val min = abs.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = abs.groupValues[3].replace(" ", "")
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (h > 23 || min > 59) return "I did not understand the time"
            val c = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
            at = c.timeInMillis
            spoken = "at " + SimpleDateFormat("h:mm
