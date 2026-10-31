// ConstructAR — native SLAM core.
//
// types.hpp: dependency-free linear algebra used by the TSDF, the Surface Nets mesher and
// the pose-graph optimiser. Deliberately no Eigen: the NDK build must stay licensing-clean
// (BSD-3/MIT only) and the host test binary must compile with nothing but a C++20 compiler.
//
// Conventions (identical to ARCore and Filament):
//   * Mat3/Mat4 are COLUMN-MAJOR (m[col*4 + row]).
//   * The scene/AR camera looks down its local -Z axis (OpenGL convention).
//   * Quaternions are stored (w, x, y, z) — glTF uses (x, y, z, w) at serialisation time.
//   * A twist is a 6-vector [omega(0..2), upsilon(3..5)] (rotation first), the ordering the
//     pose-graph residuals below use throughout.
#pragma once

#include <cmath>
#include <cstdint>
#include <cstring>

namespace constructar {

constexpr float kPi = 3.14159265358979323846f;

struct Vec3 {
  float x = 0.f, y = 0.f, z = 0.f;

  Vec3() = default;
  Vec3(float x_, float y_, float z_) : x(x_), y(y_), z(z_) {}

  Vec3 operator+(const Vec3& o) const { return {x + o.x, y + o.y, z + o.z}; }
  Vec3 operator-(const Vec3& o) const { return {x - o.x, y - o.y, z - o.z}; }
  Vec3 operator-() const { return {-x, -y, -z}; }
  Vec3 operator*(float s) const { return {x * s, y * s, z * s}; }
  Vec3 operator/(float s) const { return {x / s, y / s, z / s}; }
  Vec3& operator+=(const Vec3& o) { x += o.x; y += o.y; z += o.z; return *this; }
  Vec3& operator-=(const Vec3& o) { x -= o.x; y -= o.y; z -= o.z; return *this; }
  float operator[](int i) const { return i == 0 ? x : (i == 1 ? y : z); }
  float& operator[](int i) { return i == 0 ? x : (i == 1 ? y : z); }

  float dot(const Vec3& o) const { return x * o.x + y * o.y + z * o.z; }
  Vec3 cross(const Vec3& o) const {
    return {y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x};
  }
  float length() const { return std::sqrt(dot(*this)); }
  float lengthSquared() const { return dot(*this); }
  Vec3 normalized() const {
    const float len = length();
    return len > 1e-8f ? *this / len : Vec3(0.f, 1.f, 0.f);
  }
  bool isFinite() const { return std::isfinite(x) && std::isfinite(y) && std::isfinite(z); }
};

inline Vec3 operator*(float s, const Vec3& v) { return v * s; }

/** Row-major 3x3; only used for rotation extraction/assembly. */
struct Mat3 {
  float m[9] = {1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f};

  static Mat3 fromColumns(const Vec3& c0, const Vec3& c1, const Vec3& c2) {
    Mat3 r;
    r.m[0] = c0.x; r.m[1] = c0.y; r.m[2] = c0.z;
    r.m[3] = c1.x; r.m[4] = c1.y; r.m[5] = c1.z;
    r.m[6] = c2.x; r.m[7] = c2.y; r.m[8] = c2.z;
    return r;
  }
  Vec3 column(int i) const { return {m[i * 3], m[i * 3 + 1], m[i * 3 + 2]}; }
  Vec3 operator*(const Vec3& v) const {
    return {m[0] * v.x + m[3] * v.y + m[6] * v.z,
            m[1] * v.x + m[4] * v.y + m[7] * v.z,
            m[2] * v.x + m[5] * v.y + m[8] * v.z};
  }
  Mat3 transposed() const {
    Mat3 r;
    for (int row = 0; row < 3; ++row)
      for (int col = 0; col < 3; ++col) r.m[col * 3 + row] = m[row * 3 + col];
    return r;
  }
};

/** Column-major 4x4 rigid transform (rotation + translation). */
struct Mat4 {
  float m[16] = {1.f, 0.f, 0.f, 0.f,
                 0.f, 1.f, 0.f, 0.f,
                 0.f, 0.f, 1.f, 0.f,
                 0.f, 0.f, 0.f, 1.f};

  static Mat4 identity() { return Mat4{}; }

  static Mat4 fromRotationTranslation(const Mat3& r, const Vec3& t) {
    Mat4 out;
    for (int col = 0; col < 3; ++col) {
      out.m[col * 4 + 0] = r.m[col * 3 + 0];
      out.m[col * 4 + 1] = r.m[col * 3 + 1];
      out.m[col * 4 + 2] = r.m[col * 3 + 2];
      out.m[col * 4 + 3] = 0.f;
    }
    out.m[12] = t.x;
    out.m[13] = t.y;
    out.m[14] = t.z;
    out.m[15] = 1.f;
    return out;
  }

