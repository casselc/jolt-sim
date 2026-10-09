# Preserves: research report for the harness design (as of 2026-10-09)

**Sources checked:**
- The canonical spec repo, https://gitlab.com/preserves/preserves. I cloned it at HEAD `6bb75e3` (2026-09-19). Its site config says spec **0.996.3, "June 2025"**. Schema spec is **0.4.2 (March 2025)**, Path spec is **0.1.0 (Aug 2021)**. I worked from the spec `.md` files in that repo, which are the same pages published at https://preserves.dev.
- https://git.syndicate-lang.org/syndicate-lang/syndicate-protocols (HEAD 2026-08-20).
- The crates.io, npm and PyPI APIs.
- A hands-on run of PyPI `preserves` 0.996.3 in a local venv.

## 1. Data model (preserves.md)

`Value = Atom | Compound | Embedded`.
- **Atoms:** Boolean, Double, SignedInteger, String, ByteString, Symbol.
- **Compounds:** Record, Sequence, Set, Dictionary.

**Float is gone.** Version 0.994.0 (March 2024) removed `Float`, so `Double` is the only IEEE type (changes.md).

What each kind is:
- **SignedInteger** is arbitrary precision.
- **String** and **Symbol** are sequences of Unicode *scalar values*, so unpaired surrogates are banned.
- **ByteString** is octets.
- **Record** is a label (any Value, usually a Symbol) plus a field tuple.
- **Embedded** is a "domain-specific, potentially stateful or located" datum: an object capability, file descriptor or actor ref. Embeddeds compare by domain-specific rules. When serialized they are "usually represented as ordinary Values".

**Total order** over all values:
- `Atom < Compound < Embedded`
- `Record < Sequence < Set < Dictionary`
- `Boolean < Double < SignedInteger < String < ByteString < Symbol`

Within each kind:
- Integers compare mathematically.
- Strings and Symbols compare by scalar value, which is the same as comparing UTF-8 bytes.
- Doubles use IEEE 754-2008 §5.10 `totalOrder`. So `-0.0 ≠ 0.0` and NaN payloads are distinct, ordered values.
- Records compare by label, then lexicographically by fields.
- Sets compare as sorted sequences. Dictionaries compare as key-sorted pair sequences.

**Equivalence** means "neither is less than the other". So `1 ≠ 1.0`, `"a" ≠ 'a'` (String vs Symbol), and `#t ≠ 1`.

**Annotations** (`@v`) "are not part of the denoted value… do not play a part in equivalences and orderings" (preserves-text.md). They are syntax-level metadata, much like Clojure metadata.

## 2. Text syntax (preserves-text.md)

| Kind | Syntax |
|---|---|
| Record | `<label f1 f2 ...>`, e.g. `<date 1821 2 3>`; the label can be any value: `<[titled person] ...>` |
| Sequence | `[1 2 3]` (commas are ignored since 0.992) |
| Set | `#{a b c}` |
| Dict | `{key: value ...}`; the `:` must follow the key |
| Boolean | `#t` `#f` |
| String | `"json-style \n \u0041"` |
| ByteString | `#"ascii\x00"`, `#x"68 65"`, or `#[aGVsbG8]` (base64, url-safe recommended) |
| Symbol | bare `llm.complete`, `foo-bar?`, or quoted `'hello world'` (switched from `\|..\|` in 0.996.0, Sept 2024) |
| Numbers | JSON grammar; a Double needs `.` or `e`; NaN and Inf are written `#xd"7ff8000000000001"` |
| Embedded | `#:value`, e.g. `#:[cap 1]` (switched from `#!` in 0.994) |
| Annotation | `@ann value`; `# comment` (hash-space) becomes a string annotation; a `#!` line becomes `@<interpreter "...">` |

**Versus EDN** (I verified these with the Python parser):
- **No keywords.** `{:a 1}` is a parse error, and `:` is a delimiter. Use Symbols.
- **No nil.** `nil` parses as the Symbol `nil`.
- **No chars, no list/vector split, no `#inst`/`#uuid` tagged literals.** `#inst "2020"` gives "Invalid # syntax". Records are the extension point: write `<inst "2020-…">`.
- **Native ByteString and Embedded,** which EDN lacks.
- **Arbitrary-value record labels and dict keys.**
- **Annotations ≈ `^meta`.**

