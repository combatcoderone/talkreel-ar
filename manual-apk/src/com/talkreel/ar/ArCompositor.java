package com.talkreel.ar;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Camera-to-screen and camera-to-encoder GPU bridge.
 *
 * Camera2 writes frames into an external SurfaceTexture. Every frame is drawn
 * once to the on-screen preview surface and, while recording, once to the
 * MediaRecorder surface, so the recorded MP4 contains exactly the same AR
 * frames the user sees.
 *
 * Orientation: the camera buffer is produced in sensor orientation (landscape
 * on phones). This class rotates it to upright for the current display, mirrors
 * the front camera so the preview behaves like a mirror, and cover-crops it to
 * the target aspect ratio. Face rectangles from Camera2 are converted to the
 * same final display space by MainActivity, so every effect lands on the face
 * at any resolution or aspect ratio.
 *
 * All methods and frame callbacks must run on the supplied Handler thread.
 */
final class ArCompositor implements SurfaceTexture.OnFrameAvailableListener {

    private static final String VERTEX =
        "attribute vec4 aPosition;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "uniform mat3 uRot;\n" +
        "uniform vec2 uCrop;\n" +
        "uniform int uMirror;\n" +
        "varying vec2 vTexCoord;\n" +
        "varying vec2 vDisplay;\n" +
        "void main(){\n" +
        "  gl_Position = aPosition;\n" +
        "  vec2 q = vec2(aPosition.x * 0.5 + 0.5, 0.5 - aPosition.y * 0.5);\n" +
        "  vDisplay = q;\n" +
        "  vec2 s = (q - 0.5) * uCrop + 0.5;\n" +
        "  if (uMirror == 1) s.x = 1.0 - s.x;\n" +
        "  vec2 t = (uRot * vec3(s, 1.0)).xy;\n" +
        "  vTexCoord = (uTexMatrix * vec4(t, 0.0, 1.0)).xy;\n" +
        "}\n";

    // Effect ids match ProFrameView.FILTER_*.
    private static final String FRAGMENT =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTexCoord;\n" +
        "varying vec2 vDisplay;\n" +
        "uniform samplerExternalOES sTexture;\n" +
        "uniform vec2 uResolution;\n" +
        "uniform vec2 uTexel;\n" +
        "uniform int uFilter;\n" +
        "uniform int uHasFace;\n" +
        "uniform float uFaceAlpha;\n" +
        "uniform vec4 uFace;\n" +
        "uniform vec4 uEyes;\n" +
        "uniform vec2 uMouth;\n" +
        "uniform float uAngle;\n" +
        "uniform float uTime;\n" +

        "vec2 rot(vec2 p, float a){\n" +
        "  float c = cos(a); float s = sin(a);\n" +
        "  return vec2(c * p.x - s * p.y, s * p.x + c * p.y);\n" +
        "}\n" +

        "float boxMask(vec2 p, vec2 b, float r, float aa){\n" +
        "  vec2 q = abs(p) - b + r;\n" +
        "  float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n" +
        "  return 1.0 - smoothstep(-aa, aa, d);\n" +
        "}\n" +

        "float ellipseMask(vec2 p, vec2 r, float aa){\n" +
        "  vec2 rr = max(r, vec2(1.0, 1.0));\n" +
        "  float d = length(p / rr);\n" +
        "  float w = aa / max(min(rr.x, rr.y), 1.0);\n" +
        "  return 1.0 - smoothstep(1.0 - w, 1.0 + w, d);\n" +
        "}\n" +

        "float segMask(vec2 p, vec2 a, vec2 b, float w, float aa){\n" +
        "  vec2 pa = p - a; vec2 ba = b - a;\n" +
        "  float h = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);\n" +
        "  float d = length(pa - ba * h) - w;\n" +
        "  return 1.0 - smoothstep(-aa, aa, d);\n" +
        "}\n" +

        "float triMask(vec2 p, vec2 p0, vec2 p1, vec2 p2, float aa){\n" +
        "  vec2 e0 = p1 - p0; vec2 e1 = p2 - p1; vec2 e2 = p0 - p2;\n" +
        "  vec2 v0 = p - p0; vec2 v1 = p - p1; vec2 v2 = p - p2;\n" +
        "  vec2 pq0 = v0 - e0 * clamp(dot(v0, e0) / max(dot(e0, e0), 0.0001), 0.0, 1.0);\n" +
        "  vec2 pq1 = v1 - e1 * clamp(dot(v1, e1) / max(dot(e1, e1), 0.0001), 0.0, 1.0);\n" +
        "  vec2 pq2 = v2 - e2 * clamp(dot(v2, e2) / max(dot(e2, e2), 0.0001), 0.0, 1.0);\n" +
        "  float s = sign(e0.x * e2.y - e0.y * e2.x);\n" +
        "  vec2 d = min(min(vec2(dot(pq0, pq0), s * (v0.x * e0.y - v0.y * e0.x)),\n" +
        "                   vec2(dot(pq1, pq1), s * (v1.x * e1.y - v1.y * e1.x))),\n" +
        "                   vec2(dot(pq2, pq2), s * (v2.x * e2.y - v2.y * e2.x)));\n" +
        "  float sd = -sqrt(d.x) * sign(d.y);\n" +
        "  return 1.0 - smoothstep(-aa, aa, sd);\n" +
        "}\n" +