  Mat3 rotation() const {
    return Mat3::fromColumns({m[0], m[1], m[2]}, {m[4], m[5], m[6]}, {m[8], m[9], m[10]});
  }

  Vec3 translation() const { return {m[12], m[13], m[14]}; }
  void setTranslation(const Vec3& t) { m[12] = t.x; m[13] = t.y; m[14] = t.z; }

  Vec3 transformPoint(const Vec3& p) const {
    return {m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12],
            m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
            m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]};
  }

  Vec3 transformDirection(const Vec3& d) const {
    return {m[0] * d.x + m[4] * d.y + m[8] * d.z,
            m[1] * d.x + m[5] * d.y + m[9] * d.z,
            m[2] * d.x + m[6] * d.y + m[10] * d.z};
  }

  Mat4 operator*(const Mat4& o) const {
    Mat4 out;
    for (int col = 0; col < 4; ++col) {
      for (int row = 0; row < 4; ++row) {
        float sum = 0.f;
        for (int k = 0; k < 4; ++k) sum += m[k * 4 + row] * o.m[col * 4 + k];
        out.m[col * 4 + row] = sum;
      }
    }
    return out;
  }

  /** Inverse of a rigid transform: R^T, -R^T t. */
  Mat4 inverseRigid() const {
    Mat4 out;
    const Mat3 rt = rotation().transposed();
    for (int col = 0; col < 3; ++col) {
      out.m[col * 4 + 0] = rt.m[col * 3 + 0];
      out.m[col * 4 + 1] = rt.m[col * 3 + 1];
      out.m[col * 4 + 2] = rt.m[col * 3 + 2];
      out.m[col * 4 + 3] = 0.f;
    }
    const Vec3 t = translation();
    const Vec3 invT = -(rt * t);
    out.m[12] = invT.x;
    out.m[13] = invT.y;
    out.m[14] = invT.z;
    out.m[15] = 1.f;
    return out;
  }

  bool isFinite() const {
    for (float v : m) if (!std::isfinite(v)) return false;
    return true;
  }
};

/** Unit quaternion (w, x, y, z). */
struct Quat {
  float w = 1.f, x = 0.f, y = 0.f, z = 0.f;

  Quat() = default;
  Quat(float w_, float x_, float y_, float z_) : w(w_), x(x_), y(y_), z(z_) {}

  static Quat fromAxisAngle(const Vec3& axis, float angle) {
    const Vec3 n = axis.normalized();
    const float half = angle * 0.5f;
    const float s = std::sin(half);
    return Quat(std::cos(half), n.x * s, n.y * s, n.z * s);
  }

  Quat normalized() const {
    const float len = std::sqrt(w * w + x * x + y * y + z * z);
    if (len < 1e-8f) return Quat();
    return Quat(w / len, x / len, y / len, z / len);
  }

  Quat operator*(const Quat& o) const {
    return Quat(w * o.w - x * o.x - y * o.y - z * o.z,
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w);
  }

  Quat conjugate() const { return Quat(w, -x, -y, -z); }

  Vec3 rotate(const Vec3& v) const {
    const Vec3 q(x, y, z);
    const Vec3 t = q.cross(v) * 2.f;
    return v + t * w + q.cross(t);
  }

  Mat3 toMat3() const {
    const float xx = x * x, yy = y * y, zz = z * z;
    const float xy = x * y, xz = x * z, yz = y * z;
    const float wx = w * x, wy = w * y, wz = w * z;
    Mat3 r;
    r.m[0] = 1.f - 2.f * (yy + zz);
    r.m[1] = 2.f * (xy + wz);
    r.m[2] = 2.f * (xz - wy);
    r.m[3] = 2.f * (xy - wz);
    r.m[4] = 1.f - 2.f * (xx + zz);
    r.m[5] = 2.f * (yz + wx);
    r.m[6] = 2.f * (xz + wy);
    r.m[7] = 2.f * (yz - wx);
    r.m[8] = 1.f - 2.f * (xx + yy);
    return r;
  }

