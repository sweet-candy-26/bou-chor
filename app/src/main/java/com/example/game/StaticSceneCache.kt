package com.example.game

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pre-baked static 3D world geometry & emissive Morich Bati fairy-light buffers.
 * Built ONCE at startup into flat primitive arrays so the 60 FPS render loop does:
 *  - ZERO box decomposition / trig rotations / normal cross-products for static architecture
 *  - ZERO PointLight / SpotLight shader evaluations (1 single baked directional + hemisphere Lambert pass)
 *  - ZERO per-frame heap allocations (no Vec3, RenderQuad, or Offset GC churn)
 */
object StaticSceneCache {

    // Single directional + hemisphere Lambert light vector (matches dynamic character lighting)
    const val LIGHT_X = 0.4158f
    const val LIGHT_Y = 0.8415f
    const val LIGHT_Z = 0.3366f

    class StaticFaceBuffer(
        val count: Int,
        val ax: FloatArray, val ay: FloatArray, val az: FloatArray,
        val bx: FloatArray, val by: FloatArray, val bz: FloatArray,
        val cx: FloatArray, val cy: FloatArray, val cz: FloatArray,
        val dx: FloatArray, val dy: FloatArray, val dz: FloatArray,
        val centerX: FloatArray, val centerZ: FloatArray,
        val baseR: FloatArray, val baseG: FloatArray, val baseB: FloatArray,
        val strokeArgb: IntArray, // 0 = no stroke
        val isTopFace: BooleanArray
    )

    class FairyBulbBuffer(
        val count: Int,
        val x: FloatArray,
        val y: FloatArray,
        val z: FloatArray,
        val colorArgb: IntArray,
        val phaseOffset: FloatArray
    )

    class KacchiSteamAnchor(val x: Float, val z: Float, val phaseBase: Float)

    val staticFaces: StaticFaceBuffer by lazy { buildStaticWorldFaces() }
    val fairyBulbs: FairyBulbBuffer by lazy { buildStaticFairyBulbs() }
    val kacchiSteamAnchors: List<KacchiSteamAnchor> by lazy { buildSteamAnchors() }

    private fun buildSteamAnchors(): List<KacchiSteamAnchor> {
        val list = ArrayList<KacchiSteamAnchor>(16)
        VenueMap.diningTables.forEachIndexed { idx, t ->
            val offsets = if (t.sx > t.sz) arrayOf(-3.2f to 0f, 3.2f to 0f)
            else arrayOf(0f to -5.0f, 0f to 0f, 0f to 5.0f)
            offsets.forEachIndexed { pIdx, (ox, oz) ->
                list.add(KacchiSteamAnchor(t.x + ox, t.z + oz, idx * 0.6f + pIdx * 0.45f))
            }
        }
        return list
    }

