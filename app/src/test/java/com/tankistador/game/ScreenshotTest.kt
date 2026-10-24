package com.tankistador.game

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Renders every screen into PNG files so the art can be reviewed without a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").also { it.mkdirs() }

    private fun shot(game: Game, name: String, w: Int = 1280, h: Int = 720) {
        game.resize(w.toFloat(), h.toFloat())
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        game.draw(Canvas(b))
        FileOutputStream(File(outDir, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun run(game: Game, seconds: Float) {
        var t = 0f
        while (t < seconds) {
            game.update(1f / 60f)
            t += 1f / 60f
        }
    }

    @Test
    fun menus() {
        val kv = MemKV()
        Storage(kv).updateProfile(Profile("Левый").apply { xp = 420; rounds = 6; wins = 4; shots = 30; direct = 9; bestStreak = 3; lastPlayed = 5 })
        Storage(kv).updateProfile(Profile("Компьютер").apply { xp = 310; rounds = 6; wins = 2; shots = 28; direct = 5 })
        val game = newGame(kv)
        run(game, 1f)
        shot(game, "01_menu")
        game.go(NewGameScene(game)); run(game, 0.5f)
        shot(game, "02_new_game")
        game.go(RecordsScene(game)); run(game, 0.5f)
        shot(game, "08_records")
        game.go(SettingsScene(game)); run(game, 0.5f)
        shot(game, "09_settings")
        game.go(AuthorsScene(game)); run(game, 0.5f)
        shot(game, "10_authors")
    }

    @Test
    fun shopAndBattle() {
        val game = newGame()
        val m = aiMatch(3, 11L)
        m.players[0].isAI = false
        m.players[0].name = "Левый"
        m.players[1].name = "Компьютер"
        m.paySalaries()
        game.go(ShopScene(game, m, 0)); run(game, 0.6f)
        shot(game, "03_shop")
        m.players[0].hull = 40f
        game.go(ShopScene(game, m, 0)); run(game, 0.6f)
        game.scene.down(0, 640f - 620f + (1240f / 9f) * 4.5f, 200f)
        run(game, 0.3f)
        shot(game, "04_shop_other_model")

        val battle = BattleScene(game, m)
        game.go(battle)
        run(game, 3.6f)
        shot(game, "05_battle_aim")
        battle.world.tanks[battle.turn].power = 70f
        // Fire whatever the active player has and capture the flight.
        battle.world.fire(battle.world.tanks[battle.turn], Weapons.all[0])
        run(game, 0.6f)
        shot(game, "06_battle_flight")
        game.resize(1560f, 720f)
        shot(game, "07_battle_wide", 1560, 720)
    }

    private fun battle(game: Game, seed: Long): BattleScene {
        val m = aiMatch(3, seed)
        m.players[0].isAI = false
        m.players[0].name = "Левый"
        m.players[1].name = "Компьютер"
        m.players[1].isAI = false
        val b = BattleScene(game, m)
        game.go(b)
        run(game, 3.4f)
        return b
    }

    private fun aimAtEnemy(b: BattleScene, w: Weapon) {
        val me = b.world.tanks[b.turn]
        val enemy = b.world.tanks[1 - b.turn]
        val ai = Ai(b.world, me, enemy, 2, 99999, 9, java.util.Random(1), false)
        while (!ai.think(500)) {}
        me.aim = ai.plan!!.aim
        me.power = ai.plan!!.power
        if (w.thrust > 0f) me.power *= 0.8f
    }

    @Test
    fun weapons() {
        val game = newGame()
        var b = battle(game, 21L)
        b.down(0, 150f, 620f); b.up(0, 150f, 620f)
        run(game, 0.2f)
        shot(game, "14_arsenal")

        for ((name, id, secs) in listOf(
            Triple("15_salvo", 5, 1.1f), Triple("16_cluster", 8, 1.9f), Triple("17_nuke", 19, 0f),
            Triple("18_black_hole", 18, 0f), Triple("19_quake", 15, 0f), Triple("20_frog", 7, 0f),
        )) {
            b = battle(game, 21L + id)
            val w = Weapons.all[id]
            aimAtEnemy(b, w)
            b.world.fire(b.world.tanks[b.turn], w)
            if (secs > 0f) run(game, secs)
            else {
                var guard = 0
                while (b.world.projectiles.isNotEmpty() && guard < 60 * 12) {
                    game.update(1f / 60f); guard++
                }
                run(game, if (id == 18) 1.2f else 0.25f)
            }
            shot(game, name)
        }
    }

    @Test
    fun aiDuel() {
        val game = newGame()
        val m = aiMatch(1, 5L)
        Flow.startRound(game, m)
        var frames = 0
        var shotFlight = false
        while (game.scene is BattleScene && frames < 60 * 60 * 10) {
            game.update(1f / 60f)
            val b = game.scene as? BattleScene
            if (b != null && !shotFlight && b.world.projectiles.isNotEmpty() && frames > 600) {
                run(game, 0.8f)
                shot(game, "11_ai_flight")
                shotFlight = true
            }
            if (b != null && b.world.planets.sumOf { it.version } > 3 && frames % 600 == 0) shot(game, "12_ai_craters_$frames")
            frames++
            if (game.scene is BattleScene && (game.scene as BattleScene).world.tanks.count { it.alive } < 2) {
                run(game, 3f)
                shot(game, "13_round_end")
                break
            }
        }
    }
}
