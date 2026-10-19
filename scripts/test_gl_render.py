"""Run production GLSL on Mesa EGL; regress 518-byte rows and orientation.
Uses stdlib ctypes only. Run: EGL_PLATFORM=surfaceless python3 scripts/test_gl_render.py
"""
import ctypes as c
import os
import re
from pathlib import Path
os.environ.setdefault('EGL_PLATFORM', 'surfaceless')
os.environ.setdefault('LIBGL_ALWAYS_SOFTWARE', '1')
e = c.CDLL('libEGL.so.1')
g = c.CDLL('libGL.so.1')

def fn(lib, name, result, *args):
    f = getattr(lib, name); f.restype = result; f.argtypes = list(args); return f
I=c.c_int; U=c.c_uint; P=c.c_void_p; F=c.c_float
getDisplay=fn(e,'eglGetDisplay',P,P)
display=getDisplay(None)
assert fn(e,'eglInitialize',U,P,P,P)(display,None,None)
assert fn(e,'eglBindAPI',U,U)(0x30A0)
attrs=(I*13)(0x3033,1,0x3040,4,0x3024,8,0x3023,8,0x3022,8,0x3021,8,0x3038)
config=P(); count=I()
assert fn(e,'eglChooseConfig',U,P,P,P,I,P)(display,attrs,c.byref(config),1,c.byref(count)) and count.value
surf=fn(e,'eglCreatePbufferSurface',P,P,P,P)(display,config,(I*5)(0x3057,518,0x3056,518,0x3038))
ctx=fn(e,'eglCreateContext',P,P,P,P,P)(display,config,None,(I*3)(0x3098,2,0x3038))
assert fn(e,'eglMakeCurrent',U,P,P,P,P)(display,surf,surf,ctx)
create=fn(g,'glCreateShader',U,U)
source=fn(g,'glShaderSource',None,U,I,P,P)
compile_=fn(g,'glCompileShader',None,U)
getstatus=fn(g,'glGetShaderiv',None,U,U,P)

def shader(kind, text):
    s=create(kind); encoded=c.c_char_p(text.encode()); source(s,1,c.byref(encoded),None); compile_(s)
    ok=I();getstatus(s,0x8B81,c.byref(ok))
    if not ok.value:
        buf=c.create_string_buffer(4096);fn(g,'glGetShaderInfoLog',None,U,I,P,P)(s,4096,None,buf);raise AssertionError(buf.value)
    return s

def program(vs, fs):
    p=fn(g,'glCreateProgram',U)()
    for s in [shader(0x8B31,vs),shader(0x8B30,fs)]: fn(g,'glAttachShader',None,U,U)(p,s)
    fn(g,'glLinkProgram',None,U)(p)
    ok=I();fn(g,'glGetProgramiv',None,U,U,P)(p,0x8B82,c.byref(ok));assert ok.value
    fn(g,'glUseProgram',None,U)(p);return p

code=Path('app/src/main/java/com/example/parallaxwallpaper/wallpaper/ParallaxEngine.kt').read_text()
vs=re.search(r'VERTEX_SHADER = """(.*?)"""',code,re.S).group(1)
fs=re.search(r'FRAGMENT_SHADER = """(.*?)"""',code,re.S).group(1)
p=program(vs,fs)
# The production quad has upper-left UV (0,0), lower-left UV (0,1).
verts=(F*16)(-1,-1,0,1,1,-1,1,1,-1,1,0,0,1,1,1,0)
for name,offset in [('aPosition',0),('aTexCoord',8)]:
    loc=fn(g,'glGetAttribLocation',I,U,c.c_char_p)(p,name.encode())
    fn(g,'glEnableVertexAttribArray',None,U)(loc)
    fn(g,'glVertexAttribPointer',None,U,I,U,U,I,P)(loc,2,0x1406,0,16,c.addressof(verts)+offset)
