package com.txf.fluidwall.core

object FluidShader {

    val AGSL: String = """
uniform shader uLock;
uniform shader uHome;
uniform float2 uSize;
uniform float uProgress;
uniform float uScale;
uniform float uWarp;
uniform float uSoft;
uniform float uGrain;
uniform float uBleed;
uniform float uRadial;
uniform float uSeed;
uniform float3 uTint;
uniform float uRevealUnderlying;

float hash21(float2 p) {
    float2 q = p - floor(p * 0.015625) * 64.0;
    float3 v = fract(float3(q.x, q.y, q.x) * 0.1031);
    v += dot(v, v.yzx + 33.33);
    return fract((v.x + v.y) * v.z);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + float2(1.0, 0.0));
    float c = hash21(i + float2(0.0, 1.0));
    float d = hash21(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        s += a * vnoise(p);
        p = p * 2.03 + float2(17.3, 9.1);
        a *= 0.5;
    }
    return s / 0.9375;
}

float voronoi(float2 p) {
    float2 n = floor(p);
    float2 f = fract(p);
    float d1 = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            float2 g = float2(float(x), float(y));
            float2 o = float2(hash21(n + g), hash21(n + g + float2(31.0, 71.0)));
            float2 r = g + o - f;
            d1 = min(d1, dot(r, r));
        }
    }
    return clamp(sqrt(d1) / 1.15, 0.0, 1.0);
}

half4 main(float2 coord) {
    float2 res = max(uSize, float2(1.0, 1.0));
    float2 uv = coord / res;
    float asp = res.x / res.y;
    float2 p = float2(uv.x * asp, uv.y) * uScale + float2(uSeed * 3.7, uSeed * -1.9);
    float t = clamp(uProgress, 0.0, 1.0);

    float base = fbm(p);
    float vor = voronoi(p * 0.92 + base * 0.85 + float2(0.0, t * 0.35) + float2(uSeed * 0.4, 0.0));
    float field = base * 0.58 + (1.0 - vor) * 0.42;

    float2 d = (uv - float2(0.5, 0.44)) * float2(1.0, 1.15);
    float radial = 1.0 - clamp(length(d) * 1.35, 0.0, 1.0);
    field = mix(field, field * 0.55 + radial * 0.45, clamp(uRadial, 0.0, 1.0));

    float k = mix(0.74, 0.26, t);
    float soft = max(uSoft, 0.001);
    float m = smoothstep(k - soft * 0.5, k + soft * 0.5, field);
    m *= smoothstep(0.0, 0.02, t);

    // Hard guarantee for the end frame: whatever the noise field looks like (a large scale
    // produces big smooth blobs that can sit below the final threshold), the last part of the
    // transition forces full coverage so no lock wallpaper or noise shape can survive.
    float endFill = smoothstep(0.94, 1.0, t);
    m = mix(m, 1.0, endFill);

    float rim = 1.0 - smoothstep(0.0, soft * 0.95, abs(field - k));
    rim *= smoothstep(0.02, 0.12, t) * (1.0 - smoothstep(0.78, 0.94, t));

    // Warp belongs to the moving edge only: it scales with rim and fades out before the
    // transition ends, so the last frame is exactly the home wallpaper, never displaced.
    float warpFade = 1.0 - smoothstep(0.58, 0.88, t);
    float2 flow = float2(sin((base + vor) * 6.28318), cos((base - vor) * 6.28318));
    float2 warpAmt = (flow * 0.55 + (float2(base, vor) - 0.5) * 0.9)
        * (uWarp * res.y * rim * warpFade);
    float2 warped = clamp(coord + warpAmt, float2(0.0), res - float2(1.0));

    half4 lockC = uLock.eval(coord);
    half4 homeC = uHome.eval(warped);

    // A wallpaper child layer reveals the live system image underneath. Premultiplied alpha
    // keeps the moving rim correct without copying or sampling the host's rendering buffers.
    if (uRevealUnderlying > 0.5) {
        half4 source = uLock.eval(warped);
        float g = hash21(uv * 640.0 + float2(uSeed * 13.0, 7.0)) - 0.5;
        half3 rgb = source.rgb + half3(uTint * (rim * uBleed));
        rgb += half3(g * uGrain * (1.0 - smoothstep(0.72, 0.92, t)));
        half alpha = half(1.0 - m);
        return half4(clamp(rgb, half3(0.0), half3(1.0)) * alpha, alpha);
    }

    half4 c = mix(lockC, homeC, half(m));
    c.rgb += half3(uTint * float(rim * uBleed));
    float g = hash21(uv * 640.0 + float2(uSeed * 13.0, 7.0)) - 0.5;
    c.rgb += half3(g * uGrain * (1.0 - smoothstep(0.72, 0.92, t)));
    c.a = 1.0;
    return c;
}
""".trimIndent()
}
