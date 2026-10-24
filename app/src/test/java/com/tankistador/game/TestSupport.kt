package com.tankistador.game

class MemKV : KeyValue {
    val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

class FakeHost : Host {
    var quitCalled = false
    override fun askText(title: String, initial: String, onDone: (String) -> Unit) = onDone(initial)
    override fun quit() {
        quitCalled = true
    }
}

fun newGame(kv: KeyValue = MemKV()) = Game(FakeHost(), kv, NoSound).also { it.start() }

fun aiMatch(rounds: Int = 2, seed: Long = 42L): Match {
    val a = Player("Альфа", true, 2, 0)
    val b = Player("Бета", true, 1, 1)
    val m = Match(listOf(a, b), rounds)
    m.seed = seed
    for (p in m.players) {
        p.money = 1500
        p.fillTank()
    }
    return m
}
