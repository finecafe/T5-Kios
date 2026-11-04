from PIL import Image
import os
src = Image.open('icon_source.png').convert('RGBA')
res = 'app/src/main/res'
sizes = {'mdpi':48,'hdpi':72,'xhdpi':96,'xxhdpi':144,'xxxhdpi':192}
for d,s in sizes.items():
    p = f'{res}/mipmap-{d}'; os.makedirs(p, exist_ok=True)
    im = src.resize((s,s), Image.LANCZOS)
    im.save(f'{p}/ic_launcher.png'); im.save(f'{p}/ic_launcher_round.png')
# TV banner 320x180
b = Image.new('RGBA',(320,180),(255,255,255,255))
ic = src.resize((160,160), Image.LANCZOS)
b.alpha_composite(ic,(80,10))
b.convert('RGB').save(f'{res}/drawable-xhdpi/banner.png')
