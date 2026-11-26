package com.example.game

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

sealed class MapCollider {
    data class Box(val minX: Float, val maxX: Float, val minZ: Float, val maxZ: Float) : MapCollider()
    data class Circle(val x: Float, val z: Float, val r: Float) : MapCollider()
}

data class WallSegment(
    val minX: Float,
    val maxX: Float,
    val minZ: Float,
    val maxZ: Float,
    val height: Float = 5.4f,
    val isInterior: Boolean = true
)

data class LongTableProp(val x: Float, val z: Float, val sx: Float, val sz: Float, val hasDegchi: Boolean = true)
data class TreeProp(val x: Float, val z: Float, val scale: Float = 1f)
data class BushProp(val x: Float, val z: Float, val radius: Float = 1.6f)
data class StreetLampProp(val x: Float, val z: Float)
data class PillarProp(val x: Float, val z: Float)
data class SofaProp(val x: Float, val z: Float, val yaw: Float)
data class GiftPileProp(val x: Float, val z: Float)

/**
 * Massive 140x140 (-70..+70) Multi-Zone Bangladeshi Community Center & Outdoor Street Complex.
 * - Zone 1: Grand Wedding Hall (Center)
 * - Zone 2: VIP Bridal Room (West Wing, 2 arched doorways)
 * - Zone 3: Kacchi Dining & Buffet Area (East Wing, 2 arched doorways)
 * - Zone 4: Outdoor Garden & Street Courtyard (South & North) with 3 escape vehicles
 */
object VenueMap {
    const val HALF_EXTENT = 70.0f

    // Key Landmarks across the 140x140 complex
    val STAGE_TASK_POS = Vec3(0f, 0f, -13.5f)         // Zone 1: Grand Wedding Hall Stage
    val VIP_TASK_POS = Vec3(-42f, 0f, -9.0f)          // Zone 2: VIP Bridal Room Mirror & Sofa
    val KACCHI_TASK_POS = Vec3(40f, 0f, -4.0f)        // Zone 3: Kacchi Dining & Buffet Degchi

    // 3 Escape Vehicle Gates (South-West CNG, South-East Microbus, North Garden Decorated Rickshaw)
    val GATE_CNG_POS = Vec3(-44f, 0f, 54f)
    val GATE_MICROBUS_POS = Vec3(44f, 0f, 54f)
    val GATE_RICKSHAW_POS = Vec3(0f, 0f, -55f)

    // Grand Illuminated Biye Bari Entrance Gate
    val BIYE_BARI_GATE_POS = Vec3(0f, 0f, 22f)