    private fun buildStaticFairyBulbs(): FairyBulbBuffer {
        val palette = intArrayOf(
            Color(0xFFFFD166).toArgb(),
            Color(0xFFFF4D8D).toArgb(),
            Color(0xFF38BDF8).toArgb(),
            Color(0xFF00E676).toArgb(),
            Color(0xFFFF9F1C).toArgb()
        )
        val warmCol = Color(0xFFFFE082).toArgb()

        val xs = ArrayList<Float>(96)
        val ys = ArrayList<Float>(96)
        val zs = ArrayList<Float>(96)
        val cols = ArrayList<Int>(96)
        val phases = ArrayList<Float>(96)

        fun addBulb(x: Float, y: Float, z: Float, idx: Int, warmOnly: Boolean) {
            xs.add(x); ys.add(y); zs.add(z)
            cols.add(if (warmOnly) warmCol else palette[idx % palette.size])
            phases.add(idx * 1.15f)
        }

        // Biye Bari Entrance Gate Morich Bati lights
        for (i in 0..10) addBulb(-7.0f + i * 1.4f, 7.6f, 22f, i, false)
        // Grand Stage Archway lights
        for (i in 0..10) addBulb(-8.0f + i * 1.6f, 6.7f, -19.2f, i + 2, false)
        // Overhead Wedding Hall & Courtyard Fairy Light Canopies
        val rows = floatArrayOf(-10f, 0f, 10f, 28f, 36f)
        for (rIdx in rows.indices) {
            val rowZ = rows[rIdx]
            for (i in 0..8) {
                val fx = -16f + i * 4f
                val sag = -sin((i / 8f) * PI.toFloat()) * 0.9f
                addBulb(fx, 6.8f + sag, rowZ, i + rowZ.toInt(), rowZ < 15f)
            }
        }
        // Streetlamp Emissive Bulbs (lightweight emissive meshes — no PointLights!)
        VenueMap.streetLamps.forEachIndexed { idx, l ->
            addBulb(l.x, 6.3f, l.z, idx, true)
        }

        val n = xs.size
        return FairyBulbBuffer(
            count = n,
            x = FloatArray(n) { xs[it] },
            y = FloatArray(n) { ys[it] },
            z = FloatArray(n) { zs[it] },
            colorArgb = IntArray(n) { cols[it] },
            phaseOffset = FloatArray(n) { phases[it] }
        )
    }

