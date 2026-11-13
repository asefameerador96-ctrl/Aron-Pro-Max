import vtracer, glob, os, re, io, sys
from PIL import Image, ImageFilter
import cairosvg
res='android/feature-amo/src/main/res/drawable-nodpi'
tmp='/tmp/claude-0/-home-user-Aron-Pro-Max/6a48f321-6121-5b84-86ff-c599aaad1e9c/scratchpad/vec'
K=int(sys.argv[1]); S=int(sys.argv[2])
tot=0; shots=[]
for f in sorted(glob.glob(res+'/amo_art_*.webp')):
    n=os.path.basename(f)[:-5]
    if n=='amo_art_astha': continue
    im=Image.open(f).convert('RGB'); W=im.size[0]*S
    im=im.resize((W,W),Image.LANCZOS).filter(ImageFilter.MedianFilter(3))
    q=im.quantize(K,method=Image.Quantize.MEDIANCUT,dither=Image.Dither.NONE).convert('RGB')
    png=f'{tmp}/{n}.png'; q.save(png); svg=f'{tmp}/{n}.svg'
    vtracer.convert_image_to_svg_py(png,svg,colormode='color',hierarchical='stacked',mode='spline',filter_speckle=12,color_precision=8,layer_difference=1,corner_threshold=60,length_threshold=5.0,splice_threshold=45,path_precision=0)
    s=open(svg).read()
    paths=re.findall(r'<path d="([^"]+)" fill="(#[0-9A-Fa-f]{6})" transform="translate\(([-\d.]+),([-\d.]+)\)"/>',s)
    out=[f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="56dp" android:height="56dp" android:viewportWidth="{W}" android:viewportHeight="{W}">']
    for d,c,tx,ty in paths:
        r,g,b=int(c[1:3],16),int(c[3:5],16),int(c[5:7],16)
        if r>236 and g>236 and b>236: continue
        out.append(f'<group android:translateX="{tx}" android:translateY="{ty}"><path android:fillColor="{c}" android:pathData="{re.sub(chr(92)+"s+"," ",d)}"/></group>')
    out.append('</vector>')
    x='\n'.join(out); open(f'{res}/{n}.xml','w').write(x); tot+=len(x)
    shots.append(Image.open(io.BytesIO(cairosvg.svg2png(url=svg,output_width=168,output_height=168,background_color='white'))).convert('RGB'))
print(tot//1024,'KB')
m=Image.new('RGB',(168*6,336),'white')
for i,s_ in enumerate(shots): m.paste(s_,((i%6)*168,(i//6)*168))
m.save(tmp+'/montage.png')