  static Quat fromMat3(const Mat3& c) {
    // `c` is column-major, so the textbook row/column accessors are r(i,j) = c.m[j*3 + i].
    const float r00 = c.m[0], r10 = c.m[1], r20 = c.m[2];
    const float r01 = c.m[3], r11 = c.m[4], r21 = c.m[5];
    const float r02 = c.m[6], r12 = c.m[7], r22 = c.m[8];
    const float trace = r00 + r11 + r22;
    Quat q;
    if (trace > 0.f) {
      const float s = std::sqrt(trace + 1.f) * 2.f;
      q = Quat(0.25f * s, (r21 - r12) / s, (r02 - r20) / s, (r10 - r01) / s);
    } else if (r00 > r11 && r00 > r22) {
      const float s = std::sqrt(1.f + r00 - r11 - r22) * 2.f;
      q = Quat((r21 - r12) / s, 0.25f * s, (r01 + r10) / s, (r02 + r20) / s);
    } else if (r11 > r22) {
      const float s = std::sqrt(1.f + r11 - r00 - r22) * 2.f;
      q = Quat((r02 - r20) / s, (r01 + r10) / s, 0.25f * s, (r12 + r21) / s);
    } else {
      const float s = std::sqrt(1.f + r22 - r00 - r11) * 2.f;
      q = Quat((r10 - r01) / s, (r02 + r20) / s, (r12 + r21) / s, 0.25f * s);
    }
    return q.normalized();
  }
};

/** SO(3) logarithm: unit quaternion -> rotation vector. */
inline Vec3 quatToRotationVector(const Quat& qIn) {
  Quat q = qIn.normalized();
  if (q.w < 0.f) q = Quat(-q.w, -q.x, -q.y, -q.z); // shortest arc
  const float sinHalf = std::sqrt(q.x * q.x + q.y * q.y + q.z * q.z);
  if (sinHalf < 1e-8f) return Vec3(0.f, 0.f, 0.f);
  const float angle = 2.f * std::atan2(sinHalf, q.w);
  return Vec3(q.x, q.y, q.z) * (angle / sinHalf);
}

/** SO(3) exponential: rotation vector -> unit quaternion. */
inline Quat rotationVectorToQuat(const Vec3& rv) {
  const float angle = rv.length();
  if (angle < 1e-8f) return Quat(1.f, rv.x * 0.5f, rv.y * 0.5f, rv.z * 0.5f).normalized();
  return Quat::fromAxisAngle(rv, angle);
}

/** Twists are [omega(0..2), upsilon(3..5)]. */
struct Twist {
  Vec3 omega;
  Vec3 upsilon;
};

/** SE(3) exponential map (used to apply a Gauss-Newton update to a node). */
inline Mat4 se3Exp(const Twist& xi) {
  const Vec3 w = xi.omega;   // full rotation vector (|w| = angle)
  const Vec3 v = xi.upsilon;
  const float theta = w.length();

  Mat3 rotation;
  Mat3 vMatrix;
  if (theta < 1e-6f) {
    rotation = Mat3::fromColumns({1.f, 0.f, 0.f}, {0.f, 1.f, 0.f}, {0.f, 0.f, 1.f});
    vMatrix = rotation;
  } else {
    rotation = rotationVectorToQuat(w).toMat3();
    // V = I + A·[w]× + B·[w]×² ,  A = (1−cosθ)/θ²,  B = (θ−sinθ)/θ³
    // Built column by column (V·eᵢ = eᵢ + A(w×eᵢ) + B(w×(w×eᵢ))) so the column-major
    // storage of Mat3 cannot silently transpose the skew term.
    const float a = (1.f - std::cos(theta)) / (theta * theta);
    const float b = (theta - std::sin(theta)) / (theta * theta * theta);
    Vec3 columns[3];
    for (int i = 0; i < 3; ++i) {
      Vec3 e(0.f, 0.f, 0.f);
      e[i] = 1.f;
      columns[i] = e + w.cross(e) * a + w.cross(w.cross(e)) * b;
    }
    vMatrix = Mat3::fromColumns(columns[0], columns[1], columns[2]);
  }
  return Mat4::fromRotationTranslation(rotation, vMatrix * v);
}

/** SE(3) logarithm: rigid transform -> twist. */
inline Twist se3Log(const Mat4& t) {
  const Quat q = Quat::fromMat3(t.rotation());
  const Vec3 omega = quatToRotationVector(q);   // full rotation vector, |omega| = angle
  const float theta = omega.length();
  const Vec3 translation = t.translation();
  if (theta < 1e-6f) {
    return Twist{omega, translation};
  }
  // V⁻¹ = I − ½[ω]× + (1/θ²)(1 − (θ/2)·cot(θ/2))·[ω]×²
  const float half = 0.5f * theta;
  const float cotHalf = std::cos(half) / std::sin(half);
  const float factor = (1.f - half * cotHalf) / (theta * theta);
  const Vec3 omegaCrossT = omega.cross(translation);
  const Vec3 upsilon = translation - omegaCrossT * 0.5f + omega.cross(omegaCrossT) * factor;
  return Twist{omega, upsilon};
}


}  // namespace constructar
