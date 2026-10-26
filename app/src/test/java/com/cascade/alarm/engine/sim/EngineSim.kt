package com.cascade.alarm.engine.sim

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.SnoozeState
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.EscalationPolicy
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.CascadeDatabase
import com.cascade.alarm.data.db.EventType
import com.cascade.alarm.data.sound.SoundFiles
import com.cascade.alarm.engine.EventLog
import com.cascade.alarm.engine.TimeSource
import com.cascade.alarm.engine.ring.AlarmSoundPlayer
import com.cascade.alarm.engine.ring.RingCoordinator
import com.cascade.alarm.engine.ring.RingPresenter
import com.cascade.alarm.engine.ring.RingSound
import com.cascade.alarm.engine.ring.RingState
import com.cascade.alarm.engine.ring.RingWakeLock
import com.cascade.alarm.engine.ring.RingingAlarm
import com.cascade.alarm.engine.ring.RoomRingStore
import com.cascade.alarm.engine.schedule.AlarmScheduler
import com.cascade.alarm.engine.schedule.Reconciler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The whole engine on a virtual clock: the real Reconciler, Room (in memory), repository, ring
 * store and ring coordinator, with Android's parts played by fakes that behave like the platform:
 *
 * - [alarmManager] keeps one pending trigger per alarm and delivers it once, at its time; a time
 *   in the past is delivered at once. A reboot or a force stop clears it, as Android does.
 * - A "process" holds what lives in memory (ring state, sound, wake lock, the coordinator and its
 *   timeout timer). Killing it loses all of that; the database survives.
 * - Coroutine time ([TestScope]) is the monotonic clock the coordinator's timeouts run on; the
 *   wall clock is that plus [wallOffset], so the user can set the phone's time without time
 *   running backwards for timers.
 *
 * Every ring the coordinator shows is recorded in [rings]: what the user would see, and when.
 */
class EngineSim(private val test: TestScope, start: LocalDateTime, zone: ZoneId) {
    data class Ring(
        val at: Instant,
        val label: String?,
        val alarmId: String,
        val scheduledFor: Instant,
        val position: Int,
        val total: Int,
        val challenge: ChallengeConfig?,
        val snooze: SnoozeState,
        val wakeCheck: Boolean,
        val deadline: Instant,
    )

    inner class FakeAlarmManager : AlarmScheduler {
        val pending = mutableMapOf<String, Instant>()
        override fun schedule(alarmId: String, at: Instant) {
            pending[alarmId] = at
        }
        override fun cancel(alarmId: String) {
            pending.remove(alarmId)
        }
    }

    inner class FakeSound : AlarmSoundPlayer {
        var playing: RingSound? = null
        var wakeCheckPrompt = false
        var quieted = false
        override fun start(sound: RingSound, onFallback: (reason: String, next: String) -> Unit) {
            playing = sound
            wakeCheckPrompt = false
        }
        override fun startWakeCheck(sound: RingSound) {
            playing = null
            wakeCheckPrompt = true
        }
        override fun setDucked(ducked: Boolean) {
            quieted = ducked
        }
        override fun stop() {
            playing = null
            wakeCheckPrompt = false
            quieted = false
        }
    }

    inner class FakeWakeLock : RingWakeLock {
        var heldUntil: Instant? = null
        override fun acquire(timeout: Duration) {
            heldUntil = elapsed().plus(timeout)
        }
        override fun release() {
            heldUntil = null
        }
        val held get() = heldUntil?.isAfter(elapsed()) == true
    }

