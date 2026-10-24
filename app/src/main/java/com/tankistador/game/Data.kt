package com.tankistador.game

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How a weapon behaves once fired. */
enum class WeaponKind {
    SHELL, PIERCE, TRIPLE, BURST, ROCKETS, BOUNCER, HOPPER, CLUSTER, FIREWORK, MELON, MOLES, MINES,
    NAPALM, DRILL, QUAKE, HOMING, METEORS, GRAVITY, NUKE, TELEPORT, REPAIR, SHIELD,
}

/** How a projectile is drawn. Every weapon gets its own body instead of a glowing dot. */
enum class Look {
    SHELL, HE, AP, ROCKET, BALL, FROG, CANISTER, BOMBLET, FIREWORK, SPARK, MELON, SEED, POD, MINE_POD,
    NAPALM, DRILL, QUAKE, HOMING, BEACON, METEOR, GRAV, NUKE, CAPSULE,
}

class Weapon(
    val id: Int,
    val name: String,
    val price: Int,
    val kind: WeaponKind,
    val damage: Float,
    val radius: Float,
    val look: Look,
    val type: String,
    val desc: String,
    val color: Int,
    /** Rocket motor: acceleration along the flight path for [burn] seconds. */
    val thrust: Float = 0f,
    val burn: Float = 0f,
) {
    /** Utility "weapons" do not launch anything. */
    val instant get() = kind == WeaponKind.REPAIR || kind == WeaponKind.SHIELD
}

