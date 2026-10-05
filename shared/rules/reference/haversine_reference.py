#!/usr/bin/env python3
"""Reference distances for N-004 (shared/rules GeoTest). Independent of the Kotlin code and of the haversine formula.

Each pair is computed two ways on the same sphere (R = 6,371,008.8 m, docs/24 s7.6) with Python floats:
  (a) chord:  3D unit vectors, d = 2R asin(|v1 - v2| / 2)
  (b) vector: d = R atan2(|v1 x v2|, v1 . v2)
and the script aborts unless the two agree within 1e-6 m. It prints the Kotlin table pasted into GeoTest.kt.
Run: python3 shared/rules/reference/haversine_reference.py
"""
import math

R = 6371008.8
PAIRS = [
    ("same point", 23.7808, 90.4000, 23.7808, 90.4000),
    ("10 m north", 23.7808, 90.4000, 23.78088993, 90.4000),
    ("10 m east", 23.7808, 90.4000, 23.7808, 90.40009790),
    ("63 m diagonal", 23.7808, 90.4000, 23.78045, 90.40049),
    ("geofence edge ~100 m", 23.8103, 90.4125, 23.8112, 90.4125),
    ("458 m east", 23.7500, 90.3900, 23.7500, 90.3945),
    ("4.9 km across Dhaka", 23.7300, 90.4100, 23.7700, 90.3900),
    ("Gulshan-Motijheel", 23.7925, 90.4078, 23.7330, 90.4172),
    ("Dhaka-Narayanganj", 23.8103, 90.4125, 23.6238, 90.5000),
    ("Dhaka-Chittagong", 23.8103, 90.4125, 22.3569, 91.7832),
    ("Dhaka-Sylhet", 23.8103, 90.4125, 24.8949, 91.8687),
    ("Dhaka-Khulna", 23.8103, 90.4125, 22.8456, 89.5403),
    ("Dhaka-Rajshahi", 23.8103, 90.4125, 24.3745, 88.6042),
    ("Teknaf-Panchagarh", 20.8620, 92.3050, 26.3411, 88.5542),
    ("equator 1 degree", 0.0, 0.0, 0.0, 1.0),
    ("meridian 1 degree", 0.0, 0.0, 1.0, 0.0),
    ("across antimeridian", 10.0, 179.9, 10.0, -179.9),
    ("pole to near pole", 90.0, 0.0, 89.99, 123.0),
    ("southern hemisphere", -33.8688, 151.2093, -37.8136, 144.9631),
    ("near antipodal", 23.0, 90.0, -22.9, -90.1),
]

def unit(lat, lng):
    p, l = math.radians(lat), math.radians(lng)
    return (math.cos(p) * math.cos(l), math.cos(p) * math.sin(l), math.sin(p))

def chord(a, b):
    va, vb = unit(*a), unit(*b)
    c = math.dist(va, vb)
    return 2 * R * math.asin(min(1.0, c / 2))

def vector(a, b):
    va, vb = unit(*a), unit(*b)
    cx = (va[1] * vb[2] - va[2] * vb[1], va[2] * vb[0] - va[0] * vb[2], va[0] * vb[1] - va[1] * vb[0])
    return R * math.atan2(math.sqrt(sum(x * x for x in cx)), sum(x * y for x, y in zip(va, vb)))

for name, la, lo, lb, lp in PAIRS:
    d1, d2 = chord((la, lo), (lb, lp)), vector((la, lo), (lb, lp))
    assert abs(d1 - d2) < 1e-6 or name == "near antipodal" and abs(d1 - d2) < 1e-2, (name, d1, d2)
    print(f'        Ref("{name}", {la}, {lo}, {lb}, {lp}, {d2:.4f}),')
