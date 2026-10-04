from PIL import Image, ImageDraw, ImageFilter
import math
K=2; S=1024*K                       # 2 倍超采样，最后缩小，线条边缘更平滑
def P(v): return v*K
def lerp(a,b,t): return tuple(int(a[i]+(b[i]-a[i])*t) for i in range(3))

bg=Image.new('RGB',(S,S)); d=ImageDraw.Draw(bg)
for y in range(S): d.line([(0,y),(S,y)],fill=lerp((10,32,22),(4,14,10),y/S))
img=bg.convert('RGBA')

G=(124,242,160,255); G2=(124,242,160,140); WORD=(168,250,190,255)
SW=30                               # 字母笔画粗细（和书的线条一致的单线风格）

# ---------- 字标 nimo：单线几何字，i 的点就是萤火 ----------
xh=140; base=540; r=52              # x 高、基线、n/m 拱形半径
gap=46
x=0; layout=[]
def lay(ch,w): 
    global x; layout.append((ch,x)); x+=w+gap
lay('n',2*r); lay('i',6); lay('m',4*r); lay('o',xh)
total=x-gap; x0=(1024-total)/2
top=base-xh

def cap(dr,cx,cy,w,fill): dr.ellipse([P(cx-w/2),P(cy-w/2),P(cx+w/2),P(cy+w/2)],fill=fill)
def vline(dr,x,y1,y2,fill=WORD):
    dr.line([(P(x),P(y1)),(P(x),P(y2))],fill=fill,width=P(SW)); cap(dr,x,y1,SW,fill); cap(dr,x,y2,SW,fill)
def arch(dr,xl,fill=WORD):
    # 从 xl 起、宽 2r 的上拱，两侧竖线到基线
    cy=top+r
    # PIL 的粗弧线是往 bbox 内侧画的，所以外扩半个笔画，让线条中心落在设计的几何线上
    h=SW/2
    dr.arc([P(xl-h),P(top-h),P(xl+2*r+h),P(top+2*r+h)],180,360,fill=fill,width=P(SW))
    vline(dr,xl+2*r,cy,base,fill)

d=ImageDraw.Draw(img)
i_x=None
for ch,ox in layout:
    X=x0+ox
    if ch=='n': vline(d,X,top+r,base); arch(d,X)
    if ch=='i': vline(d,X,top,base); i_x=X
    if ch=='m': vline(d,X,top+r,base); arch(d,X); arch(d,X+2*r)
    if ch=='o':
        c=(X+xh/2, top+xh/2); R=xh/2
        R2=R+SW/2
        d.ellipse([P(c[0]-R2),P(c[1]-R2),P(c[0]+R2),P(c[1]+R2)],outline=WORD,width=P(SW))

# ---------- 萤火 = i 的点 ----------
cx,cy=i_x,top-78
glow=Image.new('RGBA',(S,S),(0,0,0,0))
for rr,a in [(300,45),(200,80),(120,130),(66,200)]:
    g=Image.new('RGBA',(S,S),(0,0,0,0)); ImageDraw.Draw(g).ellipse([P(cx-rr),P(cy-rr),P(cx+rr),P(cy+rr)],fill=(120,255,150,a))
    glow=Image.alpha_composite(glow,g.filter(ImageFilter.GaussianBlur(P(rr*0.45))))
img=Image.alpha_composite(img,glow)
d=ImageDraw.Draw(img)
d.ellipse([P(cx-34),P(cy-34),P(cx+34),P(cy+34)],fill=(230,255,220,255))

# ---------- 翻开的书（缩小，放在字标下面） ----------
W=24; half=300; y_top=700; y_bot=860; rise=58
def pts(y0,amp,s): return [(P(512+s*half*i/40), P(y0-amp*math.sin(i/40*math.pi/2))) for i in range(41)]
for s in (-1,1):
    for pl in (pts(y_top,rise,s), pts(y_bot,34,s)):
        d.line(pl,fill=G,width=P(W))
        for (px,py) in pl: d.ellipse([px-P(W)/2,py-P(W)/2,px+P(W)/2,py+P(W)/2],fill=G)   # 圆润的折点
    d.line([(P(512+s*half),P(y_bot-34)),(P(512+s*half),P(y_top-rise))],fill=G,width=P(W))
    cap(d,512+s*half,y_top-rise,W,G); cap(d,512+s*half,y_bot-34,W,G)
    for k,(y0,ln) in enumerate([(732,0.78),(772,0.6),(812,0.72)]):
        a=512+s*52; b=512+s*(52+210*ln)
        f=lambda xx: y0-40*math.sin(abs(xx-512)/half*math.pi/2)
        d.line([(P(a),P(f(a))),(P(b),P(f(b)))],fill=G2,width=P(14))
d.line([(P(512),P(y_top)),(P(512),P(y_bot))],fill=G,width=P(W))

mask=Image.new('L',(S,S),0); ImageDraw.Draw(mask).rounded_rectangle([0,0,S-1,S-1],radius=P(230),fill=255)
out=Image.new('RGBA',(S,S),(0,0,0,0)); out.paste(img,(0,0),mask)
out=out.resize((1024,1024),Image.LANCZOS)
out.resize((432,432),Image.LANCZOS).save('ic_launcher.png',optimize=True)
out.save('preview_1024.png')
pv=Image.new('RGBA',(760,300),(245,245,240,255))
for i,sz in enumerate([256,144,96,48]):
    xx=[20,300,470,600][i]; pv.alpha_composite(out.resize((sz,sz),Image.LANCZOS),(xx,(300-sz)//2))
pv.save('preview_sizes.png')
