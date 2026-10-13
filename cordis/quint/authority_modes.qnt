// -*- mode: Bluespec; -*-
/**
 * authority_modes: authority.qnt's design, a world, and mutants (see there).
 */
module design {
  import authority(CEILING_IN_GRAPH = false, PRICE_FROM_GRAPH = false, OP_ZERO_LOCAL = false, REFUSE_FREE = true).* from "./authority"
}
module guarded {
  import authority(CEILING_IN_GRAPH = false, PRICE_FROM_GRAPH = false, OP_ZERO_LOCAL = true, REFUSE_FREE = true).* from "./authority"
}
module ceilingInGraph {
  import authority(CEILING_IN_GRAPH = true, PRICE_FROM_GRAPH = false, OP_ZERO_LOCAL = false, REFUSE_FREE = true).* from "./authority"
}
module priceFromGraph {
  import authority(CEILING_IN_GRAPH = false, PRICE_FROM_GRAPH = true, OP_ZERO_LOCAL = false, REFUSE_FREE = true).* from "./authority"
}
module freeRoute {
  import authority(CEILING_IN_GRAPH = false, PRICE_FROM_GRAPH = false, OP_ZERO_LOCAL = true, REFUSE_FREE = false).* from "./authority"
}