    private fun buildStaticWorldFaces(): StaticFaceBuffer {
        val cap = 2600
        val ax = FloatArray(cap); val ay = FloatArray(cap); val az = FloatArray(cap)
        val bx = FloatArray(cap); val by = FloatArray(cap); val bz = FloatArray(cap)
        val cx = FloatArray(cap); val cy = FloatArray(cap); val cz = FloatArray(cap)
        val dx = FloatArray(cap); val dy = FloatArray(cap); val dz = FloatArray(cap)
        val centerX = FloatArray(cap); val centerZ = FloatArray(cap)
        val baseR = FloatArray(cap); val baseG = FloatArray(cap); val baseB = FloatArray(cap)
        val strokeArgb = IntArray(cap)
        val isTopFace = BooleanArray(cap)
        var count = 0

        fun emitFace(
            x0: Float, y0: Float, z0: Float,
            x1: Float, y1: Float, z1: Float,
            x2: Float, y2: Float, z2: Float,
            x3: Float, y3: Float, z3: Float,
            color: Color,
            stroke: Int,
            topFace: Boolean
        ) {
            if (count >= cap) return
            val ux = x1 - x0; val uy = y1 - y0; val uz = z1 - z0
            val vx = x2 - x0; val vy = y2 - y0; val vz = z2 - z0
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val nLen = sqrt(nx * nx + ny * ny + nz * nz)
            if (nLen > 1e-5f) {
                nx /= nLen; ny /= nLen; nz /= nLen
            } else {
                nx = 0f; ny = 1f; nz = 0f
            }
            // Fast baked Lambert + Hemisphere shading (single directional + hemisphere fill)
            val ndl = (nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z).coerceIn(-0.45f, 1f)
            val hemi = 0.56f + (ny * 0.08f)
            val factor = (hemi + ndl * 0.42f).coerceIn(0.36f, 1.12f)

            ax[count] = x0; ay[count] = y0; az[count] = z0
            bx[count] = x1; by[count] = y1; bz[count] = z1
            cx[count] = x2; cy[count] = y2; cz[count] = z2
            dx[count] = x3; dy[count] = y3; dz[count] = z3
            centerX[count] = (x0 + x1 + x2 + x3) * 0.25f
            centerZ[count] = (z0 + z1 + z2 + z3) * 0.25f
            baseR[count] = (color.red * factor).coerceIn(0f, 1f)
            baseG[count] = (color.green * factor).coerceIn(0f, 1f)
            baseB[count] = (color.blue * factor).coerceIn(0f, 1f)
            strokeArgb[count] = stroke
            isTopFace[count] = topFace
            count++
        }

        fun emitBox(
            mx: Float, my: Float, mz: Float,
            sx: Float, sy: Float, sz: Float,
            yaw: Float,
            color: Color,
            topColor: Color = color,
            stroke: Int = 0
        ) {
            val hx = sx * 0.5f; val hy = sy * 0.5f; val hz = sz * 0.5f
            val cY = cos(yaw); val sY = sin(yaw)

            fun vx(lx: Float, lz: Float) = mx + lx * cY + lz * sY
            fun vz(lx: Float, lz: Float) = mz - lx * sY + lz * cY

            val yLo = my - hy; val yHi = my + hy
            val x00 = vx(-hx, -hz); val z00 = vz(-hx, -hz)
            val x10 = vx(hx, -hz);  val z10 = vz(hx, -hz)
            val x11 = vx(hx, hz);   val z11 = vz(hx, hz)
            val x01 = vx(-hx, hz);  val z01 = vz(-hx, hz)

            // Top face
            emitFace(x00, yHi, z00, x01, yHi, z01, x11, yHi, z11, x10, yHi, z10, topColor, stroke, true)
            // +Z side
            emitFace(x01, yLo, z01, x11, yLo, z11, x11, yHi, z11, x01, yHi, z01, color, stroke, false)
            // -Z side
            emitFace(x10, yLo, z10, x00, yLo, z00, x00, yHi, z00, x10, yHi, z10, color, stroke, false)
            // +X side
            emitFace(x11, yLo, z11, x10, yLo, z10, x10, yHi, z10, x11, yHi, z11, color, stroke, false)
            // -X side
            emitFace(x00, yLo, z00, x01, yLo, z01, x01, yHi, z01, x00, yHi, z00, color, stroke, false)
        }

        // 1. Merged Venue Boundary & Interior Walls (chunked cleanly for depth sorting while minimizing face count)
        val wallStroke = Color(0x33401A22).toArgb()
        VenueMap.walls.forEach { wall ->
            val sx = wall.maxX - wall.minX
            val sz = wall.maxZ - wall.minZ
            val wallBody = if (wall.isInterior) Color(0xFFC7A496) else Color(0xFF8C434E)
            val wallTop = if (wall.isInterior) Color(0xFFFFB703) else Color(0xFFD99B66)
            val cxW = (wall.minX + wall.maxX) * 0.5f
            val czW = (wall.minZ + wall.maxZ) * 0.5f
            if (sx > sz && sx > 16f) {
                val steps = (sx / 14f).toInt().coerceAtLeast(2)
                val stepW = sx / steps
                for (k in 0 until steps) {
                    emitBox(wall.minX + stepW * (k + 0.5f), wall.height * 0.5f, czW, stepW, wall.height, sz, 0f, wallBody, wallTop, wallStroke)
                }
            } else if (sz > 16f) {
                val steps = (sz / 14f).toInt().coerceAtLeast(2)
                val stepD = sz / steps
                for (k in 0 until steps) {
                    emitBox(cxW, wall.height * 0.5f, wall.minZ + stepD * (k + 0.5f), sx, wall.height, stepD, 0f, wallBody, wallTop, wallStroke)
                }
            } else {
                emitBox(cxW, wall.height * 0.5f, czW, sx, wall.height, sz, 0f, wallBody, wallTop, wallStroke)
            }
        }

        // 2. Archway Lintels
        emitBox(-24f, 5.1f, -5f, 1.8f, 1.1f, 8.2f, 0f, Color(0xFFD91E43), Color(0xFFFFB703))
        emitBox(24f, 5.1f, -5f, 1.8f, 1.1f, 8.2f, 0f, Color(0xFFD91E43), Color(0xFFFFB703))
        emitBox(0f, 5.2f, 16f, 16.4f, 1.2f, 1.8f, 0f, Color(0xFFD91E43), Color(0xFFFFB703))
        emitBox(-40f, 5.1f, 16f, 8.2f, 1.1f, 1.8f, 0f, Color(0xFFD91E43), Color(0xFFFFB703))
        emitBox(40f, 5.1f, 16f, 8.2f, 1.1f, 1.8f, 0f, Color(0xFFD91E43), Color(0xFFFFB703))

        // 3. Zone 1: Grand Wedding Stage + Pillars + Overhead Chandelier Mounts
        emitBox(0f, 0.55f, -21.5f, 20.5f, 1.1f, 6.4f, 0f, Color(0xFFB5173B), Color(0xFFD91E43))
        emitBox(0f, 3.6f, -24.4f, 19.0f, 5.5f, 0.8f, 0f, Color(0xFF8C0D26), Color(0xFFFFB703))
        emitBox(-8.4f, 3.6f, -19.2f, 1.0f, 5.6f, 1.0f, 0f, Color(0xFFFFB703), Color(0xFFFFE066))
        emitBox(8.4f, 3.6f, -19.2f, 1.0f, 5.6f, 1.0f, 0f, Color(0xFFFFB703), Color(0xFFFFE066))
        emitBox(0f, 6.3f, -19.2f, 18.2f, 0.95f, 1.2f, 0f, Color(0xFFFF8C00), Color(0xFFFFD166))
        emitBox(0f, 1.55f, -21.8f, 6.2f, 1.0f, 2.0f, 0f, Color(0xFFE63946), Color(0xFFFF5A5F))
        emitBox(0f, 2.55f, -22.6f, 6.2f, 1.4f, 0.6f, 0f, Color(0xFFFFB703), Color(0xFFFFE066))
        VenueMap.pillars.forEach { p ->
            emitBox(p.x, 2.8f, p.z, 1.3f, 5.6f, 1.3f, 0f, Color(0xFFF6E2D3), Color(0xFFFFB703))
            emitBox(p.x, 0.45f, p.z, 1.8f, 0.9f, 1.8f, 0f, Color(0xFFD91E43), Color(0xFFFFB703))
        }
        VenueMap.chandelierPositions.forEach { ch ->
            emitBox(ch.x, ch.y + 0.9f, ch.z, 0.35f, 1.8f, 0.35f, 0f, Color(0xFFFFB703))
            emitBox(ch.x, ch.y, ch.z, 3.4f, 0.7f, 3.4f, 0.4f, Color(0xFFFFF5D1), Color(0xFFFFE066))
            emitBox(ch.x, ch.y - 0.65f, ch.z, 2.1f, 0.65f, 2.1f, 0.8f, Color(0xFFE0F7FA), Color(0xFFFFFFFF))
        }

        // 4. Zone 2: VIP Bridal Room
        val mir = VenueMap.vipMirrorPos
        emitBox(mir.x, 2.3f, mir.z, 0.6f, 4.4f, 7.6f, 0f, Color(0xFFFFB703), Color(0xFFFFE066))
        emitBox(mir.x + 0.32f, 2.4f, mir.z, 0.14f, 3.6f, 6.4f, 0f, Color(0xFF9EE7FF), Color(0xFFD6F6FF))
        VenueMap.vipSofas.forEach { s ->
            emitBox(s.x, 0.65f, s.z, 3.8f, 1.1f, 1.7f, s.yaw, Color(0xFFD91E43), Color(0xFFFF4D6D))
            emitBox(s.x, 1.35f, s.z - 0.55f, 3.8f, 0.9f, 0.55f, s.yaw, Color(0xFFFFB703))
        }
        VenueMap.vipGiftPiles.forEach { g ->
            emitBox(g.x, 0.55f, g.z, 1.4f, 1.1f, 1.4f, 0.2f, Color(0xFFFF4D8D), Color(0xFFFFD166))
            emitBox(g.x + 0.75f, 0.45f, g.z + 0.55f, 1.1f, 0.9f, 1.1f, -0.35f, Color(0xFF38BDF8), Color(0xFFFFB703))
            emitBox(g.x + 0.15f, 1.4f, g.z + 0.1f, 0.95f, 0.75f, 0.95f, 0.5f, Color(0xFF00C471), Color(0xFFFFE066))
        }

        // 5. Zone 3: Kacchi Dining Tables, Chairs & Silver Degchis
        val tableStroke = Color(0x443B1F0F).toArgb()
        VenueMap.diningTables.forEach { t ->
            emitBox(t.x, 0.65f, t.z, t.sx, 1.25f, t.sz, 0f, Color(0xFF8C4A27), Color(0xFFFFF3E0), tableStroke)
            emitBox(t.x, 1.30f, t.z, t.sx * 0.92f, 0.08f, t.sz * 0.52f, 0f, Color(0xFFC81D3B), Color(0xFFD91E43))
            if (t.sx > t.sz) {
                for (cxOff in floatArrayOf(-4.0f, 0f, 4.0f)) {
                    emitBox(t.x + cxOff, 0.55f, t.z - t.sz * 0.5f - 0.75f, 0.9f, 1.1f, 0.9f, 0f, Color(0xFF5A2D18), Color(0xFFFFB703))
                    emitBox(t.x + cxOff, 0.55f, t.z + t.sz * 0.5f + 0.75f, 0.9f, 1.1f, 0.9f, 0f, Color(0xFF5A2D18), Color(0xFFFFB703))
                }
            }
            val potOffsets = if (t.sx > t.sz) arrayOf(-3.2f to 0f, 3.2f to 0f) else arrayOf(0f to -5.0f, 0f to 0f, 0f to 5.0f)
            potOffsets.forEach { (ox, oz) ->
                emitBox(t.x + ox, 1.72f, t.z + oz, 1.45f, 0.85f, 1.45f, 0.25f, Color(0xFFBDC7D1), Color(0xFFE5ECF2))
                emitBox(t.x + ox, 2.25f, t.z + oz, 0.65f, 0.24f, 0.65f, 0f, Color(0xFFFFD166))
            }
        }

        // 6. Zone 4: Biye Bari Gate, Trees, Hiding Bushes & Streetlamps
        val gate = VenueMap.BIYE_BARI_GATE_POS
        emitBox(gate.x - 7.2f, 3.8f, gate.z, 1.8f, 7.6f, 1.8f, 0f, Color(0xFFD91E43), Color(0xFFFFB703))
        emitBox(gate.x + 7.2f, 3.8f, gate.z, 1.8f, 7.6f, 1.8f, 0f, Color(0xFFD91E43), Color(0xFFFFB703))
        emitBox(gate.x, 7.3f, gate.z, 16.4f, 1.4f, 1.8f, 0f, Color(0xFFFFB703), Color(0xFFFFE066))

        VenueMap.trees.forEach { tr ->
            val s = tr.scale
            emitBox(tr.x, 1.8f * s, tr.z, 1.1f * s, 3.6f * s, 1.1f * s, 0f, Color(0xFF6B4226))
            emitBox(tr.x, 4.4f * s, tr.z, 4.4f * s, 2.8f * s, 4.4f * s, 0.3f, Color(0xFF1E7B3C), Color(0xFF34A853))
            emitBox(tr.x, 6.4f * s, tr.z, 2.9f * s, 2.0f * s, 2.9f * s, 0.85f, Color(0xFF2B9C4F), Color(0xFF56CC78))
        }
        VenueMap.hidingBushes.forEach { b ->
            emitBox(b.x, 0.95f, b.z, b.radius * 1.45f, 1.9f, b.radius * 1.45f, 0.45f, Color(0xFF1F6E38), Color(0xFF38A159))
            emitBox(b.x + 0.55f, 0.72f, b.z - 0.35f, b.radius * 1.0f, 1.45f, b.radius * 1.0f, 0.9f, Color(0xFF2A8A48), Color(0xFF4CC470))
        }
        VenueMap.streetLamps.forEach { l ->
            emitBox(l.x, 3.2f, l.z, 0.35f, 6.4f, 0.35f, 0f, Color(0xFF374151))
            emitBox(l.x, 6.4f, l.z, 1.15f, 0.55f, 1.15f, 0f, Color(0xFFFFF3B0), Color(0xFFFFFFFF))
        }

        // 7. All 3 Parked Escape Vehicles (Green CNG, Black Microbus, Decorated Rickshaw)
        val cngX = -44f; val cngZ = 57.2f
        emitBox(cngX, 1.05f, cngZ, 4.8f, 1.35f, 2.65f, 0.08f, Color(0xFF009B55), Color(0xFF00C471))
        emitBox(cngX + 2.5f, 0.95f, cngZ - 0.15f, 1.15f, 1.15f, 2.15f, 0.08f, Color(0xFF00B86B))
        emitBox(cngX + 1.8f, 2.15f, cngZ - 0.12f, 0.38f, 1.1f, 2.25f, 0.08f, Color(0xFF93E5FF))
        emitBox(cngX - 0.25f, 2.82f, cngZ, 4.45f, 0.58f, 2.55f, 0.08f, Color(0xFFFFC300), Color(0xFFFFE066))
        emitBox(cngX + 2.35f, 0.42f, cngZ - 0.12f, 0.85f, 0.85f, 0.42f, 0.08f, Color(0xFF14151B))
        emitBox(cngX - 1.55f, 0.45f, cngZ + 1.25f, 0.9f, 0.9f, 0.42f, 0.08f, Color(0xFF14151B))
        emitBox(cngX - 1.55f, 0.45f, cngZ - 1.25f, 0.9f, 0.9f, 0.42f, 0.08f, Color(0xFF14151B))
        emitBox(cngX + 3.05f, 1.05f, cngZ - 0.15f, 0.25f, 0.45f, 0.55f, 0.08f, Color(0xFFFFF59D))

        val busX = 44.2f; val busZ = 57.2f
        emitBox(busX, 1.2f, busZ, 6.2f, 1.6f, 2.95f, -0.06f, Color(0xFF1A1D28), Color(0xFF2A3042))
        emitBox(busX + 0.15f, 2.55f, busZ, 5.65f, 1.25f, 2.8f, -0.06f, Color(0xFF283A4E), Color(0xFF12151E))
        emitBox(busX - 3.1f, 1.1f, busZ - 0.9f, 0.28f, 0.42f, 0.55f, -0.06f, Color(0xFFFFF59D))
        emitBox(busX - 3.1f, 1.1f, busZ + 0.9f, 0.28f, 0.42f, 0.55f, -0.06f, Color(0xFFFFF59D))
        emitBox(busX - 1.9f, 0.48f, busZ + 1.4f, 0.95f, 0.95f, 0.42f, -0.06f, Color(0xFF0B0C10))
        emitBox(busX + 1.9f, 0.48f, busZ + 1.4f, 0.95f, 0.95f, 0.42f, -0.06f, Color(0xFF0B0C10))
        emitBox(busX - 1.9f, 0.48f, busZ - 1.4f, 0.95f, 0.95f, 0.42f, -0.06f, Color(0xFF0B0C10))
        emitBox(busX + 1.9f, 0.48f, busZ - 1.4f, 0.95f, 0.95f, 0.42f, -0.06f, Color(0xFF0B0C10))

        val rickX = 0f; val rickZ = -58.8f
        emitBox(rickX - 0.55f, 1.05f, rickZ, 2.3f, 0.95f, 2.2f, 0f, Color(0xFFD91E43), Color(0xFFFFB703))
        emitBox(rickX - 0.85f, 2.25f, rickZ, 1.9f, 1.55f, 2.25f, 0f, Color(0xFF00B86B), Color(0xFFFF4D8D))
        emitBox(rickX - 0.85f, 3.08f, rickZ, 2.1f, 0.28f, 2.35f, 0f, Color(0xFFFFD166), Color(0xFFFFE066))
        emitBox(rickX + 1.45f, 1.1f, rickZ, 1.75f, 0.24f, 0.28f, 0f, Color(0xFF38BDF8))
        emitBox(rickX + 2.15f, 1.65f, rickZ, 0.25f, 1.25f, 1.25f, 0f, Color(0xFFFFD166))
        emitBox(rickX + 2.25f, 0.58f, rickZ, 1.15f, 1.15f, 0.22f, 0f, Color(0xFF1A1D24))
        emitBox(rickX - 0.65f, 0.65f, rickZ + 1.22f, 1.28f, 1.28f, 0.22f, 0f, Color(0xFF1A1D24))
        emitBox(rickX - 0.65f, 0.65f, rickZ - 1.22f, 1.28f, 1.28f, 0.22f, 0f, Color(0xFF1A1D24))

        return StaticFaceBuffer(
            count = count,
            ax = ax.copyOf(count), ay = ay.copyOf(count), az = az.copyOf(count),
            bx = bx.copyOf(count), by = by.copyOf(count), bz = bz.copyOf(count),
            cx = cx.copyOf(count), cy = cy.copyOf(count), cz = cz.copyOf(count),
            dx = dx.copyOf(count), dy = dy.copyOf(count), dz = dz.copyOf(count),
            centerX = centerX.copyOf(count), centerZ = centerZ.copyOf(count),
            baseR = baseR.copyOf(count), baseG = baseG.copyOf(count), baseB = baseB.copyOf(count),
            strokeArgb = strokeArgb.copyOf(count),
            isTopFace = isTopFace.copyOf(count)
        )
    }
}