object Weapons {
    val all = listOf(
        Weapon(0, "Болванка", 0, WeaponKind.SHELL, 30f, 26f, Look.SHELL, "снаряд",
            "Чугунная болванка. Бесплатная и бесконечная.", 0xFFCFD8DC.toInt()),
        Weapon(1, "Фугас", 25, WeaponKind.SHELL, 55f, 40f, Look.HE, "снаряд",
            "Толстый фугасный снаряд. Глубокая воронка и много осколков.", 0xFFFFB74D.toInt()),
        Weapon(2, "Бронебой", 45, WeaponKind.PIERCE, 50f, 17f, Look.AP, "снаряд",
            "Остроносый сердечник. При прямом попадании урон почти вдвое больше.", 0xFF90A4AE.toInt()),
        Weapon(3, "Тройчатка", 40, WeaponKind.TRIPLE, 28f, 22f, Look.SHELL, "3 снаряда",
            "Три снаряда разом, веером. Хоть один да долетит.", 0xFFFFF176.toInt()),
        Weapon(4, "Очередь", 55, WeaponKind.BURST, 22f, 18f, Look.SHELL, "5 снарядов",
            "Пять выстрелов подряд с небольшим разбросом.", 0xFFFFE082.toInt()),
        Weapon(5, "Ливень", 90, WeaponKind.ROCKETS, 25f, 22f, Look.ROCKET, "6 ракет",
            "Залп из шести ракет. Двигатели разгоняют их первые полсекунды.", 0xFFFF8A65.toInt(), thrust = 180f, burn = 0.45f),
        Weapon(6, "Попрыгунчик", 30, WeaponKind.BOUNCER, 42f, 30f, Look.BALL, "снаряд",
            "Резиновый мяч со взрывчаткой. Скачет по грунту до четырёх раз и взрывается у танка.", 0xFF81D4FA.toInt()),
        Weapon(7, "Лягушка", 55, WeaponKind.HOPPER, 27f, 22f, Look.FROG, "3 взрыва",
            "Взрывается при каждом приземлении и прыгает дальше. Три взрыва подряд.", 0xFF9CCC65.toInt()),
        Weapon(8, "Кассета", 60, WeaponKind.CLUSTER, 20f, 18f, Look.CANISTER, "8 бомбочек",
            "В верхней точке раскрывается и высыпает восемь бомбочек.", 0xFFB0BEC5.toInt()),
        Weapon(9, "Фейерверк", 65, WeaponKind.FIREWORK, 15f, 13f, Look.FIREWORK, "10 искр",
            "Рвётся в воздухе рядом с целью и разлетается десятью взрывающимися искрами.", 0xFFFF4081.toInt()),
        Weapon(10, "Арбуз", 50, WeaponKind.MELON, 19f, 15f, Look.MELON, "6 взрывов",
            "Раскалывается о грунт, и пять косточек разлетаются, взрываясь вокруг.", 0xFF66BB6A.toInt()),
        Weapon(11, "Землекопы", 60, WeaponKind.MOLES, 45f, 26f, Look.POD, "2 робота",
            "Два заводных робота. Ползут по грунту в разные стороны и взрываются у танка.", 0xFFBCAAA4.toInt()),
        Weapon(12, "Мины", 45, WeaponKind.MINES, 42f, 28f, Look.MINE_POD, "3 мины",
            "Разбрасывает три мины. Они ждут, пока рядом не проедет танк или не грохнет взрыв.", 0xFFEF5350.toInt()),
        Weapon(13, "Огнесмесь", 50, WeaponKind.NAPALM, 16f, 16f, Look.NAPALM, "пожар",
            "Разливает по грунту пламя, которое жжёт танки три хода подряд.", 0xFFFF7043.toInt()),
        Weapon(14, "Бурав", 40, WeaponKind.DRILL, 50f, 46f, Look.DRILL, "бур",
            "Вгрызается в грунт и взрывается в глубине. Отличная лопата.", 0xFF8D6E63.toInt()),
        Weapon(15, "Сейсмобомба", 80, WeaponKind.QUAKE, 22f, 20f, Look.QUAKE, "9 взрывов",
            "Ударная волна бежит по поверхности планеты цепочкой из девяти взрывов.", 0xFFA1887F.toInt()),
        Weapon(16, "Умник", 90, WeaponKind.HOMING, 50f, 30f, Look.HOMING, "ракета",
            "Самонаводящаяся ракета. Через секунду полёта сама поворачивает к врагу.", 0xFF4DD0E1.toInt(), thrust = 120f, burn = 2.5f),
        Weapon(17, "Звездопад", 110, WeaponKind.METEORS, 30f, 26f, Look.BEACON, "5 метеоритов",
            "Маяк, на который с неба обрушиваются пять метеоритов.", 0xFFFFAB40.toInt()),
        Weapon(18, "Гравибомба", 120, WeaponKind.GRAVITY, 50f, 40f, Look.GRAV, "чёрная дыра",
            "Открывает крошечную чёрную дыру: она тянет к себе всё вокруг и выедает грунт.", 0xFFB388FF.toInt()),
        Weapon(19, "Кувалда", 160, WeaponKind.NUKE, 120f, 95f, Look.NUKE, "бомба",
            "Огромный взрыв с ударной волной и тремя повторными толчками.", 0xFFFFEB3B.toInt()),
        Weapon(20, "Прыг-скок", 70, WeaponKind.TELEPORT, 0f, 0f, Look.CAPSULE, "телепорт",
            "Капсула-телепорт. Танк переносится туда, где она упадёт.", 0xFFCE93D8.toInt()),
        Weapon(21, "Ремкомплект", 100, WeaponKind.REPAIR, 0f, 0f, Look.CAPSULE, "ремонт",
            "Чинит корпус на 20 и самую разбитую деталь на 10. Тратит ход.", 0xFF69F0AE.toInt()),
        Weapon(22, "Зонтик", 60, WeaponKind.SHIELD, 0f, 0f, Look.CAPSULE, "щит",
            "Силовой купол, поглощающий 40 единиц урона. Тратит ход.", 0xFF40C4FF.toInt()),
    )

    fun byKind(k: WeaponKind) = all.first { it.kind == k }
}

/** Shape parameters for the procedurally drawn tank. */
class TankLook(
    val hull: Int, val wheels: Int, val turret: Int, val barrelLen: Float, val barrelW: Float,
    val doubleBarrel: Boolean, val antennaH: Float, val dish: Boolean, val scale: Float, val extra: Int = 0,
)

