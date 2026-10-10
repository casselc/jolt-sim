(* The CEL front door: parse, then evaluate (docs/cordis/rfc/0001-cel.md). *)

module Value = Value
module Ast = Ast
module Parser = Parser
module Eval = Eval
module Checker = Checker
module Cost = Cost
module Proto = Proto

let parse ?macros src =
  Extensions.load ();
  Parser.parse ?macros src

let eval ?limit ?(vars = []) ?(macros = true) ?container ?registry src =
  Extensions.load ();
  match Parser.parse ~macros src with
  | Error e -> Error e
  | Ok ast -> (
      let run () = Eval.run ?limit ~vars ?container ast in
      (* a revision's registry, for this evaluation only (RFC 0004) *)
      match registry with Some r -> Ok (Proto.with_registry r run) | None -> Ok (run ()))

(* The static cost estimate of an expression over declared variables
   (docs/cordis/rfc/0001-cel.md, "Cost"): each input's sizes bounded by
   [sizes], else [default_size]. *)
let estimate ?default_size ?sizes ?(macros = true) env src =
  Extensions.load ();
  match Parser.parse ~macros src with
  | Error e -> Error e
  | Ok ast -> Cost.estimate ?default_size ?sizes env ast
