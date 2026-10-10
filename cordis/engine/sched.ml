(* Fairness across graphs (docs/cordis/rfc/0006-turn-runtime.md, decision
   4; quint/schedule.qnt): deficit round robin by cost units (Shreedhar and
   Varghese), sans-IO.

   One loop serves several graphs. The scheduler is on one graph at a time.
   When the loop reaches a graph with work, the graph earns a quantum; it
   then runs while its deficit covers its next step, and the loop stays on
   it. When the deficit does not cover the step the loop moves on, and the
   deficit is kept for the next visit; a graph without work keeps no
   deficit. A graph with work therefore waits at most

     ceil(max_cost / quantum) * (graphs - 1) * (quantum + max_cost - 1)

   picks (with a step costing at least one unit), the bound [bound] gives
   and schedule.qnt's boundedWait checks. Which graphs exist, what has
   work, what a step costs (Engine.step_cost) and the quantum are the
   host's to say: the scheduler holds only the deficits and its place. *)

type t = { deficit : int array; current : int; granted : bool; quantum : int }

let create ~quantum n =
  if quantum < 1 then invalid_arg "Sched.create: a quantum below one";
  if n < 1 then invalid_arg "Sched.create: no graphs";
  { deficit = Array.make n 0; current = 0; granted = false; quantum }

let graphs t = Array.length t.deficit
let deficit t g = t.deficit.(g)

(* [cost g] is the cost of graph g's next step, or None when it has no
   work. The graph to run, if any has work, and the scheduler after. *)
let pick t ~cost =
  let n = graphs t in
  let d = Array.copy t.deficit in
  (* every graph with work gains a quantum each visit, so a round with work finds a step within
     ceil(max_cost / quantum) rounds; a whole round without work finds none *)
  let rec go g granted idle =
    if idle >= n then (None, { t with deficit = d; current = g; granted = false })
    else
      match cost g with
      | None ->
          d.(g) <- 0;
          go ((g + 1) mod n) false (idle + 1)
      | Some c ->
          let c = max 1 c in
          if not granted then d.(g) <- d.(g) + t.quantum;
          if d.(g) >= c then begin
            d.(g) <- d.(g) - c;
            (Some g, { t with deficit = d; current = g; granted = true })
          end
          else go ((g + 1) mod n) false 0
  in
  go t.current t.granted 0

let bound ~quantum ~max_cost n =
  let max_cost = max 1 max_cost in
  ((max_cost + quantum - 1) / quantum) * (n - 1) * (quantum + max_cost - 1)
