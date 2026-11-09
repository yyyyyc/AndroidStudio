package com.example.mathbowling

import android.opengl.GLES20.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GlUtil {
    fun floatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun floatBuffer(a: FloatArray): FloatBuffer = floatBuffer(a.size).apply { put(a); position(0) }

    private fun compile(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetShaderInfoLog(s)
            glDeleteShader(s)
            throw RuntimeException("Shader compile failed: $log")
        }
        return s
    }

    fun program(vs: String, fs: String, attrs: Array<String>): Int {
        val p = glCreateProgram()
        glAttachShader(p, compile(GL_VERTEX_SHADER, vs))
        glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs))
        attrs.forEachIndexed { i, name -> glBindAttribLocation(p, i, name) }
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Program link failed: " + glGetProgramInfoLog(p))
        return p
    }
}

/** Interleaved mesh: position(3) normal(3) uv(2). */
class Mesh(private val data: FloatArray) {
    private val vertexCount = data.size / 8
    private var vbo = 0

    fun upload() {
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        vbo = ids[0]
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, GlUtil.floatBuffer(data), GL_STATIC_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
    }

    fun draw() {
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 32, 0)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 32, 12)
        glEnableVertexAttribArray(2)
        glVertexAttribPointer(2, 2, GL_FLOAT, false, 32, 24)
        glDrawArrays(GL_TRIANGLES, 0, vertexCount)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
    }
}

