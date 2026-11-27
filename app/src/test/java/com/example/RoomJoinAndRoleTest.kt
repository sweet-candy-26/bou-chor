package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.SoundEngine
import com.example.game.GameEngine
import com.example.game.LobbyPlayer
import com.example.game.MatchResult
import com.example.game.Role
import com.example.game.Team
import com.example.game.Vec3
import com.example.network.BouChorMqttRelayManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomJoinAndRoleTest {

    @Test
    fun `chor speed buff and jamai guard shield work as designed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = GameEngine(
            soundEngine = SoundEngine(context),
            onBotChat = { _, _, _ -> },
            onMatchFinished = { _ -> }
        )

        val lobby = listOf(
            LobbyPlayer("host_1", "JamaiUser", isHost = true, isLocal = true),
            LobbyPlayer("guest_1", "FriendUser", isHost = false, isLocal = false)
        )
        engine.setPresetMultiplayerRoles(
            mapOf(
                "host_1" to Role.BOR,
                "guest_1" to Role.BOU_CHOR
            )
        )
        var world = engine.createInitialWorld(lobby, isSoloMode = false, roomCode = "T123")
        val chor = world.players.first { it.role == Role.BOU_CHOR }
        val bor = world.players.first { it.role == Role.BOR }
        val bou = world.players.first { it.role == Role.BOU }

        assertTrue("Chor speed should be >= 10.5", chor.role.baseSpeed >= 10.5f)
        assertEquals("GUARD", bor.role.ability2Name)

        // Move Bor next to Bou and trigger Ability 2 (GUARD)
        val playersNearBou = world.players.map {
            if (it.role == Role.BOR) it.copy(pos = Vec3(bou.pos.x + 1.5f, 0f, bou.pos.z)) else it
        }
        world = world.copy(players = playersNearBou)

        val nextWorld = engine.step(
            world = world,
            dt = 0.016f,
            moveInputX = 0f,
            moveInputZ = 0f,
            targetCameraYaw = 0f,
            targetCameraPitch = 0.6f,
            targetCameraDist = 14f,
            ability1Pressed = false,
            ability1Held = false,
            ability2Pressed = true,
            localMicLevel = 0f,
            allowRandomWeatherTransition = false
        )

        val updatedBor = nextWorld.players.first { it.role == Role.BOR }
        val updatedBou = nextWorld.players.first { it.role == Role.BOU }
        assertTrue("Bor should receive speed boost on GUARD", updatedBor.speedBoostTimer > 2.5f)
        assertTrue("Bou should receive couple speed boost on GUARD", updatedBou.speedBoostTimer > 2.5f)
        assertTrue("Bou should receive kidnap immunity shield on GUARD", updatedBou.kidnapImmunityTimer > 3.0f)
        assertTrue("Bor cooldown should start", updatedBor.ability2Cooldown > 5.0f)
    }

    @Test
    fun `mqtt relay host and guest join handshake completes over internet`() {
        val code = "T" + Random.nextInt(100, 999)
        val hostId = "p_host_${Random.nextInt(1000, 9999)}"
        val guestId = "p_guest_${Random.nextInt(1000, 9999)}"

        val hostReceivedHello = CountDownLatch(1)
        val guestReceivedSync = CountDownLatch(1)
        var syncedPlayersCount = 0

        lateinit var hostRelay: BouChorMqttRelayManager
        lateinit var guestRelay: BouChorMqttRelayManager

        hostRelay = BouChorMqttRelayManager(
            localPeerId = hostId,
            onStatus = { _, _ -> },
            onPacketReceived = { senderId, payload ->
                val obj = JSONObject(payload)
                if (obj.optString("type") == "hello" && senderId == guestId) {
                    hostReceivedHello.countDown()
                    val reply = JSONObject().apply {
                        put("type", "hello_reply")
                        put("senderId", hostId)
                        put("name", "HostTest")
                        put("isHost", true)
                        put("roomCode", code)
                    }.toString()
                    hostRelay.publish(reply)

                    val arr = JSONArray().apply {
                        put(JSONObject().put("id", hostId).put("name", "HostTest").put("isHost", true))
                        put(JSONObject().put("id", guestId).put("name", "GuestTest").put("isHost", false))
                    }
                    val sync = JSONObject().apply {
                        put("type", "lobby_sync")
                        put("senderId", hostId)
                        put("roomCode", code)
                        put("players", arr)
                    }.toString()
                    hostRelay.publish(sync)
                }
            }
        )

        guestRelay = BouChorMqttRelayManager(
            localPeerId = guestId,
            onStatus = { _, _ -> },
            onPacketReceived = { _, payload ->
                val obj = JSONObject(payload)
                if (obj.optString("type") == "lobby_sync") {
                    syncedPlayersCount = obj.getJSONArray("players").length()
                    guestRelay.markGuestHandshakeDone()
                    guestReceivedSync.countDown()
                }
            }
        )

        try {
            hostRelay.startHosting(code, "HostTest")
            Thread.sleep(1500)
            guestRelay.startJoining(code, "GuestTest")

            val hostGot = hostReceivedHello.await(12, TimeUnit.SECONDS)
            val guestGot = guestReceivedSync.await(12, TimeUnit.SECONDS)

            assertTrue("Host must receive guest hello via MQTT relay", hostGot)
            assertTrue("Guest must receive host lobby_sync via MQTT relay", guestGot)
            assertEquals(2, syncedPlayersCount)
            assertFalse(hostId == guestId)
        } finally {
            guestRelay.stopAll()
            hostRelay.stopAll()
        }
    }

    @Test
    fun `chor slows mama for 5s only once per entire match`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = GameEngine(
            soundEngine = SoundEngine(context),
            onBotChat = { _, _, _ -> },
            onMatchFinished = { _ -> }
        )

        val lobby = listOf(
            LobbyPlayer("host_chor", "ChorUser", isHost = true, isLocal = true)
        )
        engine.setPresetMultiplayerRoles(mapOf("host_chor" to Role.BOU_CHOR))
        var world = engine.createInitialWorld(lobby, isSoloMode = false, roomCode = "S51")

        // First activation: Chor slows Mama for 5.0 seconds
        world = engine.step(
            world = world,
            dt = 0.05f,
            moveInputX = 0f,
            moveInputZ = 0f,
            targetCameraYaw = 0f,
            targetCameraPitch = 0.6f,
            targetCameraDist = 14f,
            ability1Pressed = false,
            ability1Held = false,
            ability2Pressed = false,
            ability3Pressed = true,
            localMicLevel = 0f,
            allowRandomWeatherTransition = false
        )

        val chorAfterFirst = world.players.first { it.role == Role.BOU_CHOR }
        val mamaAfterFirst = world.players.first { it.role == Role.MAMA }
        assertTrue("Chor mamaSlowUsed flag must lock after first use", chorAfterFirst.mamaSlowUsed)
        assertTrue("Mama must be slowed (~5.0s)", mamaAfterFirst.slowTimer > 4.8f && mamaAfterFirst.slowTimer <= 5.0f)

        // Advance 5.1s so slowTimer drains completely
        repeat(102) {
            world = engine.step(
                world = world,
                dt = 0.05f,
                moveInputX = 0f,
                moveInputZ = 0f,
                targetCameraYaw = 0f,
                targetCameraPitch = 0.6f,
                targetCameraDist = 14f,
                ability1Pressed = false,
                ability1Held = false,
                ability2Pressed = false,
                ability3Pressed = false,
                localMicLevel = 0f,
                allowRandomWeatherTransition = false
            )
        }

        val mamaAfterExpiry = world.players.first { it.role == Role.MAMA }
        assertEquals(0f, mamaAfterExpiry.slowTimer, 0.001f)

        // Attempt second activation in the same match -> must be blocked
        world = engine.step(
            world = world,
            dt = 0.05f,
            moveInputX = 0f,
            moveInputZ = 0f,
            targetCameraYaw = 0f,
            targetCameraPitch = 0.6f,
            targetCameraDist = 14f,
            ability1Pressed = false,
            ability1Held = false,
            ability2Pressed = false,
            ability3Pressed = true,
            localMicLevel = 0f,
            allowRandomWeatherTransition = false
        )

        val mamaAfterSecondTry = world.players.first { it.role == Role.MAMA }
        assertEquals("Second Mama slow in same match must remain blocked", 0f, mamaAfterSecondTry.slowTimer, 0.001f)
    }

    @Test
    fun `jamai picks up bou and holds 3s in getaway car for wedding win`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var matchResult: MatchResult? = null
        val engine = GameEngine(
            soundEngine = SoundEngine(context),
            onBotChat = { _, _, _ -> },
            onMatchFinished = { res -> if (matchResult == null) matchResult = res }
        )

        val lobby = listOf(
            LobbyPlayer("host_bor", "JamaiHero", isHost = true, isLocal = true)
        )
        engine.setPresetMultiplayerRoles(mapOf("host_bor" to Role.BOR))
        var world = engine.createInitialWorld(lobby, isSoloMode = false, roomCode = "B3S")

        // Place Bor right next to Bou
        val bouStart = world.players.first { it.role == Role.BOU }.pos
        world = world.copy(
            players = world.players.map {
                if (it.role == Role.BOR) it.copy(pos = Vec3(bouStart.x + 1.2f, 0f, bouStart.z)) else it
            }
        )

        // Hold Ability 1 for 0.9s (> 0.8s threshold) to lift Bou into Jamai's arms
        repeat(18) {
            world = engine.step(
                world = world,
                dt = 0.05f,
                moveInputX = 0f,
                moveInputZ = 0f,
                targetCameraYaw = 0f,
                targetCameraPitch = 0.6f,
                targetCameraDist = 14f,
                ability1Pressed = false,
                ability1Held = true,
                ability2Pressed = false,
                ability3Pressed = false,
                localMicLevel = 0f,
                allowRandomWeatherTransition = false
            )
        }

        val borCarrying = world.players.first { it.role == Role.BOR }
        val bouCarried = world.players.first { it.role == Role.BOU }
        assertTrue("Bor should be carrying Bou after 0.8s hold", borCarrying.isCarryingBou)
        assertTrue("Bou should have isCarriedByBor set", bouCarried.isCarriedByBor)

        // Release hold briefly, position Bor carrying Bou inside CNG Escape Gate, then hold 3.1s
        world = engine.step(
            world = world,
            dt = 0.05f,
            moveInputX = 0f,
            moveInputZ = 0f,
            targetCameraYaw = 0f,
            targetCameraPitch = 0.6f,
            targetCameraDist = 14f,
            ability1Pressed = false,
            ability1Held = false,
            ability2Pressed = false,
            ability3Pressed = false,
            localMicLevel = 0f,
            allowRandomWeatherTransition = false
        )

        val gatePos = world.gates.first().pos
        world = world.copy(
            players = world.players.map {
                if (it.role == Role.BOR || it.role == Role.BOU) it.copy(pos = gatePos) else it
            }
        )

        // Check that 2.0s hold is NOT enough yet (requires full 3.0s)
        repeat(40) {
            world = engine.step(
                world = world,
                dt = 0.05f,
                moveInputX = 0f,
                moveInputZ = 0f,
                targetCameraYaw = 0f,
                targetCameraPitch = 0.6f,
                targetCameraDist = 14f,
                ability1Pressed = false,
                ability1Held = true,
                ability2Pressed = false,
                ability3Pressed = false,
                localMicLevel = 0f,
                allowRandomWeatherTransition = false
            )
        }
        assertEquals("Match must still be ongoing at 2.0s sit", null, matchResult)

        // Continue holding another 1.15s (total > 3.0s) to trigger Jamai Bidayi victory!
        repeat(23) {
            world = engine.step(
                world = world,
                dt = 0.05f,
                moveInputX = 0f,
                moveInputZ = 0f,
                targetCameraYaw = 0f,
                targetCameraPitch = 0.6f,
                targetCameraDist = 14f,
                ability1Pressed = false,
                ability1Held = true,
                ability2Pressed = false,
                ability3Pressed = false,
                localMicLevel = 0f,
                allowRandomWeatherTransition = false
            )
        }

        val finalRes = matchResult
        assertTrue("Jamai escaping in car after 3s hold must end match", finalRes != null)
        assertEquals(Team.WEDDING, finalRes!!.winningTeam)
        assertEquals("JAMAI ESCAPED WITH BOU!", finalRes.headline)
    }
}