/**
 * Zero-GC pooled quad buffer for painter's algorithm depth sorting and direct Android Canvas rendering.
 */
class PooledQuadBuffer(val capacity: Int = 2400) {
    val p0x = FloatArray(capacity); val p0y = FloatArray(capacity)
    val p1x = FloatArray(capacity); val p1y = FloatArray(capacity)
    val p2x = FloatArray(capacity); val p2y = FloatArray(capacity)
    val p3x = FloatArray(capacity); val p3y = FloatArray(capacity)
    val depth = FloatArray(capacity)
    val fillArgb = IntArray(capacity)
    val strokeArgb = IntArray(capacity)
    val order = IntArray(capacity)
    var count: Int = 0
        private set

    fun clear() {
        count = 0
    }

    fun add(
        x0: Float, y0: Float, x1: Float, y1: Float,
        x2: Float, y2: Float, x3: Float, y3: Float,
        d: Float, fill: Int, stroke: Int
    ) {
        val i = count
        if (i >= capacity) return
        p0x[i] = x0; p0y[i] = y0
        p1x[i] = x1; p1y[i] = y1
        p2x[i] = x2; p2y[i] = y2
        p3x[i] = x3; p3y[i] = y3
        depth[i] = d
        fillArgb[i] = fill
        strokeArgb[i] = stroke
        order[i] = i
        count = i + 1
    }

    /**
     * Zero-allocation in-place Shell sort descending by depth (painter's order, back-to-front).
     * Runs in microseconds for 400–1200 visible quads with ZERO heap allocations!
     */
    fun sortDescendingByDepth() {
        val n = count
        if (n <= 1) return
        var gap = n / 2
        while (gap > 0) {
            var i = gap
            while (i < n) {
                val idx = order[i]
                val d = depth[idx]
                var j = i
                while (j >= gap && depth[order[j - gap]] < d) {
                    order[j] = order[j - gap]
                    j -= gap
                }
                order[j] = idx
                i++
            }
            gap = (gap * 5) / 11
            if (gap == 0 && n > 1) break
        }
        // Final insertion pass guarantees exact descending order
        for (i in 1 until n) {
            val idx = order[i]
            val d = depth[idx]
            var j = i - 1
            while (j >= 0 && depth[order[j]] < d) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = idx
        }
    }
}