    // Interior & Exterior Building Walls with proper Arch Doorways!
    val walls: List<WallSegment> = listOf(
        // === NORTH WALL OF BUILDING (z = -26) with North-West & North-East Garden Archways ===
        WallSegment(-56f, -34f, -26.8f, -25.2f, 5.8f, true),
        WallSegment(-26f, 26f, -26.8f, -25.2f, 6.2f, true), // Behind Grand Stage
        WallSegment(34f, 56f, -26.8f, -25.2f, 5.8f, true),

        // === WEST OUTER WALL OF VIP BRIDAL ROOM (x = -56) ===
        WallSegment(-56.8f, -55.2f, -26f, 16f, 5.6f, true),

        // === EAST OUTER WALL OF KACCHI DINING WING (x = +56) ===
        WallSegment(55.2f, 56.8f, -26f, 16f, 5.6f, true),

        // === DIVIDER WALL: GRAND HALL <-> VIP BRIDAL ROOM (x = -24, wide arch doorway at z = -9..-1) ===
        WallSegment(-24.8f, -23.2f, -26f, -9f, 5.6f, true),
        WallSegment(-24.8f, -23.2f, -1f, 16f, 5.6f, true),

        // === DIVIDER WALL: GRAND HALL <-> KACCHI DINING AREA (x = +24, wide arch doorway at z = -9..-1) ===
        WallSegment(23.2f, 24.8f, -26f, -9f, 5.6f, true),
        WallSegment(23.2f, 24.8f, -1f, 16f, 5.6f, true),

        // === SOUTH WALL OF BUILDING (z = +16) with 3 Arch Doorways ===
        // VIP Bridal Room South Doorway at x = -44..-36
        WallSegment(-56f, -44f, 15.2f, 16.8f, 5.6f, true),
        WallSegment(-36f, -8f, 15.2f, 16.8f, 5.6f, true),
        // Grand Hall Main Front Doorway at x = -8..+8 leading straight to Biye Bari Gate!
        WallSegment(8f, 36f, 15.2f, 16.8f, 5.6f, true),
        // Kacchi Dining South Doorway at x = 36..44
        WallSegment(44f, 56f, 15.2f, 16.8f, 5.6f, true),

        // === OUTER COMPOUND BOUNDARY WALLS (with street gate openings!) ===
        // West boundary wall
        WallSegment(-70f, -68.8f, -70f, 44f, 3.6f, false),
        // East boundary wall
        WallSegment(68.8f, 70f, -70f, 44f, 3.6f, false),
        // North boundary wall (open at x = -12..12 for Rickshaw escape gate)
        WallSegment(-70f, -14f, -70f, -68.8f, 3.6f, false),
        WallSegment(14f, 70f, -70f, -68.8f, 3.6f, false)
    )

    // Grand Hall Golden Pillars & Overhead Chandelier anchors
    val pillars = listOf(
        PillarProp(-13f, -10f), PillarProp(13f, -10f),
        PillarProp(-13f, 2f), PillarProp(13f, 2f),
        PillarProp(-13f, 11f), PillarProp(13f, 11f)
    )
    val chandelierPositions = listOf(
        Vec3(0f, 9.2f, -8f),
        Vec3(0f, 9.2f, 5f)
    )

    // Zone 2: VIP Bridal Room Furniture (Dressing Mirror, Sofas, Gift Boxes)
    val vipMirrorPos = Vec3(-52.5f, 0f, -10f)
    val vipSofas = listOf(
        SofaProp(-42f, -20.5f, 0f),
        SofaProp(-31f, 6.5f, 3.14f),
        SofaProp(-49f, 5.5f, 1.57f)
    )
    val vipGiftPiles = listOf(
        GiftPileProp(-51f, -21f),
        GiftPileProp(-30f, -20.5f),
        GiftPileProp(-51f, 11.5f)
    )

    // Zone 3: Kacchi Dining & Buffet Long Tables + Steaming Degchi Pots
    val diningTables = listOf(
        LongTableProp(40f, -16f, 11.5f, 3.4f, hasDegchi = true),
        LongTableProp(40f, -4f, 11.5f, 3.4f, hasDegchi = true),
        LongTableProp(40f, 7f, 11.5f, 3.4f, hasDegchi = true),
        // Buffet counter along east wall
        LongTableProp(52f, -5f, 3.2f, 18.0f, hasDegchi = true)
    )

    // Zone 4: Outdoor Garden Trees, Hiding Bushes (non-blocking soft cover!), & Glowing Streetlamps
    val trees = listOf(
        TreeProp(-58f, 26f, 1.35f), TreeProp(-30f, 30f, 1.15f),
        TreeProp(30f, 30f, 1.2f), TreeProp(58f, 26f, 1.4f),
        TreeProp(-60f, -40f, 1.3f), TreeProp(-24f, -42f, 1.25f),
        TreeProp(24f, -42f, 1.2f), TreeProp(60f, -40f, 1.35f),
        TreeProp(-62f, -5f, 1.25f), TreeProp(62f, -5f, 1.25f)
    )