object Shapes {
    private class B {
        val f = ArrayList<Float>(4096)
        fun v(p: FloatArray) { for (x in p) f.add(x) }
        fun tri(a: FloatArray, b: FloatArray, c: FloatArray) { v(a); v(b); v(c) }
        fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) { tri(a, b, c); tri(a, c, d) }
        fun mesh() = Mesh(f.toFloatArray())
    }

    fun sphere(stacks: Int = 24, slices: Int = 32): Mesh {
        val b = B()
        fun p(i: Int, j: Int): FloatArray {
            val th = PI * i / stacks
            val ph = 2 * PI * j / slices
            val x = (sin(th) * cos(ph)).toFloat()
            val y = cos(th).toFloat()
            val z = (sin(th) * sin(ph)).toFloat()
            return floatArrayOf(x, y, z, x, y, z, j.toFloat() / slices, i.toFloat() / stacks)
        }
        for (i in 0 until stacks) for (j in 0 until slices) {
            b.quad(p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1))
        }
        return b.mesh()
    }

    /** Surface of revolution around Y. profile = list of (radius, y), ordered from bottom centre outward and up. */
    fun lathe(profile: List<Pair<Float, Float>>, slices: Int = 36): Mesh {
        val b = B()
        for (k in 0 until profile.size - 1) {
            val (r0, y0) = profile[k]
            val (r1, y1) = profile[k + 1]
            val dr = r1 - r0
            val dy = y1 - y0
            val len = sqrt(dr * dr + dy * dy)
            val nr = dy / len
            val ny = -dr / len
            for (j in 0 until slices) {
                val a0 = (2 * PI * j / slices)
                val a1 = (2 * PI * (j + 1) / slices)
                val c0 = cos(a0).toFloat(); val s0 = sin(a0).toFloat()
                val c1 = cos(a1).toFloat(); val s1 = sin(a1).toFloat()
                val u0 = j.toFloat() / slices; val u1 = (j + 1).toFloat() / slices
                val p00 = floatArrayOf(r0 * c0, y0, r0 * s0, nr * c0, ny, nr * s0, u0, y0)
                val p01 = floatArrayOf(r0 * c1, y0, r0 * s1, nr * c1, ny, nr * s1, u1, y0)
                val p10 = floatArrayOf(r1 * c0, y1, r1 * s0, nr * c0, ny, nr * s0, u0, y1)
                val p11 = floatArrayOf(r1 * c1, y1, r1 * s1, nr * c1, ny, nr * s1, u1, y1)
                b.quad(p00, p10, p11, p01)
            }
        }
        return b.mesh()
    }

    /** Unit cube centred at origin. */
    fun cube(): Mesh {
        val b = B()
        fun face(n: FloatArray, u: FloatArray, v: FloatArray) {
            fun p(s: Float, t: Float) = floatArrayOf(
                n[0] * 0.5f + u[0] * (s - 0.5f) + v[0] * (t - 0.5f),
                n[1] * 0.5f + u[1] * (s - 0.5f) + v[1] * (t - 0.5f),
                n[2] * 0.5f + u[2] * (s - 0.5f) + v[2] * (t - 0.5f),
                n[0], n[1], n[2], s, t
            )
            b.quad(p(0f, 0f), p(1f, 0f), p(1f, 1f), p(0f, 1f))
        }
        val x = floatArrayOf(1f, 0f, 0f); val y = floatArrayOf(0f, 1f, 0f); val z = floatArrayOf(0f, 0f, 1f)
        val nx = floatArrayOf(-1f, 0f, 0f); val ny = floatArrayOf(0f, -1f, 0f); val nz = floatArrayOf(0f, 0f, -1f)
        face(x, z, y); face(nx, z, y)
        face(y, x, z); face(ny, x, z)
        face(z, x, y); face(nz, x, y)
        return b.mesh()
    }

    /** Quad in the XY plane facing +Z, for textured signs (v=0 at top). */
    fun quadXY(): Mesh {
        val b = B()
        b.quad(
            floatArrayOf(-0.5f, -0.5f, 0f, 0f, 0f, 1f, 0f, 1f),
            floatArrayOf(0.5f, -0.5f, 0f, 0f, 0f, 1f, 1f, 1f),
            floatArrayOf(0.5f, 0.5f, 0f, 0f, 0f, 1f, 1f, 0f),
            floatArrayOf(-0.5f, 0.5f, 0f, 0f, 0f, 1f, 0f, 0f)
        )
        return b.mesh()
    }

    /** Quad in the XZ plane facing up, for shadows and floor decals. */
    fun quadXZ(): Mesh {
        val b = B()
        b.quad(
            floatArrayOf(-0.5f, 0f, -0.5f, 0f, 1f, 0f, 0f, 0f),
            floatArrayOf(0.5f, 0f, -0.5f, 0f, 1f, 0f, 1f, 0f),
            floatArrayOf(0.5f, 0f, 0.5f, 0f, 1f, 0f, 1f, 1f),
            floatArrayOf(-0.5f, 0f, 0.5f, 0f, 1f, 0f, 0f, 1f)
        )
        return b.mesh()
    }

    /** Flat arrow (triangle) pointing towards -Z. */
    fun arrow(): Mesh {
        val b = B()
        b.tri(
            floatArrayOf(0f, 0f, -0.5f, 0f, 1f, 0f, 0.5f, 0f),
            floatArrayOf(-0.25f, 0f, 0.3f, 0f, 1f, 0f, 0f, 1f),
            floatArrayOf(0.25f, 0f, 0.3f, 0f, 1f, 0f, 1f, 1f)
        )
        return b.mesh()
    }

    fun cone(): Mesh = lathe(
        listOf(
            0f to 0f,
            0.45f to 0f,
            0.45f to 0.06f,
            0.30f to 0.06f,
            0.07f to 0.95f,
            0.04f to 1.0f,
            0f to 1.0f
        )
    )
}

class LitShader {
    var program = 0
    var uMVP = 0; var uModel = 0; var uColor = 0; var uColor2 = 0; var uMode = 0; var uTex = 0
    var uLightDir = 0; var uEye = 0; var uFog = 0; var uFogRange = 0; var uAlpha = 0
    var uShine = 0; var uSpec = 0; var uEmissive = 0

    fun init() {
        program = GlUtil.program(VS, FS, arrayOf("aPos", "aNormal", "aUV"))
        fun u(n: String) = glGetUniformLocation(program, n)
        uMVP = u("uMVP"); uModel = u("uModel"); uColor = u("uColor"); uColor2 = u("uColor2")
        uMode = u("uMode"); uTex = u("uTex"); uLightDir = u("uLightDir"); uEye = u("uEye")
        uFog = u("uFog"); uFogRange = u("uFogRange"); uAlpha = u("uAlpha"); uShine = u("uShine")
        uSpec = u("uSpec"); uEmissive = u("uEmissive")
    }