        "vec3 softBlur(vec2 uv, float r){\n" +
        "  vec2 o = uTexel * r;\n" +
        "  vec3 s = texture2D(sTexture, uv).rgb * 0.24;\n" +
        "  s += texture2D(sTexture, uv + vec2(o.x, 0.0)).rgb * 0.12;\n" +
        "  s += texture2D(sTexture, uv - vec2(o.x, 0.0)).rgb * 0.12;\n" +
        "  s += texture2D(sTexture, uv + vec2(0.0, o.y)).rgb * 0.12;\n" +
        "  s += texture2D(sTexture, uv - vec2(0.0, o.y)).rgb * 0.12;\n" +
        "  s += texture2D(sTexture, uv + o).rgb * 0.07;\n" +
        "  s += texture2D(sTexture, uv - o).rgb * 0.07;\n" +
        "  s += texture2D(sTexture, uv + vec2(o.x, -o.y)).rgb * 0.07;\n" +
        "  s += texture2D(sTexture, uv + vec2(-o.x, o.y)).rgb * 0.07;\n" +
        "  return s;\n" +
        "}\n" +

        "float skinWeight(vec3 c){\n" +
        "  float y = dot(c, vec3(0.299, 0.587, 0.114));\n" +
        "  float cb = -0.168736 * c.r - 0.331264 * c.g + 0.5 * c.b + 0.5;\n" +
        "  float cr = 0.5 * c.r - 0.418688 * c.g - 0.081312 * c.b + 0.5;\n" +
        "  float m = smoothstep(0.31, 0.36, cb) * (1.0 - smoothstep(0.47, 0.52, cb));\n" +
        "  m *= smoothstep(0.53, 0.57, cr) * (1.0 - smoothstep(0.68, 0.74, cr));\n" +
        "  m *= smoothstep(0.16, 0.28, y);\n" +
        "  return clamp(m, 0.0, 1.0);\n" +
        "}\n" +

        "void main(){\n" +
        "  vec3 c = texture2D(sTexture, vTexCoord).rgb;\n" +
        "  vec2 q = vDisplay;\n" +
        "  vec2 res = uResolution;\n" +
        "  float t = uTime;\n" +
        "  float lum = dot(c, vec3(0.299, 0.587, 0.114));\n" +
        "  c = mix(vec3(lum), c, 1.06);\n" +
        "  c = clamp((c - 0.5) * 1.04 + 0.5, 0.0, 1.0);\n" +
        // Face geometry in display pixels. Effects are drawn in pixel space so
        // shapes stay round and scale correctly at any resolution.
        "  vec2 fcPix = (uFace.xy + uFace.zw) * 0.5 * res;\n" +
        "  float faceW = max((uFace.z - uFace.x) * res.x, 12.0);\n" +
        "  float faceH = max((uFace.w - uFace.y) * res.y, 12.0);\n" +
        "  vec2 eyeLp = uEyes.xy * res;\n" +
        "  vec2 eyeRp = uEyes.zw * res;\n" +
        "  float eyePx = max(distance(eyeLp, eyeRp), 12.0);\n" +
        "  vec2 pix = q * res;\n" +
        "  vec2 fp = rot(pix - fcPix, -uAngle);\n" +
        "  vec2 eyeL = rot(eyeLp - fcPix, -uAngle);\n" +
        "  vec2 eyeR = rot(eyeRp - fcPix, -uAngle);\n" +
        "  vec2 mouth = rot(uMouth * res - fcPix, -uAngle);\n" +
        "  float aa = 1.5;\n" +
        "  float on = (uHasFace == 1) ? uFaceAlpha : 0.0;\n" +
        "  float s = faceW;\n" +

        // ---------- image grade ----------
        "  if (uFilter == 1 || uFilter == 6) {\n" +
        "    vec3 soft = softBlur(vTexCoord, 2.2);\n" +
        "    if (uFilter == 1) {\n" +
        "      c = mix(c, soft, 0.40);\n" +
        "      c += soft * 0.10;\n" +
        "      c = mix(c, c * vec3(1.05, 0.99, 1.02), 0.55);\n" +
        "      c *= mix(0.86, 1.0, smoothstep(1.15, 0.30, length((q - 0.5) * vec2(res.x / res.y, 1.0))));\n" +
        "    } else {\n" +
        "      float skin = skinWeight(c);\n" +
        "      float area = 1.0;\n" +
        "      float faceOn = 0.55;\n" +
        "      if (uHasFace == 1) {\n" +
        "        area = 1.0 - smoothstep(0.85, 1.10, length(fp / vec2(faceW * 0.60, faceH * 0.62)));\n" +
        "        faceOn = uFaceAlpha;\n" +
        "      }\n" +
        "      float keep = smoothstep(0.030, 0.14, abs(lum - dot(soft, vec3(0.299, 0.587, 0.114))));\n" +
        "      float m = skin * area * faceOn * (1.0 - keep) * 0.90;\n" +
        "      c = mix(c, soft, m);\n" +
        "      float lift = faceOn * skin * area;\n" +
        "      c = mix(c, c + (1.0 - c) * 0.07, lift * 0.7);\n" +
        "      c *= mix(vec3(1.0), vec3(1.045, 1.015, 1.0), lift);\n" +
        "      c = mix(c, c * vec3(0.99, 1.0, 1.01), 0.35);\n" +
        "    }\n" +
        "  }\n" +

        // ---------- face effects ----------
        "  if (on > 0.002) {\n" +

