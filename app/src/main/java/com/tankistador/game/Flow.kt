package com.tankistador.game

/** Moves a match between the shop and the battlefield. */
object Flow {
    fun newMatch(game: Game): Match {
        val s = game.settings
        val left = Player(s.lastLeft, false, 1, 0)
        val right = Player(s.lastRight, s.rightIsAI, s.aiLevel, 1)
        val m = Match(listOf(left, right), s.rounds)
        for (p in m.players) {
            p.xp = game.store.profile(p.name).xp
            p.money = s.startMoney
            p.model = 0
            p.fillTank()
            val prof = game.store.profile(p.name)
            prof.lastPlayed = System.currentTimeMillis()
            game.store.updateProfile(prof)
        }
        return m
    }

    /** Pays salaries, lets the computer shop and opens the shop for the first human. */
    fun startRound(game: Game, m: Match) {
        m.paySalaries()
        for (p in m.players) if (p.isAI) aiShop(p)
        m.stage = "shop"
        game.store.saveMatch(m)
        openShopOrBattle(game, m, -1)
    }

    fun openShopOrBattle(game: Game, m: Match, after: Int) {
        val next = m.players.indices.firstOrNull { it > after && !m.players[it].isAI }
        if (next != null) {
            game.go(ShopScene(game, m, next))
        } else {
            m.stage = "battle"
            game.store.saveMatch(m)
            game.go(BattleScene(game, m))
        }
    }

    fun resume(game: Game): Boolean {
        val m = game.store.loadMatch() ?: return false
        if (m.over) {
            game.store.clearSave()
            return false
        }
        if (m.stage == "battle") game.go(BattleScene(game, m)) else openShopOrBattle(game, m, -1)
        return true
    }

    /** The computer repairs first, then upgrades when it can keep a reserve for ammunition. */
    fun aiShop(p: Player) {
        p.repair(Part.HULL)
        p.repair(Part.GUN)
        p.repair(Part.TRACKS)
        p.repair(Part.ANTENNA)
        p.refuel()
        val reserve = 450
        for (i in Tanks.all.indices.reversed()) {
            if (i <= p.model) break
            val m = Tanks.all[i]
            if (p.rank >= m.minRank && p.money - p.upgradeCost(i) >= reserve) {
                p.buy(i)
                break
            }
        }
    }
}