class TankModel(
    val id: Int, val name: String, val desc: String, val price: Int, val minRank: Int,
    val gun: Int, val hull: Int, val antenna: Int, val tracks: Int, val fuel: Int, val look: TankLook,
)

object Tanks {
    val all = listOf(
        TankModel(0, "Букашка", "Учебный танчик. Мягкий как подушка и дешёвый как пирожок.", 300, 0,
            40, 100, 30, 40, 200, TankLook(2, 3, 0, 10f, 2.4f, false, 10f, false, 1.0f)),
        TankModel(1, "Кузнечик", "Лёгкий и прыткий. Большой бак и резвые гусеницы.", 900, 0,
            45, 115, 40, 70, 320, TankLook(1, 3, 2, 12f, 2.2f, false, 12f, false, 1.02f)),
        TankModel(2, "Ёжик", "Маленький, колючий и неожиданно крепкий.", 1300, 1,
            50, 150, 30, 45, 220, TankLook(3, 4, 1, 10f, 3f, false, 9f, false, 1.05f, 1)),
        TankModel(3, "Глазастик", "Огромная антенна: дальше видит, точнее подсказывает.", 1500, 2,
            50, 125, 90, 50, 250, TankLook(2, 3, 0, 11f, 2.6f, false, 20f, true, 1.05f)),
        TankModel(4, "Самовар", "Пыхтит, но держит удар. Рабочая лошадка любой армии.", 2100, 3,
            70, 170, 50, 60, 300, TankLook(3, 4, 2, 12f, 3f, false, 12f, false, 1.1f, 2)),
        TankModel(5, "Носорог", "Тяжёлый танк с рогом. Таран не входит в комплект.", 2900, 4,
            80, 220, 45, 70, 300, TankLook(1, 4, 1, 14f, 3.5f, false, 11f, false, 1.15f, 3)),
        TankModel(6, "Броненосец", "Толстая скорлупа и спаренная пушка.", 3800, 5,
            90, 280, 60, 80, 350, TankLook(2, 5, 2, 13f, 3f, true, 13f, false, 1.2f)),
        TankModel(7, "Мамонт", "Сверхтяжёлый. Под ним прогибаются даже планеты.", 5200, 7,
            110, 350, 70, 90, 400, TankLook(3, 5, 1, 16f, 3.6f, true, 14f, true, 1.25f, 2)),
        TankModel(8, "Царь-танк", "Вершина танкостроения. Только для генералов духа.", 7500, 9,
            130, 450, 100, 110, 500, TankLook(2, 5, 0, 17f, 4f, true, 16f, true, 1.3f, 4)),
    )

    fun tradeIn(model: Int) = (all[model].price * 0.6f).roundToInt()
}

object Ranks {
    val names = listOf(
        "Рядовой", "Ефрейтор", "Мл. сержант", "Сержант", "Ст. сержант", "Старшина", "Прапорщик",
        "Лейтенант", "Ст. лейтенант", "Капитан", "Майор", "Подполковник", "Полковник", "Генерал",
    )
    private val xpFor = intArrayOf(0, 150, 400, 800, 1300, 2000, 2900, 4000, 5400, 7000, 9000, 11500, 14500, 18000)

    fun of(xp: Int): Int {
        var r = 0
        for (i in xpFor.indices) if (xp >= xpFor[i]) r = i
        return r
    }

    fun nextXp(rank: Int): Int = if (rank + 1 < xpFor.size) xpFor[rank + 1] else -1
    fun salary(rank: Int) = 200 + 50 * rank
}

object Econ {
    const val PER_DAMAGE = 4
    const val DIRECT_BONUS = 40
    const val KILL_BONUS = 250
    const val WIN_PRIZE = 300
    const val WIN_PRIZE_PER_ROUND = 100
    const val LOSE_PRIZE = 120
    const val FINE_BUILDING = 30
    const val FINE_COLONIST = 15

    // Price of one repaired point per part, and of 2 units of fuel.
    val repairCost = intArrayOf(4, 3, 3, 3)
    const val FUEL_COST_PER_2 = 1
}

