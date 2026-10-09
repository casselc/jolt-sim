// -*- mode: Bluespec; -*-
/**
 * assets: memoised assets with freshness and early cutoff (docs/cordis/rfc/
 * 0002-graph-language.md, "Graphs"; DESIGN.md "The runtime", Assets).
 *
 * A small graph: an input A, then B = A % 2, C = B + 1, and E, an emission
 * cell that sends C and whose value is the receipt (here, the value it
 * acknowledged). Each asset remembers the revisions of its inputs it was
 * computed from; it is stale when one has moved. A stale asset whose inputs
 * are fresh may recompute. Recomputing looks up a memo keyed by (cell,
 * input values): a hit reuses the output; for E the memo is the receipt
 * cache, so a hit means "already sent, here is the receipt". An asset's
 * revision moves only when its value changes (early cutoff), so A going
 * from 0 to 2 leaves B, C and E alone.
 *
 * Properties:
 *  - consistent: whenever nothing is stale, every asset equals what a
 *    from-scratch evaluation of the graph would give;
 *  - receiptBacked: at rest, the value E answers for was really sent, not
 *    stood in for by a receipt for an earlier value;
 *  - atMostOnce: no value is sent by E twice.
 * Mutants:
 *  - MEMO_BY_INPUT = false (`memoByCell`): pure cells' memo is keyed by
 *    the cell alone, so a recompute after an input change reuses an old
 *    output -> consistent;
 *  - RECEIPTS_BY_INPUT = false (`receiptsByCell`): E's receipt cache is
 *    keyed by the cell alone, so a receipt for an earlier value stands in
 *    for a send that never happened -> receiptBacked;
 *  - MEMO = false (`noMemo`): no memo; E sends again whenever it recomputes
 *    for a value it has already sent -> atMostOnce;
 *  - CUTOFF = false (`noCutoff`): revisions move on every recompute.
 *    Safety holds; the witness `cutoffSaved` shows what the cutoff saves.
 * Witnesses (checked as not(w), expected VIOLATED): cutoffSaved, memoHit,
 * sentTwoValues.
 */
module assets {
  const MEMO: bool
  const MEMO_BY_INPUT: bool      // pure cells' memo keyed by input values
  const RECEIPTS_BY_INPUT: bool  // E's receipt cache keyed by the value sent
  const CUTOFF: bool

  pure val ASSETS: Set[str] = Set("A", "B", "C", "E")
  pure val DEPS: str -> Set[str] = Map("A" -> Set(), "B" -> Set("A"), "C" -> Set("B"), "E" -> Set("C"))
  pure val INPUTS: Set[int] = Set(0, 1, 2)

  // what each cell computes from its one input value (E: the receipt for what it sent)
  pure def cell(a: str, x: int): int =
    if (a == "B") x % 2 else if (a == "C") x + 1 else x

  pure def input(a: str): str = if (a == "B") "A" else if (a == "C") "B" else "C"

  var value: str -> int
  var rev: str -> int
  var seen: str -> int             // the revision of its input an asset was computed from
  var memo: Set[(str, int, int)]   // (cell, input value, output)
  var sent: int -> int             // value -> times E sent it
  var cutoffs: int                 // recomputes whose value did not change
  var memoHits: int

  pure def fromScratch(a: int): str -> int = {
    val b = a % 2
    Map("A" -> a, "B" -> b, "C" -> b + 1, "E" -> b + 1)
  }

  action init = all {
    value' = fromScratch(0), rev' = ASSETS.mapBy(_ => 0), seen' = ASSETS.mapBy(_ => 0),
    memo' = Set(("B", 0, 0), ("C", 0, 1), ("E", 1, 1)),
    sent' = INPUTS.union(Set(3)).mapBy(v => if (v == 1) 1 else 0),
    cutoffs' = 0, memoHits' = 0,
  }

  def stale(a: str): bool = a != "A" and rev.get(input(a)) != seen.get(a)
  def quiet: bool = ASSETS.forall(a => not(stale(a)))

  action setInput(x: int): bool = all {
    x != value.get("A"),
    value' = value.set("A", x), rev' = rev.setBy("A", r => r + 1),
    seen' = seen, memo' = memo, sent' = sent, cutoffs' = cutoffs, memoHits' = memoHits,
  }

  action recompute(a: str): bool = {
    val x = value.get(input(a))
    val byInput = if (a == "E") RECEIPTS_BY_INPUT else MEMO_BY_INPUT
    val hits = memo.filter(m => m._1 == a and (not(byInput) or m._2 == x))
    val hit = MEMO and hits.size() > 0
    val out = if (hit) hits.fold(0, (acc, m) => m._3) else cell(a, x)
    val changed = out != value.get(a)
    all {
      a != "A", stale(a), not(stale(input(a))),
      value' = value.set(a, out),
      rev' = if (changed or not(CUTOFF)) rev.setBy(a, r => r + 1) else rev,
      seen' = seen.set(a, rev.get(input(a))),
      memo' = if (MEMO) memo.filter(m => not(m._1 == a and m._2 == x)).union(Set((a, x, out))) else memo,
      sent' = if (a == "E" and not(hit)) sent.setBy(x, n => n + 1) else sent,
      cutoffs' = if (changed) cutoffs else cutoffs + 1,
      memoHits' = if (hit) memoHits + 1 else memoHits,
    }
  }

  action idle = all {
    value' = value, rev' = rev, seen' = seen, memo' = memo, sent' = sent,
    cutoffs' = cutoffs, memoHits' = memoHits,
  }

  action step = any {
    nondet x = oneOf(INPUTS)
    setInput(x),
    nondet a = oneOf(ASSETS)
    recompute(a),
    idle,
  }

  // ---- properties -------------------------------------------------------------------------
  val consistent: bool = quiet implies value == fromScratch(value.get("A"))
  // at rest, what C currently holds was really sent (not just some earlier value)
  val receiptBacked: bool = quiet implies sent.get(value.get("C")) > 0
  val atMostOnce: bool = sent.keys().forall(v => sent.get(v) <= 1)
  val safety: bool = consistent and receiptBacked and atMostOnce

  // witnesses
  val cutoffSaved: bool = cutoffs > 0 and quiet
  val memoHit: bool = memoHits > 0
  val sentTwoValues: bool = sent.keys().filter(v => sent.get(v) > 0).size() > 1
}

module design {
  import assets(MEMO = true, MEMO_BY_INPUT = true, RECEIPTS_BY_INPUT = true, CUTOFF = true).* from "./assets"
}

module memoByCell {
  import assets(MEMO = true, MEMO_BY_INPUT = false, RECEIPTS_BY_INPUT = true, CUTOFF = true).* from "./assets"
}

module receiptsByCell {
  import assets(MEMO = true, MEMO_BY_INPUT = true, RECEIPTS_BY_INPUT = false, CUTOFF = true).* from "./assets"
}

module noMemo {
  import assets(MEMO = false, MEMO_BY_INPUT = true, RECEIPTS_BY_INPUT = true, CUTOFF = true).* from "./assets"
}

module noCutoff {
  import assets(MEMO = true, MEMO_BY_INPUT = true, RECEIPTS_BY_INPUT = true, CUTOFF = false).* from "./assets"
}