        // Glasses: truer lens shape, tinted glass, frame, bridge, temples.
        "    if (uFilter == 2) {\n" +
        "      vec2 mid = (eyeL + eyeR) * 0.5;\n" +
        "      vec2 g = rot(pix - fcPix - mid, -uAngle) / eyePx;\n" +
        "      float ga = aa / eyePx;\n" +
        "      vec2 lensHalf = vec2(0.34, 0.255);\n" +
        "      float outer = max(boxMask(g - vec2(-0.5, 0.02), lensHalf, 0.10, ga),\n" +
        "                        boxMask(g - vec2( 0.5, 0.02), lensHalf, 0.10, ga));\n" +
        "      float inner = max(boxMask(g - vec2(-0.5, 0.02), lensHalf - 0.035, 0.085, ga),\n" +
        "                        boxMask(g - vec2( 0.5, 0.02), lensHalf - 0.035, 0.085, ga));\n" +
        "      float ring = clamp(outer - inner, 0.0, 1.0);\n" +
        "      vec3 tint = mix(vec3(0.20, 0.42, 0.88), vec3(0.62, 0.22, 0.72),\n" +
        "                      clamp(g.y * 2.2 + 0.5, 0.0, 1.0));\n" +
        "      c = mix(c, tint, inner * 0.34 * on);\n" +
        "      float shine = ellipseMask(g - vec2(-0.62, -0.10), vec2(0.055, 0.13), ga) +\n" +
        "                    ellipseMask(g - vec2( 0.44, -0.10), vec2(0.045, 0.11), ga);\n" +
        "      c = mix(c, vec3(1.0), clamp(shine, 0.0, 1.0) * 0.42 * on);\n" +
        "      float frame = max(ring, max(\n" +
        "                    segMask(g, vec2(-0.19, 0.01), vec2(0.19, 0.01), 0.030, ga),\n" +
        "                    max(segMask(g, vec2(-0.86, 0.02), vec2(-1.10, -0.02), 0.024, ga),\n" +
        "                        segMask(g, vec2( 0.86, 0.02), vec2( 1.10, -0.02), 0.024, ga))));\n" +
        "      c = mix(c, vec3(0.045, 0.05, 0.07), clamp(frame, 0.0, 1.0) * 0.94 * on);\n" +
        "      c += vec3(0.35, 0.55, 0.85) * ring * 0.10 * on;\n" +
        "    }\n" +

        // Beauty makeup: blush, lips with gloss, eyeliner, shadow.
        "    else if (uFilter == 7) {\n" +
        "      float blush = max(ellipseMask(fp - vec2(-0.32 * s, 0.14 * s), vec2(0.15 * s, 0.10 * s), aa * 1.4),\n" +
        "                        ellipseMask(fp - vec2( 0.32 * s, 0.14 * s), vec2(0.15 * s, 0.10 * s), aa * 1.4));\n" +
        "      c = mix(c, vec3(0.96, 0.42, 0.52), blush * 0.26 * on);\n" +
        "      float lipW = max(0.15 * s, 8.0);\n" +
        "      float lipH = max(0.065 * s, 5.0);\n" +
        "      float lips = ellipseMask(fp - mouth, vec2(lipW, lipH), aa);\n" +
        "      c = mix(c, vec3(0.58, 0.05, 0.16), lips * 0.66 * on);\n" +
        "      float gloss = ellipseMask(fp - mouth - vec2(-lipW * 0.22, -lipH * 0.42),\n" +
        "                                vec2(lipW * 0.42, lipH * 0.30), aa);\n" +
        "      c = mix(c, vec3(1.0, 0.74, 0.80), gloss * 0.42 * on);\n" +
        "      float liner = max(segMask(fp, eyeL + vec2(-0.05 * s, -0.045 * s), eyeL + vec2(0.075 * s, -0.050 * s), 0.011 * s, aa),\n" +
        "                        segMask(fp, eyeR + vec2(0.05 * s, -0.045 * s), eyeR + vec2(-0.075 * s, -0.050 * s), 0.011 * s, aa));\n" +
        "      c = mix(c, vec3(0.04, 0.03, 0.04), liner * 0.80 * on);\n" +
        "      float shadow = max(ellipseMask(fp - eyeL - vec2(0.0, -0.075 * s), vec2(0.13 * s, 0.075 * s), aa * 1.5),\n" +
        "                         ellipseMask(fp - eyeR - vec2(0.0, -0.075 * s), vec2(0.13 * s, 0.075 * s), aa * 1.5));\n" +
        "      c = mix(c, vec3(0.72, 0.42, 0.55), shadow * 0.22 * on);\n" +
        "    }\n" +

