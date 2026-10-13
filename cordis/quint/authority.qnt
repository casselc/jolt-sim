// -*- mode: Bluespec; -*-
/**
 * authority: what an agent's graph may and may not decide about an agent task's budget and prices
 * (docs/cordis/rfc/0008-agent-runs.md, "Authority" and "Valuation is the operator's").
 *
 * The operator's configuration holds the CEILING on a task's `charge` limit and the PRICE of each route (a valuation
 * unit per token; the local route's is a nominal resource valuation). The graph (manifests, cells, gates.edn,
 * agents.edn, prompts) is the agent's to rewrite, and holds its own copies of both: a ceiling it would like and
 * prices it would like. A task is accepted where the request is made (yard-ctl, from the operator's configuration);
 * the broker then charges each token at the price pinned at accept and refuses a call past the limit.
 *
 * Properties:
 *  - withinCeiling: every accepted task's limit is at or below the operator's ceiling;
 *  - pricedByOperator: the price pinned for a task is the operator's price for its route;
 *  - withinLimit: a task's charge never passes its limit;
 *  - workCharged: every token a task consumed was charged at least one unit (a route priced zero reserves and charges
 *    nothing, so the limit stops binding the work).
 * Mutants:
 *  - CEILING_IN_GRAPH (`ceilingInGraph`): the ceiling is read from the graph's copy -> withinCeiling;
 *  - PRICE_FROM_GRAPH (`priceFromGraph`): the price is read from the graph's copy -> pricedByOperator;
 *  - no REFUSE_FREE (`freeRoute`): the operator priced the local route at zero and nothing refuses a zero-priced route
 *    -> workCharged. (The design prices every route above zero; `guarded` is the same operator mistake with the refusal
 *    in place: workCharged holds and the refusal is reached.)
 * Witnesses (checked as not(w), expected VIOLATED): acceptedW, refusedCeilingW, refusedFreeW, ranW.
 */
module authority {
  const CEILING_IN_GRAPH: bool
  const PRICE_FROM_GRAPH: bool
  const OP_ZERO_LOCAL: bool   // world: the operator priced the local route at zero by mistake
  const REFUSE_FREE: bool     // the rule: a task on a route priced zero is refused (yard, and the outbox as a second guard)

  pure val CEIL = 3                    // the operator's ceiling on a task's charge limit
  pure val NT = 2                      // tasks
  pure val ROUTES = 0.to(1)            // 0 local, 1 provider

  type Task = { acc: bool, route: int, limit: int, price: int, charge: int, tokens: int }
  type S = {
    tasks: List[Task],
    gCeil: int,                        // the graph's copy of the ceiling
    gPrice: List[int],                 // the graph's copy of each route's price
    refusedCeil: int, refusedFree: int, ran: int,
  }
  var s: S

  // the operator's prices: the local route's is nominal, and nonzero (FREE_ROUTE: zero)
  pure def opPrice(r: int): int = if (r == 0) (if (OP_ZERO_LOCAL) 0 else 1) else 2

  action init = s' = {
    tasks: List(),
    gCeil: CEIL, gPrice: [1, 2],
    refusedCeil: 0, refusedFree: 0, ran: 0,
  }

  // the agent rewrites its own graph: its copies of the ceiling and prices change; the operator's do not
  action edit: bool = {
    nondet c = 0.to(6).oneOf()
    nondet r = ROUTES.oneOf()
    nondet p = 0.to(2).oneOf()
    s' = { ...s, gCeil: c, gPrice: s.gPrice.replaceAt(r, p) }
  }

  // a request is made and accepted or refused at acceptance
  action request: bool = {
    nondet r = ROUTES.oneOf()
    nondet b = 1.to(5).oneOf()
    val ceiling = if (CEILING_IN_GRAPH) s.gCeil else CEIL
    val price = if (PRICE_FROM_GRAPH) s.gPrice[r] else opPrice(r)
    val tooBig = b > ceiling
    val free = price == 0 and REFUSE_FREE    // the design refuses a route priced zero
    all {
      s.tasks.length() < NT,
      s' = { ...s,
        tasks: s.tasks.append({ acc: not(tooBig) and not(free), route: r, limit: b, price: price, charge: 0, tokens: 0 }),
        refusedCeil: if (tooBig) s.refusedCeil + 1 else s.refusedCeil,
        refusedFree: if (not(tooBig) and free) s.refusedFree + 1 else s.refusedFree },
    }
  }

  // the broker meters one token at the pinned price, refused past the limit
  action meter: bool = {
    nondet t = s.tasks.indices().oneOf()
    val tk = s.tasks[t]
    all {
      tk.acc, tk.charge + tk.price <= tk.limit,
      s' = { ...s, tasks: s.tasks.replaceAt(t, { ...tk, charge: tk.charge + tk.price, tokens: tk.tokens + 1 }), ran: s.ran + 1 },
    }
  }

  action idle: bool = s' = s
  action step = any { edit, request, meter, idle }

  val withinCeiling: bool = s.tasks.indices().forall(t => s.tasks[t].acc implies s.tasks[t].limit <= CEIL)
  val pricedByOperator: bool = s.tasks.indices().forall(t => s.tasks[t].acc implies s.tasks[t].price == (if (s.tasks[t].route == 0) 1 else 2))
  val withinLimit: bool = s.tasks.indices().forall(t => s.tasks[t].charge <= s.tasks[t].limit)
  val workCharged: bool = s.tasks.indices().forall(t => s.tasks[t].charge >= s.tasks[t].tokens)
  val safety: bool = withinCeiling and pricedByOperator and withinLimit and workCharged

  val acceptedW: bool = s.tasks.indices().exists(t => s.tasks[t].acc)
  val refusedCeilingW: bool = s.refusedCeil > 0
  val refusedFreeW: bool = s.refusedFree > 0
  val ranW: bool = s.ran > 0
}