enum class Part(val title: String) { GUN("Пушка"), HULL("Корпус"), ANTENNA("Антенна"), TRACKS("Гусеницы") }

/** Long-lived per-name statistics shown in the menu and the records table. */
class Profile(var name: String) {
    var xp = 0
    var rounds = 0
    var wins = 0
    var shots = 0
    var direct = 0
    var streak = 0
    var bestStreak = 0
    var suicides = 0
    var colonists = 0
    var buildings = 0
    var hintShots = 0
    var damage = 0
    var matches = 0
    var matchWins = 0
    var lastPlayed = 0L

    val rank get() = Ranks.of(xp)
    val score get() = xp

    fun pct(a: Int, b: Int) = if (b <= 0) 0 else (a * 100f / b).roundToInt()

    fun toJson() = JSONObject().apply {
        put("name", name); put("xp", xp); put("rounds", rounds); put("wins", wins); put("shots", shots)
        put("direct", direct); put("streak", streak); put("bestStreak", bestStreak); put("suicides", suicides)
        put("colonists", colonists); put("buildings", buildings); put("hintShots", hintShots)
        put("damage", damage); put("matches", matches); put("matchWins", matchWins); put("lastPlayed", lastPlayed)
    }

    companion object {
        fun fromJson(o: JSONObject) = Profile(o.optString("name", "?")).apply {
            xp = o.optInt("xp"); rounds = o.optInt("rounds"); wins = o.optInt("wins"); shots = o.optInt("shots")
            direct = o.optInt("direct"); streak = o.optInt("streak"); bestStreak = o.optInt("bestStreak")
            suicides = o.optInt("suicides"); colonists = o.optInt("colonists"); buildings = o.optInt("buildings")
            hintShots = o.optInt("hintShots"); damage = o.optInt("damage"); matches = o.optInt("matches")
            matchWins = o.optInt("matchWins"); lastPlayed = o.optLong("lastPlayed")
        }
    }
}

/** A participant of the current match: wallet, tank and its wear. */
class Player(var name: String, var isAI: Boolean, var aiLevel: Int, val side: Int) {
    var money = 0
    var xp = 0
    var model = 0
    var gun = 0f
    var hull = 0f
    var antenna = 0f
    var tracks = 0f
    var fuel = 0f
    var roundWins = 0
    var lastSalary = 0

    // Statistics for the current round, flushed into the profile when the round ends.
    var rShots = 0
    var rDirect = 0
    var rDamage = 0
    var rSuicide = false
    var rColonists = 0
    var rBuildings = 0
    var rHintShots = 0

    val rank get() = Ranks.of(xp)
    val tank get() = Tanks.all[model]
    val color get() = if (side == 0) COLOR_LEFT else COLOR_RIGHT

    fun fillTank() {
        val m = tank
        gun = m.gun.toFloat(); hull = m.hull.toFloat(); antenna = m.antenna.toFloat()
        tracks = m.tracks.toFloat(); fuel = m.fuel.toFloat()
    }

    fun partValue(p: Part) = when (p) {
        Part.GUN -> gun; Part.HULL -> hull; Part.ANTENNA -> antenna; Part.TRACKS -> tracks
    }

    fun partMax(p: Part) = when (p) {
        Part.GUN -> tank.gun; Part.HULL -> tank.hull; Part.ANTENNA -> tank.antenna; Part.TRACKS -> tank.tracks
    }.toFloat()

    fun setPart(p: Part, v: Float) {
        when (p) {
            Part.GUN -> gun = v; Part.HULL -> hull = v; Part.ANTENNA -> antenna = v; Part.TRACKS -> tracks = v
        }
    }

    fun repairCost(p: Part): Int = ((partMax(p) - partValue(p)) * Econ.repairCost[p.ordinal]).let { kotlin.math.ceil(it).toInt() }