        // Bunny: soft ears with inner ear, blush, nose.
        "    else if (uFilter == 3) {\n" +
        "      float earL = ellipseMask(rot(fp - vec2(-0.30 * s, -0.92 * s), 0.17), vec2(0.135 * s, 0.42 * s), aa);\n" +
        "      float earR = ellipseMask(rot(fp - vec2( 0.30 * s, -0.92 * s), -0.17), vec2(0.135 * s, 0.42 * s), aa);\n" +
        "      float ears = max(earL, earR);\n" +
        "      c = mix(c, vec3(1.0, 0.72, 0.84), ears * 0.90 * on);\n" +
        "      float inL = ellipseMask(rot(fp - vec2(-0.30 * s, -0.90 * s), 0.17), vec2(0.075 * s, 0.30 * s), aa);\n" +
        "      float inR = ellipseMask(rot(fp - vec2( 0.30 * s, -0.90 * s), -0.17), vec2(0.075 * s, 0.30 * s), aa);\n" +
        "      c = mix(c, vec3(1.0, 0.88, 0.93), max(inL, inR) * 0.85 * on);\n" +
        "      float nose = ellipseMask(fp - vec2(0.0, 0.20 * s), vec2(0.055 * s, 0.042 * s), aa);\n" +
        "      c = mix(c, vec3(1.0, 0.55, 0.68), nose * 0.92 * on);\n" +
        "      float blush = max(ellipseMask(fp - vec2(-0.30 * s, 0.13 * s), vec2(0.14 * s, 0.09 * s), aa * 1.5),\n" +
        "                        ellipseMask(fp - vec2( 0.30 * s, 0.13 * s), vec2(0.14 * s, 0.09 * s), aa * 1.5));\n" +
        "      c = mix(c, vec3(1.0, 0.50, 0.62), blush * 0.18 * on);\n" +
        "    }\n" +

        // Crown: gold band, three points, jewels.
        "    else if (uFilter == 4) {\n" +
        "      float band = boxMask(fp - vec2(0.0, -0.56 * s), vec2(0.40 * s, 0.070 * s), 0.035 * s, aa);\n" +
        "      float pL = triMask(fp, vec2(-0.42 * s, -0.60 * s), vec2(-0.20 * s, -0.60 * s), vec2(-0.36 * s, -0.98 * s), aa);\n" +
        "      float pR = triMask(fp, vec2( 0.20 * s, -0.60 * s), vec2( 0.42 * s, -0.60 * s), vec2( 0.36 * s, -0.98 * s), aa);\n" +
        "      float pM = triMask(fp, vec2(-0.12 * s, -0.60 * s), vec2( 0.12 * s, -0.60 * s), vec2( 0.0, -1.10 * s), aa);\n" +
        "      float crown = max(band, max(pL, max(pR, pM)));\n" +
        "      float gy = clamp((fp.y + 0.85 * s) / (0.40 * s), 0.0, 1.0);\n" +
        "      vec3 gold = mix(vec3(1.0, 0.92, 0.55), vec3(0.82, 0.53, 0.06), gy);\n" +
        "      c = mix(c, gold, crown * 0.95 * on);\n" +
        "      float edge = clamp(crown - boxMask(fp - vec2(0.0, -0.56 * s), vec2(0.385 * s, 0.055 * s), 0.03 * s, aa), 0.0, 1.0);\n" +
        "      c = mix(c, vec3(1.0, 0.98, 0.80), edge * 0.55 * on);\n" +
        "      float jewels = ellipseMask(fp - vec2(0.0, -0.56 * s), vec2(0.032 * s, 0.032 * s), aa) * 0.85;\n" +
        "      c = mix(c, vec3(1.0, 0.20, 0.35), jewels * on);\n" +
        "      float j2 = max(ellipseMask(fp - vec2(-0.25 * s, -0.56 * s), vec2(0.024 * s, 0.024 * s), aa),\n" +
        "                     ellipseMask(fp - vec2( 0.25 * s, -0.56 * s), vec2(0.024 * s, 0.024 * s), aa));\n" +
        "      c = mix(c, vec3(0.30, 0.75, 1.0), j2 * 0.85 * on);\n" +
        "    }\n" +

        // Neon rim with animated glow.
        "    else if (uFilter == 5) {\n" +
        "      float d = length(fp / vec2(faceW * 0.58, faceH * 0.56));\n" +
        "      float pulse = 0.82 + 0.18 * sin(t * 2.4);\n" +
        "      float ring = exp(-pow((d - 1.0) * 10.0, 2.0)) * pulse;\n" +
        "      float halo = exp(-pow((d - 1.0) * 3.0, 2.0)) * 0.55 * pulse;\n" +
        "      vec3 neon = mix(vec3(0.05, 0.95, 1.0), vec3(0.85, 0.20, 1.0), clamp(fp.y / faceH + 0.5, 0.0, 1.0));\n" +
        "      c = mix(c, neon, clamp(halo, 0.0, 1.0) * 0.30 * on);\n" +
        "      c += neon * clamp(ring, 0.0, 1.0) * 0.65 * on;\n" +
        "    }\n" +

        // Masquerade mask with gold trim and glitter.
        "    else if (uFilter == 8) {\n" +
        "      float body = boxMask(fp - vec2(0.0, -0.10 * s), vec2(0.46 * s, 0.19 * s), 0.17 * s, aa * 1.6);\n" +
        "      float holeL = ellipseMask(fp - eyeL, vec2(0.17 * s, 0.10 * s), aa * 1.8);\n" +
        "      float holeR = ellipseMask(fp - eyeR, vec2(0.17 * s, 0.10 * s), aa * 1.8);\n" +
        "      float mask = clamp(body - max(holeL, holeR), 0.0, 1.0);\n" +
        "      vec3 mc = mix(vec3(0.13, 0.04, 0.24), vec3(0.45, 0.13, 0.58),\n" +
        "                    clamp((fp.y + 0.30 * s) / (0.40 * s), 0.0, 1.0));\n" +
        "      c = mix(c, mc, mask * 0.88 * on);\n" +
        "      float trim = clamp(boxMask(fp - vec2(0.0, -0.10 * s), vec2(0.46 * s, 0.185 * s), 0.17 * s, aa * 1.6)\n" +
        "                   - boxMask(fp - vec2(0.0, -0.10 * s), vec2(0.43 * s, 0.155 * s), 0.15 * s, aa * 1.6), 0.0, 1.0);\n" +
        "      c = mix(c, vec3(0.98, 0.80, 0.38), trim * 0.75 * on);\n" +
        "      float glit = pow(max(0.0, sin(fp.x * 0.42 + t * 1.3) * sin(fp.y * 0.52 - t * 0.9)), 26.0);\n" +
        "      c += vec3(1.0, 0.92, 0.66) * glit * mask * 0.85 * on;\n" +
        "    }\n" +