    // Dense leafy bushes players can crouch/hide behind (soft cover, no hard collision!)
    val hidingBushes = listOf(
        BushProp(-20f, 27f, 2.1f), BushProp(20f, 27f, 2.1f),
        BushProp(-48f, 34f, 2.2f), BushProp(48f, 34f, 2.2f),
        BushProp(-16f, 39f, 2.0f), BushProp(16f, 39f, 2.0f),
        BushProp(-38f, -38f, 2.2f), BushProp(38f, -38f, 2.2f),
        BushProp(-16f, -46f, 2.0f), BushProp(16f, -46f, 2.0f)
    )

    val streetLamps = listOf(
        StreetLampProp(-54f, 45f), StreetLampProp(-24f, 45f),
        StreetLampProp(24f, 45f), StreetLampProp(54f, 45f),
        StreetLampProp(-16f, -52f), StreetLampProp(16f, -52f)
    )

    // Solid colliders for smooth physics + doorway navigation
    val colliders: List<MapCollider> = buildList {
        walls.forEach { w ->
            add(MapCollider.Box(w.minX, w.maxX, w.minZ, w.maxZ))
        }
        // Grand Wedding Stage raised platform back/sofa
        add(MapCollider.Box(-10.5f, 10.5f, -25.0f, -18.5f))
        // Grand Hall pillars
        pillars.forEach { p -> add(MapCollider.Circle(p.x, p.z, 0.95f)) }
        // Biye Bari Entrance Gate side towers (wide 12-unit open center!)
        add(MapCollider.Circle(-7.2f, 22f, 1.15f))
        add(MapCollider.Circle(7.2f, 22f, 1.15f))
        // VIP Bridal Room mirror & sofas
        add(MapCollider.Box(-54.2f, -51.5f, -14.2f, -5.8f))
        vipSofas.forEach { s -> add(MapCollider.Circle(s.x, s.z, 1.85f)) }
        vipGiftPiles.forEach { g -> add(MapCollider.Circle(g.x, g.z, 1.35f)) }
        // Kacchi Dining long tables
        diningTables.forEach { t ->
            add(MapCollider.Box(t.x - t.sx * 0.5f, t.x + t.sx * 0.5f, t.z - t.sz * 0.5f, t.z + t.sz * 0.5f))
        }
        // Outdoor tree trunks & streetlamp poles
        trees.forEach { tr -> add(MapCollider.Circle(tr.x, tr.z, 0.95f)) }
        streetLamps.forEach { l -> add(MapCollider.Circle(l.x, l.z, 0.55f)) }
        // 3 Escape Vehicles solid chassis (escape rings extend generously around them)
        add(MapCollider.Box(-47.0f, -41.0f, 55.2f, 59.2f)) // Green CNG
        add(MapCollider.Box(40.5f, 47.8f, 55.0f, 59.4f))   // Black Microbus
        add(MapCollider.Box(-2.8f, 2.8f, -60.5f, -57.2f))  // Decorated Rickshaw
    }

    // Strategic doorway & courtyard navigation graph nodes for Smart AI bots
    val navWaypoints = listOf(
        Vec3(0f, 0f, -5f),       // Grand Hall Center
        Vec3(0f, 0f, 12f),       // Grand Hall South Entry
        Vec3(0f, 0f, 28f),       // Outside Biye Bari Gate
        Vec3(-24f, 0f, -5f),     // Doorway: Hall <-> VIP Bridal Room
        Vec3(-40f, 0f, -5f),     // VIP Bridal Room Center
        Vec3(-40f, 0f, 16f),     // Doorway: VIP Bridal Room South
        Vec3(-40f, 0f, 34f),     // South-West Courtyard
        Vec3(24f, 0f, -5f),      // Doorway: Hall <-> Kacchi Dining
        Vec3(32f, 0f, -10f),     // Kacchi Dining Lane
        Vec3(40f, 0f, 16f),      // Doorway: Kacchi Dining South
        Vec3(40f, 0f, 34f),      // South-East Courtyard
        Vec3(-30f, 0f, -26f),    // North-West Garden Archway
        Vec3(30f, 0f, -26f),     // North-East Garden Archway
        Vec3(0f, 0f, -40f),      // North Garden Center
        Vec3(0f, 0f, 48f)        // Main Street Road Center
    )