    companion object {
        const val VS = """
uniform mat4 uMVP;
uniform mat4 uModel;
attribute vec3 aPos;
attribute vec3 aNormal;
attribute vec2 aUV;
varying vec3 vWorld;
varying vec3 vNormal;
varying vec3 vObj;
varying vec2 vUV;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = (uModel * vec4(aNormal, 0.0)).xyz;
    vObj = aPos;
    vUV = aUV;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

        // uMode: 0 plain, 1 texture (unlit), 2 wood lane, 3 bowling ball, 4 cone stripes, 5 soft blob (shadow/glow)
        const val FS = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec3 uColor;
uniform vec3 uColor2;
uniform float uMode;
uniform sampler2D uTex;
uniform vec3 uLightDir;
uniform vec3 uEye;
uniform vec3 uFog;
uniform vec2 uFogRange;
uniform float uAlpha;
uniform float uShine;
uniform float uSpec;
uniform float uEmissive;
varying vec3 vWorld;
varying vec3 vNormal;
varying vec3 vObj;
varying vec2 vUV;
void main() {
    if (uMode > 0.5 && uMode < 1.5) {
        vec4 t = texture2D(uTex, vUV);
        gl_FragColor = t * uAlpha;
        return;
    }
    if (uMode > 4.5) {
        float d = length(vUV - vec2(0.5)) * 2.0;
        float a = clamp(1.0 - d, 0.0, 1.0);
        a = a * a * uAlpha;
        gl_FragColor = vec4(uColor * a, a);
        return;
    }
    vec3 base = uColor;
    if (uMode > 1.5 && uMode < 2.5) {
        float px = vWorld.x * 2.5;
        float plank = floor(px);
        float f = fract(px);
        float h = fract(sin(plank * 12.9898) * 43758.5453);
        // frequencies are multiples of 2*pi/20 so the pattern repeats every 20 units (endless lane)
        float grain = sin(vWorld.z * 1.88496 + plank * 7.0 + sin(vWorld.z * 0.314159 + plank) * 3.0) * 0.5 + 0.5;
        base = mix(uColor, uColor2, clamp(grain * 0.45 + h * 0.55, 0.0, 1.0));
        base *= 0.78 + 0.22 * smoothstep(0.0, 0.05, f) * smoothstep(1.0, 0.95, f);
    } else if (uMode > 2.5 && uMode < 3.5) {
        float s = sin(vObj.x * 5.0 + sin(vObj.y * 4.0) * 2.0 + vObj.z * 3.0);
        base = mix(uColor, uColor2, smoothstep(-0.3, 0.6, s));
        vec3 o = normalize(vObj);
        if (dot(o, normalize(vec3(0.0, 0.8, 0.6))) > 0.993 ||
            dot(o, normalize(vec3(0.25, 0.9, 0.35))) > 0.995 ||
            dot(o, normalize(vec3(-0.25, 0.9, 0.35))) > 0.995) {
            base = vec3(0.02);
        }
    } else if (uMode > 3.5) {
        float y = vObj.y;
        if ((y > 0.30 && y < 0.44) || (y > 0.56 && y < 0.67)) base = uColor2;
        if (y < 0.065) base = vec3(0.12, 0.12, 0.14);
    }
    vec3 n = normalize(vNormal);
    vec3 l = normalize(uLightDir);
    vec3 v = normalize(uEye - vWorld);
    if (dot(n, v) < 0.0) n = -n;
    float diff = max(dot(n, l), 0.0);
    vec3 hv = normalize(l + v);
    float spec = pow(max(dot(n, hv), 0.0), uShine) * uSpec;
    float fill = max(dot(n, v), 0.0) * 0.25;
    vec3 col = base * (0.30 + diff * 0.75 + fill) + vec3(spec);
    col = mix(col, base, uEmissive);
    float dist = length(uEye - vWorld);
    float fog = clamp((dist - uFogRange.x) / (uFogRange.y - uFogRange.x), 0.0, 1.0);
    col = mix(col, uFog, fog);
    gl_FragColor = vec4(col * uAlpha, uAlpha);
}
"""
    }
}

class PointShader {
    var program = 0
    var uVP = 0
    var uScale = 0

    fun init() {
        program = GlUtil.program(VS, FS, arrayOf("aPos", "aColor", "aSize"))
        uVP = glGetUniformLocation(program, "uVP")
        uScale = glGetUniformLocation(program, "uScale")
    }

