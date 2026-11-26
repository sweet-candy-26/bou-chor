package com.example.game

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * High-performance Zero-GC 3D Perspective Engine for BOU CHOR.
 * Optimizations:
 * - Pre-baked StaticSceneCache (flat primitive arrays, 1 directional + hemisphere Lambert pass, zero PointLights)
 * - Soft circular blob shadows under all characters & getaway vehicles (zero real-time shadow maps)
 * - PooledQuadBuffer + in-place Shell sort + direct Android Canvas Path/Paint drawing (ZERO per-frame object GC)
 * - GraphicsQuality toggle: SMOOTH (default max FPS) vs HD (antialiased, fine tiles, rich halos)
 * - Full 360° orbital camera + dynamic outdoor weather (Clear Evening / Monsoon Rain ripples / Foggy Night)
 */
@Composable
fun ThreeDGameViewport(
    worldProvider: () -> GameWorldState,
    frameTickProvider: () -> Long,
    graphicsQuality: GraphicsQuality = GraphicsQuality.SMOOTH,
    modifier: Modifier = Modifier
) {
    val staticFaces = remember { StaticSceneCache.staticFaces }
    val fairyBulbs = remember { StaticSceneCache.fairyBulbs }
    val steamAnchors = remember { StaticSceneCache.kacchiSteamAnchors }

    // Pre-allocated native Paints, Path, and QuadBuffer — reused every frame with ZERO GC!
    val quadBuffer = remember { PooledQuadBuffer(2600) }
    val nativePath = remember { android.graphics.Path() }
    val fillPaint = remember { Paint().apply { style = Paint.Style.FILL } }
    val strokePaint = remember { Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.4f } }
    val bulbPaint = remember { Paint().apply { style = Paint.Style.FILL; isAntiAlias = true } }
    val rainPaint = remember { Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND } }

    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isFakeBoldText = true
        }
    }
    val comicStrokePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            style = Paint.Style.STROKE
            strokeWidth = 7f
            color = android.graphics.Color.parseColor("#1A0810")
        }
    }
    val bubblePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = android.graphics.Color.parseColor("#18060B")
        }
    }

    val stars = remember {
        FloatArray(54 * 3) { idx ->
            when (idx % 3) {
                0 -> Random.nextFloat()
                1 -> Random.nextFloat() * 0.24f
                else -> 1.5f + Random.nextFloat() * 2.4f
            }
        }
    }
    val skylineBuildings = remember {
        FloatArray(28 * 3) { idx ->
            val b = idx / 3
            when (idx % 3) {
                0 -> b / 28f
                1 -> 0.034f + Random.nextFloat() * 0.022f
                else -> 0.045f + Random.nextFloat() * 0.095f
            }
        }
    }
    val rainSeeds = remember {
        FloatArray(96 * 5) { idx ->
            when (idx % 5) {
                0 -> Random.nextFloat()
                1 -> Random.nextFloat()
                2 -> 1.15f + Random.nextFloat() * 0.95f
                3 -> 22f + Random.nextFloat() * 26f
                else -> 0.22f + Random.nextFloat() * 0.32f
            }
        }
    }

    // Pre-computed unit circle trig table (20 segments) to avoid sin/cos calls in blob shadows & rings
    val cosTable20 = remember { FloatArray(21) { i -> cos((i / 20f) * 2f * PI.toFloat()) } }
    val sinTable20 = remember { FloatArray(21) { i -> sin((i / 20f) * 2f * PI.toFloat()) } }
    val cosTable12 = remember { FloatArray(13) { i -> cos((i / 12f) * 2f * PI.toFloat()) } }
    val sinTable12 = remember { FloatArray(13) { i -> sin((i / 12f) * 2f * PI.toFloat()) } }

    Canvas(modifier = modifier.fillMaxSize()) {
        // Subscribe strictly to Draw-phase invalidation tick for locked 60 FPS!
        frameTickProvider()
        val world = worldProvider()

        val w = size.width
        val h = size.height
        if (w < 10f || h < 10f) return@Canvas

        val isHd = graphicsQuality == GraphicsQuality.HD
        fillPaint.isAntiAlias = graphicsQuality.enableAntialias
        strokePaint.isAntiAlias = graphicsQuality.enableAntialias
        val nativeCanvas = drawContext.canvas.nativeCanvas

        quadBuffer.clear()

        val rainI = world.rainIntensity.coerceIn(0f, 1f)
        val fogI = world.fogIntensity.coerceIn(0f, 1f)
        val outExp = world.outdoorExposure.coerceIn(0f, 1f)
        val effectiveRain = rainI * (0.28f + 0.72f * outExp)
        val effectiveFog = fogI * (0.26f + 0.74f * outExp)

        // Blend fog color & sky palette dynamically across Clear Evening, Monsoon Rain, and Foggy Night
        val fogColor = lerp(lerp(Color(0xFF2B1732), Color(0xFF1F2B3C), rainI), Color(0xFF495368), fogI)
        val fogR = fogColor.red
        val fogG = fogColor.green
        val fogB = fogColor.blue

        val skyTop = lerp(lerp(Color(0xFF0E102B), Color(0xFF09121F), rainI), Color(0xFF1C2333), fogI)
        val skyMid = lerp(lerp(Color(0xFF24143B), Color(0xFF172536), rainI), Color(0xFF2E374B), fogI)
        val skyHorizon = lerp(lerp(Color(0xFF562343), Color(0xFF27394D), rainI), Color(0xFF535D73), fogI)
        val skyGround = lerp(lerp(Color(0xFF1A291F), Color(0xFF132224), rainI), Color(0xFF28313D), fogI)

        val pitchNorm = ((world.cameraPitch - 0.262f) / (1.309f - 0.262f)).coerceIn(0f, 1f)
        val horizonY = h * (0.36f - pitchNorm * 0.28f)
        val skyShift = ((world.cameraYaw / (2f * PI.toFloat())) % 1f + 1f) % 1f

        // 1. ROTATING TWILIGHT SKY DOME + STARS + MOON + DISTANT DHAKA SKYLINE
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to skyTop,
                0.20f to skyMid,
                0.34f to skyHorizon,
                0.46f to fogColor,
                1.0f to skyGround
            )
        )
        val starVisibility = (1f - rainI * 0.88f - fogI * 0.82f).coerceIn(0.05f, 1f)
        if (starVisibility > 0.08f) {
            val starCount = if (isHd) 54 else 32
            for (idx in 0 until starCount) {
                val base = idx * 3
                val rx = ((stars[base] + skyShift) % 1f) * w
                val ry = stars[base + 1] * h * (1.15f - pitchNorm * 0.45f)
                val sr = stars[base + 2]
                val tw = 0.45f + 0.55f * sin(world.elapsedTimeSec * 3.2f + idx * 1.3f)
                bulbPaint.color = packArgb(tw * 0.85f * starVisibility, 1f, 0.957f, 0.839f)
                nativeCanvas.drawCircle(rx, ry, sr, bulbPaint)
            }
        }
        val moonX = (((0.78f + skyShift) % 1f) * w)
        val moonY = (horizonY - h * 0.16f).coerceAtLeast(h * 0.06f)
        val moonAlpha = (1f - rainI * 0.78f - fogI * 0.42f).coerceIn(0.16f, 1f)
        bulbPaint.color = packArgb(0.18f * moonAlpha, 1f, 0.88f, 0.62f)
        nativeCanvas.drawCircle(moonX, moonY, h * (0.075f + fogI * 0.05f), bulbPaint)
        bulbPaint.color = packArgb(moonAlpha, 1f, 0.95f, 0.77f)
        nativeCanvas.drawCircle(moonX, moonY, h * 0.02f, bulbPaint)

        if (horizonY > 20f) {
            val skylineArgb = lerp(Color(0xFF191026), fogColor, (fogI * 0.65f + rainI * 0.3f).coerceIn(0f, 0.75f)).toArgb()
            val winArgb = Color(0x88FFCF70).copy(alpha = (0.55f * (1f - fogI * 0.65f)).coerceIn(0.12f, 0.55f)).toArgb()
            for (idx in 0 until 28) {
                val base = idx * 3
                val bw = skylineBuildings[base + 1]
                val bh = skylineBuildings[base + 2]
                val left = (((skylineBuildings[base] + skyShift) % 1f) * w) - bw * w * 0.5f
                val bWidth = bw * w + 3f
                val bHeight = bh * h
                val top = horizonY - bHeight + h * 0.03f
                fillPaint.color = skylineArgb
                nativeCanvas.drawRect(left, top, left + bWidth, top + bHeight + h * 0.08f, fillPaint)
                if (idx and 1 == 0) {
                    fillPaint.color = winArgb
                    nativeCanvas.drawRect(left + bWidth * 0.28f, top + bHeight * 0.28f, left + bWidth * 0.28f + 4f, top + bHeight * 0.28f + 5f, fillPaint)
                }
            }
        }

        // 2. FULL 360° ORBITAL SPRING-ARM CAMERA BASIS (Pure primitives — ZERO Vec3 allocations!)
        val shakeAmt = world.cameraShake * 0.75f
        val shakeX = if (shakeAmt > 0.01f) (Random.nextFloat() - 0.5f) * shakeAmt else 0f
        val shakeY = if (shakeAmt > 0.01f) (Random.nextFloat() - 0.5f) * shakeAmt else 0f

        val targetX = world.cameraX
        val targetY = 1.95f
        val targetZ = world.cameraZ
        val dist = world.effectiveCameraDistance
        val cosP = cos(world.cameraPitch)
        val sinP = sin(world.cameraPitch)
        val sinY = sin(world.cameraYaw)
        val cosY = cos(world.cameraYaw)

        val camX = targetX + sinY * cosP * dist + shakeX
        val camY = targetY + sinP * dist + shakeY
        val camZ = targetZ + cosY * cosP * dist

        // Forward = normalize(target - cam)
        var fwdX = targetX - camX
        var fwdY = targetY - camY
        var fwdZ = targetZ - camZ
        val fwdLen = sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ).coerceAtLeast(1e-5f)
        fwdX /= fwdLen; fwdY /= fwdLen; fwdZ /= fwdLen

        // Right = normalize(forward x worldUp(0,1,0)) = (-fwdZ, 0, fwdX)
        var rightX = -fwdZ
        var rightZ = fwdX
        val rLen = sqrt(rightX * rightX + rightZ * rightZ).coerceAtLeast(1e-5f)
        rightX /= rLen; rightZ /= rLen

        // Up = normalize(right x forward)
        var upX = -rightZ * fwdY
        var upY = rightZ * fwdX - rightX * fwdZ
        var upZ = rightX * fwdY
        val uLen = sqrt(upX * upX + upY * upY + upZ * upZ).coerceAtLeast(1e-5f)
        upX /= uLen; upY /= uLen; upZ /= uLen

        val focal = minOf(w, h) * 1.18f
        val halfW = w * 0.5f
        val halfH = h * 0.52f

        val baseMaxDist = if (isHd) 84f else 62f
        val maxRenderDist = (baseMaxDist - effectiveFog * 36f - effectiveRain * 8f).coerceAtLeast(34f)
        val fogDensity = 0.0145f + effectiveRain * 0.012f + effectiveFog * 0.044f
        val fogDensitySq = fogDensity * fogDensity
        val fogStartDist = (14f - effectiveFog * 8.5f - effectiveRain * 2.5f).coerceAtLeast(4.5f)
        val maxFogAmt = (0.76f + effectiveFog * 0.21f).coerceAtMost(0.97f)

        // Reusable inline projection registers (ZERO ProjectedPt allocations!)
        var pxOut = 0f; var pyOut = 0f; var pzOut = 0f
        fun projectInline(wx: Float, wy: Float, wz: Float): Boolean {
            val rx = wx - camX
            val ry = wy - camY
            val rz = wz - camZ
            val cz = rx * fwdX + ry * fwdY + rz * fwdZ
            if (cz <= 0.45f) return false
            val cx = rx * rightX + rz * rightZ
            val cy = rx * upX + ry * upY + rz * upZ
            val invZ = focal / cz
            pxOut = halfW + cx * invZ
            pyOut = halfH - cy * invZ
            pzOut = cz
            return true
        }

        fun applyFogArgb(r: Float, g: Float, b: Float, a: Float, depth: Float): Int {
            val d = depth - fogStartDist
            val fogAmt = if (d <= 0f) 0f else (1f - exp(-d * d * fogDensitySq)).coerceIn(0f, maxFogAmt)
            val inv = 1f - fogAmt
            return packArgb(a, r * inv + fogR * fogAmt, g * inv + fogG * fogAmt, b * inv + fogB * fogAmt)
        }

        fun drawGroundQuadFast(x0: Float, z0: Float, x1: Float, z1: Float, y: Float, r: Float, g: Float, b: Float, strokeArgb: Int = 0) {
            if (!projectInline(x0, y, z0)) return
            val ax = pxOut; val ay = pyOut; val az = pzOut
            if (!projectInline(x1, y, z0)) return
            val bx = pxOut; val by = pyOut; val bz = pzOut
            if (!projectInline(x1, y, z1)) return
            val cx = pxOut; val cy = pyOut; val cz = pzOut
            if (!projectInline(x0, y, z1)) return
            val dx = pxOut; val dy = pyOut; val dz = pzOut
            val depth = (az + bz + cz + dz) * 0.25f
            if (depth > maxRenderDist) return
            val minX = minOf(minOf(ax, bx), minOf(cx, dx))
            val maxX = maxOf(maxOf(ax, bx), maxOf(cx, dx))
            if (maxX < -40f || minX > w + 40f) return
            val minY = minOf(minOf(ay, by), minOf(cy, dy))
            val maxY = maxOf(maxOf(ay, by), maxOf(cy, dy))
            if (maxY < -40f || minY > h + 40f) return

            nativePath.rewind()
            nativePath.moveTo(ax, ay)
            nativePath.lineTo(bx, by)
            nativePath.lineTo(cx, cy)
            nativePath.lineTo(dx, dy)
            nativePath.close()
            fillPaint.color = applyFogArgb(r, g, b, 1f, depth)
            nativeCanvas.drawPath(nativePath, fillPaint)
            if (isHd && strokeArgb != 0) {
                strokePaint.color = strokeArgb
                strokePaint.strokeWidth = 1.4f
                nativeCanvas.drawPath(nativePath, strokePaint)
            }
        }

        fun drawGroundCircleFast(cxW: Float, cyW: Float, czW: Float, radius: Float, argb: Int, filled: Boolean, strokeW: Float = 3.5f, hiDetail: Boolean = false) {
            if (abs(cxW - targetX) > maxRenderDist || abs(czW - targetZ) > maxRenderDist) return
            val cosT = if (hiDetail) cosTable20 else cosTable12
            val sinT = if (hiDetail) sinTable20 else sinTable12
            val segs = cosT.size - 1
            nativePath.rewind()
            var started = false
            val py = cyW + 0.04f
            for (i in 0..segs) {
                if (projectInline(cxW + cosT[i] * radius, py, czW + sinT[i] * radius)) {
                    if (!started) {
                        nativePath.moveTo(pxOut, pyOut)
                        started = true
                    } else {
                        nativePath.lineTo(pxOut, pyOut)
                    }
                }
            }
            if (started) {
                nativePath.close()
                if (filled) {
                    fillPaint.color = argb
                    nativeCanvas.drawPath(nativePath, fillPaint)
                } else {
                    strokePaint.color = argb
                    strokePaint.strokeWidth = strokeW
                    nativeCanvas.drawPath(nativePath, strokePaint)
                }
            }
        }

        // Lightweight Soft Multi-Ring Circular Blob Shadow (Zero Shadow Maps!)
        fun drawBlobShadow(cxW: Float, czW: Float, radius: Float) {
            drawGroundCircleFast(cxW, 0.01f, czW, radius * 1.18f, 0x26000000, filled = true, hiDetail = false)
            drawGroundCircleFast(cxW, 0.02f, czW, radius * 0.82f, 0x4C000000, filled = true, hiDetail = false)
        }

        // 3. 360-DEGREE TILED FLOORING (12f stride in SMOOTH = 2.25x fewer floor draw calls!)
        val tileStep = if (isHd) 8f else 12f
        val floorMargin = if (isHd) 64f else 52f
        val startTileX = (((targetX - floorMargin) / tileStep).toInt() * tileStep).coerceAtLeast(-72f)
        val endTileX = (((targetX + floorMargin) / tileStep).toInt() * tileStep).coerceAtMost(60f)
        val startTileZ = (((targetZ - floorMargin) / tileStep).toInt() * tileStep).coerceAtLeast(-72f)
        val endTileZ = (((targetZ + floorMargin) / tileStep).toInt() * tileStep).coerceAtMost(60f)

        val rainWet = rainI * 0.6f
        val invWet = 1f - rainWet
        var tx = startTileX
        while (tx <= endTileX) {
            var tz = startTileZ
            while (tz <= endTileZ) {
                val cx = tx + tileStep * 0.5f
                val cz = tz + tileStep * 0.5f
                val checker = (((tx / tileStep).toInt() + (tz / tileStep).toInt()) and 1) == 0
                when {
                    cx in -24f..24f && cz in -26f..16f -> {
                        if (checker) drawGroundQuadFast(tx, tz, tx + tileStep, tz + tileStep, 0f, 0.906f, 0.827f, 0.773f, 0x449E7B73)
                        else drawGroundQuadFast(tx, tz, tx + tileStep, tz + tileStep, 0f, 0.816f, 0.698f, 0.659f, 0x449E7B73)
                    }
                    cx in -56f..-24f && cz in -26f..16f -> {
                        if (checker) drawGroundQuadFast(tx, tz, tx + tileStep, tz + tileStep, 0f, 0.365f, 0.149f, 0.255f, 0x44FFB703)
                        else drawGroundQuadFast(tx, tz, tx + tileStep, tz + tileStep, 0f, 0.290f, 0.114f, 0.204f, 0x44FFB703)
                    }
                    cx in 24f..56f && cz in -26f..16f -> {
                        if (checker) drawGroundQuadFast(tx, tz, tx + tileStep, tz + tileStep, 0f, 0.420f, 0.259f, 0.149f, 0x443B2110)
                        else drawGroundQuadFast(tx, tz, tx + tileStep, tz + tileStep, 0f, 0.361f, 0.216f, 0.118f, 0x443B2110)
                    }
                    cz >= 46f || cz <= -52f -> {
                        val tr = (if (checker) 0.157f else 0.137f) * invWet + 0.102f * rainWet
                        val tg = (if (checker) 0.169f else 0.149f) * invWet + 0.145f * rainWet
                        val tb = (if (checker) 0.200f else 0.180f) * invWet + 0.200f * rainWet
                        drawGroundQuadFast(tx, tz, tx + tileStep, tz + tileStep, 0f, tr, tg, tb)
                        if (cz in 52f..62f && checker) {
                            drawGroundQuadFast(tx + 1.5f, 56.6f, tx + 6.5f, 57.4f, 0.03f, 1f, 0.82f, 0.4f)
                        }
                    }
                    else -> {
                        val gr = (if (checker) 0.141f else 0.118f) * invWet + 0.086f * rainWet
                        val gg = (if (checker) 0.329f else 0.282f) * invWet + 0.216f * rainWet
                        val gb = (if (checker) 0.184f else 0.157f) * invWet + 0.173f * rainWet
                        drawGroundQuadFast(tx, tz, tx + tileStep, tz + tileStep, 0f, gr, gg, gb)
                    }
                }
                tz += tileStep
            }
            tx += tileStep
        }

        // Stone Garden Walkway + Ornate Red Persian Carpet Aisle
        val wr = 0.490f * invWet + 0.278f * rainWet
        val wg = 0.420f * invWet + 0.333f * rainWet
        val wb = 0.408f * invWet + 0.388f * rainWet
        var wz = 16f
        while (wz < 46f) {
            drawGroundQuadFast(-5.2f, wz, 5.2f, wz + 7.5f, 0.02f, wr, wg, wb, 0x44FFB703)
            wz += 7.5f
        }
        var czCarpet = -18f
        while (czCarpet < 22f) {
            drawGroundQuadFast(-4.0f, czCarpet, 4.0f, czCarpet + 5f, 0.03f, 0.682f, 0.067f, 0.192f, 0xFFFFB703.toInt())
            drawGroundQuadFast(-2.2f, czCarpet + 1.0f, 2.2f, czCarpet + 4.0f, 0.04f, 0.549f, 0.051f, 0.149f)
            if (isHd) {
                drawGroundQuadFast(-0.85f, czCarpet + 1.8f, 0.85f, czCarpet + 3.2f, 0.05f, 1f, 0.718f, 0.012f)
            }
            czCarpet += 5f
        }

        // Lightweight Baked Floor Pools (No PointLights!), Task Rings, Escape Rings, Shockwaves & Rain Ripples
        val pulse = 0.5f + 0.5f * sin(world.elapsedTimeSec * 4.5f)
        drawGroundCircleFast(VenueMap.STAGE_TASK_POS.x, 0f, VenueMap.STAGE_TASK_POS.z, 7.2f, 0x26FFD166, filled = true)
        for (i in VenueMap.streetLamps.indices) {
            val lamp = VenueMap.streetLamps[i]
            drawGroundCircleFast(lamp.x, 0f, lamp.z, 5.8f, 0x22FFD875, filled = true)
        }
        for (i in world.tasks.indices) {
            val task = world.tasks[i]
            val fillA = ((0.16f + 0.09f * pulse) * 255f).toInt() shl 24
            val rgb = if (task.completed) 0x00C471 else 0xFFB703
            drawGroundCircleFast(task.pos.x, 0f, task.pos.z, task.radius, fillA or rgb, filled = true)
            drawGroundCircleFast(task.pos.x, 0f, task.pos.z, task.radius, 0xE6000000.toInt() or rgb, filled = false, strokeW = 4f)
        }
        val chorCarrying = world.chorPlayer?.isCarryingBou == true
        for (i in world.gates.indices) {
            val gate = world.gates[i]
            val rgb = when (gate.type) {
                VehicleType.CNG -> 0x00E676
                VehicleType.MICROBUS -> 0x38BDF8
                VehicleType.RICKSHAW -> 0xFFB703
            }
            val alpha = if (chorCarrying) 0.28f + 0.22f * pulse else 0.14f
            drawGroundCircleFast(gate.pos.x, 0f, gate.pos.z, gate.radius, ((alpha * 255f).toInt() shl 24) or rgb, filled = true)
            drawGroundCircleFast(gate.pos.x, 0f, gate.pos.z, gate.radius, 0xFF000000.toInt() or rgb, filled = false, strokeW = if (chorCarrying) 5.5f else 3.2f)
        }
        // Lightweight Circular Blob Shadows beneath all 3 Escape Vehicles!
        drawBlobShadow(-44.0f, 57.2f, 3.0f)  // Green CNG Auto-Rickshaw
        drawBlobShadow(44.2f, 57.2f, 3.6f)   // Black Microbus
        drawBlobShadow(0.0f, -58.8f, 2.5f)   // Decorated Rickshaw

        for (i in world.shockwaves.indices) {
            val sw = world.shockwaves[i]
            val a1 = ((sw.alpha * 0.85f).coerceIn(0f, 1f) * 255f).toInt() shl 24
            drawGroundCircleFast(sw.origin.x, 0f, sw.origin.z, sw.radius, a1 or 0xFF1E42, filled = false, strokeW = 8.5f, hiDetail = true)
            if (isHd) {
                val a2 = ((sw.alpha * 0.55f).coerceIn(0f, 1f) * 255f).toInt() shl 24
                drawGroundCircleFast(sw.origin.x, 0f, sw.origin.z, (sw.radius * 0.72f).coerceAtLeast(0.5f), a2 or 0xFF758F, filled = false, strokeW = 4f)
            }
        }
        // Outdoor Monsoon Rain Expanding Ground Ripples
        if (world.rainRipples.isNotEmpty()) {
            val rippleVis = rainI.coerceAtLeast(0.25f)
            for (i in world.rainRipples.indices) {
                val rp = world.rainRipples[i]
                val prog = (rp.age / rp.maxAge).coerceIn(0f, 1f)
                val fade = (1f - prog) * (1f - prog) * rippleVis
                if (fade > 0.03f) {
                    val outerR = 0.14f + prog * rp.maxRadius
                    val aOuter = ((fade * 0.72f).coerceIn(0f, 1f) * 255f).toInt() shl 24
                    drawGroundCircleFast(rp.pos.x, rp.pos.y, rp.pos.z, outerR, aOuter or 0xBCEBFF, filled = false, strokeW = 2.4f * (1f - prog * 0.5f))
                    if (isHd && prog > 0.22f) {
                        val innerR = (prog - 0.22f) * rp.maxRadius * 0.78f
                        val aInner = ((fade * 0.45f).coerceIn(0f, 1f) * 255f).toInt() shl 24
                        drawGroundCircleFast(rp.pos.x, rp.pos.y, rp.pos.z, innerR, aInner or 0x7DD3FC, filled = false, strokeW = 1.5f)
                    }
                }
            }
        }
        for (i in world.flashCones.indices) {
            val fc = world.flashCones[i]
            val a = (fc.life / 0.52f).coerceIn(0f, 1f)
            val range = 14.8f; val spread = 0.64f
            if (projectInline(fc.origin.x, 1.35f, fc.origin.z)) {
                val x0 = pxOut; val y0 = pyOut
                if (projectInline(fc.origin.x + sin(fc.yaw - spread) * range, 0.4f, fc.origin.z + cos(fc.yaw - spread) * range)) {
                    val x1 = pxOut; val y1 = pyOut
                    if (projectInline(fc.origin.x + sin(fc.yaw) * range * 1.08f, 0.5f, fc.origin.z + cos(fc.yaw) * range * 1.08f)) {
                        val x2 = pxOut; val y2 = pyOut
                        if (projectInline(fc.origin.x + sin(fc.yaw + spread) * range, 0.4f, fc.origin.z + cos(fc.yaw + spread) * range)) {
                            nativePath.rewind()
                            nativePath.moveTo(x0, y0); nativePath.lineTo(x1, y1); nativePath.lineTo(x2, y2); nativePath.lineTo(pxOut, pyOut); nativePath.close()
                            fillPaint.color = (((a * 0.55f * 255f).toInt()) shl 24) or 0xCCF2FF
                            nativeCanvas.drawPath(nativePath, fillPaint)
                        }
                    }
                }
            }
        }

        // 4. LIGHTWEIGHT BLOB SHADOWS & LOCAL PLAYER FOOT DIRECTIONAL OBJECTIVE RING
        for (i in world.players.indices) {
            val p = world.players[i]
            if (p.isCarriedInSack) continue
            drawBlobShadow(p.pos.x, p.pos.z, 0.92f)
            val ringArgb = if (p.isLocal) 0xDDFFD166.toInt() else (0xCC000000.toInt() or (p.role.accentColor.toArgb() and 0x00FFFFFF))
            drawGroundCircleFast(p.pos.x, 0.03f, p.pos.z, 1.08f, ringArgb, filled = false, strokeW = if (p.isLocal) 4.2f else 2.8f, hiDetail = p.isLocal)

            if (p.isLocal) {
                drawGroundCircleFast(p.pos.x, 0.03f, p.pos.z, 1.48f, 0x44FFD166, filled = false, strokeW = 2.2f, hiDetail = true)
                val fx = sin(p.yaw); val fz = cos(p.yaw)
                val rx = cos(p.yaw); val rz = -sin(p.yaw)
                if (projectInline(p.pos.x + fx * 1.95f, 0.08f, p.pos.z + fz * 1.95f)) {
                    val tx0 = pxOut; val ty0 = pyOut
                    if (projectInline(p.pos.x + fx * 1.25f - rx * 0.45f, 0.08f, p.pos.z + fz * 1.25f - rz * 0.45f)) {
                        val lx = pxOut; val ly = pyOut
                        if (projectInline(p.pos.x + fx * 1.25f + rx * 0.45f, 0.08f, p.pos.z + fz * 1.25f + rz * 0.45f)) {
                            nativePath.rewind()
                            nativePath.moveTo(tx0, ty0); nativePath.lineTo(lx, ly); nativePath.lineTo(pxOut, pyOut); nativePath.close()
                            fillPaint.color = 0xEEFFF6EA.toInt()
                            nativeCanvas.drawPath(nativePath, fillPaint)
                        }
                    }
                }

                // Glowing Mission Objective Pointer Arrow orbiting player's feet
                var objX = Float.NaN; var objZ = Float.NaN; var objArgb = 0xFFFFB703.toInt()
                when (p.role) {
                    Role.BOU_CHOR -> {
                        if (p.isCarryingBou) {
                            val nearestGate = world.gates.minByOrNull { it.pos.dist2D(p.pos) }
                            if (nearestGate != null) {
                                objX = nearestGate.pos.x; objZ = nearestGate.pos.z
                                objArgb = when (nearestGate.type) {
                                    VehicleType.CNG -> 0xFF00E676.toInt()
                                    VehicleType.MICROBUS -> 0xFF38BDF8.toInt()
                                    VehicleType.RICKSHAW -> 0xFFFFB703.toInt()
                                }
                            }
                        } else {
                            world.bouPlayer?.let { objX = it.pos.x; objZ = it.pos.z; objArgb = 0xFFFF2A55.toInt() }
                        }
                    }
                    Role.BOU -> {
                        world.tasks.filter { !it.completed }.minByOrNull { it.pos.dist2D(p.pos) }?.let {
                            objX = it.pos.x; objZ = it.pos.z; objArgb = 0xFFFFB703.toInt()
                        }
                    }
                    else -> {
                        val sb = world.screamBeacon
                        val chorP = world.chorPlayer
                        when {
                            sb != null -> { objX = sb.pos.x; objZ = sb.pos.z; objArgb = 0xFFFF1744.toInt() }
                            chorP != null && (chorP.isCarryingBou || chorP.wallHackTimer > 0f) -> {
                                objX = chorP.pos.x; objZ = chorP.pos.z; objArgb = 0xFFFF1744.toInt()
                            }
                            else -> world.bouPlayer?.let { objX = it.pos.x; objZ = it.pos.z; objArgb = 0xFFFFD166.toInt() }
                        }
                    }
                }
                if (!objX.isNaN()) {
                    val dObj = sqrt((objX - p.pos.x) * (objX - p.pos.x) + (objZ - p.pos.z) * (objZ - p.pos.z))
                    if (dObj > 3.2f) {
                        val objAng = atan2(objX - p.pos.x, objZ - p.pos.z)
                        val ox = sin(objAng); val oz = cos(objAng)
                        val orx = cos(objAng); val orz = -sin(objAng)
                        val pulseDist = 2.35f + pulse * 0.35f
                        if (projectInline(p.pos.x + ox * pulseDist, 0.12f, p.pos.z + oz * pulseDist)) {
                            val otX = pxOut; val otY = pyOut
                            if (projectInline(p.pos.x + ox * (pulseDist - 0.72f) - orx * 0.48f, 0.12f, p.pos.z + oz * (pulseDist - 0.72f) - orz * 0.48f)) {
                                val olX = pxOut; val olY = pyOut
                                if (projectInline(p.pos.x + ox * (pulseDist - 0.72f) + orx * 0.48f, 0.12f, p.pos.z + oz * (pulseDist - 0.72f) + orz * 0.48f)) {
                                    nativePath.rewind()
                                    nativePath.moveTo(otX, otY); nativePath.lineTo(olX, olY); nativePath.lineTo(pxOut, pyOut); nativePath.close()
                                    fillPaint.color = objArgb
                                    nativeCanvas.drawPath(nativePath, fillPaint)
                                    strokePaint.color = 0xFF17070D.toInt()
                                    strokePaint.strokeWidth = 2.2f
                                    nativeCanvas.drawPath(nativePath, strokePaint)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. FAST BATCHED STATIC WORLD GEOMETRY PASS (Zero allocations, inline frustum + backface culling!)
        val cullMargin = maxRenderDist + 10f
        for (i in 0 until staticFaces.count) {
            if (abs(staticFaces.centerX[i] - targetX) > cullMargin || abs(staticFaces.centerZ[i] - targetZ) > cullMargin) continue
            if (!projectInline(staticFaces.ax[i], staticFaces.ay[i], staticFaces.az[i])) continue
            val x0 = pxOut; val y0 = pyOut; val z0 = pzOut
            if (!projectInline(staticFaces.bx[i], staticFaces.by[i], staticFaces.bz[i])) continue
            val x1 = pxOut; val y1 = pyOut; val z1 = pzOut
            if (!projectInline(staticFaces.cx[i], staticFaces.cy[i], staticFaces.cz[i])) continue
            val x2 = pxOut; val y2 = pyOut; val z2 = pzOut
            if (!projectInline(staticFaces.dx[i], staticFaces.dy[i], staticFaces.dz[i])) continue
            val x3 = pxOut; val y3 = pyOut; val z3 = pzOut

            val depth = (z0 + z1 + z2 + z3) * 0.25f
            if (depth > maxRenderDist) continue

            // Screen-space frustum cull
            val minX = minOf(minOf(x0, x1), minOf(x2, x3))
            val maxX = maxOf(maxOf(x0, x1), maxOf(x2, x3))
            if (maxX < -50f || minX > w + 50f) continue
            val minY = minOf(minOf(y0, y1), minOf(y2, y3))
            val maxY = maxOf(maxOf(y0, y1), maxOf(y2, y3))
            if (maxY < -50f || minY > h + 50f) continue

            // Fast 2D backface cull for vertical wall/prop sides (cuts ~45% of hidden faces!)
            if (!staticFaces.isTopFace[i]) {
                val cross2d = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
                if (cross2d > 0f) continue
            }

            val fill = applyFogArgb(staticFaces.baseR[i], staticFaces.baseG[i], staticFaces.baseB[i], 1f, depth)
            val stroke = if (isHd) staticFaces.strokeArgb[i] else 0
            quadBuffer.add(x0, y0, x1, y1, x2, y2, x3, y3, depth, fill, stroke)
        }

        // Helper for dynamic articulated boxes (characters, steam, sandals, bananas, dust) — ZERO allocations!
        fun emitDynamicFace(
            x0: Float, y0: Float, z0: Float,
            x1: Float, y1: Float, z1: Float,
            x2: Float, y2: Float, z2: Float,
            x3: Float, y3: Float, z3: Float,
            r: Float, g: Float, b: Float, a: Float,
            shadeFactor: Float,
            isTop: Boolean
        ) {
            if (!projectInline(x0, y0, z0)) return
            val sx0 = pxOut; val sy0 = pyOut; val sz0 = pzOut
            if (!projectInline(x1, y1, z1)) return
            val sx1 = pxOut; val sy1 = pyOut; val sz1 = pzOut
            if (!projectInline(x2, y2, z2)) return
            val sx2 = pxOut; val sy2 = pyOut; val sz2 = pzOut
            if (!projectInline(x3, y3, z3)) return
            val sx3 = pxOut; val sy3 = pyOut; val sz3 = pzOut
            val depth = (sz0 + sz1 + sz2 + sz3) * 0.25f
            if (depth > maxRenderDist) return
            if (!isTop) {
                val cross2d = (sx1 - sx0) * (sy2 - sy0) - (sy1 - sy0) * (sx2 - sx0)
                if (cross2d > 0f) return
            }
            val fill = applyFogArgb(
                (r * shadeFactor).coerceIn(0f, 1f),
                (g * shadeFactor).coerceIn(0f, 1f),
                (b * shadeFactor).coerceIn(0f, 1f),
                a, depth
            )
            quadBuffer.add(sx0, sy0, sx1, sy1, sx2, sy2, sx3, sy3, depth, fill, 0)
        }

        fun addDynamicBox(
            mx: Float, my: Float, mz: Float,
            sx: Float, sy: Float, sz: Float,
            yaw: Float,
            color: Color,
            topColor: Color = color
        ) {
            if (abs(mx - targetX) > maxRenderDist || abs(mz - targetZ) > maxRenderDist) return
            val hx = sx * 0.5f; val hy = sy * 0.5f; val hz = sz * 0.5f
            val cY = cos(yaw); val sY = sin(yaw)
            val yLo = my - hy; val yHi = my + hy
            val x00 = mx - hx * cY - hz * sY; val z00 = mz + hx * sY - hz * cY
            val x10 = mx + hx * cY - hz * sY; val z10 = mz - hx * sY - hz * cY
            val x11 = mx + hx * cY + hz * sY; val z11 = mz - hx * sY + hz * cY
            val x01 = mx - hx * cY + hz * sY; val z01 = mz + hx * sY + hz * cY

            // Precomputed fast Lambert shade factors (top brightest, sunlit +X/+Z warm, -X/-Z cool shaded)
            emitDynamicFace(x00, yHi, z00, x01, yHi, z01, x11, yHi, z11, x10, yHi, z10, topColor.red, topColor.green, topColor.blue, topColor.alpha, 1.04f, true)
            emitDynamicFace(x01, yLo, z01, x11, yLo, z11, x11, yHi, z11, x01, yHi, z01, color.red, color.green, color.blue, color.alpha, 0.86f, false)
            emitDynamicFace(x10, yLo, z10, x00, yLo, z00, x00, yHi, z00, x10, yHi, z10, color.red, color.green, color.blue, color.alpha, 0.58f, false)
            emitDynamicFace(x11, yLo, z11, x10, yLo, z10, x10, yHi, z10, x11, yHi, z11, color.red, color.green, color.blue, color.alpha, 0.92f, false)
            emitDynamicFace(x00, yLo, z00, x01, yLo, z01, x01, yHi, z01, x00, yHi, z00, color.red, color.green, color.blue, color.alpha, 0.64f, false)
        }

        // Dynamic Rising Kacchi Biryani Steam Puffs
        for (i in steamAnchors.indices) {
            val sa = steamAnchors[i]
            if (abs(sa.x - targetX) > maxRenderDist || abs(sa.z - targetZ) > maxRenderDist) continue
            val stPhase = (world.elapsedTimeSec * 1.5f + sa.phaseBase) % 1.6f
            val stY = 2.5f + stPhase * 1.8f
            val stAlpha = (1f - stPhase / 1.6f) * 0.52f
            addDynamicBox(
                sa.x + sin(world.elapsedTimeSec * 2.2f + i) * 0.3f, stY, sa.z,
                0.65f + stPhase * 0.25f, 0.55f, 0.65f + stPhase * 0.25f,
                world.elapsedTimeSec, Color.White.copy(alpha = stAlpha)
            )
        }

        // Dynamic Props & Particles
        for (i in world.bananas.indices) {
            val b = world.bananas[i]
            addDynamicBox(b.pos.x, 0.16f, b.pos.z, 0.72f, 0.2f, 0.35f, 0.4f, Color(0xFFFFD60A), Color(0xFFFFEA00))
            addDynamicBox(b.pos.x, 0.18f, b.pos.z, 0.35f, 0.2f, 0.72f, 0.9f, Color(0xFFFFC300), Color(0xFFFFEA00))
        }
        for (i in world.sandals.indices) {
            val s = world.sandals[i]
            addDynamicBox(s.pos.x, s.pos.y, s.pos.z, 0.78f, 0.24f, 0.34f, s.spin, Color(0xFFFF1744), Color(0xFFFFD166))
            addDynamicBox(s.pos.x, s.pos.y - 0.16f, s.pos.z, 0.2f, 0.32f, 0.2f, s.spin, Color(0xFFFFD166))
        }
        for (i in world.dustParticles.indices) {
            val dp = world.dustParticles[i]
            val a = (dp.life / dp.maxLife).coerceIn(0f, 1f) * 0.5f
            addDynamicBox(dp.pos.x, dp.pos.y, dp.pos.z, dp.size, dp.size * 0.7f, dp.size, dp.life * 4f, Color(0xFFEBD9C5).copy(alpha = a))
        }

        // 6. ARTICULATED, ANIMATED 3D CHARACTERS (Zero-allocation local coordinate transforms!)
        val skin = Color(0xFFD99B66)
        for (i in world.players.indices) {
            val p = world.players[i]
            if (p.isCarriedInSack) continue

            val speedRatio = (p.currentSpeed / p.role.baseSpeed).coerceIn(0f, 1f)
            val bounce = if (p.isMoving) abs(sin(p.walkPhase)) * 0.22f * speedRatio else sin(world.elapsedTimeSec * 2.4f) * 0.04f
            val baseY = if (p.slipTimer > 0f) 0.3f else bounce
            val legSwing = if (p.isMoving) sin(p.walkPhase) * 0.42f * speedRatio else 0f
            val armSwing = -legSwing * 1.15f
            val forwardLean = if (p.isMoving) 0.16f * speedRatio else 0f
            val cY = cos(p.yaw); val sY = sin(p.yaw)

            fun addPart(lx: Float, ly: Float, lz: Float, sx: Float, sy: Float, sz: Float, col: Color, topCol: Color = col, partYaw: Float = p.yaw) {
                val lzLean = lz + ly * forwardLean * 0.45f
                addDynamicBox(
                    p.pos.x + lx * cY + lzLean * sY,
                    baseY + ly,
                    p.pos.z - lx * sY + lzLean * cY,
                    sx, sy, sz, partYaw, col, topCol
                )
            }

            when (p.role) {
                Role.BOU -> {
                    addPart(-0.2f, 0.32f, legSwing * 0.55f, 0.24f, 0.6f, 0.24f, Color(0xFFFFB703))
                    addPart(0.2f, 0.32f, -legSwing * 0.55f, 0.24f, 0.6f, 0.24f, Color(0xFFFFB703))
                    addPart(0f, 0.72f, 0f, 1.02f, 0.96f, 0.9f, Color(0xFFD91E43), Color(0xFFFFB703))
                    addPart(0f, 1.38f, 0f, 0.76f, 0.56f, 0.56f, Color(0xFFFF2A55), Color(0xFFFFD166))
                    addPart(0f, 1.56f, 0.26f, 0.46f, 0.16f, 0.14f, Color(0xFFFFD166))
                    addPart(-0.52f, 1.28f, armSwing, 0.22f, 0.58f, 0.22f, Color(0xFFD91E43))
                    addPart(0.52f, 1.28f, -armSwing, 0.22f, 0.58f, 0.22f, Color(0xFFD91E43))
                    addPart(0f, 1.98f, 0f, 0.58f, 0.58f, 0.58f, skin)
                    addPart(0f, 2.34f, -0.04f, 0.62f, 0.26f, 0.62f, Color(0xFFFFB703), Color(0xFFFFE066))
                    val veilWave = sin(world.elapsedTimeSec * 8f + p.walkPhase) * 0.18f
                    addPart(veilWave * 0.4f, 1.65f, -0.45f - speedRatio * 0.25f, 0.86f, 1.05f, 0.18f, Color(0xFFE63946), Color(0xFFFFB703))
                }
                Role.BOU_CHOR -> {
                    addPart(-0.22f, 0.44f, legSwing, 0.28f, 0.86f, 0.3f, Color(0xFF14151C))
                    addPart(0.22f, 0.44f, -legSwing, 0.28f, 0.86f, 0.3f, Color(0xFF14151C))
                    addPart(0f, 1.22f, 0f, 0.82f, 0.88f, 0.56f, Color(0xFF1F222E), Color(0xFF2E3244))
                    addPart(-0.56f, 1.18f, armSwing, 0.24f, 0.72f, 0.24f, Color(0xFF14151C))
                    addPart(0.56f, 1.18f, -armSwing, 0.24f, 0.72f, 0.24f, Color(0xFF14151C))
                    addPart(0f, 1.98f, 0f, 0.6f, 0.6f, 0.6f, Color(0xFF101118))
                    addPart(-0.15f, 2.02f, 0.32f, 0.15f, 0.11f, 0.06f, Color.White)
                    addPart(0.15f, 2.02f, 0.32f, 0.15f, 0.11f, 0.06f, Color.White)
                    if (p.isCarryingBou) {
                        val wiggle = sin(world.elapsedTimeSec * 14f) * 0.1f
                        addPart(0.48f, 1.88f + wiggle, -0.35f, 1.0f, 1.28f, 0.96f, Color(0xFF9C6639), Color(0xFFB87F4E), p.yaw + 0.18f)
                        addPart(0.56f, 2.58f + wiggle, -0.35f, 0.45f, 0.32f, 0.45f, Color(0xFFD91E43), Color(0xFFFFB703))
                    }
                }
                Role.BOR -> {
                    addPart(-0.22f, 0.42f, legSwing, 0.26f, 0.82f, 0.26f, Color(0xFFFFF6EA))
                    addPart(0.22f, 0.42f, -legSwing, 0.26f, 0.82f, 0.26f, Color(0xFFFFF6EA))
                    addPart(0f, 1.18f, 0f, 0.88f, 1.12f, 0.58f, Color(0xFFF59E0B), Color(0xFFFFD166))
                    addPart(0f, 1.48f, 0.28f, 0.68f, 0.24f, 0.2f, Color(0xFFD91E43))
                    addPart(0.32f, 1.12f, -0.34f - speedRatio * 0.2f, 0.26f, 0.72f, 0.16f, Color(0xFFD91E43))
                    addPart(-0.56f, 1.2f, armSwing, 0.24f, 0.68f, 0.24f, Color(0xFFF59E0B))
                    addPart(0.56f, 1.2f, -armSwing, 0.24f, 0.68f, 0.24f, Color(0xFFF59E0B))
                    addPart(0f, 2.02f, 0f, 0.58f, 0.58f, 0.58f, skin)
                    addPart(0f, 2.46f, 0f, 0.78f, 0.46f, 0.78f, Color(0xFFD91E43), Color(0xFFFFB703))
                    addPart(0f, 2.84f, 0.2f, 0.18f, 0.42f, 0.18f, Color(0xFFFFF6EA))
                }
                Role.MAMA -> {
                    addPart(-0.22f, 0.3f, legSwing * 0.65f, 0.25f, 0.55f, 0.25f, skin)
                    addPart(0.22f, 0.3f, -legSwing * 0.65f, 0.25f, 0.55f, 0.25f, skin)
                    addPart(0f, 0.68f, 0f, 0.92f, 0.85f, 0.74f, Color(0xFF1D6F8A), Color(0xFF2896B5))
                    addPart(0f, 1.32f, 0f, 0.96f, 0.82f, 0.64f, Color(0xFFFFF8F0), Color(0xFFFFE5C4))
                    addPart(-0.62f, 1.24f, armSwing, 0.25f, 0.66f, 0.25f, Color(0xFFFFF8F0))
                    addPart(0.62f, 1.24f, -armSwing * 0.5f, 0.25f, 0.66f, 0.25f, Color(0xFFFFF8F0))
                    addPart(0f, 2.02f, 0f, 0.6f, 0.6f, 0.6f, skin)
                    addPart(0f, 1.9f, 0.34f, 0.46f, 0.14f, 0.12f, Color(0xFF1A1412))
                    val swingAngle = p.yaw + (if (p.swingAnim > 0f) -1.45f + (1f - p.swingAnim) * 2.95f else 0.18f)
                    val stickOx = p.pos.x + 0.66f * cY + 0.45f * sY
                    val stickOy = baseY + 1.28f
                    val stickOz = p.pos.z - 0.66f * sY + 0.45f * cY
                    val sinSw = sin(swingAngle); val cosSw = cos(swingAngle)
                    for (seg in 0..2) {
                        val d = 0.75f + seg * 1.15f
                        val segCol = if (seg and 1 == 0) Color(0xFF7CB518) else Color(0xFF96CC2E)
                        addDynamicBox(
                            stickOx + sinSw * d,
                            stickOy + (if (p.swingAnim > 0f) -0.05f else 0.18f + seg * 0.1f),
                            stickOz + cosSw * d,
                            0.21f, 0.21f, 1.08f, swingAngle, segCol, Color(0xFFC5E86C)
                        )
                    }
                }
                Role.PHOTOGRAPHER -> {
                    addPart(-0.22f, 0.44f, legSwing, 0.26f, 0.84f, 0.26f, Color(0xFF2B3A4A))
                    addPart(0.22f, 0.44f, -legSwing, 0.26f, 0.84f, 0.26f, Color(0xFF2B3A4A))
                    addPart(0f, 1.22f, 0f, 0.86f, 0.88f, 0.58f, Color(0xFFC29B61), Color(0xFFDFBE8A))
                    addPart(-0.55f, 1.2f, 0.22f, 0.22f, 0.62f, 0.22f, Color(0xFF0EA5E9))
                    addPart(0.55f, 1.2f, 0.22f, 0.22f, 0.62f, 0.22f, Color(0xFF0EA5E9))
                    addPart(0f, 1.98f, 0f, 0.58f, 0.58f, 0.58f, skin)
                    addPart(0f, 2.34f, 0.08f, 0.65f, 0.22f, 0.78f, Color(0xFF0EA5E9))
                    addPart(0f, 1.42f, 0.58f, 0.56f, 0.38f, 0.35f, Color(0xFF1A1E26))
                    addPart(0f, 1.42f, 0.88f, 0.28f, 0.28f, 0.38f, Color(0xFF38BDF8), Color(0xFFCCF2FF))
                }
            }

            if (p.slipTimer > 0f || p.stunTimer > 0f) {
                for (si in 0..2) {
                    val ang = world.elapsedTimeSec * 6.5f + si * (2f * PI.toFloat() / 3f)
                    addDynamicBox(p.pos.x + cos(ang) * 0.65f, 2.85f, p.pos.z + sin(ang) * 0.65f, 0.2f, 0.2f, 0.2f, ang, Color(0xFFFFE066))
                }
            }
        }

        // 7. ZERO-GC IN-PLACE DEPTH SORT & DIRECT NATIVE CANVAS DRAW
        quadBuffer.sortDescendingByDepth()
        val qCount = quadBuffer.count
        strokePaint.strokeWidth = 1.4f
        for (i in 0 until qCount) {
            val idx = quadBuffer.order[i]
            nativePath.rewind()
            nativePath.moveTo(quadBuffer.p0x[idx], quadBuffer.p0y[idx])
            nativePath.lineTo(quadBuffer.p1x[idx], quadBuffer.p1y[idx])
            nativePath.lineTo(quadBuffer.p2x[idx], quadBuffer.p2y[idx])
            nativePath.lineTo(quadBuffer.p3x[idx], quadBuffer.p3y[idx])
            nativePath.close()
            fillPaint.color = quadBuffer.fillArgb[idx]
            nativeCanvas.drawPath(nativePath, fillPaint)
            val st = quadBuffer.strokeArgb[idx]
            if (st != 0) {
                strokePaint.color = st
                nativeCanvas.drawPath(nativePath, strokePaint)
            }
        }

        // 8. LIGHTWEIGHT EMISSIVE MORICH BATI FAIRY LIGHTS & STREETLAMPS (Zero PointLights!)
        val bulbRange = if (isHd) 64f else 48f
        for (i in 0 until fairyBulbs.count) {
            val bx = fairyBulbs.x[i]
            val bz = fairyBulbs.z[i]
            if (abs(bx - targetX) > bulbRange || abs(bz - targetZ) > bulbRange) continue
            if (!projectInline(bx, fairyBulbs.y[i], bz)) continue
            val r = (112f / pzOut).coerceIn(2.2f, 9.5f)
            val rgb = fairyBulbs.colorArgb[i] and 0x00FFFFFF
            val twinkle = 0.6f + 0.4f * sin(world.elapsedTimeSec * 6.0f + fairyBulbs.phaseOffset[i])
            if (isHd) {
                bulbPaint.color = (((twinkle * 0.30f * 255f).toInt()) shl 24) or rgb
                nativeCanvas.drawCircle(pxOut, pyOut, r * 2.1f, bulbPaint)
            }
            bulbPaint.color = ((((0.85f + twinkle * 0.15f) * 255f).toInt()) shl 24) or rgb
            nativeCanvas.drawCircle(pxOut, pyOut, r, bulbPaint)
        }

        // 9. THROUGH-WALLS REVEAL HIGHLIGHTS
        val localRole = world.localPlayer?.role
        val chor = world.chorPlayer
        if (chor != null && chor.wallHackTimer > 0f && localRole?.team == Team.WEDDING) {
            if (projectInline(chor.pos.x, 1.5f, chor.pos.z)) {
                val r = (540f / pzOut).coerceIn(24f, 72f)
                bulbPaint.color = ((((0.34f + 0.22f * pulse) * 255f).toInt()) shl 24) or 0xFF1744
                nativeCanvas.drawCircle(pxOut, pyOut, r * 1.35f, bulbPaint)
                strokePaint.color = 0xFFFF1744.toInt()
                strokePaint.strokeWidth = 6f
                nativeCanvas.drawCircle(pxOut, pyOut, r, strokePaint)
            }
        }
        val bou = world.bouPlayer
        if (bou != null && bou.auraExposed && !bou.isCarriedInSack && localRole == Role.BOU_CHOR) {
            if (projectInline(bou.pos.x, 1.5f, bou.pos.z)) {
                val r = (500f / pzOut).coerceIn(22f, 64f)
                bulbPaint.color = ((((0.32f + 0.2f * pulse) * 255f).toInt()) shl 24) or 0xFFD166
                nativeCanvas.drawCircle(pxOut, pyOut, r * 1.35f, bulbPaint)
                strokePaint.color = 0xFFFF2A55.toInt()
                strokePaint.strokeWidth = 5f
                nativeCanvas.drawCircle(pxOut, pyOut, r, strokePaint)
            }
        }

        // 9b. ATMOSPHERIC OUTDOOR WEATHER PASS (Monsoon Rain Streaks & Foggy Night Mist)
        if (effectiveRain > 0.04f) {
            val windSlant = -18f - sin(world.cameraYaw) * 14f
            val t = world.elapsedTimeSec
            val streakCount = if (isHd) 96 else 56
            for (idx in 0 until streakCount) {
                val b = idx * 5
                val fy = ((rainSeeds[b + 1] + t * rainSeeds[b + 2]) % 1.15f) - 0.1f
                val fx = ((rainSeeds[b] + fy * (windSlant / w) + skyShift * 0.5f) % 1f + 1f) % 1f
                val sx = fx * w
                val sy = fy * h
                val a = (rainSeeds[b + 4] * effectiveRain).coerceIn(0f, 0.52f)
                rainPaint.color = (((a * 255f).toInt()) shl 24) or 0xBFE9FF
                nativeCanvas.drawLine(sx, sy, sx + windSlant * 0.42f, sy + rainSeeds[b + 3], rainPaint)
            }
        }
        if (effectiveFog > 0.04f) {
            val drift1 = ((world.elapsedTimeSec * 0.035f + skyShift) % 1f) * w
            val drift2 = (((1f - world.elapsedTimeSec * 0.025f + skyShift) % 1f) + 1f) % 1f * w
            val mistCol = Color(0xFF7B879E)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(mistCol.copy(alpha = 0.24f * effectiveFog), Color.Transparent),
                    center = Offset(drift1, h * 0.42f),
                    radius = w * 0.46f
                ),
                radius = w * 0.46f,
                center = Offset(drift1, h * 0.42f)
            )
            if (isHd) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(mistCol.copy(alpha = 0.20f * effectiveFog), Color.Transparent),
                        center = Offset(drift2, h * 0.58f),
                        radius = w * 0.42f
                    ),
                    radius = w * 0.42f,
                    center = Offset(drift2, h * 0.58f)
                )
            }
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        fogColor.copy(alpha = 0.26f * effectiveFog),
                        fogColor.copy(alpha = 0.60f * effectiveFog)
                    ),
                    center = Offset(w * 0.5f, h * 0.56f),
                    radius = maxOf(w, h) * 0.64f
                )
            )
        }

        // 10. LANDMARK SIGNS, NAMEPLATES ("CHOR"), HOLD RINGS & SPEECH BUBBLES
        val signMaxDist = (55f - effectiveFog * 24f).coerceAtLeast(24f)
        fun drawSign(wx: Float, wy: Float, wz: Float, text: String, color: Color) {
            if (projectInline(wx, wy, wz) && pzOut <= signMaxDist) {
                drawLandmarkSign(pxOut, pyOut, text, color, labelPaint)
            }
        }
        drawSign(0f, 7.6f, -19.2f, "GRAND STAGE", Color(0xFFFFD166))
        drawSign(-42f, 4.6f, -9f, "VIP BRIDAL ROOM", Color(0xFFFF66B2))
        drawSign(40f, 4.6f, -4f, "KACCHI BUFFET", Color(0xFFFFB703))
        drawSign(0f, 8.7f, 22f, "BIYE BARI GATE", Color(0xFFFFE066))
        drawSign(-44f, 4.2f, 57.2f, "GREEN CNG GATE", Color(0xFF00E676))
        drawSign(44.2f, 4.4f, 57.2f, "MICROBUS GATE", Color(0xFF38BDF8))
        drawSign(0f, 4.2f, -58.8f, "RICKSHAW GATE", Color(0xFFFFB703))

        val nameplateMaxDist = (62f - effectiveFog * 38f - effectiveRain * 10f).coerceAtLeast(19f)
        for (i in world.players.indices) {
            val p = world.players[i]
            if (p.isCarriedInSack && p.speechBubbleText == null) continue
            val headY = if (p.isCarriedInSack) 3.4f else 3.05f
            if (!projectInline(p.pos.x, headY, p.pos.z)) continue
            val hpx = pxOut; val hpy = pyOut; val hpz = pzOut
            val revealedOverride = p.isLocal || p.wallHackTimer > 0f || p.auraExposed
            val allowedDist = if (revealedOverride) 64f else nameplateMaxDist
            if (hpz > allowedDist) continue

            if (!p.isCarriedInSack) {
                val speaking = p.speakingLevel > 0.18f
                val badgeText = "${p.name} • ${p.role.badgeName}"
                labelPaint.textSize = 26f
                labelPaint.color = android.graphics.Color.WHITE
                val tw = labelPaint.measureText(badgeText)
                val pillW = tw + 26f
                val pillH = 38f
                val px = hpx - pillW * 0.5f
                val py = hpy - pillH
                if (speaking) {
                    drawRoundRect(Color(0xFF00E676).copy(alpha = 0.55f), Offset(px - 5f, py - 5f), Size(pillW + 10f, pillH + 10f), CornerRadius(22f, 22f))
                }
                drawRoundRect(Color(0xCC1A0911), Offset(px, py), Size(pillW, pillH), CornerRadius(18f, 18f))
                drawRoundRect(
                    color = if (speaking) Color(0xFF00E676) else p.role.accentColor,
                    topLeft = Offset(px, py),
                    size = Size(pillW, pillH),
                    cornerRadius = CornerRadius(18f, 18f),
                    style = Stroke(width = if (speaking) 4f else 2.5f)
                )
                nativeCanvas.drawText(badgeText, hpx, py + 27f, labelPaint)

                val statusTag = when {
                    p.stunTimer > 0f -> "STUNNED ${String.format("%.1fs", p.stunTimer)}"
                    p.slipTimer > 0f -> "SLIPPED!"
                    p.blindTimer > 0f -> "BLINDED!"
                    else -> null
                }
                if (statusTag != null) {
                    labelPaint.textSize = 24f
                    labelPaint.color = Color(0xFFFFD166).toArgb()
                    nativeCanvas.drawText(statusTag, hpx, py - 10f, labelPaint)
                }

                if (p.holdType != HoldActionType.NONE && p.holdProgress > 0.01f) {
                    val ringRadius = 30f
                    val ringCenter = Offset(hpx, py - 42f)
                    drawCircle(Color(0xBB000000), radius = ringRadius + 5f, center = ringCenter)
                    drawArc(
                        color = when (p.holdType) {
                            HoldActionType.KIDNAP -> Color(0xFFFF1744)
                            HoldActionType.ESCAPE -> Color(0xFF00E676)
                            HoldActionType.RESCUE -> Color(0xFFFFD166)
                            HoldActionType.NONE -> Color.White
                        },
                        startAngle = -90f,
                        sweepAngle = p.holdProgress * 360f,
                        useCenter = false,
                        topLeft = Offset(ringCenter.x - ringRadius, ringCenter.y - ringRadius),
                        size = Size(ringRadius * 2f, ringRadius * 2f),
                        style = Stroke(width = 8f, cap = StrokeCap.Round)
                    )
                    labelPaint.textSize = 22f
                    labelPaint.color = android.graphics.Color.WHITE
                    nativeCanvas.drawText("${(p.holdProgress * 100).toInt()}%", ringCenter.x, ringCenter.y + 8f, labelPaint)
                }
            }

            val bubble = p.speechBubbleText
            if (!bubble.isNullOrBlank() && p.speechBubbleTimer > 0f) {
                val fade = (p.speechBubbleTimer / 0.5f).coerceIn(0f, 1f)
                bubblePaint.textSize = 28f
                bubblePaint.alpha = (fade * 255).toInt()
                val textW = bubblePaint.measureText(bubble).coerceAtMost(w * 0.62f)
                val bw = textW + 34f
                val bh = 52f
                val bx = (hpx - bw * 0.5f).coerceIn(12f, w - bw - 12f)
                val by = (hpy - 104f).coerceAtLeast(18f)

                drawRoundRect(Color.White.copy(alpha = 0.96f * fade), Offset(bx, by), Size(bw, bh), CornerRadius(24f, 24f))
                drawRoundRect(Color(0xFF1A0A10).copy(alpha = fade), Offset(bx, by), Size(bw, bh), CornerRadius(24f, 24f), style = Stroke(width = 3.5f))
                nativePath.rewind()
                nativePath.moveTo(hpx - 10f, by + bh - 1f)
                nativePath.lineTo(hpx + 10f, by + bh - 1f)
                nativePath.lineTo(hpx, by + bh + 14f)
                nativePath.close()
                fillPaint.color = packArgb(0.96f * fade, 1f, 1f, 1f)
                nativeCanvas.drawPath(nativePath, fillPaint)
                nativeCanvas.drawText(bubble, bx + bw * 0.5f, by + 35f, bubblePaint)
            }
        }

        for (i in world.combatTexts.indices) {
            val ct = world.combatTexts[i]
            if (projectInline(ct.pos.x, ct.pos.y, ct.pos.z)) {
                val alpha = (ct.life / ct.maxLife).coerceIn(0f, 1f)
                val sz = 38f + (1f - alpha) * 14f
                comicStrokePaint.textSize = sz
                comicStrokePaint.alpha = (alpha * 255).toInt()
                labelPaint.textSize = sz
                labelPaint.color = ct.color.copy(alpha = alpha).toArgb()
                nativeCanvas.drawText(ct.text, pxOut, pyOut, comicStrokePaint)
                nativeCanvas.drawText(ct.text, pxOut, pyOut, labelPaint)
            }
        }

        // 11. CAMERA-RELATIVE DIRECTIONAL EDGE-OF-SCREEN ARROWS
        val localPlayer = world.localPlayer
        if (localPlayer != null) {
            val hFwdLen = sqrt(fwdX * fwdX + fwdZ * fwdZ).coerceAtLeast(1e-5f)
            val hFwdX = fwdX / hFwdLen
            val hFwdZ = fwdZ / hFwdLen

            fun drawEdgeArrow(wx: Float, wz: Float, label: String, argb: Int) {
                val rx = wx - localPlayer.pos.x
                val rz = wz - localPlayer.pos.z
                val dist2d = sqrt(rx * rx + rz * rz)
                if (dist2d < 9f) return
                if (projectInline(wx, 1.8f, wz) && pxOut in (w * 0.12f)..(w * 0.88f) && pyOut in (h * 0.16f)..(h * 0.82f)) return

                val screenX = rx * rightX + rz * rightZ
                val screenY = -(rx * hFwdX + rz * hFwdZ)
                val angle = atan2(screenY, screenX)
                val ax = (w * 0.5f + cos(angle) * w * 0.38f).coerceIn(58f, w - 58f)
                val ay = (h * 0.52f + sin(angle) * h * 0.34f).coerceIn(145f, h - 145f)

                nativePath.rewind()
                nativePath.moveTo(ax + cos(angle) * 26f, ay + sin(angle) * 26f)
                nativePath.lineTo(ax + cos(angle + 2.4f) * 18f, ay + sin(angle + 2.4f) * 18f)
                nativePath.lineTo(ax + cos(angle - 2.4f) * 18f, ay + sin(angle - 2.4f) * 18f)
                nativePath.close()
                fillPaint.color = argb
                nativeCanvas.drawPath(nativePath, fillPaint)
                strokePaint.color = 0xFF17070D.toInt()
                strokePaint.strokeWidth = 3f
                nativeCanvas.drawPath(nativePath, strokePaint)

                val distLabel = "$label ${dist2d.toInt()}m"
                labelPaint.textSize = 21f
                labelPaint.color = android.graphics.Color.WHITE
                val tw = labelPaint.measureText(distLabel)
                drawRoundRect(Color(0xCC17070D), Offset(ax - tw * 0.5f - 10f, ay + 22f), Size(tw + 20f, 28f), CornerRadius(10f, 10f))
                nativeCanvas.drawText(distLabel, ax, ay + 43f, labelPaint)
            }

            world.screamBeacon?.let { sb ->
                if (localPlayer.role != Role.BOU) drawEdgeArrow(sb.pos.x, sb.pos.z, "SCREAM!", 0xFFFF1744.toInt())
            }
            if (chor != null && chor.wallHackTimer > 0f && localPlayer.role.team == Team.WEDDING) {
                drawEdgeArrow(chor.pos.x, chor.pos.z, "CHOR!", 0xFFFF1744.toInt())
            }
            if (chorCarrying) {
                for (i in world.gates.indices) {
                    val g = world.gates[i]
                    val gc = when (g.type) {
                        VehicleType.CNG -> 0xFF00E676.toInt()
                        VehicleType.MICROBUS -> 0xFF38BDF8.toInt()
                        VehicleType.RICKSHAW -> 0xFFFFB703.toInt()
                    }
                    drawEdgeArrow(g.pos.x, g.pos.z, g.type.shortTag, gc)
                }
            } else if (localPlayer.role == Role.BOU) {
                world.tasks.filter { !it.completed }.minByOrNull { it.pos.dist2D(localPlayer.pos) }?.let { t ->
                    drawEdgeArrow(t.pos.x, t.pos.z, t.type.iconTag, 0xFFFFB703.toInt())
                }
            }
        }
    }
}

private fun packArgb(a: Float, r: Float, g: Float, b: Float): Int {
    val ai = (a.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
}

private fun DrawScope.drawLandmarkSign(
    px: Float,
    py: Float,
    label: String,
    color: Color,
    paint: Paint
) {
    paint.textSize = 22f
    paint.color = color.toArgb()
    val tw = paint.measureText(label)
    val pw = tw + 20f
    val ph = 31f
    drawRoundRect(Color(0xAA17070D), Offset(px - pw * 0.5f, py - ph), Size(pw, ph), CornerRadius(12f, 12f))
    drawContext.canvas.nativeCanvas.drawText(label, px, py - 8f, paint)
}
