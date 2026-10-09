from preserves import parse, canonicalize, stringify, compare
print("compare.eq(1, 1.0):", compare.eq(parse('1'), parse('1.0')), " cmp:", compare.cmp(parse('1.0'), parse('1')))
for t in ['{1: a 1.0: b}', '{#t: a 1: b}', '#{1 1.0 #t}']:
    try:
        v = parse(t); print(t, "->", stringify(v), canonicalize(v).hex())
    except Exception as e:
        print(t, "-> ERROR", type(e).__name__, e)