        // Cat: ears, nose, whiskers, blush.
        "    else if (uFilter == 9) {\n" +
        "      float earL = triMask(fp, vec2(-0.50 * s, -0.46 * s), vec2(-0.08 * s, -0.60 * s), vec2(-0.36 * s, -1.02 * s), aa);\n" +
        "      float earR = triMask(fp, vec2( 0.08 * s, -0.60 * s), vec2( 0.50 * s, -0.46 * s), vec2( 0.36 * s, -1.02 * s), aa);\n" +
        "      float ears = max(earL, earR);\n" +
        "      c = mix(c, vec3(0.30, 0.27, 0.30), ears * 0.94 * on);\n" +
        "      float inL = triMask(fp, vec2(-0.42 * s, -0.53 * s), vec2(-0.16 * s, -0.62 * s), vec2(-0.34 * s, -0.90 * s), aa);\n" +
        "      float inR = triMask(fp, vec2( 0.16 * s, -0.62 * s), vec2( 0.42 * s, -0.53 * s), vec2( 0.34 * s, -0.90 * s), aa);\n" +
        "      c = mix(c, vec3(1.0, 0.62, 0.72), max(inL, inR) * 0.80 * on);\n" +
        "      float nose = triMask(fp, vec2(-0.055 * s, 0.17 * s), vec2(0.055 * s, 0.17 * s), vec2(0.0, 0.25 * s), aa);\n" +
        "      c = mix(c, vec3(1.0, 0.55, 0.66), nose * 0.95 * on);\n" +
        "      float whisk = 0.0;\n" +
        "      whisk = max(whisk, segMask(fp, vec2(-0.14 * s, 0.20 * s), vec2(-0.66 * s, 0.10 * s), 0.004 * s, aa));\n" +
        "      whisk = max(whisk, segMask(fp, vec2(-0.14 * s, 0.23 * s), vec2(-0.68 * s, 0.24 * s), 0.004 * s, aa));\n" +
        "      whisk = max(whisk, segMask(fp, vec2(-0.14 * s, 0.26 * s), vec2(-0.66 * s, 0.38 * s), 0.004 * s, aa));\n" +
        "      whisk = max(whisk, segMask(fp, vec2( 0.14 * s, 0.20 * s), vec2( 0.66 * s, 0.10 * s), 0.004 * s, aa));\n" +
        "      whisk = max(whisk, segMask(fp, vec2( 0.14 * s, 0.23 * s), vec2( 0.68 * s, 0.24 * s), 0.004 * s, aa));\n" +
        "      whisk = max(whisk, segMask(fp, vec2( 0.14 * s, 0.26 * s), vec2( 0.66 * s, 0.38 * s), 0.004 * s, aa));\n" +
        "      c = mix(c, vec3(0.98, 0.96, 0.92), whisk * 0.72 * on);\n" +
        "      float blush = max(ellipseMask(fp - vec2(-0.30 * s, 0.12 * s), vec2(0.13 * s, 0.085 * s), aa * 1.5),\n" +
        "                        ellipseMask(fp - vec2( 0.30 * s, 0.12 * s), vec2(0.13 * s, 0.085 * s), aa * 1.5));\n" +
        "      c = mix(c, vec3(1.0, 0.45, 0.55), blush * 0.16 * on);\n" +
        "    }\n" +

        // Dog: floppy ears, muzzle, nose, tongue.
        "    else if (uFilter == 10) {\n" +
        "      float earL = ellipseMask(rot(fp - vec2(-0.52 * s, -0.06 * s), 0.28), vec2(0.19 * s, 0.44 * s), aa);\n" +
        "      float earR = ellipseMask(rot(fp - vec2( 0.52 * s, -0.06 * s), -0.28), vec2(0.19 * s, 0.44 * s), aa);\n" +
        "      float ears = max(earL, earR);\n" +
        "      c = mix(c, vec3(0.36, 0.22, 0.12), ears * 0.94 * on);\n" +
        "      float muzzle = ellipseMask(fp - vec2(0.0, 0.19 * s), vec2(0.24 * s, 0.16 * s), aa * 1.4);\n" +
        "      c = mix(c, vec3(0.97, 0.93, 0.86), muzzle * 0.55 * on);\n" +
        "      float nose = ellipseMask(fp - vec2(0.0, 0.13 * s), vec2(0.085 * s, 0.062 * s), aa);\n" +
        "      c = mix(c, vec3(0.10, 0.08, 0.09), nose * 0.95 * on);\n" +
        "      float tongue = ellipseMask(fp - vec2(0.0, 0.30 * s), vec2(0.070 * s, 0.085 * s), aa);\n" +
        "      c = mix(c, vec3(0.95, 0.42, 0.48), tongue * 0.90 * on);\n" +
        "      float spot = ellipseMask(rot(fp - vec2(0.34 * s, -0.30 * s), 0.4), vec2(0.12 * s, 0.16 * s), aa);\n" +
        "      c = mix(c, vec3(0.28, 0.17, 0.10), spot * 0.35 * on);\n" +
        "    }\n" +
        "  }\n" +

