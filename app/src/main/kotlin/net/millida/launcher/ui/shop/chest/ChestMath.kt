package net.millida.launcher.ui.shop.chest

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Column-major 4×4 matrices in the layout GLES uniforms take, with the desktop scene's (three.js) conventions. */
internal object Mat4 {
    fun identity(out: FloatArray = FloatArray(16)): FloatArray {
        out.fill(0f)
        out[0] = 1f
        out[5] = 1f
        out[10] = 1f
        out[15] = 1f
        return out
    }

    fun multiply(a: FloatArray, b: FloatArray, out: FloatArray = FloatArray(16)): FloatArray {
        val r = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
                r[col * 4 + row] = sum
            }
        }
        r.copyInto(out)
        return out
    }

    /** T·R·S like Object3D.updateMatrix; [order] is the Euler order, "XYZ" or "ZYX". */
    fun compose(
        px: Float, py: Float, pz: Float,
        rx: Float, ry: Float, rz: Float,
        sx: Float, sy: Float, sz: Float,
        order: EulerOrder = EulerOrder.XYZ,
        out: FloatArray = FloatArray(16),
    ): FloatArray {
        val rot = rotation(rx, ry, rz, order)
        out[0] = rot[0] * sx
        out[1] = rot[1] * sx
        out[2] = rot[2] * sx
        out[3] = 0f
        out[4] = rot[3] * sy
        out[5] = rot[4] * sy
        out[6] = rot[5] * sy
        out[7] = 0f
        out[8] = rot[6] * sz
        out[9] = rot[7] * sz
        out[10] = rot[8] * sz
        out[11] = 0f
        out[12] = px
        out[13] = py
        out[14] = pz
        out[15] = 1f
        return out
    }

    /** Column-major 3×3 rotation: XYZ = Rx·Ry·Rz, ZYX = Rz·Ry·Rx, as three.js builds them. */
    fun rotation(x: Float, y: Float, z: Float, order: EulerOrder): FloatArray {
        val a = cos(x)
        val b = sin(x)
        val c = cos(y)
        val d = sin(y)
        val e = cos(z)
        val f = sin(z)
        val m = FloatArray(9)
        when (order) {
            EulerOrder.XYZ -> {
                val ae = a * e
                val af = a * f
                val be = b * e
                val bf = b * f
                m[0] = c * e
                m[3] = -c * f
                m[6] = d
                m[1] = af + be * d
                m[4] = ae - bf * d
                m[7] = -b * c
                m[2] = bf - ae * d
                m[5] = be + af * d
                m[8] = a * c
            }
            EulerOrder.ZYX -> {
                val ae = a * e
                val af = a * f
                val be = b * e
                val bf = b * f
                m[0] = c * e
                m[3] = be * d - af
                m[6] = ae * d + bf
                m[1] = c * f
                m[4] = bf * d + ae
                m[7] = af * d - be
                m[2] = -d
                m[5] = b * c
                m[8] = a * c
            }
        }
        return m
    }

    fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float, out: FloatArray = FloatArray(16)): FloatArray {
        val f = 1f / tan(Math.toRadians(fovYDeg / 2.0).toFloat())
        out.fill(0f)
        out[0] = f / aspect
        out[5] = f
        out[10] = (far + near) / (near - far)
        out[11] = -1f
        out[14] = 2f * far * near / (near - far)
        return out
    }

    /** The view matrix of a camera at [eye] looking at [target] with +y up. */
    fun lookAt(eye: FloatArray, target: FloatArray, out: FloatArray = FloatArray(16)): FloatArray {
        var zx = eye[0] - target[0]
        var zy = eye[1] - target[1]
        var zz = eye[2] - target[2]
        var len = sqrt(zx * zx + zy * zy + zz * zz)
        zx /= len
        zy /= len
        zz /= len
        var xx = zz
        var xy = 0f
        var xz = -zx
        len = sqrt(xx * xx + xy * xy + xz * xz)
        xx /= len
        xy /= len
        xz /= len
        val yx = zy * xz - zz * xy
        val yy = zz * xx - zx * xz
        val yz = zx * xy - zy * xx
        out[0] = xx
        out[1] = yx
        out[2] = zx
        out[3] = 0f
        out[4] = xy
        out[5] = yy
        out[6] = zy
        out[7] = 0f
        out[8] = xz
        out[9] = yz
        out[10] = zz
        out[11] = 0f
        out[12] = -(xx * eye[0] + xy * eye[1] + xz * eye[2])
        out[13] = -(yx * eye[0] + yy * eye[1] + yz * eye[2])
        out[14] = -(zx * eye[0] + zy * eye[1] + zz * eye[2])
        out[15] = 1f
        return out
    }

    /** Inverse-transpose of the upper 3×3, column-major: normals stay perpendicular under a squash. */
    fun normalMatrix(m: FloatArray, out: FloatArray = FloatArray(9)): FloatArray {
        val a00 = m[0]
        val a01 = m[1]
        val a02 = m[2]
        val a10 = m[4]
        val a11 = m[5]
        val a12 = m[6]
        val a20 = m[8]
        val a21 = m[9]
        val a22 = m[10]
        val b01 = a22 * a11 - a12 * a21
        val b11 = -a22 * a10 + a12 * a20
        val b21 = a21 * a10 - a11 * a20
        val det = a00 * b01 + a01 * b11 + a02 * b21
        val inv = if (det == 0f) 0f else 1f / det
        out[0] = b01 * inv
        out[3] = (-a22 * a01 + a02 * a21) * inv
        out[6] = (a12 * a01 - a02 * a11) * inv
        out[1] = b11 * inv
        out[4] = (a22 * a00 - a02 * a20) * inv
        out[7] = (-a12 * a00 + a02 * a10) * inv
        out[2] = b21 * inv
        out[5] = (-a21 * a00 + a01 * a20) * inv
        out[8] = (a11 * a00 - a01 * a10) * inv
        return out
    }

    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
    )

    fun columnLength(m: FloatArray, col: Int): Float {
        val i = col * 4
        return sqrt(m[i] * m[i] + m[i + 1] * m[i + 1] + m[i + 2] * m[i + 2])
    }
}

internal enum class EulerOrder { XYZ, ZYX }