    companion object {
        const val VS = """
uniform mat4 uVP;
uniform float uScale;
attribute vec3 aPos;
attribute vec4 aColor;
attribute float aSize;
varying vec4 vColor;
void main() {
    vec4 p = uVP * vec4(aPos, 1.0);
    gl_Position = p;
    gl_PointSize = max(1.0, aSize * uScale / p.w);
    vColor = aColor;
}
"""
        const val FS = """
precision mediump float;
varying vec4 vColor;
void main() {
    vec2 c = gl_PointCoord - vec2(0.5);
    float d = length(c);
    if (d > 0.5) discard;
    float a = smoothstep(0.5, 0.1, d) * vColor.a;
    gl_FragColor = vec4(vColor.rgb * a, a);
}
"""
    }
}

class Particles(private val cap: Int) {
    private val px = FloatArray(cap); private val py = FloatArray(cap); private val pz = FloatArray(cap)
    private val vx = FloatArray(cap); private val vy = FloatArray(cap); private val vz = FloatArray(cap)
    private val life = FloatArray(cap); private val maxLife = FloatArray(cap); private val size = FloatArray(cap)
    private val cr = FloatArray(cap); private val cg = FloatArray(cap); private val cb = FloatArray(cap)
    private val grav = FloatArray(cap); private val drag = FloatArray(cap)
    private val fire = BooleanArray(cap)
    var count = 0
        private set
    val buffer: FloatBuffer = GlUtil.floatBuffer(cap * 8)

    fun clear() { count = 0 }

    /** Moves every particle along Z (used when the endless lane shifts the world). */
    fun shiftZ(d: Float) { for (i in 0 until count) pz[i] += d }

    fun emit(
        x: Float, y: Float, z: Float, dx: Float, dy: Float, dz: Float,
        lifeSec: Float, sz: Float, r: Float, g: Float, b: Float,
        gravity: Float, dragK: Float, isFire: Boolean
    ) {
        if (count >= cap) return
        val i = count++
        px[i] = x; py[i] = y; pz[i] = z; vx[i] = dx; vy[i] = dy; vz[i] = dz
        life[i] = lifeSec; maxLife[i] = lifeSec; size[i] = sz
        cr[i] = r; cg[i] = g; cb[i] = b; grav[i] = gravity; drag[i] = dragK; fire[i] = isFire
    }

    private fun copy(from: Int, to: Int) {
        px[to] = px[from]; py[to] = py[from]; pz[to] = pz[from]
        vx[to] = vx[from]; vy[to] = vy[from]; vz[to] = vz[from]
        life[to] = life[from]; maxLife[to] = maxLife[from]; size[to] = size[from]
        cr[to] = cr[from]; cg[to] = cg[from]; cb[to] = cb[from]
        grav[to] = grav[from]; drag[to] = drag[from]; fire[to] = fire[from]
    }

    fun update(dt: Float) {
        var i = 0
        while (i < count) {
            life[i] -= dt
            if (life[i] <= 0f) {
                count--
                if (i != count) copy(count, i)
                continue
            }
            vy[i] += grav[i] * dt
            val damp = kotlin.math.exp(-drag[i] * dt)
            vx[i] *= damp; vy[i] *= damp; vz[i] *= damp
            px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * dt
            if (py[i] < 0.03f && vy[i] < 0f) {
                py[i] = 0.03f; vy[i] *= -0.35f; vx[i] *= 0.7f; vz[i] *= 0.7f
            }
            i++
        }
    }

    /** Fills [buffer] with x,y,z,r,g,b,a,size per particle and returns the count. */
    fun fill(): Int {
        buffer.position(0)
        for (i in 0 until count) {
            val f = life[i] / maxLife[i]
            val heat = if (fire[i]) 0.35f + 0.65f * f else 1f
            val s = if (fire[i]) size[i] * (1f + (1f - f) * 0.8f) else size[i]
            val a = if (fire[i]) f else kotlin.math.min(1f, f * 2.5f)
            buffer.put(px[i]); buffer.put(py[i]); buffer.put(pz[i])
            buffer.put(cr[i] * heat); buffer.put(cg[i] * heat * heat); buffer.put(cb[i] * heat * heat)
            buffer.put(a); buffer.put(s)
        }
        buffer.position(0)
        return count
    }
}