        "  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);\n" +
        "}\n";

    // Minimal program used if the effect shader cannot be compiled on a device.
    private static final String FRAGMENT_FALLBACK =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTexCoord;\n" +
        "uniform samplerExternalOES sTexture;\n" +
        "void main(){ gl_FragColor = vec4(texture2D(sTexture, vTexCoord).rgb, 1.0); }\n";

    private static final float FACE_HOLD_MS = 420f;

    private final Handler handler;
    private EGLDisplayHolder egl;
    private SurfaceTexture cameraTexture;
    private Surface cameraInput;
    private Surface previewSurface;
    private EGL14Surface previewEgl;
    private Surface encoderSurface;
    private EGL14Surface encoderEgl;

    private int program;
    private int textureId;
    private volatile int filter;
    private int positionLoc, matrixLoc, rotLoc, cropLoc, mirrorLoc, samplerLoc,
            resLoc, texelLoc, filterLoc, hasFaceLoc, faceAlphaLoc, faceLoc,
            eyesLoc, mouthLoc, angleLoc, timeLoc;
    private FloatBuffer vertexBuffer;

    private int cameraWidth = 1280, cameraHeight = 960;
    private int rotation = 0;
    private boolean mirror = true;

    // Face state, display-normalised (0..1, origin top-left).
    private boolean hasFace;
    private float faceL, faceT, faceR, faceB;
    private float eyeLx, eyeLy, eyeRx, eyeRy;
    private float mouthX, mouthY;
    private float faceAngle;
    private float faceAlpha;
    private long lastFaceAt;
    private long lastDrawAt;
    private boolean released;
    private final float[] transform = new float[16];
    private final float[] rotMatrix = new float[9];

    ArCompositor(Handler handler) {
        this.handler = handler;
    }

    Surface start(SurfaceTexture previewTexture, int cameraW, int cameraH,
                  int previewW, int previewH, int rotationDegrees, boolean mirrored) {
        checkThread();
        stop();
        released = false;
        cameraWidth = Math.max(2, cameraW);
        cameraHeight = Math.max(2, cameraH);
        rotation = ((rotationDegrees % 360) + 360) % 360;
        mirror = mirrored;

        egl = new EGLDisplayHolder();
        egl.open();

        previewTexture.setDefaultBufferSize(Math.max(2, previewW), Math.max(2, previewH));
        previewSurface = new Surface(previewTexture);
        previewEgl = new EGL14Surface(egl, previewSurface);
        if (!previewEgl.makeCurrent()) throw new RuntimeException("EGL preview context activation failed");

        textureId = createExternalTexture();
        cameraTexture = new SurfaceTexture(textureId);
        cameraTexture.setDefaultBufferSize(cameraWidth, cameraHeight);
        cameraTexture.setOnFrameAvailableListener(this, handler);
        cameraInput = new Surface(cameraTexture);

        program = createProgram();
        if (program == 0) program = createFallbackProgram();
        positionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        matrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");
        rotLoc = GLES20.glGetUniformLocation(program, "uRot");
        cropLoc = GLES20.glGetUniformLocation(program, "uCrop");
        mirrorLoc = GLES20.glGetUniformLocation(program, "uMirror");
        samplerLoc = GLES20.glGetUniformLocation(program, "sTexture");
        resLoc = GLES20.glGetUniformLocation(program, "uResolution");
        texelLoc = GLES20.glGetUniformLocation(program, "uTexel");
        filterLoc = GLES20.glGetUniformLocation(program, "uFilter");
        hasFaceLoc = GLES20.glGetUniformLocation(program, "uHasFace");
        faceAlphaLoc = GLES20.glGetUniformLocation(program, "uFaceAlpha");
        faceLoc = GLES20.glGetUniformLocation(program, "uFace");
        eyesLoc = GLES20.glGetUniformLocation(program, "uEyes");
        mouthLoc = GLES20.glGetUniformLocation(program, "uMouth");
        angleLoc = GLES20.glGetUniformLocation(program, "uAngle");
        timeLoc = GLES20.glGetUniformLocation(program, "uTime");

        float[] vertices = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(vertices).position(0);

        buildRotationMatrix(rotation, rotMatrix);
        return cameraInput;
    }

    void setEncoderSurface(Surface surface) {
        checkThread();
        if (encoderEgl != null) {
            encoderEgl.release();
            encoderEgl = null;
        }
        encoderSurface = surface;
        if (surface != null) encoderEgl = new EGL14Surface(egl, surface);
    }

    void setFilter(int value) { filter = value; }