    fun initialTasks(): List<TaskStation> = listOf(
        TaskStation(TaskType.STAGE, STAGE_TASK_POS, radius = 4.6f),
        TaskStation(TaskType.VIP_MAKEUP, VIP_TASK_POS, radius = 4.4f),
        TaskStation(TaskType.KACCHI_TABLE, KACCHI_TASK_POS, radius = 4.6f)
    )

    fun initialGates(): List<EscapeGate> = listOf(
        EscapeGate(VehicleType.CNG, GATE_CNG_POS, radius = 5.5f),
        EscapeGate(VehicleType.MICROBUS, GATE_MICROBUS_POS, radius = 5.6f),
        EscapeGate(VehicleType.RICKSHAW, GATE_RICKSHAW_POS, radius = 5.4f)
    )

    fun spawnPositionFor(role: Role): Vec3 = when (role) {
        Role.BOU -> Vec3(0f, 0f, -13.5f)             // Zone 1 North: Front of Grand Wedding Stage
        Role.BOR -> Vec3(32.5f, 0f, -10.0f)          // Zone 3 East: Kacchi Dining & Buffet Area
        Role.MAMA -> Vec3(0f, 0f, 10.5f)             // Zone 1 South: Grand Entrance Hall
        Role.PHOTOGRAPHER -> Vec3(-36.5f, 0f, -4.5f) // Zone 2 West: VIP Bridal Room / Photo Booth
        Role.BOU_CHOR -> Vec3(0f, 0f, 34.0f)         // Zone 4 South: Outside the Biye Bari Gate
    }

    fun zoneNameAt(pos: Vec3): String = when {
        pos.z in -26f..16f && pos.x in -24f..24f -> "Zone 1: Grand Wedding Hall"
        pos.z in -26f..16f && pos.x in -56f..-24f -> "Zone 2: VIP Bridal Room"
        pos.z in -26f..16f && pos.x in 24f..56f -> "Zone 3: Kacchi Dining Area"
        pos.z >= 44f -> "Zone 4: Escape Street Road"
        pos.z <= -26f -> "Zone 4: North Garden & Rickshaw"
        else -> "Zone 4: Outdoor Courtyard & Gate"
    }

    fun resolveMovement(pos: Vec3, playerRadius: Float = 0.72f): Vec3 {
        var x = pos.x.coerceIn(-HALF_EXTENT + playerRadius, HALF_EXTENT - playerRadius)
        var z = pos.z.coerceIn(-HALF_EXTENT + playerRadius, HALF_EXTENT - playerRadius)

        repeat(3) {
            for (c in colliders) {
                when (c) {
                    is MapCollider.Circle -> {
                        val dx = x - c.x
                        val dz = z - c.z
                        val dist = hypot(dx, dz)
                        val minDist = c.r + playerRadius
                        if (dist < minDist) {
                            if (dist > 1e-4f) {
                                val push = (minDist - dist) / dist
                                x += dx * push
                                z += dz * push
                            } else {
                                x += minDist
                            }
                        }
                    }
                    is MapCollider.Box -> {
                        val closestX = max(c.minX, min(x, c.maxX))
                        val closestZ = max(c.minZ, min(z, c.maxZ))
                        val dx = x - closestX
                        val dz = z - closestZ
                        val distSq = dx * dx + dz * dz
                        if (distSq < playerRadius * playerRadius) {
                            val dist = kotlin.math.sqrt(distSq)
                            if (dist > 1e-4f) {
                                val push = (playerRadius - dist) / dist
                                x += dx * push
                                z += dz * push
                            } else {
                                val dLeft = abs(x - c.minX)
                                val dRight = abs(c.maxX - x)
                                val dTop = abs(z - c.minZ)
                                val dBottom = abs(c.maxZ - z)
                                val m = minOf(dLeft, dRight, dTop, dBottom)
                                when (m) {
                                    dLeft -> x = c.minX - playerRadius
                                    dRight -> x = c.maxX + playerRadius
                                    dTop -> z = c.minZ - playerRadius
                                    else -> z = c.maxZ + playerRadius
                                }
                            }
                        }
                    }
                }
            }
            x = x.coerceIn(-HALF_EXTENT + playerRadius, HALF_EXTENT - playerRadius)
            z = z.coerceIn(-HALF_EXTENT + playerRadius, HALF_EXTENT - playerRadius)
        }
        return Vec3(x, pos.y, z)
    }