The spec's guidance on Symbol vs String: use Symbols for static, shape-level text such as field names and labels, and Strings for data.

## 3. Binary syntax and canonical form (preserves-binary.md, canonical-binary.md)

Every tag byte is in 0x80–0xBF. Binary therefore can never be valid UTF-8, so text and binary can be told apart from the first byte.

| Tag | Meaning and layout |
|---|---|
| `80` / `81` | false / true |
| `84` | end marker |
| `85 «ann» «v»` | annotation |
| `86 «v»` | embedded |
| `87 08 <8 bytes BE binary64>` | Double |
| `B0 varint(len) <minimal big-endian two's-complement>` | SignedInteger; `0` is `B0 00`, `-1` is `B0 01 FF`, `255` is `B0 02 00 FF` |
| `B1` / `B2` / `B3 varint(len) bytes` | String (UTF-8) / ByteString / Symbol (UTF-8) |
| `B4 «label» «fields…» 84` | Record |
| `B5 … 84` / `B6 … 84` / `B7 k v … 84` | Sequence / Set / Dict |

Varints must be in shortest form. Integer bytes are minimal by definition. The 0.990 release (Oct 2023) collapsed all the integer tags into `B0`.

**Canonical form** is the whole of canonical-binary.md. It has three rules:
1. Annotations MUST NOT be present.
2. Set elements are sorted ascending by their *canonical encoded bytes*.
3. Dict pairs are sorted by the canonical encoded bytes of the key.

Every other kind is already unique under the base rules. Two consequences of sorting by bytes:
- **Length comes first.** The length prefix is compared before the content, so `zeta` (B3 04…) sorts before `alpha` (B3 05…).
- **Byte order is not value order.** `0` (B0 00) sorts before `1` (B0 01 01), which sorts before `-1` (B0 01 FF).

The spec says plainly that canonical bytes give *equivalence*, not ordering. That is the same approach as RFC 8949 §4.2.1 deterministic CBOR, which also sorts map keys by their encoded bytes.

**Hashing.** In its Security Considerations, the binary spec says canonical binary is the form "for cryptographic hashing and signing". Implementations SHOULD produce canonical output by default, and **no canonical text form exists**. Syndicate itself relies on this: sturdy-ref signatures are HMACs over "canonical machine-oriented serialization" (see §6).

**Draft syntaxes since mid-2025** (the core model and binary are unchanged):
- preserves-zerocopy.md
- preserves-tiny.md
- preserves-split.md, marked DRAFT, with commits through 2026-09-09
- P-expressions (0.991, Oct 2023)

## 4. Preserves Schema and Path

**Schema** (preserves-schema.md 0.4.2) is a `.prs` file of clauses ending in `.`:
- `version 1 .`
- `embeddedType Ref .`
- `Name = Pattern .`

Patterns:
- **Atom kinds:** `bool double int string bytes symbol any`.
- **Literals:** `=sym`, plain non-symbol atoms, or `<<lit> v>`.
- **Collections:** `[P ...]` (seq-of), `#{P}` (set-of), `{K: V ...:...}` (dict-of).
- **Tuples:** `[@a P @b Q]`, and `[@a P @rest Q ...]` for a variable tail.
- **Records:** `<label @f1 P @f2 Q>`, or the general form `<<rec> labelPat fieldsPat>`.
- **Dict with fixed keys:** `{key: P ...}`.
- **Embedded:** `#:Iface`, where the inner pattern is the *interface* of the referenced actor, e.g. `#:Session`.
- **Refs:** `Mod.Sub.Name`.

Composition:
- **Alternation** `@a P / @b Q` gives an ordered, biased sum type.
- **Intersection** `A & B` gives a product. It is marked *experimental*, and is currently the only way to express optional dict keys: `{a: int} & @c MaybeC` with `MaybeC = @present {c: x} / @absent {}`. The spec says "a future Schema version will likely include first-class support for optionality."

