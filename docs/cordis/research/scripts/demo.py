import hashlib
from preserves import parse, parse_with_annotations, encode, canonicalize, stringify, decode

def show(label, text, **kw):
    v = parse_with_annotations(text) if kw.pop('annotated', False) else parse(text)
    b = canonicalize(v, **kw)
    print(f"{label}\n  text : {text}\n  value: {stringify(v)}\n  hex  : {b.hex()}\n  sha256: {hashlib.sha256(b).hexdigest()}")
    return b

# (a)+(b) intent record with dict + embedded capability ref
a1 = show("A1 intent, keys order prompt/max_tokens",
          '<intent llm.complete {prompt: "x" max_tokens: 4000} #:[cap 1]>')
# (c) same value, keys reordered
a2 = show("A2 intent, keys reordered",
          '<intent llm.complete {max_tokens: 4000 prompt: "x"} #:[cap 1]>')
print("A1 == A2 canonical bytes:", a1 == a2)

s1 = show("S1 set", '#{c a b 3 -1 "z"}')
s2 = show("S2 set reordered", '#{"z" -1 b 3 c a}')
print("S1 == S2:", s1 == s2)

# Non-canonical (plain) encoding preserves insertion order of a Python dict
v = parse('{zeta: 1 alpha: 2}')
print("plain encode   :", encode(v).hex())
print("canonical      :", canonicalize(v).hex())

# (d) annotations
t_plain = '<intent llm.complete {prompt: "x" max_tokens: 4000} #:[cap 1]>'
t_ann   = '@"why: user asked" <intent llm.complete {prompt: @provenance "x" max_tokens: 4000} #:[cap 1]>'
d1 = show("D1 annotated, read WITH annotations, canonicalize (default drops them)", t_ann, annotated=True)
print("D1 == A1:", d1 == a1)
d2 = show("D2 annotated, canonicalize(include_annotations=True)", t_ann, annotated=True, include_annotations=True)
print("D2 == A1:", d2 == a1)
va = parse_with_annotations(t_ann); vp = parse(t_plain)
print("semantic equality annotated vs plain:", va == vp)

# Ordering: -1 sorts after 0 by bytes but before by semantics
print("canonical #{0 -1 1}:", canonicalize(parse('#{0 -1 1}')).hex())
print("stringify sorted text:", stringify(parse('#{1 0 -1 b a}')))
# floats vs ints are distinct
print("1 == 1.0 ?", parse('1') == parse('1.0'), canonicalize(parse('1.0')).hex())
# roundtrip
print("roundtrip ok:", decode(a1) == parse(t_plain))