    /** Repairs as much of [p] as the wallet allows. Returns true if anything changed. */
    fun repair(p: Part): Boolean {
        val missing = partMax(p) - partValue(p)
        if (missing <= 0.01f) return false
        val per = Econ.repairCost[p.ordinal]
        val affordable = min(missing, (money / per).toFloat())
        if (affordable <= 0f) return false
        val pts = if (affordable >= missing) missing else kotlin.math.floor(affordable)
        if (pts <= 0f) return false
        val cost = kotlin.math.ceil(pts * per).toInt()
        money -= cost
        setPart(p, min(partMax(p), partValue(p) + pts))
        return true
    }

    fun refuelCost(): Int = kotlin.math.ceil((tank.fuel - fuel) / 2f * Econ.FUEL_COST_PER_2).toInt()

    fun refuel(): Boolean {
        val missing = tank.fuel - fuel
        if (missing <= 0.5f) return false
        val canBuy = money * 2f / Econ.FUEL_COST_PER_2
        val amount = min(missing, canBuy)
        if (amount < 1f) return false
        money -= kotlin.math.ceil(amount / 2f * Econ.FUEL_COST_PER_2).toInt()
        fuel = min(tank.fuel.toFloat(), fuel + amount)
        return true
    }

    fun upgradeCost(newModel: Int) = max(0, Tanks.all[newModel].price - Tanks.tradeIn(model))

    fun canBuy(newModel: Int) =
        newModel != model && rank >= Tanks.all[newModel].minRank && money >= upgradeCost(newModel)

    fun buy(newModel: Int): Boolean {
        if (!canBuy(newModel)) return false
        money -= upgradeCost(newModel)
        model = newModel
        fillTank()
        return true
    }

    fun resetRoundStats() {
        rShots = 0; rDirect = 0; rDamage = 0; rSuicide = false; rColonists = 0; rBuildings = 0; rHintShots = 0
    }

    fun toJson() = JSONObject().apply {
        put("name", name); put("isAI", isAI); put("aiLevel", aiLevel); put("side", side); put("money", money)
        put("xp", xp); put("model", model); put("gun", gun.toDouble()); put("hull", hull.toDouble())
        put("antenna", antenna.toDouble()); put("tracks", tracks.toDouble()); put("fuel", fuel.toDouble())
        put("roundWins", roundWins); put("lastSalary", lastSalary)
    }

    companion object {
        const val COLOR_LEFT = 0xFF5CCB4A.toInt()
        const val COLOR_RIGHT = 0xFFE8513B.toInt()

        fun fromJson(o: JSONObject) = Player(o.getString("name"), o.getBoolean("isAI"), o.optInt("aiLevel", 1), o.getInt("side")).apply {
            money = o.optInt("money"); xp = o.optInt("xp"); model = o.optInt("model")
            gun = o.optDouble("gun").toFloat(); hull = o.optDouble("hull").toFloat()
            antenna = o.optDouble("antenna").toFloat(); tracks = o.optDouble("tracks").toFloat()
            fuel = o.optDouble("fuel").toFloat(); roundWins = o.optInt("roundWins"); lastSalary = o.optInt("lastSalary")
        }
    }
}

class Match(val players: List<Player>, val totalRounds: Int) {
    var round = 1
    var starter = 0
    var seed = System.nanoTime()

    /** "shop" while players are shopping, "battle" once the round has been entered. */
    var stage = "shop"

    val over get() = round > totalRounds

    /** Pays every player for showing up to the next round. */
    fun paySalaries() {
        for (p in players) {
            p.lastSalary = Ranks.salary(p.rank)
            p.money += p.lastSalary
        }
    }

