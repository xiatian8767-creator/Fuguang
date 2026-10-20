"""Compare production GLSL with 2.1.2 using depth exported by DepthFieldTest.
Run test_gl_render.py prerequisites first. User images and output stay ignored.
"""
from pathlib import Path
exec(compile(Path('scripts/test_gl_render.py').read_text(encoding='utf8'), 'scripts/test_gl_render.py', 'exec'))
import json
import math
import numpy as np
from PIL import Image, ImageDraw

root = fixture_dir
old_fs = Path('qa/shaders/2.1.2.frag').read_text(encoding='utf8')
width, height = 518, 388
fn(g,'glViewport',None,I,I,I,I)(0,0,width,height)
rgba = Image.open(root / 'input.png').convert('RGBA')
source_data = rgba.tobytes()
fn(g,'glActiveTexture',None,U)(0x84C0)
buf=(c.c_ubyte*len(source_data)).from_buffer_copy(source_data)
teximage(0x0DE1,0,0x1908,rgba.width,rgba.height,0,0x1908,0x1401,buf)
readback = (c.c_ubyte*(width*height*4))()

def upload(data, fmt):
    fn(g,'glActiveTexture',None,U)(0x84C1)
    for param in [0x2800,0x2801]: fn(g,'glTexParameteri',None,U,U,I)(0x0DE1,param,0x2601)
    buf=(c.c_ubyte*len(data)).from_buffer_copy(data)
    teximage(0x0DE1,0,fmt,518,518,0,fmt,0x1401,buf)

def bind(active):
    global p
    p=active
    fn(g,'glUseProgram',None,U)(p)
    for name,offset in [('aPosition',0),('aTexCoord',8)]:
        loc=fn(g,'glGetAttribLocation',I,U,c.c_char_p)(p,name.encode())
        fn(g,'glEnableVertexAttribArray',None,U)(loc)
        fn(g,'glVertexAttribPointer',None,U,I,U,U,I,P)(loc,2,0x1406,0,16,c.addressof(verts)+offset)
    for name,val in [('uImageTexture',0),('uDepthTexture',1)]: fn(g,'glUniform1i',None,I,I)(uniform(name),val)
    fn(g,'glUniform2f',None,I,F,F)(uniform('uDepthTexel'),1/518,1/518)
    fn(g,'glUniform1f',None,I,F)(uniform('uParallaxStrength'),.035)

def draw(x,y):
    fn(g,'glUniform2f',None,I,F,F)(uniform('uParallaxOffset'),x,y)
    fn(g,'glDrawArrays',None,U,I,I)(5,0,4)
    read(0,0,width,height,0x1908,0x1401,readback)
    return np.frombuffer(readback,dtype=np.uint8).reshape(height,width,4).copy()

uv_output = '''vec2 q = floor(clamp(shifted, 0.0, 1.0) * 65535.0 + 0.5);
gl_FragColor = vec4(floor(q.x / 256.0), mod(q.x, 256.0), floor(q.y / 256.0), mod(q.y, 256.0)) / 255.0;'''
statistics={}
for label in ['high','low']:
    raw=np.frombuffer((root/f'{label}-raw.bin').read_bytes(),dtype='>f4').astype(np.float32).reshape(518,518)
    prepared=np.frombuffer((root/f'{label}-prepared.bin').read_bytes(),dtype='>f4').astype(np.float32).reshape(518,518)
    statistics[label]={
        'raw_neighbor_difference_p99':float(np.percentile(np.abs(np.diff(raw,axis=1)),99)),
        'prepared_neighbor_difference_p99':float(np.percentile(np.abs(np.diff(prepared,axis=1)),99)),
    }
    for name,shader_text,field in [('before',old_fs,raw),('after',fs,prepared)]:
        if name=='before': data=np.clip(field*255,0,255).astype(np.uint8).tobytes();fmt=0x1909
        else:
            q=np.rint(field.astype(np.float64)*65535).astype(np.uint16)
            data=np.stack([q>>8,q&255,np.zeros_like(q),np.full_like(q,255)],axis=-1).astype(np.uint8).tobytes();fmt=0x1908
        upload(data,fmt)
        active=program(vs,shader_text);bind(active)
        for x in [-.8,0,.8]:
            pixels=draw(x,.35)
            Image.fromarray(pixels[::-1]).save(root/f'{label}-{name}-{x}.png')
        uv_shader=re.sub(r'gl_FragColor = texture2D\(uImageTexture, clamp\(shifted, 0.001, 0.999\)\);',uv_output,shader_text)
        assert uv_shader != shader_text
        bind(program(vs,uv_shader))
        positions=[];stretch=[];folds=[]
        for frame in range(61):
            phase=frame*2*math.pi/60
            pixels=draw(math.sin(phase)*.8,math.cos(phase)*.35).astype(np.float32)
            uv=np.stack([(pixels[:,:,0]*256+pixels[:,:,1])/65535,(pixels[:,:,2]*256+pixels[:,:,3])/65535],axis=-1)
            positions.append(uv[::4,::4]*[width,height])
            dx=np.diff(uv[:,:,0],axis=1)*width
            dy=-np.diff(uv[:,:,1],axis=0)*height
            stretch.append(max(float(dx.max()),float(dy.max())))
            folds.append(int((dx<-.03).sum()+(dy<-.03).sum()))
        acceleration=np.linalg.norm(np.diff(np.array(positions),n=2,axis=0),axis=-1)
        statistics[label][name]={
            'max_neighbor_stretch':max(stretch),
            'max_reversed_neighbors':max(folds),
            'temporal_acceleration_p99_px':float(np.percentile(acceleration,99)),
            'temporal_acceleration_max_px':float(acceleration.max()),
        }
        if name=='after':
            assert max(folds)==0, statistics[label][name]
            assert max(stretch)<2.0, statistics[label][name]
        assert fn(g,'glGetError',U)()==0
    assert statistics[label]['after']['temporal_acceleration_p99_px'] < statistics[label]['before']['temporal_acceleration_p99_px']

(root/'metrics.json').write_text(json.dumps(statistics,indent=2),encoding='utf8')
sheet=Image.new('RGB',(width*3,(height+26)*2),'white');titles=ImageDraw.Draw(sheet)
for row,name in enumerate(['before','after']):
    for col,x in enumerate([-.8,0,.8]):
        image=Image.open(root/f'high-{name}-{x}.png')
        sheet.paste(image,(col*width,row*(height+26)+26))
        titles.text((col*width+8,row*(height+26)+6),f'{name} tilt={x}',fill='black')
sheet.save(root/'comparison.png')
print(json.dumps(statistics,indent=2))
print('PASS: real-photo full-cycle GPU continuity, no reversed neighbors, finite spatial stretch.')
