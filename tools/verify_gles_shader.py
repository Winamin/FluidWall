#!/usr/bin/env python3
"""Compile and pixel-test the production GLSL on Windows ANGLE, without an Android device.

Usage: python tools/verify_gles_shader.py --angle-dir PATH_TO_ANGLE_DLLS
Requires libEGL.dll, libGLESv2.dll and their dependencies in the supplied directory.
Uses a software D3D11 EGL pbuffer. This validates GLSL and point tiling, not Android
Hook execution, GL state restoration by the Kotlin wrapper, or system composition.
"""
import argparse
import ctypes as C
import os
from pathlib import Path
import re
import textwrap

I, U, F, P = C.c_int, C.c_uint, C.c_float, C.c_void_p
NONE = 0x3038
ROOT = Path(__file__).resolve().parents[1]


def require(value, message):
    if not value:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--angle-dir', type=Path, required=True)
    args = parser.parse_args()
    require(os.name == 'nt', 'This ANGLE runner requires Windows')
    directory = args.angle_dir.resolve()
    dll_path = os.add_dll_directory(str(directory))
    egl = C.WinDLL(str(directory / 'libEGL.dll'))
    gl = C.WinDLL(str(directory / 'libGLESv2.dll'))

    def bind(lib, name, result, *parameters):
        fn = getattr(lib, name)
        fn.restype, fn.argtypes = result, parameters
        return fn

    get_proc = bind(egl, 'eglGetProcAddress', P, C.c_char_p)
    address = get_proc(b'eglGetPlatformDisplayEXT')
    require(address, 'ANGLE EGL platform extension unavailable')
    platform = C.WINFUNCTYPE(P, U, P, C.POINTER(I))(address)
    # EGL_PLATFORM_ANGLE, TYPE_D3D11, DEVICE_TYPE_WARP: deterministic software rendering.
    display = platform(0x3202, None, (I * 5)(0x3203, 0x3208, 0x3209, 0x320B, NONE))
    initialize = bind(egl, 'eglInitialize', U, P, C.POINTER(I), C.POINTER(I))
    major, minor = I(), I()
    require(initialize(display, C.byref(major), C.byref(minor)), 'eglInitialize failed')
    choose = bind(egl, 'eglChooseConfig', U, P, C.POINTER(I), C.POINTER(P), I, C.POINTER(I))
    attrs = (I * 15)(0x3033, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3025, 0, NONE)
    config, count = P(), I()
    require(choose(display, attrs, C.byref(config), 1, C.byref(count)) and count.value, 'No ES2 RGBA8 config')
    width, height = 53, 79
    create_surface = bind(egl, 'eglCreatePbufferSurface', P, P, P, C.POINTER(I))
    surface = create_surface(display, config, (I * 5)(0x3057, width, 0x3056, height, NONE))
    create_context = bind(egl, 'eglCreateContext', P, P, P, P, C.POINTER(I))
    context = create_context(display, config, None, (I * 3)(0x3098, 2, NONE))
    make_current = bind(egl, 'eglMakeCurrent', U, P, P, P, P)
    require(surface and context and make_current(display, surface, surface, context), 'No current ES2 pbuffer')
    get_string = bind(gl, 'glGetString', C.c_char_p, U)
    print('GL:', get_string(0x1F02).decode(), '/', get_string(0x1F01).decode())
    error = bind(gl, 'glGetError', U)

    def check(label):
        value = error()
        require(value == 0, f'{label}: GL error 0x{value:x}')

    create_shader = bind(gl, 'glCreateShader', U, U)
    shader_source = bind(gl, 'glShaderSource', None, U, I, C.POINTER(C.c_char_p), C.POINTER(I))
    compile_shader = bind(gl, 'glCompileShader', None, U)
    shader_iv = bind(gl, 'glGetShaderiv', None, U, U, C.POINTER(I))
    shader_log = bind(gl, 'glGetShaderInfoLog', None, U, I, C.POINTER(I), P)
    create_program = bind(gl, 'glCreateProgram', U)
    attach_shader = bind(gl, 'glAttachShader', None, U, U)
    link_program = bind(gl, 'glLinkProgram', None, U)
    program_iv = bind(gl, 'glGetProgramiv', None, U, U, C.POINTER(I))
    program_log = bind(gl, 'glGetProgramInfoLog', None, U, I, C.POINTER(I), P)
    use_program = bind(gl, 'glUseProgram', None, U)
    location = bind(gl, 'glGetUniformLocation', I, U, C.c_char_p)
    uniform1f = bind(gl, 'glUniform1f', None, I, F)
    uniform1i = bind(gl, 'glUniform1i', None, I, I)
    uniform2f = bind(gl, 'glUniform2f', None, I, F, F)
    uniform3f = bind(gl, 'glUniform3f', None, I, F, F, F)

    source = (ROOT / 'app/src/main/java/com/txf/fluidwall/core/WallpaperGlTransition.kt').read_text('utf-8')

    def literal(name):
        match = re.search(r'private val ' + name + r' = [^\n]*?"""(.*?)"""\.trimIndent\(\)', source, re.S)
        require(match, f'Production shader literal {name} not found')
        return textwrap.dedent(match.group(1)).strip()

    vertex = literal('VERTEX')
    prefix = literal('PRECISION') + '\n' + literal('COLOR_OUTPUT') + '\n'

    def program(fragment):
        result = create_program()
        for kind, text in ((0x8B31, vertex), (0x8B30, fragment)):
            shader = create_shader(kind)
            encoded = C.c_char_p(text.encode())
            shader_source(shader, 1, C.byref(encoded), None)
            compile_shader(shader)
            ok = I()
            shader_iv(shader, 0x8B81, C.byref(ok))
            log = C.create_string_buffer(8192)
            shader_log(shader, len(log), None, log)
            require(ok.value, 'Shader compilation failed: ' + log.value.decode(errors='replace'))
            attach_shader(result, shader)
            bind(gl, 'glDeleteShader', None, U)(shader)
        link_program(result)
        ok = I()
        program_iv(result, 0x8B82, C.byref(ok))
        log = C.create_string_buffer(8192)
        program_log(result, len(log), None, log)
        require(ok.value, 'Shader linking failed: ' + log.value.decode(errors='replace'))
        return result

    transition = program(prefix + literal('FRAGMENT'))
    copy = program(prefix + literal('COPY_FRAGMENT'))
    print('PASS: production vertex, transition and recovery shaders compile/link')
    active_texture = bind(gl, 'glActiveTexture', None, U)
    bind_texture = bind(gl, 'glBindTexture', None, U, U)
    tex_image = bind(gl, 'glTexImage2D', None, U, I, I, I, I, I, U, U, P)
    tex_parameter = bind(gl, 'glTexParameteri', None, U, U, I)
    textures = (U * 2)()
    bind(gl, 'glGenTextures', None, I, C.POINTER(U))(2, textures)
    bind(gl, 'glPixelStorei', None, U, I)(0x0CF5, 1)
    # Deliberately different aspect ratios; lock rows are top-first, copied home rows bottom-first.
    lock_width, lock_height = 106, 79
    lock = bytes(channel for y in range(lock_height) for x in range(lock_width)
                 for channel in (int(x * 255 / (lock_width - 1)), int(y * 255 / (lock_height - 1)), 40, 255))
    home = bytes(channel for y in range(height) for x in range(width)
                 for channel in (20, int(y * 220 / (height - 1)), int(x * 255 / (width - 1)), 255))

    def upload(unit, w, h, pixels):
        active_texture(0x84C0 + unit)
        bind_texture(0x0DE1, textures[unit])
        data = (C.c_ubyte * len(pixels)).from_buffer_copy(pixels)
        tex_image(0x0DE1, 0, 0x1908, w, h, 0, 0x1908, 0x1401, data)
        for name, value in ((0x2801, 0x2601), (0x2800, 0x2601), (0x2802, 0x812F), (0x2803, 0x812F)):
            tex_parameter(0x0DE1, name, value)

    upload(0, lock_width, lock_height, lock)
    upload(1, width, height, home)
    viewport = bind(gl, 'glViewport', None, I, I, I, I)
    scissor = bind(gl, 'glScissor', None, I, I, I, I)
    enable = bind(gl, 'glEnable', None, U)
    disable = bind(gl, 'glDisable', None, U)
    draw_arrays = bind(gl, 'glDrawArrays', None, U, I, I)
    read_pixels = bind(gl, 'glReadPixels', None, I, I, I, I, U, U, P)
    viewport(0, 0, width, height)
    enable(0x0C11)
    disable(0x0BD0)  # Dither would obscure exact endpoint comparison.
    point_range = (F * 2)()
    bind(gl, 'glGetFloatv', None, U, C.POINTER(F))(0x846D, point_range)
    require(point_range[1] >= max(width, height), 'ANGLE point-size limit too small for comparison')

    def draw(p, tile=32, prog=transition):
        use_program(prog)
        def loc(name):
            return location(prog, name.encode())
        for name, value in {'uFluidWallOwner': 0, 'uProgress': p, 'uScale': 3.2, 'uWarp': .055,
                            'uSoft': .16, 'uGrain': 0, 'uBleed': .45, 'uRadial': .3, 'uSeed': 1.7}.items():
            uniform1f(loc(name), value)
        uniform1i(loc('uLock'), 0)
        uniform1i(loc('uHome'), 1)
        uniform1i(loc('uLinearOutput'), 0)
        uniform2f(loc('uSize'), width, height)
        uniform2f(loc('uLockUvScale'), .5, 1)
        uniform3f(loc('uTint'), .4, .6, 1)
        for y in range(0, height, tile):
            for x in range(0, width, tile):
                w, h = min(tile, width - x), min(tile, height - y)
                scissor(x, y, w, h)
                uniform2f(loc('uPointCenter'), (x + w * .5) * 2 / width - 1, (y + h * .5) * 2 / height - 1)
                uniform1f(loc('uPointSize'), max(w, h))
                draw_arrays(0, 0, 1)
        pixels = (C.c_ubyte * (width * height * 4))()
        read_pixels(0, 0, width, height, 0x1908, 0x1401, pixels)
        check('draw/read pixels')
        return bytes(pixels)

    zero = draw(0)
    for x, y in ((0, 0), (width - 1, 0), (0, height - 1), (width - 1, height - 1), (26, 39)):
        offset = (y * width + x) * 4
        expected = ((x + 26.5) * 255 / (lock_width - 1), (height - 1 - y) * 255 / (height - 1), 40, 255)
        require(all(abs(zero[offset + c] - expected[c]) <= 2 for c in range(4)), 'p0 lock orientation/crop mismatch')
    print('PASS: p0 lock orientation and center crop')
    middle = draw(.55)
    require(middle != zero and middle != home, 'Middle transition did not change pixels')
    require(middle == draw(.55, max(width, height)), 'Tile seams or coordinate mismatch')
    require(draw(1) == home, 'p1 changed native home pixels or orientation')
    require(draw(1, prog=copy) == home, 'Recovery shader did not reproduce native home')
    print('PASS: middle frame changes, tiles match, p1 and recovery preserve all home pixels')
    # A new stock draw must replace the snapshot, not sample the previous transition result.
    home2 = bytes(255 - c if i % 4 != 3 else 255 for i, c in enumerate(home))
    upload(1, width, height, home2)
    require(draw(1) == home2, 'Updated home texture was not sampled')
    check('final')
    print('PASS: changed home frame sampled; no GL errors. Android runtime remains unverified.')
    make_current(display, None, None, None)
    bind(egl, 'eglDestroyContext', U, P, P)(display, context)
    bind(egl, 'eglDestroySurface', U, P, P)(display, surface)
    bind(egl, 'eglTerminate', U, P)(display)
    dll_path.close()


if __name__ == '__main__':
    main()