    /**
     * Face data in final display space. Points are normalised 0..1 with the
     * origin at the top-left of the visible (cropped, upright, mirrored) frame,
     * which is the same space the shader draws in.
     */
    void setFace(float left, float top, float right, float bottom,
                 float lex, float ley, float rex, float rey,
                 float mx, float my, float angle) {
        long now = SystemClock.uptimeMillis();
        if (!hasFace) {
            faceL = left; faceT = top; faceR = right; faceB = bottom;
            eyeLx = lex; eyeLy = ley; eyeRx = rex; eyeRy = rey;
            mouthX = mx; mouthY = my; faceAngle = angle;
            hasFace = true;
            faceAlpha = Math.min(faceAlpha, 0.25f);
        } else {
            final float boxA = 0.38f;
            faceL = lerp(faceL, left, boxA);
            faceT = lerp(faceT, top, boxA);
            faceR = lerp(faceR, right, boxA);
            faceB = lerp(faceB, bottom, boxA);
            final float ptA = 0.32f;
            eyeLx = lerp(eyeLx, lex, ptA); eyeLy = lerp(eyeLy, ley, ptA);
            eyeRx = lerp(eyeRx, rex, ptA); eyeRy = lerp(eyeRy, rey, ptA);
            mouthX = lerp(mouthX, mx, 0.25f); mouthY = lerp(mouthY, my, 0.25f);
            float delta = angle - faceAngle;
            while (delta > Math.PI) delta -= 2 * Math.PI;
            while (delta < -Math.PI) delta += 2 * Math.PI;
            faceAngle += delta * 0.30f;
        }
        lastFaceAt = now;
    }

    void clearFace() {
        hasFace = false;
        faceAlpha = 0f;
    }

    Surface getCameraInputSurface() { return cameraInput; }

    @Override public void onFrameAvailable(SurfaceTexture ignored) {
        if (released || cameraTexture == null || egl == null || previewEgl == null) return;
        try {
            cameraTexture.updateTexImage();
            cameraTexture.getTransformMatrix(transform);
            long timestamp = cameraTexture.getTimestamp();
            long now = SystemClock.uptimeMillis();
            if (hasFace && now - lastFaceAt > (long) FACE_HOLD_MS) hasFace = false;
            float dt = lastDrawAt == 0 ? 16f : Math.min(120f, now - lastDrawAt);
            lastDrawAt = now;
            float target = hasFace ? 1f : 0f;
            float step = target > faceAlpha ? dt / 140f : dt / 200f;
            faceAlpha += Math.max(-step, Math.min(step, target - faceAlpha));
            if (faceAlpha < 0f) faceAlpha = 0f;
            if (faceAlpha > 1f) faceAlpha = 1f;

            draw(previewEgl, timestamp, now);
            if (encoderEgl != null) draw(encoderEgl, timestamp, now);
        } catch (RuntimeException ignoredError) {
            // A single dropped frame must never kill the preview.
        }
    }

    private void draw(EGL14Surface target, long timestamp, long now) {
        if (target == null || !target.makeCurrent()) return;
        GLES20.glViewport(0, 0, target.width, target.height);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(samplerLoc, 0);

        float[] crop = cropFor(cameraWidth, cameraHeight, rotation, target.width, target.height);
        GLES20.glUniformMatrix3fv(rotLoc, 1, false, rotMatrix, 0);
        GLES20.glUniform2f(cropLoc, crop[0], crop[1]);
        GLES20.glUniform1i(mirrorLoc, mirror ? 1 : 0);
        GLES20.glUniformMatrix4fv(matrixLoc, 1, false, transform, 0);

        GLES20.glUniform2f(resLoc, target.width, target.height);
        GLES20.glUniform2f(texelLoc, 1f / cameraWidth, 1f / cameraHeight);
        GLES20.glUniform1i(filterLoc, filter);
        GLES20.glUniform1i(hasFaceLoc, hasFace ? 1 : 0);
        GLES20.glUniform1f(faceAlphaLoc, faceAlpha);
        GLES20.glUniform4f(faceLoc, faceL, faceT, faceR, faceB);
        GLES20.glUniform4f(eyesLoc, eyeLx, eyeLy, eyeRx, eyeRy);
        GLES20.glUniform2f(mouthLoc, mouthX, mouthY);
        GLES20.glUniform1f(angleLoc, faceAngle);
        GLES20.glUniform1f(timeLoc, now * 0.001f);

        vertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(positionLoc);
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(positionLoc);

        if (target == encoderEgl && timestamp > 0) {
            EGLExt.eglPresentationTimeANDROID(egl.display, target.surface, timestamp);
        }
        EGL14.eglSwapBuffers(egl.display, target.surface);
    }

    /** Visible fraction of the camera image in x and y for a cover-fit target. */
    static float[] cropFor(int cameraW, int cameraH, int rotationDegrees, int targetW, int targetH) {
        boolean rotated = rotationDegrees == 90 || rotationDegrees == 270;
        float imgW = rotated ? cameraH : cameraW;
        float imgH = rotated ? cameraW : cameraH;
        float imageAspect = imgW / Math.max(1f, imgH);
        float targetAspect = targetW / (float) Math.max(1, targetH);
        float cx = 1f, cy = 1f;
        if (imageAspect <= 0f || targetAspect <= 0f) return new float[]{1f, 1f};
        if (targetAspect > imageAspect) cy = imageAspect / targetAspect;
        else cx = targetAspect / imageAspect;
        return new float[]{Math.min(1f, cx), Math.min(1f, cy)};
    }