Compound patterns are **prefix-extensible**: `<a @v int>` also matches `<a 1 extra>`, and `{a: int}` ignores extra keys. A metaschema (`schema/schema.prs`) describes schemas as Preserves values.

**Codegen and runtime:**

| Language | How schemas are used |
|---|---|
| TypeScript | `@preserves/schema` with the `preserves-schema-ts` compiler: types, constructors, `asX` / `toX` / `fromX` |
| Rust | `preserves-schema` (build.rs) |
| Racket | compiler |
| Nim | codegen |
| Squeak | codegen |
| Python | runtime interpreter (`preserves.schema`, loads compiled `.prb`), no codegen |

**Versus Malli / JSON Schema.** Preserves Schema is an *ADT parser/unparser* language. Each definition yields a host type, a parser and a serializer. JSON Schema and Malli are *validators*.

It lacks:
- numeric ranges, string regex and length constraints
- first-class optional fields and defaults
- `oneOf` exclusivity
- unions other than ordered first-match

It has round-trip unparse, embedded and capability typing, and a self-describing metaschema.

Importing protobuf or OpenAPI maps acceptably for structure, but constraints would be lost. You would keep those as CEL predicates alongside.

**Preserves Path** (0.1.0, 2021) is XPath-like and has not changed since. It has:
- **Axes:** `/` (children), `//` (all descendants), `.key`, `.^` (label), `.keys`, `.length`, `.annotations`, `.embedded`, `%Def` (schema parse), `%-Def` (unparse).
- **Filters:** `eq/lt/gt/re`, `^label`, type filters, `~real` / `~int`.
- **Predicates:** `!`, `+`, `&`.

Its regex dialect is still listed as an open design question.

## 5. Implementations and maturity

| Language | Where | Latest (date) | Notes |
|---|---|---|---|
| Rust | gitlab.com/preserves/preserves-rs, crate `preserves` | **5.0.0-rc.10 (2026-09-03)**; last stable 4.996.0 (2024-09-26) | serde interop. `preserves-schema` is at 6.0.0-rc.10. About 90k all-time downloads. Long rc series. |
| TypeScript/JS | in the main repo, npm `@preserves/core` | **1.0.0-rc.1 (2025-07-18)**; `true` tag 0.12.0 | 607 downloads in the last month. `@preserves/schema` is at 1.0.0-rc.2. |
| Python | in the main repo, PyPI `preserves` | **0.996.3 (2025-05-27)**; the repo has 0.996.4 unreleased | about 3.7k LOC |
| Racket | in the main repo, `raco pkg install preserves` | follows the repo | reference-grade |
| Nim | git.syndicate-lang.org/ehmry/preserves-nim (Emery Hemingway) | tag 1.20260823 (repo updated 2026-09-10) | active |
| Squeak Smalltalk | squeaksource.com/Preserves | — | — |
| C / C++ | main repo, `implementations/c`, `cpp` | — | the site calls them "pre-alpha" |
| Haskell, Guile, JS, Python, Racket | Syrup-syntax variants (OCapN) | — | data model only, *Syrup* wire syntax |
| Java | — | — | **no Preserves library**; `syndicate-java` exists, but is a separate Syndicate implementation |
| **OCaml** | — | — | **none found** |
| **Clojure/JVM** | — | — | **none found** |
| Go | — | — | **none found** |

Sources for the OCaml/Clojure/Go row: the index.md implementations table, syndicate-lang.org/code/repos (as of 2026-09-02), and web search.

**Bus factor:** of the repo's commits, Tony Garnock-Jones has 1334. The next contributors have 7 (Christopher Lemmer Webber), 5 (Emery Hemingway), 2 and 1. The main repo had 43 commits since June 2025.

**Conformance corpus:** `tests/samples.pr` (about 180 cases) and `samples.bin`. It covers canonical encode, loose encode, annotations, and parse and decode errors. This is the main thing that makes a new implementation tractable.