    /** What lives in the app's process; gone after [killProcess] or [reboot]. */
    inner class Process {
        private val job = SupervisorJob()
        val scope = CoroutineScope(test.backgroundScope.coroutineContext + job)
        val state = RingState()
        val sound = FakeSound()
        val wakeLock = FakeWakeLock()
        var notification: RingingAlarm? = null
        val presenter = object : RingPresenter {
            override fun show(ringing: RingingAlarm) {
                notification = ringing
                rings += Ring(
                    at = now(), label = ringing.label, alarmId = ringing.alarmId, scheduledFor = ringing.scheduledFor,
                    position = ringing.position, total = ringing.total, challenge = ringing.challenge,
                    snooze = ringing.snooze, wakeCheck = ringing.isWakeCheck, deadline = ringing.deadline,
                )
            }
            override fun finish() {
                notification = null
            }
        }
        val coordinator = RingCoordinator(scope, store, ::now, sound, wakeLock, presenter, state)

        fun kill() = job.cancel()
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    val db: CascadeDatabase = Room.inMemoryDatabaseBuilder(context, CascadeDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()

    private val startedAt: Instant = start.atZone(zone).toInstant()
    var wallOffset: Duration = Duration.ZERO
        private set
    var zone: ZoneId = zone
        private set

    val time = object : TimeSource {
        override fun now() = this@EngineSim.now()
        override fun zone() = this@EngineSim.zone
        override fun bootTime(): Instant = bootedAt
    }
    private var bootedAt: Instant = startedAt.minus(Duration.ofDays(1))

    val alarmManager = FakeAlarmManager()
    val events = EventLog(db.alarmEventDao(), time)
    val reconciler = Reconciler(db, alarmManager, time, events)
    val repo = AlarmRepository(db, reconciler, time, SoundFiles(context))
    val store = RoomRingStore(reconciler, db.alarmDao(), db.alarmGroupDao(), db.activeRingDao(), events)
    val rings = mutableListOf<Ring>()
    var process = Process()
        private set

    /** Monotonic time since the simulation began (what coroutine delays and wake locks run on). */
    private fun elapsed(): Instant = Instant.EPOCH.plusMillis(test.testScheduler.currentTime)

    fun now(): Instant = startedAt.plusMillis(test.testScheduler.currentTime).plus(wallOffset)

    fun local(): LocalDateTime = LocalDateTime.ofInstant(now(), zone)

    val ringing: RingingAlarm? get() = process.state.current.value

    // --- Time ---

    /** Lets time pass to [target], delivering every alarm that comes due on the way, in order. */
    suspend fun advanceTo(target: Instant) {
        require(!target.isBefore(now())) { "time only moves forward; use setClock to change the phone's clock" }
        while (true) {
            val (id, at) = alarmManager.pending.minByOrNull { it.value }?.toPair() ?: (null to null)
            if (id == null || at == null || at.isAfter(target)) {
                passTime(Duration.between(now(), target))
                return
            }
            if (at.isAfter(now())) passTime(Duration.between(now(), at))
            alarmManager.pending.remove(id)
            deliver(id, at)
        }
    }

    suspend fun advanceBy(duration: Duration) = advanceTo(now().plus(duration))

    suspend fun advanceTo(local: LocalDateTime) = advanceTo(local.atZone(zone).toInstant())

    private fun passTime(duration: Duration) {
        if (duration.isNegative || duration.isZero) {
            test.testScheduler.runCurrent()
            return
        }
        test.testScheduler.advanceTimeBy(duration.toMillis())
        test.testScheduler.runCurrent()
    }

    /** AlarmManager → AlarmFireReceiver → AlarmRingService(ACTION_FIRE). */
    private suspend fun deliver(alarmId: String, scheduledFor: Instant) {
        process.coordinator.onFire(alarmId, scheduledFor)
        test.testScheduler.runCurrent()
    }

    /** The user sets the phone's clock (or the network corrects it): TIME_SET. */
    suspend fun setClock(local: LocalDateTime) {
        wallOffset = wallOffset.plus(Duration.between(now(), local.atZone(zone).toInstant()))
        systemEvent("TIME_SET")
    }

    /** The phone's time zone changes (travel): TIMEZONE_CHANGED. */
    suspend fun setZone(newZone: ZoneId) {
        zone = newZone
        systemEvent("TIMEZONE_CHANGED")
    }

    // --- The phone ---

    /**
     * Power off for [offFor], then boot: memory and AlarmManager are cleared; time passes with
     * nothing delivered; then LOCKED_BOOT_COMPLETED reconciles and resumes a persisted ring.
     */
    var reboots = 0
        private set
    var processDeaths = 0
        private set

    suspend fun reboot(offFor: Duration = Duration.ofMinutes(1)) {
        reboots++
        process.kill()
        alarmManager.pending.clear()
        passTime(offFor)
        bootedAt = now()
        process = Process()
        systemEvent("LOCKED_BOOT_COMPLETED", afterBoot = true)
    }

    /** Android kills the process (memory pressure); a ringing service restarts sticky. */
    suspend fun killProcess() {
        processDeaths++
        process.kill()
        process = Process()
        if (db.activeRingDao().get() != null) {
            process.coordinator.onResume(afterBoot = false)
            test.testScheduler.runCurrent()
        }
    }

    /** "Force stop" in app info: the process dies and Android drops the app's alarms. */
    suspend fun forceStop() {
        process.kill()
        alarmManager.pending.clear()
        process = Process()
    }

    /** The user opens Cascade (MainActivity): reconcile, and resume a persisted ring. */
    suspend fun openApp() {
        reconciler.reconcileAll("app_start")
        if (db.activeRingDao().get() != null) process.coordinator.onResume(afterBoot = false)
        test.testScheduler.runCurrent()
        deliverDue()
    }

    private suspend fun systemEvent(action: String, afterBoot: Boolean = false) {
        reconciler.reconcileAll(action)
        if (db.activeRingDao().get() != null) process.coordinator.onResume(afterBoot)
        test.testScheduler.runCurrent()
        deliverDue()
    }

    /** AlarmManager delivers a trigger whose time has passed straight away. */
    suspend fun deliverDue() = advanceTo(now())

    // --- The user, on the alarm screen ---

    suspend fun dismiss(misses: Int = 0) {
        checkNotNull(ringing) { "nothing is ringing at ${local()}" }
        process.coordinator.dismiss(misses = misses)
        test.testScheduler.runCurrent()
    }

    suspend fun giveUp() {
        checkNotNull(ringing) { "nothing is ringing at ${local()}" }
        process.coordinator.dismiss(gaveUp = true)
        test.testScheduler.runCurrent()
    }

    suspend fun snooze() {
        checkNotNull(ringing) { "nothing is ringing at ${local()}" }
        process.coordinator.snooze()
        test.testScheduler.runCurrent()
    }

    // --- Setting things up, through the repository like the screens do ---

    /**
     * A group whose alarms ring at [times] ("06:00"), labelled A, B, C… in order unless [labels]
     * says otherwise. Returns the group id and the alarm ids in ring order.
     */
    suspend fun group(
        name: String,
        vararg times: String,
        days: Set<DayOfWeek> = emptySet(),
        ladder: List<ChallengeConfig> = emptyList(),
        wakeCheckMinutes: Int? = null,
        timeoutMinutes: Int = 15,
        snooze: Boolean = true,
        snoozeMinutes: Int = 5,
        snoozeLimit: Int = 3,
        labels: List<String>? = null,
    ): Pair<String, List<String>> {
        val groupId = repo.createGroup(name)
        times.forEachIndexed { i, t ->
            val (h, m) = t.split(":").map(String::toInt)
            val draft = repo.newAlarmDraft(groupId)
            repo.saveAlarm(
                draft.copy(
                    hour = h, minute = m, daysOfWeek = days, label = labels?.get(i) ?: ('A' + i).toString(),
                    timeoutMinutes = timeoutMinutes, snoozeEnabled = snooze, snoozeMinutes = snoozeMinutes, snoozeLimit = snoozeLimit,
                ),
            )
        }
        if (ladder.isNotEmpty()) repo.setEscalationPolicy(groupId, EscalationPolicy(ladder))
        if (wakeCheckMinutes != null) repo.setWakeCheck(groupId, enabled = true, delayMinutes = wakeCheckMinutes)
        return groupId to db.alarmDao().inGroup(groupId).map { it.id }
    }

    suspend fun alarm(id: String): AlarmEntity = checkNotNull(db.alarmDao().get(id))

    suspend fun alarmByLabel(label: String): AlarmEntity = db.alarmDao().all().single { it.label == label }

    // --- Reading the outcome ---

    /** "06:10 B" for each ring since [from]; a wake-up check reads "06:33 check C". */
    fun ringLog(from: Int = 0): List<String> = rings.drop(from).map {
        val t = LocalDateTime.ofInstant(it.at, zone)
        "%02d:%02d%s %s".format(t.hour, t.minute, if (t.second != 0) ":%02d".format(t.second) else "", (if (it.wakeCheck) "check " else "") + it.label)
    }

    data class Event(val label: String?, val type: EventType, val meta: Map<String, String>, val at: Instant)

    suspend fun events(): List<Event> {
        val labels = db.alarmDao().all().associate { it.id to it.label }
        return db.alarmEventDao().since(Instant.EPOCH).sortedBy { it.timestamp }.map { e ->
            val meta = e.metadata?.let { JSONObject(it) }?.let { json -> json.keys().asSequence().associateWith { json.getString(it) } }.orEmpty()
            Event(labels[e.alarmId], e.type, meta, e.timestamp)
        }
    }

    /** Events other than SCHEDULED, as "B MISSED superseded", for compact assertions. */
    suspend fun story(): List<String> = events().filter { it.type != EventType.SCHEDULED }.map { e ->
        listOfNotNull(e.label, e.type.name, e.meta["reason"]?.takeIf { e.type != EventType.FIRED && e.type != EventType.DISMISSED }).joinToString(" ")
    }

    fun close() = db.close()
}