    /**
     * Camera buffer normalised point to upright, mirrored, cover-cropped display
     * space. MainActivity uses this for face coordinates so the shader and the
     * face detector can never disagree about orientation.
     */
    static float[] sensorToDisplay(float sx, float sy, int rotationDegrees, boolean mirrored,
                                   float cropX, float cropY) {
        float dx;
        float dy;
        switch (rotationDegrees) {
            case 90:  dx = 1f - sy; dy = sx; break;
            case 180: dx = 1f - sx; dy = 1f - sy; break;
            case 270: dx = sy; dy = 1f - sx; break;
            default:  dx = sx; dy = sy; break;
        }
        if (mirrored) dx = 1f - dx;
        float cx = cropX <= 0f ? 1f : cropX;
        float cy = cropY <= 0f ? 1f : cropY;
        return new float[]{(dx - 0.5f) / cx + 0.5f, (dy - 0.5f) / cy + 0.5f};
    }

    /** Inverse of the display rotation: display normalised point to camera buffer point. */
    private static void buildRotationMatrix(int rotationDegrees, float[] out) {
        switch (rotationDegrees) {
            case 90:  // t = (y, 1 - x)
                out[0] = 0; out[1] = -1; out[2] = 0;
                out[3] = 1; out[4] = 0;  out[5] = 0;
                out[6] = 0; out[7] = 1;  out[8] = 1;
                break;
            case 180: // t = (1 - x, 1 - y)
                out[0] = -1; out[1] = 0; out[2] = 0;
                out[3] = 0;  out[4] = -1; out[5] = 0;
                out[6] = 1;  out[7] = 1;  out[8] = 1;
                break;
            case 270: // t = (1 - y, x)
                out[0] = 0;  out[1] = 1; out[2] = 0;
                out[3] = -1; out[4] = 0; out[5] = 0;
                out[6] = 1;  out[7] = 0; out[8] = 1;
                break;
            default:
                out[0] = 1; out[1] = 0; out[2] = 0;
                out[3] = 0; out[4] = 1; out[5] = 0;
                out[6] = 0; out[7] = 0; out[8] = 1;
                break;
        }
    }

    static float[] rotationMatrix(int rotationDegrees) {
        float[] matrix = new float[9];
        buildRotationMatrix(((rotationDegrees % 360) + 360) % 360, matrix);
        return matrix;
    }

    void stop() {
        checkThread();
        released = true;
        if (egl != null && previewEgl != null) previewEgl.makeCurrent();
        if (program != 0) { GLES20.glDeleteProgram(program); program = 0; }
        if (textureId != 0) { GLES20.glDeleteTextures(1, new int[]{textureId}, 0); textureId = 0; }
        if (cameraTexture != null) {
            cameraTexture.setOnFrameAvailableListener(null);
            cameraTexture.release();
            cameraTexture = null;
        }
        if (cameraInput != null) { cameraInput.release(); cameraInput = null; }
        if (encoderEgl != null) { encoderEgl.release(); encoderEgl = null; }
        encoderSurface = null;
        if (previewEgl != null) { previewEgl.release(); previewEgl = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        if (egl != null) { egl.close(); egl = null; }
        hasFace = false;
        faceAlpha = 0f;
        lastDrawAt = 0;
    }

    private void checkThread() {
        if (handler.getLooper().getThread() != Thread.currentThread())
            throw new IllegalStateException("ArCompositor must be used on camera thread");
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static int createExternalTexture() {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return ids[0];
    }

    /** Full effect program, or 0 when the device driver rejects it. */
    private static int createProgram() {
        int vs = 0, fs = 0;
        try {
            vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
            fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vs);
            GLES20.glAttachShader(p, fs);
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                GLES20.glDeleteProgram(p);
                return 0;
            }
            return p;
        } catch (RuntimeException unsupported) {
            return 0;
        } finally {
            if (vs != 0) GLES20.glDeleteShader(vs);
            if (fs != 0) GLES20.glDeleteShader(fs);
        }
    }

    private static int createFallbackProgram() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_FALLBACK);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("AR shader link failed: " + log);
        }
        return p;
    }

    private static int compile(int type, String source) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, source);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("AR shader compile failed: " + log);
        }
        return s;
    }

    private static final class EGLDisplayHolder {
        android.opengl.EGLDisplay display;
        android.opengl.EGLContext context;
        android.opengl.EGLConfig config;

        void open() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, v, 0, v, 1))
                throw new RuntimeException("EGL init failed");
            int[] attrs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            };
            android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0)
                throw new RuntimeException("No EGL config");
            config = configs[0];
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("EGL context failed");
        }

        void close() {
            if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (context != null) EGL14.eglDestroyContext(display, context);
                EGL14.eglTerminate(display);
            }
            display = null;
            context = null;
        }
    }

    private static final class EGL14Surface {
        final EGLDisplayHolder owner;
        final android.opengl.EGLSurface surface;
        final int width, height;

        EGL14Surface(EGLDisplayHolder owner, Surface window) {
            this.owner = owner;
            surface = EGL14.eglCreateWindowSurface(owner.display, owner.config, window,
                    new int[]{EGL14.EGL_NONE}, 0);
            if (surface == EGL14.EGL_NO_SURFACE) throw new RuntimeException("EGL window surface failed");
            int[] w = new int[1], h = new int[1];
            EGL14.eglQuerySurface(owner.display, surface, EGL14.EGL_WIDTH, w, 0);
            EGL14.eglQuerySurface(owner.display, surface, EGL14.EGL_HEIGHT, h, 0);
            width = Math.max(1, w[0]);
            height = Math.max(1, h[0]);
        }

        boolean makeCurrent() {
            return EGL14.eglMakeCurrent(owner.display, surface, surface, owner.context);
        }

        void release() {
            EGL14.eglDestroySurface(owner.display, surface);
        }
    }
}