**Effort estimate for one experienced developer.** Numbers are scaled from Python's per-module LOC: binary 488, text 632, values 621, compare 200, schema 1038, path 544.

- **OCaml:**
  - Value variant type plus `compare` with totalOrder doubles and annotation-stripped equality: about 2 days. Do not use polymorphic `compare`, because it equates `0.0` and `-0.0`.
  - Binary codec plus canonical form (Zarith for bigints): about 2–3 days.
  - Text reader and writer: about 4–6 days. The work is float round-tripping, three ByteString forms, Unicode category checks for bare symbols (`uucp`), and the SymbolOrNumber disambiguation step.
  - Passing samples.pr: about 2 days.
  - **Codec MVP: about 2 weeks.**
  - Schema: parsing `.prs` into the metaschema is easy. A codegen that emits `.ml` types plus parse and unparse functions, or a ppx, is about 2–3 weeks.
  - Path: about 1 week.
- **Clojure:** about 2–3 weeks for the codec, about 1–2 weeks for a runtime schema interpreter. Malli-style data-driven is more idiomatic than codegen.

  Clojure equality and hashing do not match the Preserves model, so the codec needs wrapper types:
  - `(= 0.0 -0.0)` is true and NaN ≠ NaN, so Doubles need a wrapper.
  - `byte[]` compares by identity, so ByteString needs a wrapper.
  - Records need a `deftype` with value semantics.
  - Embeddeds need a wrapper.
  - Symbols map naturally. Keywords have no counterpart, so pick a policy, for example keyword ↔ symbol.

## 6. Syndicate-specific features

