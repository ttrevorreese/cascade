import json, sys, colorsys

def lin(c):
    c = c / 255
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

def lum(h):
    h = h.lstrip('#')
    r, g, b = (int(h[i:i+2], 16) for i in (0, 2, 4))
    return 0.2126*lin(r) + 0.7152*lin(g) + 0.0722*lin(b)

def cr(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)

def lstar(h):
    """CIE L*: perceived lightness. Even L* steps look even; even luminance steps do not."""
    y = lum(h)
    return 116 * (y ** (1/3)) - 16 if y > 0.008856 else 903.3 * y

def hx(r, g, b):
    return '#%02X%02X%02X' % (round(r*255), round(g*255), round(b*255))

def nudge(base, target):
    """Find the nearest colour to `base` at the given relative luminance, hue preserved."""
    h = base.lstrip('#')
    r, g, b = (int(h[i:i+2], 16) / 255 for i in (0, 2, 4))
    hh, ll, ss = colorsys.rgb_to_hls(r, g, b)
    best = None
    for li in range(60, 980):
        for si in range(300, 1001, 20):
            cand = hx(*colorsys.hls_to_rgb(hh, li/1000, si/1000))
            d = abs(lum(cand) - target)
            if best is None or d < best[0]:
                best = (d, cand)
    return best[1]

def audit(path):
    d = json.load(open(path))
    t = d['themes']
    fails, warns = [], []

    listed = [x for f in d['families'].values() for x in f['themes']]
    if len(listed) != len(set(listed)):
        fails.append('a theme appears in more than one family')
    if set(listed) != set(t):
        fails.append(f'family/theme mismatch: {set(t) ^ set(listed)}')

    keys = ['name','descriptor','tier','light','ground','groundDeep','surface','surfaceHigh',
            'surfaceMuted','line','textPrimary','textSecondary','textQuiet','ramp','onRamp5','firing']
    for k, v in sorted(t.items()):
        miss = [x for x in keys if x not in v]
        if miss:
            fails.append(f'{k}: missing {miss}')
            continue
        if len(v['ramp']) != 5:
            fails.append(f'{k}: ramp has {len(v["ramp"])} stops')
        if len(v['firing']) != 3:
            fails.append(f'{k}: firing has {len(v["firing"])} stops')

        for tok, minimum in (('textPrimary', 4.5), ('textSecondary', 4.5), ('textQuiet', 3.0)):
            r = cr(v[tok], v['ground'])
            if r < minimum:
                fails.append(f'{k}.{tok} on ground = {r:.2f} (need {minimum})')

        r = cr(v['onRamp5'], v['ramp'][4])
        if r < 4.5:
            fails.append(f'{k}.onRamp5 on ramp5 = {r:.2f} (need 4.5)')

        direction = v.get('rampDirection', 'ascend')
        if direction not in ('ascend', 'descend'):
            fails.append(f'{k}: rampDirection {direction!r} is not ascend or descend')
            direction = 'ascend'
        ls = [lum(c) for c in v['ramp']]
        for i in range(4):
            broken = ls[i+1] <= ls[i] if direction == 'ascend' else ls[i+1] >= ls[i]
            if broken:
                end = (1.0 if direction == 'ascend' else 0.0)
                want = (ls[i] + (ls[i+2] if i+2 < 5 else end)) / 2
                op = '<=' if direction == 'ascend' else '>='
                fails.append(
                    f'{k} ramp{i+2} luminance {ls[i+1]:.3f} {op} ramp{i+1} {ls[i]:.3f} '
                    f'({direction}) -> try {nudge(v["ramp"][i+1], want)} (target {want:.3f})')

        for i, c in enumerate(v['ramp']):
            r = cr(c, v['ground'])
            if r < 3.0:
                warns.append(f'{k}.ramp{i+1} vs ground = {r:.2f}')

        # Perceptual evenness. A big spread means one pair of neighbouring stops is
        # nearly indistinguishable while another jumps, so the escalation reads lumpy.
        Ls = [lstar(c) for c in v['ramp']]
        steps = [abs(Ls[i+1] - Ls[i]) for i in range(4)]
        spread = max(steps) - min(steps)
        if min(steps) < 6:
            warns.append(f'{k}: ramp has a near-invisible step ({min(steps):.1f} L*) — '
                         f'steps {[round(s) for s in steps]}')
        elif spread > 14:
            warns.append(f'{k}: ramp steps uneven by {spread:.1f} L* — '
                         f'steps {[round(s) for s in steps]}')

    print(f'{len(t)} themes, {len(d["families"])} families')
    print(f'\nFAILS ({len(fails)}):')
    print('\n'.join('  ' + f for f in fails) if fails else '  none')
    print(f'\nramp-vs-ground under 3:1 ({len(warns)}) — fills and dots only, never text:')
    print('\n'.join('  ' + w for w in warns) if warns else '  none')
    return len(fails)

if __name__ == '__main__':
    sys.exit(1 if audit(sys.argv[1]) else 0)
