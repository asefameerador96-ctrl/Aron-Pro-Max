# Jobs for trace-amo-icons.py: crops of the owner's screenshots (disp_w = width the box coordinates were read at)
def crop(src, disp_w, box):
    im = Image.open(A + src).convert('RGB'); sx = im.size[0] / disp_w
    return im.crop(tuple(int(v * sx) for v in box))
H8 = '08-dashboard_menu.jpg'; H20 = '20-manual_override_and_control_call_menu.jpg'
cx = {0: 147, 1: 360, 2: 573}
home = {'joint_call': (1, 350), 'control_call': (2, 350), 'team_location': (0, 553), 'team_performance': (1, 553), 'live_dashboard': (0, 757),
        'sr_stock': (1, 757), 'report': (0, 1365), 'campaign': (2, 1352), 'astha': (2, 1163), 'sales_journey': (1, 1362)}
res = 'android/feature-amo/src/main/res/drawable-nodpi'; ares = 'android/app-amo/src/main/res/drawable-nodpi'
tot = [0, 0]
for n, (c, y) in home.items():
    x = cx[c]; r = best(crop(H8, 720, (x - 56, y - 56, x + 56, y + 56)), f'amo_art_{n}', 72 if n == 'astha' else 56, 2, res); tot[0] += r[0]
for n, (x, y) in {'cc_sale': (868, 612), 'cc_assess': (1124, 617), 'cc_survey': (1381, 614)}.items():
    r = best(crop(H20, 1515, (x - 62, y - 62, x + 62, y + 62)), f'amo_art_{n}', 56, 2, res); tot[0] += r[0]
print('icons total KB', tot[0] // 1024)
MAX_KB, MAX_PATHS = 24.0, 150   # the update illustrations show once per release, not on Home
for name, src, dw, box, dp in (('amo_update_ribbon_cloud', '05-new_update_available.jpg', 1423, (50, 390, 655, 665), 300),
                               ('amo_update_mandatory', '07-update_from_inside_the_app.jpg', 1420, (85, 100, 615, 610), 260),
                               ('amo_update_progress', '07-update_from_inside_the_app.jpg', 1420, (855, 160, 1320, 620), 230)):
    best(crop(src, dw, box), name, dp, 1, ares)