**The Syndicated Actor Model** (https://syndicate-lang.org/about/):
- Actors publish *assertions*: state that is replicated with eventual consistency.
- *Dataspaces* route those assertions by interest. Interest is itself an assertion (`Observe`).
- Assertions are retracted when the asserting facet dies.
- Entity refs are object capabilities, carried as `Embedded`s.

**Wire protocol** (`syndicate-protocols/schemas/protocol.prs`):
- `Packet = Nop / Turn / Error / Extension`.
- `Turn = [[oid Event] ...]`, where `Event` is `<A assertion handle>`, `<R handle>`, `<M body>` or `<S #:peer>`.
- There is an experimental credit-based flow control, `<do <credit-flow-control 202506>>`.
- On the wire, an Embedded is `WireRef = [0 oid]` for a ref the sender owns, or `[1 oid caveat...]` for a ref the receiver owns, with **attenuation**. So refs are per-connection object IDs.

**Sturdy refs** (`sturdy.prs`, synit-manual gatekeeper page) are macaroons:
- `SturdyRef = <ref {oid: any, sig: bytes, caveats?: [Caveat ...]}>`
- `sig = f(...f(f(k, e(oid)), e(caveat1))...)` where `f = HMAC-BLAKE2s-256` truncated to 16 bytes and `e` = canonical binary.
- Caveats run right to left (newest is rightmost). They are `<rewrite Pattern Template>`, `<or [Rewrite...]>` or `<reject Pattern>`. These are *first-party* caveats that filter or rewrite assertion and message *structure*. "3rd-party caveats and richer predicates later."
- A gatekeeper `<resolve step #:observer>` turns a sturdy ref into a live ref.

This lines up directly with your intent attenuation. A caveat is a pattern-match or rewrite over the intent value, and attenuation can be done offline by anyone holding the ref, by appending a caveat and re-HMACing. Note the limits: the caveat language is a pattern and template algebra, not CEL. There is no expiry or third-party discharge.

## 7. Hands-on (Python `preserves` 0.996.3, Python 3.13)

Script: `scripts/demo.py`

```python
import hashlib
from preserves import parse, parse_with_annotations, encode, canonicalize, stringify, decode

def show(label, text, **kw):
    v = parse_with_annotations(text) if kw.pop('annotated', False) else parse(text)
    b = canonicalize(v, **kw)
    print(f"{label}\n  text : {text}\n  value: {stringify(v)}\n  hex  : {b.hex()}\n  sha256: {hashlib.sha256(b).hexdigest()}")
    return b

a1 = show("A1 intent, keys order prompt/max_tokens",
          '<intent llm.complete {prompt: "x" max_tokens: 4000} #:[cap 1]>')
a2 = show("A2 intent, keys reordered",
          '<intent llm.complete {max_tokens: 4000 prompt: "x"} #:[cap 1]>')
print("A1 == A2 canonical bytes:", a1 == a2)
s1 = show("S1 set", '#{c a b 3 -1 "z"}')
s2 = show("S2 set reordered", '#{"z" -1 b 3 c a}')
print("S1 == S2:", s1 == s2)
v = parse('{zeta: 1 alpha: 2}')
print("plain encode   :", encode(v).hex()); print("canonical      :", canonicalize(v).hex())
t_plain = '<intent llm.complete {prompt: "x" max_tokens: 4000} #:[cap 1]>'
t_ann   = '@"why: user asked" <intent llm.complete {prompt: @provenance "x" max_tokens: 4000} #:[cap 1]>'
d1 = show("D1 annotated, canonicalize (default drops them)", t_ann, annotated=True); print("D1 == A1:", d1 == a1)
d2 = show("D2 canonicalize(include_annotations=True)", t_ann, annotated=True, include_annotations=True); print("D2 == A1:", d2 == a1)
print("semantic equality annotated vs plain:", parse_with_annotations(t_ann) == parse(t_plain))
print("canonical #{0 -1 1}:", canonicalize(parse('#{0 -1 1}')).hex())
print("roundtrip ok:", decode(a1) == parse(t_plain))
```

Output (trimmed):

```
A1 hex   : b4b306696e74656e74b30c6c6c6d2e636f6d706c657465b7b30670726f6d7074b10178b30a6d61785f746f6b656e73b0020fa08486b5b303636170b001018484
A1 sha256: d91988e554525f90df8b6c17bbd8a64fe466c25047be1615d8c2eb1257a6fa78
A2 sha256: d91988e554525f90df8b6c17bbd8a64fe466c25047be1615d8c2eb1257a6fa78
A1 == A2 canonical bytes: True
S1/S2 hex: b6b00103b001ffb1017ab30161b30162b3016384   (both)  S1 == S2: True
plain encode   : b7b3047a657461b00101b305616c706861b0010284
canonical      : b7b3047a657461b00101b305616c706861b0010284   (zeta before alpha: length-prefix sort)
D1 sha256: d91988e5...a78   D1 == A1: True     (annotations stripped by canonicalize)
D2 hex: 85b10f7768793a20757365722061736b6564b4...b0020fa08486b5b303636170b001018484
D2 sha256: 81ab89cf448bad737e4e3c7a915feca536ad91980b801e4cd65b4e8f45f8d673   D2 == A1: False
semantic equality annotated vs plain: True
canonical #{0 -1 1}: b6b000b00101b001ff84
roundtrip ok: True
```

Reading the A1 bytes: `B4` record, `B3 06 "intent"`, `B3 0C "llm.complete"`, `B7` dict, … `B0 02 0F A0` is 4000, then `86 B5 B3 03 "cap" B0 01 01 84` is the embedded `#:[cap 1]`.

The embedded syntax `#:[cap 1]` parsed as-is. The Python default `parse_embedded` keeps the inner value, and `canonicalize` writes it back unchanged.

**Python implementation defects found** (demo2.py):
- **Host equality leaks into collections.** `{1: a 1.0: b}` → `DecodeError Duplicate key: 1.0`. `{#t: a 1: b}` → `Duplicate key: 1`. `#{1 1.0 #t}` → duplicate. Plain `==` also gives `1 == 1.0` True. Only `preserves.compare.eq` follows the spec: it returns False, and `cmp(1.0, 1) = -1`.
- **Text printer doesn't follow the spec's SHOULD.** It prints sets in binary-repr order, `#{0 1 a b -1}`, rather than total order.

Other probes: `nil` is the Symbol `nil`, `{:a 1}` fails to parse, and `-0.0` is `87 08 80 00…`.

## 8. Preserves vs canonical EDN + deterministic CBOR

**Pros of Preserves**
- **One data model, two syntaxes, one spec.** A human text form and a canonical binary are defined together with exact equivalence. EDN plus CBOR needs a hand-written mapping: keywords vs symbols, how to encode sets (CBOR tag 258?), tagged literals, ratio and char types. That mapping becomes an extra mini-spec you have to own.
- **Canonical form is tiny and testable.** Three rules plus a shared conformance corpus. There is no text canonical form to argue about.
- **Annotations are semantically outside the value.** Provenance and comments can travel on a definition without changing its hash.
- **Embedded is a first-class capability slot,** and Schema `#:Iface` types the capability's protocol. This is a natural fit for intents crossing a hypervisor trap.
- **Records give nominal typing** that EDN tagged literals give only by reader convention.
- **Syndicate gives you a working capability design to borrow:** macaroon sturdy refs over canonical bytes, and pattern/rewrite caveats.

**Cons and risks**
- **No OCaml and no Clojure/JVM implementation.** You would write and own both. That is roughly 2 weeks plus 2–3 weeks for the codecs, more for schema codegen. Agents emitting EDN would need a translation layer, or would have to learn Preserves text: no keywords, no nil, symbols vs strings.
- **Tiny ecosystem, effectively one maintainer.** About 95% of commits are Tony Garnock-Jones'. npm sees about 600 downloads a month.
- **Packages are perpetually pre-1.0 or rc.** Rust 5.0.0-rc.10, JS 1.0.0-rc.1, the spec at "0.996". There is no standards-body backing; CBOR is IETF RFC 8949 with mature libraries everywhere, including OCaml and Clojure.
- **Spec churn has been real, even if it has slowed:**
  - 2023: integer tags collapsed, float tags changed
  - 2024: `Float` removed, `#!` became `#:`
  - Sept 2024: `|sym|` became `'sym'`

  The binary changes would have changed content hashes. The core has been stable since 0.996 (Sept 2024 to June 2025), but new draft syntaxes keep appearing. **Mitigation:** pin a version tag inside your hashed envelope.
- **Implementations deviate from the spec in practice,** as the Python defects show. Your OCaml and Clojure codecs must be checked against samples.pr, not against another implementation's behaviour.
- **Embeddeds inside hashed values are a trap.** Their canonical bytes depend on whatever representation you choose; on the Syndicate wire they are per-connection OIDs. Content-addressed definitions should ban Embeddeds, or require a stable, non-wire representation such as a cap id or hash.
- **Schema gaps.** It is weaker than JSON Schema or Malli for constraints, optional fields are only experimental via `&`, and protobuf/JSON Schema/OpenAPI importers would be lossy. CEL would have to carry the constraints.
- **Canonical sort is by bytes,** length-prefix first, not "sorted keys" as humans read them. This is fine for hashing, but the canonical binary and the pretty-printed text order will differ. The same is true of deterministic CBOR.

**Bottom line:** Preserves' data model and canonical form fit content-addressing plus capabilities better than EDN+CBOR glue. A pragmatic hybrid is:
1. Adopt the *Preserves data model and canonical binary rules* as your hash format.
2. Implement the small binary codec yourself in OCaml and Clojure, validated against `tests/samples.pr`.
3. Treat the text syntax, Schema and Path as optional. Keep EDN at the agent edge, with a fixed keyword→symbol mapping and `#tag` → record mapping.

This limits your exposure to the single-maintainer risk to a roughly 500-LOC codec you own.

**Files:** `scripts/demo.py` and `scripts/demo2.py` in this directory (run with `pip install preserves==0.996.3`).

**Other sources:**
- https://crates.io/crates/preserves
- https://www.npmjs.com/package/@preserves/core
- https://pypi.org/project/preserves/
- https://git.syndicate-lang.org/synit/synit-manual/src/branch/main/src/operation/builtin/gatekeeper.md
- https://syndicate-lang.org/code/repos/