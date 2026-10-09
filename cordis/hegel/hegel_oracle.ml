(* The entropy oracle over a Hegel test case: every simulator choice is a
   Hegel draw, so a failing run shrinks to a minimal schedule and fault set,
   and a [rerun with:] blob replays it exactly. *)

let of_test_case (tc : Hegel.test_case) : Cordis_sim.Oracle.t =
  {
    int =
      (fun n ->
        if n <= 1 then 0
        else Hegel.draw_silent tc (Hegel.Generators.integers ~min_value:0 ~max_value:(n - 1) ()));
    label = "hegel";
  }