    fun hitsObstacle(x: Float, z: Float, radius: Float = 0.35f): Boolean {
        if (abs(x) > HALF_EXTENT - radius || abs(z) > HALF_EXTENT - radius) return true
        for (c in colliders) {
            when (c) {
                is MapCollider.Circle -> {
                    if (hypot(x - c.x, z - c.z) < c.r + radius) return true
                }
                is MapCollider.Box -> {
                    val cx = max(c.minX, min(x, c.maxX))
                    val cz = max(c.minZ, min(z, c.maxZ))
                    if (hypot(x - cx, z - cz) < radius) return true
                }
            }
        }
        return false
    }

    /** Returns true if the straight line segment from a to b is unobstructed by walls/colliders. */
    fun hasLineOfSight(a: Vec3, b: Vec3): Boolean {
        val dist = a.dist2D(b)
        val steps = (dist / 1.8f).toInt().coerceIn(2, 24)
        for (i in 1 until steps) {
            val t = i.toFloat() / steps
            val sx = a.x + (b.x - a.x) * t
            val sz = a.z + (b.z - a.z) * t
            if (hitsObstacle(sx, sz, 0.55f)) return false
        }
        return true
    }

    fun isInsideBuilding(pos: Vec3): Boolean =
        pos.x in -56f..56f && pos.z in -26f..16f

    /**
     * Spring-arm camera raycast from player's shoulder toward desired orbital camera position.
     * Pulls the camera smoothly inward whenever a wall or venue boundary would block the view.
     */
    fun raycastCameraDistance(
        originX: Float,
        originY: Float,
        originZ: Float,
        dirX: Float,
        dirY: Float,
        dirZ: Float,
        maxDist: Float
    ): Float {
        val steps = 28
        val stepLen = maxDist / steps
        val pad = 0.75f
        for (i in 1..steps) {
            val d = i * stepLen
            val px = originX + dirX * d
            val py = originY + dirY * d
            val pz = originZ + dirZ * d
            // Keep camera strictly inside the 140x140 map boundary
            if (abs(px) > HALF_EXTENT - pad || abs(pz) > HALF_EXTENT - pad) {
                return (d - stepLen).coerceAtLeast(3.2f)
            }
            // Check against every wall segment up to its height
            for (wall in walls) {
                if (py <= wall.height + 0.35f &&
                    px >= wall.minX - pad && px <= wall.maxX + pad &&
                    pz >= wall.minZ - pad && pz <= wall.maxZ + pad
                ) {
                    return (d - stepLen).coerceAtLeast(3.2f)
                }
            }
        }
        return maxDist
    }

    /** Picks an intermediate doorway waypoint when a bot's direct path is blocked by room walls. */
    fun nextSteerTarget(from: Vec3, goal: Vec3): Vec3 {
        if (hasLineOfSight(from, goal)) return goal
        val best = navWaypoints
            .filter { it.dist2D(from) > 2.0f && hasLineOfSight(from, it) }
            .minByOrNull { it.dist2D(goal) + it.dist2D(from) * 0.35f }
        return best ?: goal
    }
}