    fun toJson() = JSONObject().apply {
        put("total", totalRounds); put("round", round); put("starter", starter); put("seed", seed); put("stage", stage)
        put("players", JSONArray().apply { players.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Match {
            val arr = o.getJSONArray("players")
            val ps = (0 until arr.length()).map { Player.fromJson(arr.getJSONObject(it)) }
            return Match(ps, o.getInt("total")).apply {
                round = o.getInt("round"); starter = o.optInt("starter"); seed = o.optLong("seed")
                stage = o.optString("stage", "shop")
            }
        }
    }
}

class Settings {
    var sound = true
    var music = true
    var turnTime = 240
    var hints = true
    var follow = true
    var lastLeft = "Левый"
    var lastRight = "Компьютер"
    var rightIsAI = true
    var aiLevel = 1
    var rounds = 5
    var startMoney = 1500

    fun toJson() = JSONObject().apply {
        put("sound", sound); put("music", music); put("turnTime", turnTime); put("hints", hints); put("follow", follow)
        put("lastLeft", lastLeft); put("lastRight", lastRight); put("rightIsAI", rightIsAI); put("aiLevel", aiLevel)
        put("rounds", rounds); put("startMoney", startMoney); put("version", VERSION)
    }

    companion object {
        const val VERSION = 2
        val TURN_TIMES = listOf(30, 45, 60, 90, 120, 180, 240, 0)

        fun fromJson(o: JSONObject) = Settings().apply {
            sound = o.optBoolean("sound", true); music = o.optBoolean("music", true); turnTime = o.optInt("turnTime", 240); hints = o.optBoolean("hints", true)
            // Version 1 defaulted to 45 seconds; turns now default to four minutes.
            if (o.optInt("version", 1) < 2) turnTime = 240
            follow = o.optBoolean("follow", true); lastLeft = o.optString("lastLeft", "Левый")
            lastRight = o.optString("lastRight", "Компьютер"); rightIsAI = o.optBoolean("rightIsAI", true)
            aiLevel = o.optInt("aiLevel", 1); rounds = o.optInt("rounds", 5); startMoney = o.optInt("startMoney", 1500)
        }
    }
}

/** Key-value persistence; implemented over SharedPreferences on device. */
interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class Storage(private val kv: KeyValue) {
    fun loadSettings(): Settings = kv.get("settings")?.let { runCatching { Settings.fromJson(JSONObject(it)) }.getOrNull() } ?: Settings()
    fun saveSettings(s: Settings) = kv.put("settings", s.toJson().toString())

    fun loadProfiles(): MutableList<Profile> {
        val raw = kv.get("profiles") ?: return mutableListOf()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { Profile.fromJson(a.getJSONObject(it)) }.toMutableList()
        }.getOrElse { mutableListOf() }
    }

    fun saveProfiles(list: List<Profile>) = kv.put("profiles", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())

    fun profile(name: String): Profile {
        val list = loadProfiles()
        return list.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Profile(name)
    }

    fun updateProfile(p: Profile) {
        val list = loadProfiles()
        val i = list.indexOfFirst { it.name.equals(p.name, ignoreCase = true) }
        if (i >= 0) list[i] = p else list.add(p)
        saveProfiles(list)
    }

    fun clearProfiles() = kv.put("profiles", null)

    /** The automatic save (round start, shop visits, leaving a battle). */
    fun hasSave() = kv.get("save") != null
    fun saveMatch(m: Match) = kv.put("save", m.toJson().put("savedAt", System.currentTimeMillis()).toString())
    fun loadMatch(): Match? = kv.get("save")?.let { runCatching { Match.fromJson(JSONObject(it)) }.getOrNull() }
    fun clearSave() = kv.put("save", null)
    fun autoSavedAt(): Long = kv.get("save")?.let { runCatching { JSONObject(it).optLong("savedAt") }.getOrNull() } ?: 0L

    // Numbered save slots the player picks by hand.
    fun saveSlot(i: Int, m: Match) = kv.put("slot_$i", m.toJson().put("savedAt", System.currentTimeMillis()).toString())
    fun loadSlot(i: Int): Match? = kv.get("slot_$i")?.let { runCatching { Match.fromJson(JSONObject(it)) }.getOrNull() }
    fun slotSavedAt(i: Int): Long = kv.get("slot_$i")?.let { runCatching { JSONObject(it).optLong("savedAt") }.getOrNull() } ?: 0L
    fun deleteSlot(i: Int) = kv.put("slot_$i", null)
    fun hasAnySave() = hasSave() || (0 until SLOTS).any { kv.get("slot_$it") != null }

    companion object {
        const val SLOTS = 8
    }
}