fn(g,'glViewport',None,I,I,I,I)(0,0,518,518)
textures=(U*2)();fn(g,'glGenTextures',None,I,P)(2,textures)
image=bytearray()
# Top half red, bottom blue; horizontal gradient in green.
for y in range(518):
    for x in range(518):image.extend((240 if y<259 else 10,x*255//517,10 if y<259 else 240,255))
# Depth varies with both axes, identifying row drift instead of just constant data.
depth=bytes((x*3+y*7)%256 for y in range(518) for x in range(518))
teximage=fn(g,'glTexImage2D',None,U,I,I,I,I,I,U,U,P)
pixelstore=fn(g,'glPixelStorei',None,U,I)
for i,data,fmt in [(0,image,0x1908),(1,depth,0x1909)]:
    fn(g,'glActiveTexture',None,U)(0x84C0+i);fn(g,'glBindTexture',None,U,U)(0x0DE1,textures[i])
    for param,value in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)]:
        fn(g,'glTexParameteri',None,U,U,I)(0x0DE1,param,value)
    pixelstore(0x0CF5,1)
    buf=(c.c_ubyte*len(data)).from_buffer_copy(data)
    teximage(0x0DE1,0,fmt,518,518,0,fmt,0x1401,buf)
    pixelstore(0x0CF5,4)

def uniform(name):return fn(g,'glGetUniformLocation',I,U,c.c_char_p)(p,name.encode())
for name,val in [('uImageTexture',0),('uDepthTexture',1)]:fn(g,'glUniform1i',None,I,I)(uniform(name),val)
fn(g,'glUniform2f',None,I,F,F)(uniform('uDepthTexel'),1/518,1/518)
fn(g,'glUniform2f',None,I,F,F)(uniform('uParallaxOffset'),0,0)
fn(g,'glUniform1f',None,I,F)(uniform('uParallaxStrength'),0.018)
fn(g,'glDrawArrays',None,U,I,I)(5,0,4)
read=fn(g,'glReadPixels',None,I,I,I,I,U,U,P)
pixels=(c.c_ubyte*(518*518*4))();read(0,0,518,518,0x1908,0x1401,pixels)
def pixel(x,y):return tuple(pixels[(y*518+x)*4:(y*518+x)*4+3])
assert pixel(259,480)[0]>200 and pixel(259,40)[2]>200, ('vertical flip',pixel(259,480),pixel(259,40))
assert pixel(480,259)[1]>pixel(40,259)[1], 'horizontal flip'
assert fn(g,'glGetError',U)()==0
print('PASS: production shaders compile; image stays upright and unmirrored.')
# Directly sample uploaded depth with nearest filtering. Compare every pixel.
fs2='precision highp float; uniform sampler2D uDepthTexture; varying vec2 vTexCoord; void main(){float d=texture2D(uDepthTexture,vTexCoord).r; gl_FragColor=vec4(d,d,d,1.0);}'
p=program(vs,fs2)
for name,offset in [('aPosition',0),('aTexCoord',8)]:
    loc=fn(g,'glGetAttribLocation',I,U,c.c_char_p)(p,name.encode());fn(g,'glEnableVertexAttribArray',None,U)(loc)
    fn(g,'glVertexAttribPointer',None,U,I,U,U,I,P)(loc,2,0x1406,0,16,c.addressof(verts)+offset)
fn(g,'glUniform1i',None,I,I)(uniform('uDepthTexture'),1)
fn(g,'glActiveTexture',None,U)(0x84C1)
for param in [0x2800,0x2801]:fn(g,'glTexParameteri',None,U,U,I)(0x0DE1,param,0x2600)
fn(g,'glDrawArrays',None,U,I,I)(5,0,4)
read(0,0,518,518,0x1908,0x1401,pixels)
errors=sum(abs(pixels[(y*518+x)*4]-depth[(517-y)*518+x])>1 for y in range(518) for x in range(518))
assert errors==0, f'{errors} corrupted depth pixels'
print('PASS: all 268324 depth pixels survive 518-byte row upload without skew.')
# Reproduce legacy bug with padded buffer (avoids an out-of-bounds host read).
padded=depth+bytes(1040);buf=(c.c_ubyte*len(padded)).from_buffer_copy(padded)
pixelstore(0x0CF5,4);teximage(0x0DE1,0,0x1909,518,518,0,0x1909,0x1401,buf)
fn(g,'glDrawArrays',None,U,I,I)(5,0,4);read(0,0,518,518,0x1908,0x1401,pixels)
errors=sum(abs(pixels[(y*518+x)*4]-depth[(517-y)*518+x])>1 for y in range(518) for x in range(518))
assert errors>100000
print(f'REPRODUCED: default alignment corrupts {errors} depth pixels; alignment=1 fixes them.')
assert 'GL_UNPACK_ALIGNMENT, 1' in code
assert 'GL_LINEAR_MIPMAP_LINEAR' not in code
assert 'mod(shifted' not in code
print('PASS: production upload uses alignment=1, ES2-compatible filtering, and no mirror inversion.')
